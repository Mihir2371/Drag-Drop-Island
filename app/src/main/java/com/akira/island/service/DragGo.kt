package com.akira.island.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.geometry.Rect

/** Additive drag-to-share handling for text and image payloads. */
internal object DragGo {
    fun accepts(event: DragEvent): Boolean {
        val description = event.clipDescription ?: return false
        return (0 until description.mimeTypeCount).any { i ->
            val type = description.getMimeType(i)
            type.startsWith("text/") || type.startsWith("image/") || type == "application/uri-list"
        }
    }

    fun isOverIsland(x: Float, y: Float, bounds: List<Rect>) =
        bounds.any { !it.isEmpty && it.contains(x, y) }

    fun drop(context: Context, event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val item = clip.getItemAt(0)
        val uri: Uri? = item.uri
        val text = item.text?.toString()
        val mime = event.clipDescription?.let { d ->
            (0 until d.mimeTypeCount).firstNotNullOfOrNull { i ->
                d.getMimeType(i).takeIf { it.startsWith("image/") }
            }
        } ?: "image/*"

        val shareIntent = when {
            uri != null -> Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Dragged item", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            !text.isNullOrBlank() -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newPlainText("Dragged text", text)
            }
            else -> return false
        }
        return runCatching {
            val chooser = Intent.createChooser(shareIntent, "Drag & Go")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.isSuccess
    }

    fun listener(context: Context, bounds: () -> List<Rect>): View.OnDragListener =
        View.OnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> accepts(event)
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION ->
                    isOverIsland(event.x, event.y, bounds())
                DragEvent.ACTION_DRAG_EXITED -> true
                DragEvent.ACTION_DROP -> isOverIsland(event.x, event.y, bounds()) && drop(context, event)
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }
}
