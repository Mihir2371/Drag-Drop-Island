package com.akira.island.service

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.geometry.Rect

/**
 * Additive Origin Island-style Drag & Go: accept text/images dropped onto the island
 * and offer Android's compatible share destinations. Keeps content in the source app
 * until the user explicitly chooses a destination.
 */
internal object DragGo {
    fun accepts(event: DragEvent): Boolean {
        val description = event.clipDescription ?: return false
        return (0 until description.mimeTypeCount).any { index ->
            val type = description.getMimeType(index)
            type.startsWith("text/") || type.startsWith("image/") ||
                type == "application/uri-list"
        }
    }

    fun isOverIsland(x: Float, y: Float, bounds: List<Rect>): Boolean =
        bounds.any { !it.isEmpty && it.contains(x, y) }

    fun drop(context: android.content.Context, event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val item = clip.getItemAt(0)
        val uri: Uri? = item.uri
        val text = item.text?.toString()
        val mime = event.clipDescription?.let { desc ->
            (0 until desc.mimeTypeCount).firstNotNullOfOrNull { i ->
                desc.getMimeType(i).takeIf { it.startsWith("image/") || it.startsWith("text/") }
            }
        } ?: "text/plain"

        val share = if (uri != null) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime.takeIf { it.startsWith("image/") } ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Dragged item", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else if (!text.isNullOrBlank()) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newPlainText("Dragged text", text)
            }
        } else return false

        return runCatching {
            context.startActivity(Intent.createChooser(share, "Drag & Go").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    fun listener(bounds: () -> List<Rect>): View.OnDragListener = View.OnDragListener { _, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> accepts(event)
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION ->
                isOverIsland(event.x, event.y, bounds())
            DragEvent.ACTION_DRAG_EXITED -> true
            DragEvent.ACTION_DROP -> isOverIsland(event.x, event.y, bounds()) && dropFromEvent(event)
            DragEvent.ACTION_DRAG_ENDED -> true
            else -> false
        }
    }

    // The context is supplied by the host view at drop time.
    private fun dropFromEvent(event: DragEvent): Boolean = false
}
