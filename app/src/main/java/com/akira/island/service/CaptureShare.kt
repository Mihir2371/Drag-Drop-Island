package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Context
import android.content.Intent
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

/** Capture a screenshot, share its PNG, and optionally attach locally recognized OCR text. */
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
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    if (bitmap == null) {
                        toast(context, "Couldn't read screenshot")
                        return
                    }
                    val uri = savePng(context, bitmap)
                    if (uri == null) {
                        bitmap.recycle()
                        toast(context, "Couldn't save screenshot")
                        return
                    }
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { recognized ->
                            val text = recognized.text.trim()
                            bitmap.recycle()
                            shareImage(context, uri, text)
                        }
                        .addOnFailureListener {
                            bitmap.recycle()
                            shareImage(context, uri, "")
                        }
                }
                override fun onFailure(errorCode: Int) {
                    toast(context, "Screenshot failed ($errorCode)")
                }
            })
    }

    private fun savePng(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "screen-" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("PNG encoding failed")
        }
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    private fun shareImage(context: Context, uri: Uri, recognizedText: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (recognizedText.isNotBlank()) putExtra(Intent.EXTRA_TEXT, recognizedText)
            clipData = ClipData.newUri(context.contentResolver, "Captured screenshot", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "Share captured screenshot")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(chooser) }
            .onFailure { toast(context, "Couldn't open share targets") }
    }

    private fun toast(context: Context, message: String) {
        ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}