package com.akira.island.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.akira.island.service.DragGo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Transparent Activity drop receiver prototype; launch before the drag for split-screen testing. */
class DragReceiverExperimentActivity : Activity() {
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        status = TextView(this).apply {
            text = "Drag & Go receiver\nDrop one file or image"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0xCC202228.toInt())
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 48 })
        root.setOnDragListener { _, event -> handleDrag(event) }
        setContentView(root)
    }

    private fun handleDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val desc = event.clipDescription ?: return false
                val accepted = (0 until desc.mimeTypeCount).any { i ->
                    val mime = desc.getMimeType(i)
                    mime.startsWith("image/") || mime.startsWith("video/") ||
                        mime.startsWith("audio/") || mime.startsWith("application/") || mime.startsWith("text/")
                }
                status.text = if (accepted) "Drop one item here" else "Unsupported drag"
                return accepted
            }
            DragEvent.ACTION_DRAG_ENTERED -> { status.text = "Release to receive"; return true }
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> return true
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData
                if (clip == null) { showStatus("Drop contained no ClipData"); return true }
                // Activity-only URI grant API must be called synchronously during ACTION_DROP.
                val permissions = requestDragAndDropPermissions(event)
                val mimeList = event.clipDescription?.let { d ->
                    (0 until d.mimeTypeCount).map { d.getMimeType(it) }
                }.orEmpty()
                if (clip.itemCount != 1) {
                    permissions?.release()
                    showStatus("Only one item per drop is supported")
                    Toast.makeText(this, "Drag & Go accepts a single item", Toast.LENGTH_SHORT).show()
                    return true
                }
                val item = clip.getItemAt(0)
                val uri = item.uri ?: item.intent?.data
                    ?: item.intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uri == null) {
                    permissions?.release()
                    showStatus("Dropped item has no URI")
                    return true
                }
                val mime = contentResolver.getType(uri) ?: mimeList.firstOrNull() ?: "application/octet-stream"
                showStatus("Reading dropped item…")
                io.execute {
                    val result = runCatching {
                        val dir = File(cacheDir, "draggo/received").apply { mkdirs() }
                        val file = File(dir, "drop-${System.currentTimeMillis()}")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        } ?: error("Unable to open dropped URI")
                        file to mime
                    }
                    permissions?.release()
                    runOnUiThread {
                        result.fold(
                            { (file, type) ->
                                showStatus("Received ${file.length()} bytes")
                                Toast.makeText(this, "Drop copied — opening share sheet", Toast.LENGTH_LONG).show()
                                launchShare(file, type)
                            },
                            { err ->
                                showStatus("Read failed: ${err.message ?: err.javaClass.simpleName}")
                                Toast.makeText(this, "Couldn't read dropped item", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return false
    }

    private fun showStatus(message: String) { status.text = message }

    private fun launchShare(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        // Hand off the cached file to the island's own target picker instead of
        // launching a standalone chooser. The Activity can now close; picker state
        // is held in DragGo and rendered by the accessibility overlay.
        DragGo.activateReceivedFile(this, uri, mime)
        finish()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}