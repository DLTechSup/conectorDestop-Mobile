package com.dltechsup.conector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/**
 * Mostra a tela do PC, converte toques em mouse/teclado e permite ZOOM:
 * pinça para ampliar, dois dedos para mover. A região visível é enviada ao PC,
 * que recorta na resolução nativa (imagem ampliada nítida, não borrada).
 */
class RemoteView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Output {
        fun mouse(action: String, x: Float, y: Float, button: String = "left")
        fun scroll(dy: Int)
        fun key(name: String)
        fun text(s: String)
        /** Região visível mudou (zoom/pan): normalizada 0..1 sobre a tela do PC. */
        fun view(x: Float, y: Float, w: Float, h: Float)
        fun zoomChanged(zoom: Float)
    }

    enum class Mode { MOUSE, DRAG, SCROLL }

    var output: Output? = null
    var mode = Mode.MOUSE

    val zoomLevel: Float get() = zoom

    private var bitmap: Bitmap? = null
    private var fx = 0f; private var fy = 0f; private var fw = 1f; private var fh = 1f // região do quadro
    private var zoom = 1f
    private var vx = 0f; private var vy = 0f // canto sup. esq. da região visível
    private val fit = RectF()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var lastMove = 0L
    private var multi = false
    private var dragging = false
    private var scrollAcc = 0f
    private var lastTouchY = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var lastViewSent = 0L
    private var viewPending = false

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------ desenho / geometria
    fun setFrame(b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        bitmap = b
        fx = x; fy = y; fw = w; fh = h
        invalidate()
    }

    private fun computeFit(): Boolean {
        val b = bitmap ?: return false
        if (width == 0 || height == 0) return false
        // o recorte tem a mesma proporção da tela; usa a do quadro
        val aspect = (b.width / fw) / (b.height / fh)
        val vw = width.toFloat(); val vh = height.toFloat()
        val w: Float; val h: Float
        if (vw / vh > aspect) { h = vh; w = vh * aspect } else { w = vw; h = vw / aspect }
        fit.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        if (!computeFit()) return
        val fwid = fit.width(); val fhei = fit.height()
        dst.left = fit.left + (fx - vx) * zoom * fwid
        dst.top = fit.top + (fy - vy) * zoom * fhei
        dst.right = dst.left + fw * zoom * fwid
        dst.bottom = dst.top + fh * zoom * fhei
        c.save()
        c.clipRect(fit)
        c.drawBitmap(b, null, dst, paint)
        c.restore()
    }

    /** Toque na tela do celular -> coordenadas normalizadas da tela inteira do PC. */
    private fun norm(x: Float, y: Float): Pair<Float, Float>? {
        if (!computeFit()) return null
        val u = vx + ((x - fit.left) / fit.width()) / zoom
        val v = vy + ((y - fit.top) / fit.height()) / zoom
        return Pair(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------ zoom
    private fun clampView() {
        val size = 1f / zoom
        vx = vx.coerceIn(0f, 1f - size)
        vy = vy.coerceIn(0f, 1f - size)
    }

    fun resetZoom() {
        zoom = 1f; vx = 0f; vy = 0f
        applyView(force = true)
    }

    private fun applyView(force: Boolean = false) {
        clampView()
        invalidate()
        output?.zoomChanged(zoom)
        val now = System.currentTimeMillis()
        if (force || now - lastViewSent >= 90) {
            lastViewSent = now
            viewPending = false
            output?.view(vx, vy, 1f / zoom, 1f / zoom)
        } else if (!viewPending) { // garante o envio final do último estado
            viewPending = true
            postDelayed({
                if (viewPending) {
                    viewPending = false
                    lastViewSent = System.currentTimeMillis()
                    output?.view(vx, vy, 1f / zoom, 1f / zoom)
                }
            }, 100)
        }
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX; lastFy = d.focusY
            return computeFit()
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (!computeFit()) return false
            val oldZoom = zoom
            // ponto da tela do PC que estava sob o foco anterior...
            val u = vx + ((lastFx - fit.left) / fit.width()) / oldZoom
            val v = vy + ((lastFy - fit.top) / fit.height()) / oldZoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
            // ...deve acompanhar o foco atual (zoom ancorado + movimento de dois dedos)
            vx = u - ((d.focusX - fit.left) / fit.width()) / zoom
            vy = v - ((d.focusY - fit.top) / fit.height()) / zoom
            lastFx = d.focusX; lastFy = d.focusY
            applyView()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            applyView(force = true)
        }
    }).also { it.isQuickScaleEnabled = false }

    // ------------------------------------------------------------ toques
    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            norm(e.x, e.y)?.let { output?.mouse("click", it.first, it.second) }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            norm(e.x, e.y)?.let { output?.mouse("dclick", it.first, it.second) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            norm(e.x, e.y)?.let { output?.mouse("rclick", it.first, it.second, "right") }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount >= 2 || scaleDetector.isInProgress) {
            if (!multi) {
                multi = true
                if (dragging) { // cancela arrasto em andamento
                    norm(e.x, e.y)?.let { output?.mouse("up", it.first, it.second) }
                    dragging = false
                }
            }
            scaleDetector.onTouchEvent(e)
            return true
        }
        if (multi) { // sobrou um dedo depois do gesto de 2: ignora até soltar tudo
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
            return true
        }

        val p = norm(e.x, e.y) ?: return true
        when (mode) {
            Mode.DRAG -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    output?.mouse("move", p.first, p.second)
                    output?.mouse("down", p.first, p.second)
                    dragging = true
                }
                MotionEvent.ACTION_MOVE -> throttledMove(p)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) output?.mouse("up", p.first, p.second)
                    dragging = false
                }
            }
            Mode.SCROLL -> {
                detector.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastTouchY = e.y; scrollAcc = 0f }
                    MotionEvent.ACTION_MOVE -> {
                        scrollAcc += e.y - lastTouchY
                        lastTouchY = e.y
                        val step = 22f * resources.displayMetrics.density
                        while (abs(scrollAcc) >= step) {
                            output?.scroll(if (scrollAcc > 0) 1 else -1)
                            scrollAcc -= if (scrollAcc > 0) step else -step
                        }
                    }
                }
            }
            Mode.MOUSE -> {
                detector.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_MOVE) throttledMove(p)
            }
        }
        return true
    }

    private fun throttledMove(p: Pair<Float, Float>) {
        val now = System.currentTimeMillis()
        if (now - lastMove >= 30) {
            lastMove = now
            output?.mouse("move", p.first, p.second)
        }
    }

    // ------------------------------------------------------------ teclado virtual
    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // "senha visível" evita composição/sugestões: cada letra chega pronta.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            // Texto em composição só é enviado ao PC quando o teclado o confirma.
            private var composing = ""

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) output?.text(composing)
                composing = ""
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                output?.text(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) { composing = ""; return true }
                repeat(maxOf(beforeLength, 1)) { output?.key("backspace") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> output?.key("backspace")
                        KeyEvent.KEYCODE_ENTER -> output?.key("enter")
                        KeyEvent.KEYCODE_TAB -> output?.key("tab")
                        else -> event.unicodeChar.takeIf { it > 0 }?.let { output?.text(it.toChar().toString()) }
                    }
                }
                return true
            }
        }
    }

    companion object {
        const val MAX_ZOOM = 6f
    }
}
