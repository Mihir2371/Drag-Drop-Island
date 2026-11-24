package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screen capture with local OCR and a custom app-target picker; never invokes ACTION_CHOOSER. */
internal object CaptureShare {
    @Volatile private var service: AccessibilityService? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val _previewUri = MutableStateFlow<Uri?>(null)
    val previewUri = _previewUri.asStateFlow()
    private val _previewText = MutableStateFlow("")
    val previewText = _previewText.asStateFlow()
    private val _showTargets = MutableStateFlow(false)
    val showTargets = _showTargets.asStateFlow()
    private val _targets = MutableStateFlow<List<ResolveInfo>>(emptyList())
    val targets = _targets.asStateFlow()
    fun attach(s: AccessibilityService) { service = s }
    fun detach(s: AccessibilityService) { if (service === s) service = null }
    fun toggleTargets() { _showTargets.value = !_showTargets.value }
    fun dismiss() { _previewUri.value = null; _previewText.value = ""; _showTargets.value = false; _targets.value = emptyList() }

    fun capture(context: Context) {
        val s = service
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(context, "Screen capture requires Android 11 or newer", Toast.LENGTH_LONG).show()
            return
        }
        if (s == null) {
            Toast.makeText(context, "Island accessibility service isn't connected", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "Capturing screen…", Toast.LENGTH_SHORT).show()
        s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { result.hardwareBuffer.close() }
                    if (bitmap == null) { toast(context, "Couldn't read screenshot"); return }
                    val uri = savePng(context, bitmap)
                    if (uri == null) { bitmap.recycle(); toast(context, "Couldn't save screenshot"); return }
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { recognized ->
                            val text = recognized.text.trim()
                            bitmap.recycle()
                            showPreview(context, uri, text)
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            showPreview(context, uri, "")
                        }
                }
                override fun onFailure(errorCode: Int) { toast(context, "Screenshot failed ($errorCode)") }
            })
    }

    private fun showPreview(context: Context, uri: Uri, text: String) {
        _previewUri.value = uri
        _previewText.value = text
        _showTargets.value = false
        val targets = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("image/png"), 0)
            .filter { it.activityInfo?.packageName != context.packageName }
            .distinctBy { targetKey(it) }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
        _targets.value = targets
        toast(context, if (text.isBlank()) "Capture ready — hold preview for app targets" else "Capture ready — hold preview for app targets")
    }

    fun targetKey(info: ResolveInfo) = info.activityInfo?.let { it.packageName + "/" + it.name }.orEmpty()

    fun sendToTarget(context: Context, key: String): Boolean {
        val uri = _previewUri.value ?: return false
        val target = _targets.value.firstOrNull { targetKey(it) == key }?.activityInfo ?: return false
        val text = _previewText.value
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newUri(context.contentResolver, "Drag & Go capture", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setClassName(target.packageName, target.name)
        }
        runCatching {
            context.grantUriPermission(target.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(send)
        }.onFailure { toast(context, "Couldn't share to selected app") }.onSuccess { dismiss() }
        return true
    }

    private fun savePng(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("PNG save failed") }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    private fun toast(context: Context, message: String) {
        ContextCompat.getMainExecutor(context).execute { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}