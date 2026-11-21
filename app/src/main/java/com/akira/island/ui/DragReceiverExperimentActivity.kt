package com.akira.island.ui

import android.app.Activity
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Experimental Activity receiver for verifying cross-app URI permissions. */
class DragReceiverExperimentActivity : Activity() {
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 48, 40, 48)
            setBackgroundColor(0xFF15171B.toInt())
        }
        status = TextView(this).apply {
            text = "Drag a file or image here.\n\nFor cross-app testing, use split-screen alongside the source app."
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.setOnDragListener { _, event -> handleDrag(event) }
        setContentView(root)
    }

    private fun handleDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val desc = event.clipDescription ?: return false
                val accepted = (0 until desc.mimeTypeCount).any { i ->
                    val m = desc.getMimeType(i)
                    m.startsWith("image/") || m.startsWith("video/") ||
                        m.startsWith("audio/") || m.startsWith("application/") || m.startsWith("text/")
                }
                status.text = if (accepted) "Drag detected — drop the file here." else "Unsupported drag."
                return accepted
            }
            DragEvent.ACTION_DRAG_ENTERED -> { status.text = "Release to receive the file."; return true }
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> return true
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData ?: return false
                // Request permission synchronously while handling the actual drop.
                val permissions = requestDragAndDropPermissions(event)
                val item = clip.getItemAt(0)
                val uri = item.uri ?: item.intent?.data
                if (uri == null) {
                    permissions?.release()
                    status.text = "Drop received, but no content URI.\nText: ${item.text ?: "(none)"}"
                    return true
                }
                status.text = "Drop received. Testing URI read permission…"
                io.execute {
                    val result = runCatching {
                        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                        val outFile = File(cacheDir, "dnd-received-${System.currentTimeMillis()}")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        } ?: error("Provider returned no stream")
                        "${outFile.name}\nMIME: $mime\nBytes: ${outFile.length()}\nURI: $uri"
                    }
                    permissions?.release()
                    runOnUiThread {
                        status.text = result.fold(
                            { "SUCCESS: URI permission worked; copied into app cache.\n\n$it" },
                            { "FAILED to read URI: ${it.javaClass.simpleName}: ${it.message}\n\nURI: $uri" }
                        )
                        Toast.makeText(this, if (result.isSuccess) "Receiver test: SUCCESS" else "Receiver test: FAILED", Toast.LENGTH_LONG).show()
                    }
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return false
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
