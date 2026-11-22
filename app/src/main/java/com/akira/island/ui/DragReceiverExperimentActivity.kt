package com.akira.island.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Activity drop receiver: obtains temporary drag URI permission, copies to app cache, then shares the copy. */
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
                    val mime = desc.getMimeType(i)
                    mime.startsWith("image/") || mime.startsWith("video/") ||
                        mime.startsWith("audio/") || mime.startsWith("application/") || mime.startsWith("text/")
                }
                val mimes = (0 until desc.mimeTypeCount).joinToString(", ") { desc.getMimeType(it) }
                status.text = "DRAG STARTED\nMIME types: $mimes\nAccepted: $accepted\n\nDrop the item here."
                return accepted
            }
            DragEvent.ACTION_DRAG_ENTERED -> {
                status.text = "Drag entered — release to receive and share."
                return true
            }
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> return true
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData
                if (clip == null) {
                    status.text = "DROP RECEIVED\nClipData is NULL."
                    return true
                }

                // Activity-only API: call synchronously while handling the actual ACTION_DROP.
                val permissions = requestDragAndDropPermissions(event)
                val mimeList = event.clipDescription?.let { d ->
                    (0 until d.mimeTypeCount).map { d.getMimeType(it) }
                }.orEmpty()
                if (clip.itemCount != 1) {
                    permissions?.release()
                    status.text = "DROP REJECTED\\nThis experiment accepts exactly one item per drop.\\nReceived: ${clip.itemCount}"
                    Toast.makeText(this, "Drag & Go test accepts one item only", Toast.LENGTH_SHORT).show()
                    return true
                }
                val details = buildString {
                    append("DROP RECEIVED\nClip items: ${clip.itemCount}\nMIME types: ${mimeList.joinToString(", ")}\n\n")
                    for (i in 0 until clip.itemCount) {
                        val item = clip.getItemAt(i)
                        append("Item $i:\nURI: ${item.uri ?: "(null)"}\nIntent data: ${item.intent?.data ?: "(null)"}\nText: ${item.text ?: "(null)"}\nHTML: ${item.htmlText ?: "(null)"}\n\n")
                    }
                }
                val uri = (0 until clip.itemCount).mapNotNull { i ->
                    val item = clip.getItemAt(i)
                    item.uri ?: item.intent?.data
                }.firstOrNull()
                if (uri == null) {
                    permissions?.release()
                    status.text = "$details\nRESULT: No URI found in ClipData."
                    return true
                }
                status.text = "$details\nURI found: $uri\nReading dropped content…"
                io.execute {
                    val result = runCatching {
                        val mime = contentResolver.getType(uri) ?: mimeList.firstOrNull() ?: "application/octet-stream"
                        val dir = File(cacheDir, "draggo/received").apply { mkdirs() }
                        val file = File(dir, "dnd-received-${System.currentTimeMillis()}")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        } ?: error("ContentResolver.openInputStream returned null")
                        file to mime
                    }
                    permissions?.release()
                    runOnUiThread {
                        status.text = result.fold(
                            { (file, mime) -> "$details\nURI found: $uri\n\nREAD SUCCESS\nSaved: ${file.name}\nMIME: $mime\nBytes: ${file.length()}\nLaunching Android share sheet…" },
                            { error -> "$details\nURI found: $uri\n\nREAD FAILED\n${error.javaClass.simpleName}: ${error.message}" }
                        )
                        Toast.makeText(this, if (result.isSuccess) "Drop read — opening share sheet" else "Drop read failed", Toast.LENGTH_LONG).show()
                        result.getOrNull()?.let { (file, mime) -> launchShare(file, mime) }
                    }
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return false
    }

    private fun launchShare(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share dropped item"))
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
