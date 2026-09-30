package com.dltechsup.conector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.dltechsup.conector.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity(), RemoteService.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var openOnConnect = false

    private val scan = registerForActivityResult(ScanContract()) { r ->
        val p = r.contents?.let { Pairing.parse(it) }
        if (p == null) {
            if (r.contents != null) toast("QR inválido. Use o QR do DeskLink no PC.")
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
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setup()
        fillFields()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun setup() {
        b.version.text = "DeskLink " + try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }

        b.btnScan.setOnClickListener {
            scan.launch(
                ScanOptions().setPrompt("Aponte para o QR code do DeskLink no PC")
                    .setBeepEnabled(false).setOrientationLocked(false)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            )
        }
        b.btnConnect.setOnClickListener { connect() }
        b.btnOpen.setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        b.btnExit.setOnClickListener { RemoteService.disconnect(this) }

        b.switchAudio.isChecked = prefs.audioOn
        b.switchAudio.setOnCheckedChangeListener { _, c ->
            prefs.audioOn = c
            RemoteService.instance?.sendAudio()
        }
        b.switchAudioBg.isChecked = prefs.audioBg
        b.switchAudioBg.setOnCheckedChangeListener { _, c ->
            prefs.audioBg = c
            RemoteService.instance?.sendAudio()
        }
        b.btnRingtone.setOnClickListener {
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            if (prefs.soundUri.isNotEmpty())
                i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(prefs.soundUri))
            ringtone.launch(i)
        }
        b.btnFile.setOnClickListener { pickFile.launch(arrayOf("audio/*")) }
        b.switchLoud.isChecked = prefs.loud
        b.switchLoud.setOnCheckedChangeListener { _, c -> prefs.loud = c }
        b.sliderVolume.value = prefs.volume.coerceIn(5, 100).toFloat()
        b.sliderVolume.addOnChangeListener { _, v, _ -> prefs.volume = v.toInt() }
        b.btnTest.setOnClickListener { SoundPlayer.play(this, prefs) }
        b.btnStop.setOnClickListener { SoundPlayer.stop() }
        b.blockedInput.setText(prefs.blocked)
        b.blockedInput.doAfterTextChanged { prefs.blocked = it?.toString() ?: "" }
        b.btnBattery.setOnClickListener { requestIgnoreBattery() }
        updateSoundLabel()
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
        b.statusText.text = text
        val color = when (state) {
            RemoteService.State.CONNECTED -> R.color.ok
            RemoteService.State.IDLE -> R.color.idle
            else -> R.color.warn
        }
        b.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))

        val active = state != RemoteService.State.IDLE
        b.connectCard.visibility = if (active) View.GONE else View.VISIBLE
        b.sessionCard.visibility = if (active) View.VISIBLE else View.GONE
        b.btnOpen.isEnabled = state == RemoteService.State.CONNECTED
        b.sessionTitle.text = if (RemoteService.pcName.isNotEmpty()) RemoteService.pcName else "Sessão ativa"
        if (state == RemoteService.State.CONNECTED && openOnConnect) {
            openOnConnect = false
            startActivity(Intent(this, ViewerActivity::class.java))
        }
    }

    override fun onFrame(bmp: Bitmap, vx: Float, vy: Float, vw: Float, vh: Float, cx: Float, cy: Float) {}

    // ---------------------------------------------------------------- ações
    private fun connect() {
        val hosts = b.hostInput.text.toString().trim()
        val p = b.portInput.text.toString().trim().toIntOrNull()
        val k = b.keyInput.text.toString().trim()
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
        b.hostInput.setText(prefs.hosts)
        b.portInput.setText(prefs.port.toString())
        b.keyInput.setText(prefs.key)
    }

    private fun updateSoundLabel() {
        val uri = prefs.soundUri
        b.soundLabel.text = "Som: " + when {
            uri.isEmpty() -> "alarme padrão do aparelho"
            else -> try {
                RingtoneManager.getRingtone(this, Uri.parse(uri)).getTitle(this)
            } catch (e: Exception) {
                "arquivo personalizado"
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

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
}
