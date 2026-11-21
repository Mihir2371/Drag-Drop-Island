package com.akira.island.service

import android.util.Log
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.webkit.MimeTypeMap
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dynamic Drag & Go destination discovery and island picker state. */
internal object DragGo {
    private const val TAG = "DragGo-Debug"

    private fun uriSummary(uri: Uri?): String = uri?.let { "scheme=${it.scheme}, authority=${it.authority}" } ?: "null"
    private val _targets = MutableStateFlow<List<ResolveInfo>>(emptyList())
    val targets = _targets.asStateFlow()
    private val _selectedTarget = MutableStateFlow<String?>(null)
    val selectedTarget = _selectedTarget.asStateFlow()
    // Session lifecycle is independent of the destination list: targets can be empty
    // briefly while Android delivers drag-enter/exit events.
    private val _sessionActive = MutableStateFlow(false)
    private val _edgeScroll = MutableStateFlow(0)
    val edgeScroll = _edgeScroll.asStateFlow()
    private val pickerBounds = MutableStateFlow(Rect.Zero)
    fun updatePickerBounds(bounds: Rect) { pickerBounds.value = bounds }
    val sessionActive = _sessionActive.asStateFlow()
    private val targetBounds = mutableMapOf<String, Rect>()
    private var pendingMimeTypes: List<String> = emptyList()
    private var preparedForCurrentDrag = false

    fun targetKey(info: ResolveInfo): String =
        info.activityInfo?.let { "${it.packageName}/${it.name}" }.orEmpty()

    fun updateTargetBounds(key: String, bounds: Rect) {
        if (key.isNotBlank()) targetBounds[key] = bounds
    }

    private fun selectAt(x: Float, y: Float) {
        val next = targetBounds.entries.firstOrNull { it.value.contains(androidx.compose.ui.geometry.Offset(x, y)) }?.key
        if (next != _selectedTarget.value) _selectedTarget.value = next
    }

    fun dismiss() {
        _sessionActive.value = false
        _edgeScroll.value = 0
        pendingMimeTypes = emptyList()
        preparedForCurrentDrag = false
        targetBounds.clear()
        _selectedTarget.value = null
        _targets.value = emptyList()
    }

    private fun prepareTargets(context: Context, event: DragEvent) {
        if (preparedForCurrentDrag) return
        val description = event.clipDescription ?: return
        val mimeTypes = (0 until description.mimeTypeCount)
            .map { description.getMimeType(it) }
            .filter { type -> type.startsWith("text/") || type.startsWith("image/") ||
                type.startsWith("video/") || type.startsWith("audio/") ||
                type.startsWith("application/") || type == "application/uri-list" }
            .distinct()
        if (mimeTypes.isEmpty()) return
        preparedForCurrentDrag = true
        pendingMimeTypes = mimeTypes
        val pm = context.packageManager
        val compatible = mimeTypes.flatMap { mime ->
            pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime), 0)
        }.filter { it.activityInfo?.packageName != context.packageName }
            .distinctBy { targetKey(it) }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        targetBounds.clear()
        pickerBounds.value = Rect.Zero
        _selectedTarget.value = null
        _targets.value = compatible
    }

    private fun edgeDirection(screenX: Float, context: Context): Int {
        val rect = pickerBounds.value
        if (rect.isEmpty) return 0
        val edge = 36f * context.resources.displayMetrics.density
        return when { screenX <= rect.left + edge -> -1; screenX >= rect.right - edge -> 1; else -> 0 }
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
        // Prefer concrete MIME types. Wildcards such as image/* are useful for
        // discovery, but some receivers require image/jpeg or image/png.
        fun concrete(type: String): String? {
            if (type == "text/uri-list" || type == "application/uri-list") return null
            if (!type.contains("*")) return type
            if (type.startsWith("image/") && uri != null) {
                val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                    ?.takeIf { it.startsWith("image/") && !it.contains("*") }?.let { return it }
            }
            return null
        }
        uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull()?.let(::concrete)?.let { return it } }
        val description = event.clipDescription
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                concrete(description.getMimeType(i))?.let { return it }
            }
        }
        uri?.let {
            val extension = MimeTypeMap.getFileExtensionFromUrl(it.toString())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())?.let { return it }
        }
        return if (uri != null) "application/octet-stream" else "text/plain"
    }

    fun listener(
        context: Context,
        bounds: () -> List<Rect>,
        screenOrigin: () -> IntArray,
        enabled: () -> Boolean,
    ): View.OnDragListener =
        View.OnDragListener { hostView, event ->
            if (!enabled()) {
                if (_targets.value.isNotEmpty()) dismiss()
                return@OnDragListener false
            }
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    Log.d(TAG, "DRAG_STARTED: context=${hostView.context.javaClass.name}, payloadMimeTypes=${(0 until (event.clipDescription?.mimeTypeCount ?: 0)).map { event.clipDescription!!.getMimeType(it) }}")
                    // Start a fresh session only for supported payloads. Keep this
                    // signal alive through Android's transient enter/exit callbacks.
                    dismiss()
                    val accepted = accepts(event)
                    // This is an Accessibility overlay, not an Activity. It cannot acquire
                    // the Activity-bound drag URI permission here.
                    Log.d(TAG, "Accessibility overlay received drag; Activity permission request unavailable")
                    _sessionActive.value = accepted
                    android.widget.Toast.makeText(context, if (accepted) "DragGo: drag started" else "DragGo: unsupported drag", android.widget.Toast.LENGTH_SHORT).show()
                    accepted
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    Log.d(TAG, "DRAG_ENTERED")
                    // The touch window expands during the drag; do not reject events
                    // based on host-local coordinates. Target selection uses screen bounds.
                    prepareTargets(context, event)
                    val origin = screenOrigin()
                    val screenX = origin[0] + event.x
                    selectAt(screenX, origin[1] + event.y)
                    _edgeScroll.value = edgeDirection(screenX, context)
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val origin = screenOrigin()
                    val screenX = origin[0] + event.x
                    selectAt(screenX, origin[1] + event.y)
                    _edgeScroll.value = edgeDirection(screenX, context)
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    _edgeScroll.value = 0
                    _selectedTarget.value = null
                    true
                }
                DragEvent.ACTION_DROP -> {
                    Log.d(TAG, "DROP received")
                    android.widget.Toast.makeText(context, "DragGo: drop received", android.widget.Toast.LENGTH_SHORT).show()
                    _edgeScroll.value = 0
                    val origin = screenOrigin()
                    selectAt(origin[0] + event.x, origin[1] + event.y)
                    sendToTarget(context, event)
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    Log.d(TAG, "DRAG_ENDED")
                    // End session on every system completion path, including drops outside the island.
                    dismiss()
                    true
                }
                else -> false
            }
        }
}
