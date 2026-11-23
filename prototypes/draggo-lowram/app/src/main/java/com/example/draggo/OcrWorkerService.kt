package com.example.draggo
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.IBinder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** Isolated OCR worker. Cache path avoids large bitmap parcels across Binder. */
class OcrWorkerService : Service() {
    private val main=Handler(Looper.getMainLooper())
    override fun onBind(intent:Intent?): IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val path=intent?.getStringExtra(EXTRA_IMAGE_PATH) ?: return START_NOT_STICKY
        Thread {
            val file=File(path)
            val bitmap=BitmapFactory.decodeFile(path)
            if(bitmap==null) finishOcr("",file,startId) else {
                val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(bitmap,0))
                    .addOnSuccessListener { result -> bitmap.recycle(); recognizer.close(); finishOcr(result.text,file,startId) }
                    .addOnFailureListener { bitmap.recycle(); recognizer.close(); finishOcr("",file,startId) }
            }
        }.start()
        return START_NOT_STICKY
    }
    private fun finishOcr(text:String,file:File,startId:Int) {
        main.post {
            sendBroadcast(Intent(ACTION_OCR_RESULT).setPackage(packageName).putExtra(EXTRA_TEXT,text).putExtra(EXTRA_IMAGE_PATH,file.absolutePath))
            stopSelf(startId)
            main.postDelayed({ Process.killProcess(Process.myPid()) },450)
        }
    }
    companion object {
        const val ACTION_OCR_RESULT="com.example.draggo.OCR_RESULT"
        const val EXTRA_TEXT="ocr_text"
        const val EXTRA_IMAGE_PATH="image_path"
    }
}
