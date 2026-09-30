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
 *
 * Modos: TOUCHPAD (padrão, como no Chrome Remote Desktop: o dedo move o cursor
 * sem ele "pular" para o toque), DIRECT (o cursor vai para onde toca), DRAG e SCROLL.
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

    enum class Mode { TOUCHPAD, DIRECT, DRAG, SCROLL }

    var output: Output? = null
    var mode = Mode.TOUCHPAD

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

    // cursor (normalizado sobre a tela inteira do PC), desenhado aqui para responder sem atraso
    private var cu = 0.5f
    private var cv = 0.5f
    private var cursorKnown = false
    private var lastCursorTouch = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var multiStart = 0L
    private var multiMoved = false
    private var multiFx = 0f
    private var multiFy = 0f
    private var multiZoom = 1f
    private val cursorPath = android.graphics.Path()
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeJoin = Paint.Join.ROUND
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------ desenho / geometria
    fun setFrame(b: Bitmap, x: Float, y: Float, w: Float, h: Float, curX: Float, curY: Float) {
        bitmap = b
        fx = x; fy = y; fw = w; fh = h
        // Sincroniza com o cursor real do PC, exceto enquanto o usuário o está movendo.
        if (curX >= 0f && curY >= 0f &&
            (!cursorKnown || System.currentTimeMillis() - lastCursorTouch > 700)
        ) {
            cu = curX; cv = curY; cursorKnown = true
        }
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
        if (cursorKnown) drawCursor(c)
        c.restore()
    }

    private fun drawCursor(c: Canvas) {
        val sx = fit.left + (cu - vx) * zoom * fit.width()
        val sy = fit.top + (cv - vy) * zoom * fit.height()
        if (sx < fit.left - 4 || sx > fit.right + 4 || sy < fit.top - 4 || sy > fit.bottom + 4) return
        val k = resources.displayMetrics.density * 1.15f
        cursorPath.reset()
        cursorPath.moveTo(sx, sy)
        cursorPath.lineTo(sx, sy + 17f * k)
        cursorPath.lineTo(sx + 4.2f * k, sy + 13.2f * k)
        cursorPath.lineTo(sx + 7.2f * k, sy + 20f * k)
        cursorPath.lineTo(sx + 9.8f * k, sy + 18.8f * k)
        cursorPath.lineTo(sx + 6.8f * k, sy + 12.4f * k)
        cursorPath.lineTo(sx + 12f * k, sy + 12.4f * k)
        cursorPath.close()
        c.drawPath(cursorPath, cursorFill)
        c.drawPath(cursorPath, cursorStroke)
    }

    private fun setCursor(u: Float, v: Float) {
        cu = u.coerceIn(0f, 1f); cv = v.coerceIn(0f, 1f)
        cursorKnown = true
        lastCursorTouch = System.currentTimeMillis()
    }

    /** Com zoom, acompanha o cursor para que ele não saia da área visível. */
    private fun keepCursorInView() {
        if (zoom <= 1.01f) return
        val size = 1f / zoom
        val margin = size * 0.08f
        var changed = false
        if (cu < vx + margin) { vx = cu - margin; changed = true }
        if (cu > vx + size - margin) { vx = cu - size + margin; changed = true }
        if (cv < vy + margin) { vy = cv - margin; changed = true }
        if (cv > vy + size - margin) { vy = cv - size + margin; changed = true }
        if (changed) applyView()
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

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Touchpad: clica na hora (sem esperar o toque duplo) onde o cursor está.
            if (mode == Mode.TOUCHPAD) output?.mouse("click", cu, cv)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (mode != Mode.TOUCHPAD) norm(e.x, e.y)?.let {
                setCursor(it.first, it.second)
                output?.mouse("click", it.first, it.second)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Toque duplo: no touchpad é o 2º clique (o sistema junta em duplo clique).
            if (mode == Mode.TOUCHPAD) output?.mouse("click", cu, cv)
            else norm(e.x, e.y)?.let { output?.mouse("dclick", it.first, it.second) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode == Mode.TOUCHPAD) {
                // Segurar = pega e arrasta (botão esquerdo) a partir do cursor atual.
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                output?.mouse("down", cu, cv)
                dragging = true
            } else {
                norm(e.x, e.y)?.let { output?.mouse("rclick", it.first, it.second, "right") }
            }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount >= 2 || scaleDetector.isInProgress) {
            if (!multi) {
                multi = true
                multiStart = System.currentTimeMillis()
                multiMoved = false
                multiZoom = zoom
                multiFx = (e.getX(0) + e.getX(1)) / 2
                multiFy = (e.getY(0) + e.getY(1)) / 2
                if (dragging) { // cancela arrasto em andamento
                    output?.mouse("up", cu, cv)
                    dragging = false
                }
            } else if (e.pointerCount >= 2) {
                val fx2 = (e.getX(0) + e.getX(1)) / 2
                val fy2 = (e.getY(0) + e.getY(1)) / 2
                val slop = 20f * resources.displayMetrics.density
                if (abs(fx2 - multiFx) > slop || abs(fy2 - multiFy) > slop || abs(zoom - multiZoom) > 0.02f) multiMoved = true
            }
            // Dois dedos tocam e soltam rápido, sem mover = clique direito
            if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && !multiMoved &&
                System.currentTimeMillis() - multiStart < 280 && mode != Mode.SCROLL
            ) {
                multiMoved = true // só uma vez
                if (mode == Mode.TOUCHPAD) output?.mouse("rclick", cu, cv, "right")
                else norm(multiFx, multiFy)?.let { output?.mouse("rclick", it.first, it.second, "right") }
            }
            scaleDetector.onTouchEvent(e)
            return true
        }
        if (multi) { // sobrou um dedo depois do gesto de 2: ignora até soltar tudo
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
            return true
        }

        when (mode) {
            Mode.TOUCHPAD -> {
                detector.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; lastT = e.eventTime }
                    MotionEvent.ACTION_MOVE -> touchpadMove(e)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) output?.mouse("up", cu, cv)
                        dragging = false
                    }
                }
            }
            Mode.DIRECT -> {
                val p = norm(e.x, e.y) ?: return true
                detector.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) setCursor(p.first, p.second)
                if (e.actionMasked == MotionEvent.ACTION_MOVE) throttledMove(p)
                invalidate()
            }
            Mode.DRAG -> {
                val p = norm(e.x, e.y) ?: return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        setCursor(p.first, p.second)
                        output?.mouse("move", p.first, p.second)
                        output?.mouse("down", p.first, p.second)
                        dragging = true
                    }
                    MotionEvent.ACTION_MOVE -> { setCursor(p.first, p.second); throttledMove(p) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) output?.mouse("up", p.first, p.second)
                        dragging = false
                    }
                }
                invalidate()
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
        }
        return true
    }

    /** Touchpad: deslocamento relativo do dedo -> deslocamento do cursor (com aceleração leve). */
    private fun touchpadMove(e: MotionEvent) {
        if (!computeFit()) return
        val dx = e.x - lastX
        val dy = e.y - lastY
        val dt = maxOf(1L, e.eventTime - lastT)
        lastX = e.x; lastY = e.y; lastT = e.eventTime
        val speed = kotlin.math.hypot(dx, dy) / dt // px/ms
        val gain = 1.2f + (speed * 0.8f).coerceAtMost(2.8f)
        setCursor(cu + dx * gain / (fit.width() * zoom), cv + dy * gain / (fit.height() * zoom))
        keepCursorInView()
        throttledMove(Pair(cu, cv))
        invalidate()
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
