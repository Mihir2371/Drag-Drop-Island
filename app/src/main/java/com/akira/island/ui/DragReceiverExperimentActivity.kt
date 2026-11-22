package com.akira.island.ui

import android.app.Activity
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Experimental Activity receiver for diagnosing cross-app drag payloads and URI grants. */
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
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.TOP
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(status, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
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
                val mimes = (0 until desc.mimeTypeCount).joinToString(", ") { desc.getMimeType(it) }
                status.text = "DRAG STARTED\nMIME types: $mimes\nAccepted: $accepted\n\nDrop the item here."
                return accepted
            }
            DragEvent.ACTION_DRAG_ENTERED -> { status.text = "Drag entered — release to inspect payload."; return true }
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> return true
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData
                if (clip == null) {
                    status.text = "DROP RECEIVED\nClipData is NULL."
                    return true
                }

                // Must request drag permissions synchronously while handling the actual drop.
                val permissions = requestDragAndDropPermissions(event)
                val desc = event.clipDescription
                val mimes = desc?.let { d -> (0 until d.mimeTypeCount).joinToString(", ") { d.getMimeType(it) } } ?: "(no description)"
                val itemDiagnostics = (0 until clip.itemCount).map { index ->
                    val item = clip.getItemAt(index)
                    val uri = item.uri ?: item.intent?.data
                    buildString {
                        append("Item $index:")
                        append("\n  URI: ${item.uri ?: "(null)"}")
                        append("\n  Intent data: ${item.intent?.data ?: "(null)"}")
                        append("\n  Resolved URI: ${uri ?: "(null)"}")
                        append("\n  Text: ${item.text ?: "(null)"}")
                        append("\n  HTML: ${item.htmlText ?: "(null)"}")
                    }
                }
                val firstUri = (0 until clip.itemCount).mapNotNull { index ->
                    val item = clip.getItemAt(index)
                    item.uri ?: item.intent?.data
                }.firstOrNull()
                val details = "DROP RECEIVED\nClip items: ${clip.itemCount}\nMIME types: $mimes\n\n${itemDiagnostics.joinToString("\n\n")}"
                if (firstUri == null) {
                    permissions?.release()
                    status.text = "$details\n\nRESULT: NO URI FOUND IN ANY ITEM."
                    return true
                }

                status.text = "$details\n\nURI found: $firstUri\nRESULT: Testing read access…"
                io.execute {
                    val result = runCatching {
                        val mime = contentResolver.getType(firstUri) ?: "application/octet-stream"
                        val receivedDir = File(cacheDir, "draggo/received").apply { mkdirs() }
                        val outFile = File(receivedDir, "dnd-received-${System.currentTimeMillis()}")
                        contentResolver.openInputStream(firstUri)?.use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        } ?: error("ContentResolver.openInputStream returned null")
                        outFile to mime
                    }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return false
    }

    private fun openShareSheet(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share dropped item"))
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
