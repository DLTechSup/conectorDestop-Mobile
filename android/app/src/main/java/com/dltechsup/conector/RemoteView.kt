package com.dltechsup.conector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Mostra a tela do PC e converte toques em ações de mouse/teclado. */
class RemoteView(ctx: Context) : View(ctx) {

    interface Output {
        fun mouse(action: String, x: Float, y: Float, button: String = "left")
        fun scroll(dy: Int)
        fun key(name: String)
        fun text(s: String)
    }

    var output: Output? = null
    /** true: arrastar com o dedo = segurar botão esquerdo (selecionar / mover janelas). */
    var dragMode = false

    private var bitmap: Bitmap? = null
    private val dest = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastMove = 0L
    private var twoFingerY = 0f
    private var scrollAcc = 0f
    private var multi = false
    private var dragging = false

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setFrame(b: Bitmap) {
        bitmap = b
        invalidate()
    }

    private fun fit() {
        val b = bitmap ?: return
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s
        val h = b.height * s
        dest.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        fit()
        c.drawBitmap(b, null, dest, paint)
    }

    /** Coordenadas normalizadas (0..1) dentro da imagem, ou null se fora dela. */
    private fun norm(e: MotionEvent, idx: Int = 0): Pair<Float, Float>? {
        if (bitmap == null) return null
        fit()
        val x = (e.getX(idx) - dest.left) / dest.width()
        val y = (e.getY(idx) - dest.top) / dest.height()
        return Pair(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            norm(e)?.let { output?.mouse("click", it.first, it.second) }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            norm(e)?.let { output?.mouse("dclick", it.first, it.second) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            norm(e)?.let { output?.mouse("rclick", it.first, it.second, "right") }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount >= 2) {
            // dois dedos: rolagem (roda do mouse)
            val y = (e.getY(0) + e.getY(1)) / 2
            if (!multi) {
                multi = true
                twoFingerY = y
                scrollAcc = 0f
                if (dragging) { // cancela arrasto em andamento
                    norm(e)?.let { output?.mouse("up", it.first, it.second) }
                    dragging = false
                }
            } else if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                scrollAcc += y - twoFingerY
                twoFingerY = y
                val step = 40f * resources.displayMetrics.density / 3f
                while (kotlin.math.abs(scrollAcc) >= step) {
                    output?.scroll(if (scrollAcc > 0) 1 else -1)
                    scrollAcc -= if (scrollAcc > 0) step else -step
                }
            }
            return true
        }
        if (multi) { // sobrou um dedo depois do gesto de 2: ignora até soltar tudo
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
            return true
        }

        val p = norm(e) ?: return true
        if (dragMode) {
            when (e.actionMasked) {
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
            return true
        }
        detector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_MOVE) throttledMove(p)
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
}
