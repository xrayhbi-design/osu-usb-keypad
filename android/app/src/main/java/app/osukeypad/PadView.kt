package app.osukeypad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * The whole UI. Two modes:
 *  - pad:    two big touch zones (left/right), sends key down/up straight from the touch handler.
 *  - picker: long-press a zone -> pick its key from a grid; slider at the bottom moves the split.
 */
@SuppressLint("ViewConstructor")
class PadView(ctx: Context, private val link: Link) : View(ctx) {

    private class Key(val label: String, val vk: Int)

    private val keys: List<Key> = ArrayList<Key>().apply {
        for (c in 'A'..'Z') add(Key(c.toString(), c.code))
        for (c in '0'..'9') add(Key(c.toString(), c.code))
        add(Key("Space", 0x20)); add(Key("Enter", 0x0D)); add(Key("Esc", 0x1B)); add(Key("Tab", 0x09))
        add(Key("LShift", 0xA0)); add(Key("LCtrl", 0xA2))
        add(Key("Left", 0x25)); add(Key("Up", 0x26)); add(Key("Right", 0x27)); add(Key("Down", 0x28))
        add(Key(";", 0xBA)); add(Key(",", 0xBC)); add(Key(".", 0xBE)); add(Key("/", 0xBF))
    }

    private val prefs = ctx.getSharedPreferences("keypad", Context.MODE_PRIVATE)
    private val vk = intArrayOf(prefs.getInt("vk0", 0x5A), prefs.getInt("vk1", 0x58))   // Z, X
    private var split = prefs.getFloat("split", 0.5f).coerceIn(MIN_SPLIT, MAX_SPLIT)

    // pad state
    private val zoneOf = IntArray(32) { -1 }      // pointer id -> zone
    private val held = IntArray(2)                // fingers currently down per zone
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = arrayOf(Runnable { onLongPress(0) }, Runnable { onLongPress(1) })

    // picker state
    private var picking = -1                      // zone being edited, -1 = pad mode
    private var downCell = -2
    private var sliding = false
    private var precise = false
    private var lastX = 0f
    private var anchorX = 0f
    private var anchorVal = 0f
    private val preciseRun = Runnable {
        if (sliding) { precise = true; anchorX = lastX; anchorVal = split; invalidate() }
    }

    // paints / scratch (no allocation in onDraw)
    private val idle = Color.rgb(28, 28, 30)
    private val active = Color.rgb(58, 123, 213)
    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
    }

    fun releaseAll() {
        for (z in 0..1) {
            handler.removeCallbacks(longPress[z])
            if (held[z] > 0) link.send(vk[z], false)
            held[z] = 0
        }
        java.util.Arrays.fill(zoneOf, -1)
        invalidate()
    }

    private fun labelOf(code: Int) = keys.firstOrNull { it.vk == code }?.label ?: "?"

    private fun onLongPress(z: Int) {
        if (picking >= 0 || held[z] != 1) return
        if (link.connected && !ALLOW_SETUP_WHILE_CONNECTED) return   // would drop a held key mid-song
        releaseAll()
        picking = z
        downCell = -2
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, rr: Int, b: Int) {
        super.onLayout(changed, l, t, rr, b)
        // stop edge swipes from triggering Back / app switching while playing
        systemGestureExclusionRects = listOf(Rect(0, 0, rr - l, b - t))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (picking >= 0) pickTouch(e) else padTouch(e)
        return true
    }

    // ---------------------------------------------------------------- pad mode

    private fun padTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { requestUnbufferedDispatch(e); press(e, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> press(e, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
    }

    private fun press(e: MotionEvent, idx: Int) {
        val id = e.getPointerId(idx)
        if (id >= zoneOf.size) return
        val z = if (e.getX(idx) < width * split) 0 else 1
        zoneOf[id] = z
        if (++held[z] == 1) {
            link.send(vk[z], true)                    // first: get the key to the PC
            handler.postDelayed(longPress[z], LONG_PRESS_MS)
            invalidate()
        }
    }

    private fun release(id: Int) {
        if (id >= zoneOf.size) return
        val z = zoneOf[id]
        if (z < 0) return
        zoneOf[id] = -1
        if (--held[z] == 0) {
            link.send(vk[z], false)
            handler.removeCallbacks(longPress[z])
            invalidate()
        }
    }

    // ---------------------------------------------------------------- picker mode

    private val cols = 13
    private val rows = 4
    private fun gridTop() = height * 0.14f
    private fun cellH() = height * 0.15f
    private fun sliderTop() = height * 0.78f

    /** 0 = Back, 1.. = keys[i-1], -2 = no cell */
    private fun cellAt(x: Float, y: Float): Int {
        val c = (x / (width / cols.toFloat())).toInt()
        val rw = ((y - gridTop()) / cellH()).toInt()
        if (c !in 0 until cols || rw !in 0 until rows) return -2
        val i = rw * cols + c
        return if (i <= keys.size) i else -2
    }

    private fun pickTouch(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCell = -2
                if (y >= sliderTop()) {
                    sliding = true; precise = false; lastX = x
                    setCoarse(x)
                    handler.postDelayed(preciseRun, PRECISE_MS)
                } else if (y >= gridTop()) {
                    downCell = cellAt(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> if (sliding) {
                lastX = x
                if (precise) {
                    split = (anchorVal + (x - anchorX) / width * 0.1f).coerceIn(MIN_SPLIT, MAX_SPLIT)
                } else {
                    setCoarse(x)
                    handler.removeCallbacks(preciseRun)           // hold still to switch to fine mode
                    handler.postDelayed(preciseRun, PRECISE_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(preciseRun)
                if (sliding) {
                    prefs.edit().putFloat("split", split).apply()
                } else if (downCell >= 0 && cellAt(x, y) == downCell) {
                    if (downCell > 0) {
                        vk[picking] = keys[downCell - 1].vk
                        prefs.edit().putInt(if (picking == 0) "vk0" else "vk1", vk[picking]).apply()
                    }
                    picking = -1                                  // applied immediately, back to the pad
                }
                sliding = false; precise = false; downCell = -2
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(preciseRun)
                sliding = false; precise = false; downCell = -2
            }
        }
    }

    private fun setCoarse(x: Float) {
        split = (x / width).coerceIn(MIN_SPLIT, MAX_SPLIT)
    }

    // ---------------------------------------------------------------- drawing

    private fun centered(s: String, cx: Float, cy: Float, size: Float, color: Int, c: Canvas) {
        text.textSize = size
        text.color = color
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (picking >= 0) drawPicker(c, w, h) else drawPad(c, w, h)
    }

    private fun drawPad(c: Canvas, w: Float, h: Float) {
        val sx = w * split
        val gap = 3f
        for (z in 0..1) {
            r.set(if (z == 0) 0f else sx + gap, 0f, if (z == 0) sx - gap else w, h)
            fill.color = if (held[z] > 0) active else idle
            c.drawRect(r, fill)
            centered(labelOf(vk[z]), r.centerX(), r.centerY(), minOf(r.width(), h) * 0.38f, Color.WHITE, c)
        }
        val s = if (link.connected) {
            val us = link.rttUs
            if (us >= 0) "Connected   USB round trip " + String.format("%.1f ms", us / 1000.0) else "Connected"
        } else "Disconnected"
        centered(s, w / 2f, h * 0.045f, h * 0.04f, if (link.connected) Color.rgb(90, 200, 110) else Color.rgb(200, 90, 80), c)
    }

    private fun drawPicker(c: Canvas, w: Float, h: Float) {
        centered("Key for " + (if (picking == 0) "LEFT" else "RIGHT") + " button  (now: " + labelOf(vk[picking]) + ")  - tap a key",
            w / 2f, h * 0.07f, h * 0.05f, Color.WHITE, c)
        val cw = w / cols
        val ch = cellH()
        for (i in 0..keys.size) {
            val col = i % cols
            val row = i / cols
            r.set(col * cw + 4f, gridTop() + row * ch + 4f, (col + 1) * cw - 4f, gridTop() + (row + 1) * ch - 4f)
            val key = if (i == 0) null else keys[i - 1]
            fill.color = if (key != null && key.vk == vk[picking]) active else idle
            c.drawRoundRect(r, 12f, 12f, fill)
            centered(if (key == null) "Back" else key.label, r.centerX(), r.centerY(), minOf(cw, ch) * 0.32f, Color.WHITE, c)
        }
        // split slider
        val top = sliderTop()
        val mid = top + (h - top) * 0.6f
        centered("Left " + Math.round(split * 100) + "%   |   Right " + Math.round((1 - split) * 100) + "%" +
            (if (precise) "   (fine)" else "   drag; hold still for fine adjust"),
            w / 2f, top + (h - top) * 0.2f, h * 0.04f, Color.LTGRAY, c)
        fill.color = idle
        r.set(0f, mid - 6f, w, mid + 6f)
        c.drawRect(r, fill)
        fill.color = if (sliding) active else Color.GRAY
        c.drawCircle(split * w, mid, h * 0.05f, fill)
    }

    companion object {
        const val MIN_SPLIT = 0.2f
        const val MAX_SPLIT = 0.8f
        const val LONG_PRESS_MS = 800L
        const val PRECISE_MS = 450L

        /**
         * Long-press-to-change-key is ignored while the PC host is connected: osu! sliders need a key
         * held for seconds, and a long press would pop up the menu and release the key mid-song.
         * Close the PC host (or unplug) to change keys. Set to true to allow it any time.
         */
        const val ALLOW_SETUP_WHILE_CONNECTED = false
    }
}
