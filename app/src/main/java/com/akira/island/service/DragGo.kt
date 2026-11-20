package com.akira.island.service

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import android.view.DragEvent
import android.view.DragAndDropPermissions
import android.view.View
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dynamic Drag & Go destination discovery and island picker state. */
internal object DragGo {
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
    private var dragPermissions: DragAndDropPermissions? = null

    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

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
        dragPermissions?.release()
        dragPermissions = null
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

    private fun copyToShareCache(context: Context, source: Uri, mime: String): Uri? {
        return runCatching {
            val dir = File(context.cacheDir, "draggo").apply { mkdirs() }
            // Remove stale copies so temporary shared images don't accumulate indefinitely.
            dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 24L * 60L * 60L * 1000L }?.forEach { it.delete() }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.isNotBlank() } ?: "dat"
            val file = File(dir, "drop-${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun sendToTarget(context: Context, event: DragEvent): Boolean {
        val clip = event.clipData ?: return false
        if (clip.itemCount == 0) return false
        val selectedKey = _selectedTarget.value ?: return false
        val target = _targets.value.firstOrNull { targetKey(it) == selectedKey } ?: return false
        val activity = target.activityInfo ?: return false
        val item = clip.getItemAt(0)
        // Some Android image drag sources expose the URI through an embedded Intent rather than Item.uri.
        val uri = item.uri
            ?: item.intent?.data
            ?: item.intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: item.intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: item.text?.toString()?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("content://") || it.startsWith("file://") }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val text = item.text?.toString()
        val mime = mimeFor(context, event, uri)
        val isText = mime.startsWith("text/") || mime == "application/uri-list"
        if (uri == null && text.isNullOrBlank()) return false
        // Drag source grants may expire when the drag session ends; share an app-owned copy.
        val outgoingUri = if (!isText && uri != null) copyToShareCache(context, uri, mime) ?: run {
            android.widget.Toast.makeText(context, "Couldn't read the dropped image.", android.widget.Toast.LENGTH_SHORT).show()
            dismiss()
            return false
        } else uri
        val send = Intent(Intent.ACTION_SEND).apply {
            setType(mime)
            if (isText) {
                putExtra(Intent.EXTRA_TEXT, text ?: uri?.toString())
                clipData = if (uri != null) {
                    ClipData.newUri(context.contentResolver, "Drag & Go", uri)
                } else {
                    ClipData.newPlainText("Drag & Go", text)
                }
            } else if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, outgoingUri)
                // Send an app-owned cache URI with an explicit read grant.
                clipData = ClipData.newUri(context.contentResolver, "Drag & Go", outgoingUri)
            } else {
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newPlainText("Drag & Go", text)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setClassName(activity.packageName, activity.name)
        }
        val launchResult = runCatching { context.startActivity(send) }
        if (launchResult.isFailure) {
            android.widget.Toast.makeText(context, "Couldn't open ${activity.packageName} for sharing. Try another destination.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Opening ${activity.packageName}…", android.widget.Toast.LENGTH_SHORT).show()
        }
        dismiss()
        return launchResult.isSuccess
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
        // Prefer the provider's concrete MIME. URI-list is only a transport MIME,
        // not the actual file type.
        uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull()?.let { type ->
            if (type != "text/uri-list" && type != "application/uri-list") return type
        } }
        val description = event.clipDescription
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                val type = description.getMimeType(i)
                if (type != "text/uri-list" && type != "application/uri-list") return type
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
                    // Start a fresh session only for supported payloads. Keep this
                    // signal alive through Android's transient enter/exit callbacks.
                    dismiss()
                    val accepted = accepts(event)
                    if (accepted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        dragPermissions = findActivity(hostView.context)?.requestDragAndDropPermissions(event)
                    }
                    _sessionActive.value = accepted
                    accepted
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
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
                    _edgeScroll.value = 0
                    val origin = screenOrigin()
                    selectAt(origin[0] + event.x, origin[1] + event.y)
                    sendToTarget(context, event)
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    // End session on every system completion path, including drops outside the island.
                    dismiss()
                    true
                }
                else -> false
            }
        }
}
