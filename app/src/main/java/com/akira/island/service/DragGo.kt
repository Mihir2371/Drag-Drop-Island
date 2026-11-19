package com.akira.island.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.DragEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.app.AlertDialog
import androidx.compose.ui.geometry.Rect

/** Additive drag-to-share handling with dynamically resolved app destinations. */
internal object DragGo {
    fun accepts(event: DragEvent): Boolean {
        val description = event.clipDescription ?: return false
        return (0 until description.mimeTypeCount).any { i ->
            val type = description.getMimeType(i)
            type.startsWith("text/") || type.startsWith("image/") ||
                type.startsWith("video/") || type == "application/uri-list" ||
                type == "application/pdf" || type == "application/octet-stream"
        }
    }

    fun isOverIsland(x: Float, y: Float, bounds: List<Rect>) =
        bounds.any { !it.isEmpty && it.contains(x, y) }

    private fun mimeFor(event: DragEvent, uri: Uri?): String {
        val description = event.clipDescription
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                val type = description.getMimeType(i)
                if (type != "text/plain" && type != "text/uri-list" && type != "application/uri-list") return type
            }
            if ((0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("text/") }) return "text/plain"
        }
        return uri?.let { runCatching { "content".takeIf { it == uri.scheme }?.let { "application/octet-stream" } }.getOrNull() }
            ?: "text/plain"
    }

    fun drop(context: Context, event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val item = clip.getItemAt(0)
        val uri = item.uri
        val text = item.text?.toString() ?: item.intent?.toUri(Intent.URI_INTENT_SCHEME)
        if (uri == null && text.isNullOrBlank()) return false
        val mime = mimeFor(event, uri)
        val isText = mime.startsWith("text/") || mime == "application/uri-list"
        val textPayload = text ?: uri?.toString()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            if (isText) {
                putExtra(Intent.EXTRA_TEXT, textPayload)
                clipData = ClipData.newPlainText("Dragged text", textPayload)
            } else if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Dragged content", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(Intent.EXTRA_TEXT, textPayload)
                clipData = ClipData.newPlainText("Dragged text", textPayload)
            }
        }

        val pm = context.packageManager
        val destinations = pm.queryIntentActivities(send, 0)
            .filter { it.activityInfo?.packageName != context.packageName }
            .distinctBy { it.activityInfo?.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (destinations.isEmpty()) {
            android.widget.Toast.makeText(context, "No compatible apps found", android.widget.Toast.LENGTH_SHORT).show()
            return true
        }

        val labels = destinations.map { it.loadLabel(pm).toString() }.toTypedArray()
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.select_dialog_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(24, 18, 24, 18)
                    textSize = 16f
                }
                val app = destinations[position]
                row.text = labels[position]
                row.setCompoundDrawablesWithIntrinsicBounds(app.loadIcon(pm), null, null, null)
                row.compoundDrawablePadding = 20
                return row
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Drag & Go · Send to")
            .setAdapter(adapter) { d, index ->
                val target = destinations.getOrNull(index)?.activityInfo ?: return@setAdapter
                val direct = Intent(send).apply {
                    setClassName(target.packageName, target.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(direct) }
                    .onFailure { android.widget.Toast.makeText(context, "Couldn't open destination", android.widget.Toast.LENGTH_SHORT).show() }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
        return runCatching {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.show()
            true
        }.getOrDefault(false)
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
