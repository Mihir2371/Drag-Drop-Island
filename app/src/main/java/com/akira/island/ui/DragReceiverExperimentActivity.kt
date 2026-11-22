package com.akira.island.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.akira.island.service.DragGo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** A transparent, island-sized floating Activity that is a drag target only over the island. */
class DragReceiverExperimentActivity : Activity() {
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        applyBounds(intent)
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnDragListener { _, event -> handleDrag(event) }
        }
        status = TextView(this).apply {
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyBounds(intent)
    }

    private fun applyBounds(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_ACTIVE, false)) {
            finish()
            return
        }
        val w = intent.getIntExtra(EXTRA_WIDTH, 1).coerceAtLeast(1)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 1).coerceAtLeast(1)
        val lp = window.attributes
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = intent.getIntExtra(EXTRA_X, 0)
        lp.y = intent.getIntExtra(EXTRA_Y, 0)
        window.attributes = lp
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
                return accepted
            }
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> return true
            DragEvent.ACTION_DROP -> {
                val clip = event.clipData ?: run { showStatus("Drop contained no ClipData"); return true }
                // Android's URI grant must be requested synchronously during ACTION_DROP.
                val permissions = requestDragAndDropPermissions(event)
                val mimeList = event.clipDescription?.let { d ->
                    (0 until d.mimeTypeCount).map { d.getMimeType(it) }
                }.orEmpty()
                if (clip.itemCount != 1) {
                    permissions?.release()
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
                                Toast.makeText(this, "Drop copied — opening island picker", Toast.LENGTH_LONG).show()
                                launchShare(file, type)
                            },
                            { Toast.makeText(this, "Couldn't read dropped item", Toast.LENGTH_LONG).show() }
                        )
                    }
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return false
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun launchShare(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        DragGo.activateReceivedFile(this, uri, mime)
        finish()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACTIVE = "drag_receiver_active"
        const val EXTRA_X = "drag_receiver_x"
        const val EXTRA_Y = "drag_receiver_y"
        const val EXTRA_WIDTH = "drag_receiver_width"
        const val EXTRA_HEIGHT = "drag_receiver_height"
    }
}
