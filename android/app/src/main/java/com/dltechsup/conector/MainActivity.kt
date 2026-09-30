package com.dltechsup.conector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity(), RemoteService.Listener {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var key: EditText
    private lateinit var connectBtn: Button
    private lateinit var openBtn: Button
    private lateinit var exitBtn: Button
    private lateinit var soundLabel: TextView
    private var openOnConnect = false

    private val scan = registerForActivityResult(ScanContract()) { r ->
        val p = r.contents?.let { Pairing.parse(it) }
        if (p == null) {
            if (r.contents != null) toast("QR inválido. Use o QR do Conector Desktop.")
            return@registerForActivityResult
        }
        prefs.applyPairing(p)
        fillFields()
        connect()
    }

    private val ringtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.soundUri = uri?.toString() ?: ""
            updateSoundLabel()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            prefs.soundUri = uri.toString()
            updateSoundLabel()
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        fillFields()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        RemoteService.listeners.add(this)
        onState(RemoteService.state, RemoteService.stateText)
    }

    override fun onStop() {
        RemoteService.listeners.remove(this)
        super.onStop()
    }

    // ---------------------------------------------------------------- Listener
    override fun onState(state: RemoteService.State, text: String) {
        status.text = text
        val active = state != RemoteService.State.IDLE
        connectBtn.visibility = if (active) View.GONE else View.VISIBLE
        openBtn.visibility = if (state == RemoteService.State.CONNECTED) View.VISIBLE else View.GONE
        exitBtn.visibility = if (active) View.VISIBLE else View.GONE
        if (state == RemoteService.State.CONNECTED && openOnConnect) {
            openOnConnect = false
            startActivity(Intent(this, ViewerActivity::class.java))
        }
    }

    override fun onFrame(bmp: Bitmap) {}

    // ---------------------------------------------------------------- ações
    private fun connect() {
        val hosts = host.text.toString().trim()
        val p = port.text.toString().trim().toIntOrNull()
        val k = key.text.toString().trim()
        if (hosts.isEmpty() || p == null || k.isEmpty()) {
            toast("Preencha endereço, porta e chave (ou leia o QR).")
            return
        }
        // Endereço editado à mão: o certificado confiado antes deixa de valer (será confiado no 1º uso).
        if (hosts.split(",").map { it.trim() }.toSet() != prefs.hostList().toSet()) prefs.fingerprint = ""
        prefs.hosts = hosts
        prefs.port = p
        prefs.key = k
        openOnConnect = true
        RemoteService.start(this)
    }

    private fun fillFields() {
        host.setText(prefs.hosts)
        port.setText(prefs.port.toString())
        key.setText(prefs.key)
    }

    private fun updateSoundLabel() {
        val uri = prefs.soundUri
        soundLabel.text = "Som atual: " + when {
            uri.isEmpty() -> "alarme padrão do aparelho"
            else -> try {
                RingtoneManager.getRingtone(this, Uri.parse(uri)).getTitle(this)
            } catch (e: Exception) {
                "arquivo personalizado"
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        fun title(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 18f; setPadding(0, dp(20), 0, dp(6))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        col.addView(TextView(this).apply { text = "Conector Mobile"; textSize = 26f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)

        openBtn = btn("Abrir tela do PC") { startActivity(Intent(this, ViewerActivity::class.java)) }
        exitBtn = btn("Sair e desconectar") { RemoteService.disconnect(this) }
        col.addView(openBtn); col.addView(exitBtn)

        title("Conexão")
        host = field("Endereço(s) do PC (separe por vírgula)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        port = field("Porta", InputType.TYPE_CLASS_NUMBER)
        key = field("Chave de acesso", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        col.addView(host); col.addView(port); col.addView(key)
        col.addView(btn("Ler QR code do PC") {
            scan.launch(ScanOptions().setPrompt("Aponte para o QR do Conector Desktop")
                .setBeepEnabled(false).setOrientationLocked(false)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE))
        })
        connectBtn = btn("Conectar") { connect() }
        col.addView(connectBtn)

        title("Som das notificações do PC")
        soundLabel = TextView(this)
        col.addView(soundLabel)
        col.addView(btn("Escolher toque do aparelho") {
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            if (prefs.soundUri.isNotEmpty())
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(prefs.soundUri))
            ringtone.launch(i)
        })
        col.addView(btn("Escolher arquivo de áudio (mp3…)") { pickFile.launch(arrayOf("audio/*")) })
        col.addView(CheckBox(this).apply {
            text = "Som ALTO (toca mesmo no silencioso, como alarme)"
            isChecked = prefs.loud
            setOnCheckedChangeListener { _, c -> prefs.loud = c }
        })
        col.addView(TextView(this).apply { text = "Volume" })
        col.addView(SeekBar(this).apply {
            max = 100; progress = prefs.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { prefs.volume = maxOf(p, 5) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        col.addView(btn("Testar som") { SoundPlayer.play(this, prefs) })
        col.addView(btn("Parar som") { SoundPlayer.stop() })
        col.addView(TextView(this).apply { text = "Ignorar apps (nomes separados por vírgula):"; setPadding(0, dp(12), 0, 0) })
        col.addView(field("ex.: spotify, teams", InputType.TYPE_CLASS_TEXT).apply {
            setText(prefs.blocked)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable) { prefs.blocked = s.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        title("Manter conectado em segundo plano")
        col.addView(TextView(this).apply {
            text = "Alguns aparelhos (Xiaomi, Samsung, Huawei…) encerram apps em segundo plano. " +
                "Desative a otimização de bateria para este app para a conexão não cair."
        })
        col.addView(btn("Desativar otimização de bateria") { requestIgnoreBattery() })

        setContentView(ScrollView(this).apply { addView(col) })
        updateSoundLabel()
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Já está desativada para este app."); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; setSingleLine(true)
    }
}
