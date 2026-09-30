package com.dltechsup.conector

import android.content.Context
import android.net.Uri

/** Configurações e perfil de conexão persistidos. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("conector", Context.MODE_PRIVATE)

    var hosts: String
        get() = sp.getString("hosts", "") ?: ""
        set(v) = sp.edit().putString("hosts", v).apply()
    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()
    var key: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v).apply()
    /** SHA-256 (hex) do certificado do PC. Vazio = ainda não confiado (TOFU). */
    var fingerprint: String
        get() = sp.getString("fp", "") ?: ""
        set(v) = sp.edit().putString("fp", v).apply()
    var pcName: String
        get() = sp.getString("pcname", "") ?: ""
        set(v) = sp.edit().putString("pcname", v).apply()
    /** true enquanto o usuário não tiver tocado em "Sair e desconectar". */
    var sessionActive: Boolean
        get() = sp.getBoolean("active", false)
        set(v) = sp.edit().putBoolean("active", v).apply()

    var soundUri: String
        get() = sp.getString("sound", "") ?: ""
        set(v) = sp.edit().putString("sound", v).apply()
    var loud: Boolean
        get() = sp.getBoolean("loud", true)
        set(v) = sp.edit().putBoolean("loud", v).apply()
    var volume: Int
        get() = sp.getInt("volume", 100)
        set(v) = sp.edit().putInt("volume", v).apply()
    var blocked: String
        get() = sp.getString("blocked", "") ?: ""
        set(v) = sp.edit().putString("blocked", v).apply()

    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) = sp.edit().putInt("quality", v).apply()
    var monitor: Int
        get() = sp.getInt("monitor", 1)
        set(v) = sp.edit().putInt("monitor", v).apply()

    fun hostList(): List<String> = hosts.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun applyPairing(p: Pairing) {
        hosts = p.hosts.joinToString(",")
        port = p.port
        key = p.key
        fingerprint = p.fingerprint
        if (p.name.isNotEmpty()) pcName = p.name
    }

    fun blockedList(): List<String> =
        blocked.split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}

/** Dados do QR code: conector://pair?h=ip1,ip2&p=8765&k=CHAVE&f=SHA256&n=NOME */
data class Pairing(
    val hosts: List<String>, val port: Int, val key: String,
    val fingerprint: String, val name: String,
) {
    companion object {
        fun parse(text: String): Pairing? = try {
            val u = Uri.parse(text.trim())
            if (u.scheme != "conector") null else Pairing(
                hosts = (u.getQueryParameter("h") ?: "").split(",").filter { it.isNotBlank() },
                port = (u.getQueryParameter("p") ?: "8765").toInt(),
                key = u.getQueryParameter("k") ?: "",
                fingerprint = (u.getQueryParameter("f") ?: "").lowercase(),
                name = u.getQueryParameter("n") ?: "",
            ).takeIf { it.hosts.isNotEmpty() && it.key.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
