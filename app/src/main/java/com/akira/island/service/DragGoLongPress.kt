package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.app.RemoteAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextSelection
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Long-press contextual actions for accessible text. Lightweight custom Canvas UI;
 * no Compose, GPU rendering, or persistent worker is created for this feature.
 */
internal class DragGoLongPress(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val wm = service.getSystemService(WindowManager::class.java)
    private var overlay: ActionOverlay? = null
    private var text = ""
    private val dismiss = Runnable { hide() }

    fun onLongClick(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == service.packageName ||
            event.packageName?.toString() == "com.android.systemui") return
        val node = event.source ?: return
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val content = try { collectText(node) } finally { node.recycle() }
        if (content.isBlank()) { if (Build.VERSION.SDK_INT >= 30) captureOcr(bounds); return }
        text = content.take(MAX_TEXT)
        showActions(text)
    }

    private fun collectText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val visited = HashSet<String>()
        queue.add(root)
        val out = StringBuilder()
        var count = 0
        while (queue.isNotEmpty() && count++ < 48 && out.length < MAX_TEXT) {
            val node = queue.removeFirst()
            val value = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && visited.add(value)) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(value)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            if (node !== root) runCatching { node.recycle() }
        }
        return out.toString()
    }

    /** Screenshot/OCR fallback for non-exposed text, clipped to source node bounds. */
    private fun captureOcr(bounds: Rect) {
        if (bounds.isEmpty) return
        val metrics = service.resources.displayMetrics
        val rect = Rect(bounds).apply {
            left = left.coerceIn(0, metrics.widthPixels); right = right.coerceIn(0, metrics.widthPixels)
            top = top.coerceIn(0, metrics.heightPixels); bottom = bottom.coerceIn(0, metrics.heightPixels)
        }
        if (rect.width() < 4 || rect.height() < 4) return
        service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, ocrExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val full = try {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { result.hardwareBuffer.close() }
                    if (full == null) return
                    val crop = try {
                        val left=rect.left.coerceAtMost(full.width-1); val top=rect.top.coerceAtMost(full.height-1)
                        Bitmap.createBitmap(full,left,top,rect.width().coerceAtMost(full.width-left).coerceAtLeast(1),
                            rect.height().coerceAtMost(full.height-top).coerceAtLeast(1))
                    } catch (_: Throwable) { full.recycle(); return }
                    full.recycle()
                    val edge=720
                    val bmp=if(maxOf(crop.width,crop.height)>edge) {
                        val ratio=edge.toFloat()/maxOf(crop.width,crop.height)
                        Bitmap.createScaledBitmap(crop,(crop.width*ratio).toInt().coerceAtLeast(1),(crop.height*ratio).toInt().coerceAtLeast(1),false).also { crop.recycle() }
                    } else crop
                    val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(bmp,0))
                        .addOnSuccessListener { result ->
                            val detected=result.text.trim().take(MAX_TEXT)
                            if(detected.isNotBlank()) { text=detected; showActions(detected) } else toast("No readable text detected")
                        }
                        .addOnFailureListener { toast("Text recognition failed") }
                        .addOnCompleteListener { bmp.recycle(); recognizer.close() }
                }
                override fun onFailure(errorCode: Int) { toast("Screenshot/OCR unavailable ($errorCode)") }
            })
    }

    private fun showActions(text: String) {
        val actions = mutableListOf<Action>()
        actions += Action("Copy") { copy(text) }
        actions += Action("Share") { share(text) }
        actions += Action("Search") { search(text) }
        actions += Action("Translate") { translate(text) }
        val smart = classifierActions(text)
        smart.forEach { (label, action) -> if (actions.none { it.label == label }) actions.add(Action(label, action)) }
        showOverlay(actions.take(5))
    }

    private fun classifierActions(text: String): List<Pair<String, () -> Unit>> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        return runCatching {
            val manager = service.getSystemService(TextClassificationManager::class.java)
            val classifier = manager?.textClassifier ?: return emptyList()
            val request = TextClassification.Request.Builder(text, 0, text.length).build()
            val result = classifier.classifyText(request)
            (0 until result.actions.size).mapNotNull { i ->
                val remote = result.actions[i]
                val title = remote.title?.toString()?.trim()?.take(14)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                title to { runCatching { remote.actionIntent.send() }.onFailure { toast("Action unavailable") } }
            }.take(3)
        }.getOrDefault(emptyList())
    }

    private data class Action(val label: String, val run: () -> Unit)

    private fun showOverlay(actions: List<Action>) {
        hide()
        val view = ActionOverlay(service, actions) { hide() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, dp(64),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
            title = "DragGoContextActions"
        }
        overlay = view
        runCatching { wm.addView(view, lp) }.onFailure { overlay = null }
        main.removeCallbacks(dismiss)
        main.postDelayed(dismiss, 8000)
    }

    private fun copy(value: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Drag & Go", value))
        toast("Copied")
    }
    private fun share(value: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(Intent.createChooser(intent, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Couldn't open share sheet") }
    }
    private fun search(value: String) {
        val uri = android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(value))
        runCatching { service.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Search couldn't be opened") }
    }
    private fun translate(value: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://translate.google.com/?sl=auto&tl=en&text=" + android.net.Uri.encode(value)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(intent) }.onFailure { toast("Translate couldn't be opened") }
    }
    private fun toast(message: String) = ContextCompat.getMainExecutor(service).execute {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }
    private fun hide() {
        main.removeCallbacks(dismiss)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }
    fun destroy() { hide(); ocrExecutor.shutdown() }
    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()
    companion object { private const val MAX_TEXT = 1800 }

    private class ActionOverlay(
        context: android.content.Context,
        private val actions: List<Action>,
        private val close: () -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff22252b.toInt() }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f*density; textAlign = Paint.Align.CENTER }
        private val bounds = RectF()
        private val cells = mutableListOf<RectF>()
        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null); isClickable = true }
        override fun onMeasure(w: Int, h: Int) {
            val width = (actions.size * 76*density + 16*density).toInt()
            setMeasuredDimension(width.coerceAtLeast(120), (64*density).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            bounds.set(0f,0f,width.toFloat(),height.toFloat())
            canvas.drawRoundRect(bounds, height/2f, height/2f, fill)
            cells.clear()
            actions.forEachIndexed { index, action ->
                val left=8*density+index*76*density
                val cell=RectF(left,0f,left+76*density,height.toFloat()); cells.add(cell)
                ink.textSize=13*density
                canvas.drawText(action.label.take(12),cell.centerX(),cell.centerY()+4.5f*density,ink)
            }
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.action==android.view.MotionEvent.ACTION_UP) {
                val i=cells.indexOfFirst { it.contains(e.x,e.y) }
                if(i>=0 && i<actions.size) actions[i].run()
                close(); return true
            }
            return e.action==android.view.MotionEvent.ACTION_DOWN
        }
    }
}
