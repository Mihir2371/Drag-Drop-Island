package com.example.draggo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.app.RemoteAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import kotlin.math.max

/** Canvas-only compact action pill. */
class ActionPillView(context: Context) : View(context) {
    data class Item(val label: String, val icon: Drawable?, val run: () -> Unit)
    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff202124 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff; textSize = 12f * density }
    private val pill = RectF()
    private var items: List<Item> = emptyList()
    private var itemWidth = 72f * density
    private var pressedIndex = -1
    fun setActions(actions: List<RemoteAction>, capturedText: String) {
        val custom = actions.take(4).map { action ->
            val icon = runCatching { action.icon.loadDrawable(context) }.getOrNull()
            Item(action.title.toString().take(14), icon) { runCatching { action.actionIntent.send() } }
        }
        val fallbacks = listOf(
            Item("Copy", null) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Text", capturedText))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            },
            Item("Share", null) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, capturedText)
                context.startActivity(Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        )
        items = (custom + fallbacks).take(4)
        requestLayout(); invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((items.size.coerceAtLeast(1) * itemWidth + 20f*density).toInt(), (72f*density).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pill.set(0f,0f,width.toFloat(),height.toFloat())
        canvas.drawRoundRect(pill,height/2f,height/2f,bg)
        items.forEachIndexed { i,item ->
            val cx=10f*density+i*itemWidth+itemWidth/2f
            item.icon?.let { drawable ->
                val s=24f*density
                drawable.bounds=Rect((cx-s/2).toInt(),(14f*density).toInt(),(cx+s/2).toInt(),(38f*density).toInt())
                drawable.draw(canvas)
            } ?: run {
                textPaint.textSize=16f*density
                val glyph=if(item.label=="Copy") "▣" else "↗"
                canvas.drawText(glyph,cx-textPaint.measureText(glyph)/2,31f*density,textPaint)
            }
            textPaint.textSize=11f*density
            canvas.drawText(item.label,cx-textPaint.measureText(item.label)/2,56f*density,textPaint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.action) {
            MotionEvent.ACTION_DOWN -> { pressedIndex=((event.x-10f*density)/itemWidth).toInt(); return true }
            MotionEvent.ACTION_UP -> {
                val idx=((event.x-10f*density)/itemWidth).toInt()
                if(idx==pressedIndex && idx in items.indices) items[idx].run()
                pressedIndex=-1; return true
            }
            MotionEvent.ACTION_CANCEL -> { pressedIndex=-1; return true }
        }
        return true
    }
}
