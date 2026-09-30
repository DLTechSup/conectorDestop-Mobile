package com.dltechsup.conector

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tela remota. Sair desta tela (voltar / home) NÃO encerra a sessão:
 * só o botão "Sair" (ou a ação da notificação) desconecta.
 */
class ViewerActivity : AppCompatActivity(), RemoteService.Listener, RemoteView.Output {

    private lateinit var prefs: Prefs
    private lateinit var view: RemoteView
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
    private lateinit var keysRow: LinearLayout
    private lateinit var dragBtn: Button
    private var gotFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        view = RemoteView(this).also { it.output = this }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setPadding(24, 16, 24, 16)
        }

        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC202020.toInt()) }
        addBtn("⌨ Teclado") { toggleKeyboard() }
        addBtn("Teclas") { keysRow.visibility = if (keysRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        dragBtn = addBtn("Arrastar: OFF") {
            view.dragMode = !view.dragMode
            dragBtn.text = "Arrastar: " + if (view.dragMode) "ON" else "OFF"
        }
        addBtn("Tela") { cycleMonitor() }
        addBtn("Qualidade: ${RemoteService.QUALITY_NAMES[prefs.quality]}") { b ->
            prefs.quality = (prefs.quality + 1) % RemoteService.QUALITY.size
            (b as Button).text = "Qualidade: ${RemoteService.QUALITY_NAMES[prefs.quality]}"
            RemoteService.instance?.sendVideo()
        }
        addBtn("Ocultar") { bar.visibility = View.GONE; keysRow.visibility = View.GONE; showHandle(true) }
        addBtn("Sair") { confirmExit() }

        keysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC303030.toInt()); visibility = View.GONE
        }
        listOf(
            "Esc" to { key("esc") }, "Tab" to { key("tab") }, "Enter" to { key("enter") },
            "⌫" to { key("backspace") }, "Del" to { key("delete") },
            "←" to { key("left") }, "↑" to { key("up") }, "↓" to { key("down") }, "→" to { key("right") },
            "Win" to { key("win") }, "Alt+Tab" to { key("tab", "alt") },
            "Ctrl+C" to { key("c", "ctrl") }, "Ctrl+V" to { key("v", "ctrl") },
            "Ctrl+A" to { key("a", "ctrl") }, "Ctrl+Z" to { key("z", "ctrl") },
            "F5" to { key("f5") }, "Alt+F4" to { key("f4", "alt") },
        ).forEach { (label, act) ->
            keysRow.addView(smallBtn(label) { act() })
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(HorizontalScrollView(this).apply { addView(bar) })
        top.addView(HorizontalScrollView(this).apply { addView(keysRow) })

        handle = Button(this).apply {
            text = "≡"; visibility = View.GONE; alpha = 0.6f
            setOnClickListener { bar.visibility = View.VISIBLE; showHandle(false) }
        }

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(handle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
    }

    private lateinit var handle: Button
    private fun showHandle(show: Boolean) { handle.visibility = if (show) View.VISIBLE else View.GONE }

    private fun addBtn(label: String, onClick: (View) -> Unit): Button {
        val b = smallBtn(label, onClick)
        bar.addView(b)
        return b
    }

    private fun smallBtn(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        minWidth = 0; minimumWidth = 0
        setOnClickListener(onClick)
    }

    // ------------------------------------------------------------ ciclo de vida
    override fun onStart() {
        super.onStart()
        hideSystemBars()
        RemoteService.listeners.add(this)
        if (RemoteService.state == RemoteService.State.IDLE) {
            finish(); return
        }
        onState(RemoteService.state, RemoteService.stateText)
        RemoteService.instance?.setVideo(true)
    }

    override fun onStop() {
        // Ir para segundo plano só pausa o vídeo; a conexão e as notificações continuam.
        RemoteService.instance?.setVideo(false)
        RemoteService.listeners.remove(this)
        gotFrame = false
        super.onStop()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------ Listener
    override fun onState(state: RemoteService.State, text: String) {
        when {
            state == RemoteService.State.IDLE -> finish()
            state == RemoteService.State.CONNECTED && gotFrame -> status.visibility = View.GONE
            state == RemoteService.State.CONNECTED -> { status.text = "Aguardando imagem…"; status.visibility = View.VISIBLE }
            else -> { status.text = text; status.visibility = View.VISIBLE }
        }
    }

    override fun onFrame(bmp: Bitmap) {
        if (!gotFrame) { gotFrame = true; status.visibility = View.GONE }
        view.setFrame(bmp)
    }

    // ------------------------------------------------------------ RemoteView.Output
    override fun mouse(action: String, x: Float, y: Float, button: String) {
        RemoteService.instance?.send(
            JSONObject().put("t", "mouse").put("a", action).put("x", x.toDouble()).put("y", y.toDouble()).put("b", button)
        )
    }

    override fun scroll(dy: Int) {
        RemoteService.instance?.send(JSONObject().put("t", "mouse").put("a", "scroll").put("dy", dy))
    }

    override fun key(name: String) = key(name, *emptyArray<String>())

    private fun key(name: String, vararg mods: String) {
        RemoteService.instance?.send(
            JSONObject().put("t", "key").put("k", name).put("mods", JSONArray(mods.toList()))
        )
    }

    override fun text(s: String) {
        RemoteService.instance?.send(JSONObject().put("t", "text").put("s", s))
    }

    // ------------------------------------------------------------ ações
    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
    }

    private fun cycleMonitor() {
        val n = RemoteService.monitors
        if (n <= 1) return
        prefs.monitor = prefs.monitor % n + 1
        gotFrame = false
        RemoteService.instance?.sendVideo()
        status.text = "Monitor ${prefs.monitor} de $n"
        status.visibility = View.VISIBLE
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Sair e desconectar?")
            .setMessage("A conexão com o PC será encerrada e você deixará de receber as notificações dele.")
            .setPositiveButton("Sair e desconectar") { _, _ ->
                RemoteService.disconnect(this)
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
