package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/** On-demand screen capture + on-device OCR. No screenshot or text is uploaded. */
internal object CaptureShare {
    @Volatile private var service: AccessibilityService? = null
    private val executor = Executors.newSingleThreadExecutor()
    fun attach(s: AccessibilityService) { service = s }
    fun detach(s: AccessibilityService) { if (service === s) service = null }

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
                    if (bitmap == null) {
                        toast(context, "Couldn't read screenshot")
                        return
                    }
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { recognized ->
                            val text = recognized.text.trim()
                            bitmap.recycle()
                            if (text.isBlank()) toast(context, "No text found in screenshot")
                            else {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(share, "Share captured text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        .addOnFailureListener { bitmap.recycle(); toast(context, "OCR failed") }
                }
                override fun onFailure(errorCode: Int) { toast(context, "Screenshot failed ($errorCode)") }
            })
    }
    private fun toast(context: Context, msg: String) {
        ContextCompat.getMainExecutor(context).execute { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}