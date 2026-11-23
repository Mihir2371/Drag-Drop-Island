package com.example.draggo
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassification.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class DragAndGoAccessibilityService : AccessibilityService() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pill: ActionPillView? = null
    private var ocrFile: File? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if(intent.action!=OcrWorkerService.ACTION_OCR_RESULT) return
            val text=intent.getStringExtra(OcrWorkerService.EXTRA_TEXT).orEmpty()
            val path=intent.getStringExtra(OcrWorkerService.EXTRA_IMAGE_PATH)
            if(path!=ocrFile?.absolutePath) return
            classifyAndShow(text)
        }
    }
    override fun onServiceConnected() {
        serviceInfo=serviceInfo.apply {
            eventTypes=AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout=80
        }
        val filter=IntentFilter(OcrWorkerService.ACTION_OCR_RESULT)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(ocrReceiver,filter,RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(ocrReceiver,filter)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event?.eventType!=AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
        val source=event.source ?: return
        try {
            if(source.isEditable) return
            val bounds=Rect().also(source::getBoundsInScreen)
            val text=StringBuilder()
            collectNodeText(source,text,0)
            val clean=text.toString().trim().take(MAX_TEXT)
            if(clean.isNotBlank()) classifyAndShow(clean) else captureNodeCrop(bounds)
        } finally { source.recycle() }
    }
    private fun collectNodeText(node: AccessibilityNodeInfo,out: StringBuilder,depth:Int) {
        if(depth>MAX_DEPTH || out.length>=MAX_TEXT) return
        val t=node.text?.toString()?.trim().orEmpty()
        val d=node.contentDescription?.toString()?.trim().orEmpty()
        if(t.isNotEmpty() && !out.contains(t)) out.append(t).append('\n')
        if(d.isNotEmpty() && !out.contains(d)) out.append(d).append('\n')
        for(i in 0 until node.childCount) {
            val child=runCatching { node.getChild(i) }.getOrNull() ?: continue
            try { collectNodeText(child,out,depth+1) } finally { child.recycle() }
            if(out.length>=MAX_TEXT) break
        }
    }
    private fun captureNodeCrop(bounds: Rect) {
        if(Build.VERSION.SDK_INT<30) { showFallback("Image capture needs Android 11+"); return }
        val dm=resources.displayMetrics
        val clipped=Rect(bounds).apply {
            left=left.coerceIn(0,dm.widthPixels); right=right.coerceIn(0,dm.widthPixels)
            top=top.coerceIn(0,dm.heightPixels); bottom=bottom.coerceIn(0,dm.heightPixels)
        }
        if(clipped.width()<2 || clipped.height()<2) { showFallback("Couldn't crop selected area"); return }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,worker,object: TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var full: Bitmap?=null
                var crop: Bitmap?=null
                try {
                    full=Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)?.copy(Bitmap.Config.ARGB_8888,false)
                    val src=full ?: return
                    val r=Rect(clipped).apply { right=min(right,src.width); bottom=min(bottom,src.height) }
                    if(r.width()<2 || r.height()<2) return
                    crop=Bitmap.createBitmap(src,r.left,r.top,r.width(),r.height())
                    val scale=min(1f,720f/max(crop!!.width,crop!!.height))
                    if(scale<1f) {
                        val reduced=Bitmap.createScaledBitmap(crop!!,max(1,(crop!!.width*scale).toInt()),max(1,(crop!!.height*scale).toInt()),false)
                        crop!!.recycle(); crop=reduced
                    }
                    val output=File(cacheDir,"ocr-crop-${System.currentTimeMillis()}.png")
                    FileOutputStream(output).use { crop!!.compress(Bitmap.CompressFormat.PNG,90,it) }
                    ocrFile=output
                    val serviceIntent=Intent(this@DragAndGoAccessibilityService,OcrWorkerService::class.java).putExtra(OcrWorkerService.EXTRA_IMAGE_PATH,output.absolutePath)
                    startForegroundService(serviceIntent)
                } catch(_:Throwable) { main.post { showFallback("Capture failed") } }
                finally { crop?.recycle(); full?.recycle(); result.hardwareBuffer.close() }
            }
            override fun onFailure(errorCode:Int) { showFallback("Screenshot failed: $errorCode") }
        })
    }
    private fun classifyAndShow(text:String)=worker.execute {
        val actions=if(Build.VERSION.SDK_INT>=28) runCatching {
            val manager=getSystemService(TextClassificationManager::class.java)
            val request=Request.Builder(text,0,text.length).build()
            manager.textClassifier.classifyText(request).actions.toList()
        }.getOrDefault(emptyList()) else emptyList()
        main.post { showPill(actions,text) }
    }
    private fun showPill(actions:List<android.app.RemoteAction>,text:String) {
        dismissPill()
        val view=ActionPillView(this).apply { setActions(actions,text) }
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity=android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; y=180
        }
        runCatching { wm.addView(view,params) }.onSuccess { pill=view }
    }
    private fun showFallback(msg:String) { main.post { android.widget.Toast.makeText(this,msg,android.widget.Toast.LENGTH_SHORT).show() } }
    private fun dismissPill() { val v=pill; pill=null; if(v!=null) runCatching { wm.removeView(v) } }
    override fun onInterrupt()=dismissPill()
    override fun onDestroy() {
        dismissPill(); runCatching { unregisterReceiver(ocrReceiver) }; worker.shutdownNow(); super.onDestroy()
    }
    override fun onBind(intent:Intent?)=super.onBind(intent)
    companion object { private const val MAX_DEPTH=12; private const val MAX_TEXT=4000 }
}
