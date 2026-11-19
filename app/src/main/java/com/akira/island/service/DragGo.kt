package com.akira.island.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dynamic Drag & Go destination discovery and island picker state. */
internal object DragGo {
    private val _targets = MutableStateFlow<List<ResolveInfo>>(emptyList())
    val targets = _targets.asStateFlow()
    private var pendingSend: Intent? = null

    fun dismiss() {
        pendingSend = null
        _targets.value = emptyList()
    }

    fun launchTarget(context: Context, info: ResolveInfo) {
        val base = pendingSend ?: return dismiss()
        val activity = info.activityInfo ?: return dismiss()
        val intent = Intent(base).apply {
            setClassName(activity.packageName, activity.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
        dismiss()
    }

    fun accepts(event: DragEvent): Boolean {
        val description = event.clipDescription ?: return false
        return (0 until description.mimeTypeCount).any { i ->
            val type = description.getMimeType(i)
            type.startsWith("text/") || type.startsWith("image/") ||
                type.startsWith("video/") || type.startsWith("audio/") ||
                type.startsWith("application/") || type == "application/uri-list"
        }
    }

    fun isOverIsland(x: Float, y: Float, bounds: List<Rect>) =
        bounds.any { !it.isEmpty && it.contains(androidx.compose.ui.geometry.Offset(x, y)) }

    private fun mimeFor(context: Context, event: DragEvent, uri: Uri?): String {
        uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull()?.let { return it } }
        val description = event.clipDescription
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                val type = description.getMimeType(i)
                if (type != "text/uri-list" && type != "application/uri-list") return type
            }
        }
        return "text/plain"
    }

    fun drop(context: Context, event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val item = clip.getItemAt(0)
        val uri = item.uri
        val text = item.text?.toString() ?: item.intent?.toUri(Intent.URI_INTENT_SCHEME)
        if (uri == null && text.isNullOrBlank()) return false
        val mime = mimeFor(context, event, uri)
        val isText = mime.startsWith("text/") || mime == "application/uri-list"
        val payloadText = text ?: uri?.toString()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            if (isText) {
                putExtra(Intent.EXTRA_TEXT, payloadText)
                clipData = ClipData.newPlainText("Dragged text", payloadText)
            } else if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Dragged content", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(Intent.EXTRA_TEXT, payloadText)
                clipData = ClipData.newPlainText("Dragged text", payloadText)
            }
        }
        val pm = context.packageManager
        val compatible = pm.queryIntentActivities(send, 0)
            .filter { it.activityInfo?.packageName != context.packageName }
            .distinctBy { it.activityInfo?.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (compatible.isEmpty()) {
            android.widget.Toast.makeText(context, "No compatible apps found", android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        pendingSend = send
        _targets.value = compatible
        return true
    }

    fun listener(context: Context, bounds: () -> List<Rect>, enabled: () -> Boolean): View.OnDragListener =
        View.OnDragListener { _, event ->
            if (!enabled()) {
                if (_targets.value.isNotEmpty()) dismiss()
                return@OnDragListener false
            }
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
