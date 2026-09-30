package com.dltechsup.conector

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dltechsup.conector.databinding.ActivityViewerBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Tela remota. Sair desta tela (voltar / home) NÃO encerra a sessão:
 * só o botão "Sair" (ou a ação da notificação) desconecta.
 */
class ViewerActivity : AppCompatActivity(), RemoteService.Listener, RemoteView.Output {

    private lateinit var b: ActivityViewerBinding
    private lateinit var prefs: Prefs
    private var gotFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.remote.output = this
        b.btnKeyboard.setOnClickListener { toggleKeyboard() }
        b.btnKeys.setOnClickListener {
            b.keysPanel.visibility = if (b.keysPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.btnMode.setOnClickListener { cycleMode() }
        b.btnMonitor.setOnClickListener { cycleMonitor() }
        b.btnQuality.setOnClickListener {
            prefs.quality = (prefs.quality + 1) % RemoteService.QUALITY.size
            RemoteService.instance?.sendVideo()
            toast("Qualidade: ${RemoteService.QUALITY_NAMES[prefs.quality]}")
        }
        b.btnFit.setOnClickListener { b.remote.resetZoom() }
        b.btnHide.setOnClickListener { setToolbarVisible(false) }
        b.btnShow.setOnClickListener { setToolbarVisible(true) }
        b.btnExit.setOnClickListener { confirmExit() }
        buildKeysRow()
    }

    private fun setToolbarVisible(show: Boolean) {
        b.toolbar.visibility = if (show) View.VISIBLE else View.GONE
        b.btnShow.visibility = if (show) View.GONE else View.VISIBLE
        if (!show) b.keysPanel.visibility = View.GONE
    }

    private fun buildKeysRow() {
        val keys = listOf(
            "Esc" to { key("esc") }, "Tab" to { key("tab") }, "Enter" to { key("enter") },
            "⌫" to { key("backspace") }, "Del" to { key("delete") },
            "←" to { key("left") }, "↑" to { key("up") }, "↓" to { key("down") }, "→" to { key("right") },
            "Win" to { key("win") }, "Alt+Tab" to { key("tab", "alt") },
            "Ctrl+C" to { key("c", "ctrl") }, "Ctrl+V" to { key("v", "ctrl") },
            "Ctrl+A" to { key("a", "ctrl") }, "Ctrl+Z" to { key("z", "ctrl") },
            "F5" to { key("f5") }, "Alt+F4" to { key("f4", "alt") },
        )
        val gap = (6 * resources.displayMetrics.density).toInt()
        keys.forEach { (label, act) ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                minWidth = 0; minimumWidth = 0
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
                setOnClickListener { act() }
            }
            val lp = android.widget.LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = gap
            b.keysRow.addView(btn, lp)
        }
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
            state == RemoteService.State.CONNECTED && gotFrame -> b.loading.visibility = View.GONE
            state == RemoteService.State.CONNECTED -> showLoading("Aguardando imagem…")
            else -> showLoading(text)
        }
    }

    private fun showLoading(text: String) {
        b.loadingText.text = text
        b.loading.visibility = View.VISIBLE
    }

    override fun onFrame(bmp: Bitmap, vx: Float, vy: Float, vw: Float, vh: Float) {
        if (!gotFrame) { gotFrame = true; b.loading.visibility = View.GONE }
        b.remote.setFrame(bmp, vx, vy, vw, vh)
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

    override fun view(x: Float, y: Float, w: Float, h: Float) {
        RemoteService.viewport = floatArrayOf(x, y, w, h)
        RemoteService.instance?.sendVideo()
    }

    override fun zoomChanged(zoom: Float) {
        if (zoom > 1.02f) {
            b.zoomBadge.text = String.format(Locale.US, "%.1f×", zoom)
            b.zoomBadge.visibility = View.VISIBLE
        } else {
            b.zoomBadge.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------ ações
    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        b.remote.requestFocus()
        imm.showSoftInput(b.remote, InputMethodManager.SHOW_FORCED)
    }

    private fun cycleMode() {
        val next = when (b.remote.mode) {
            RemoteView.Mode.MOUSE -> RemoteView.Mode.DRAG
            RemoteView.Mode.DRAG -> RemoteView.Mode.SCROLL
            RemoteView.Mode.SCROLL -> RemoteView.Mode.MOUSE
        }
        b.remote.mode = next
        val (icon, label) = when (next) {
            RemoteView.Mode.MOUSE -> R.drawable.ic_pointer to "Mouse: toque = clique, segurar = botão direito"
            RemoteView.Mode.DRAG -> R.drawable.ic_drag to "Arrastar: mover o dedo segura o botão esquerdo"
            RemoteView.Mode.SCROLL -> R.drawable.ic_scroll to "Rolar: deslize para cima/baixo"
        }
        b.btnMode.setImageResource(icon)
        toast(label)
    }

    private fun cycleMonitor() {
        val n = RemoteService.monitors
        if (n <= 1) { toast("Só há um monitor"); return }
        prefs.monitor = prefs.monitor % n + 1
        gotFrame = false
        b.remote.resetZoom()
        RemoteService.instance?.sendVideo()
        toast("Monitor ${prefs.monitor} de $n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
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
