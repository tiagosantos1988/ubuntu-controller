package com.tsantos.controle

import android.content.Context

/**
 * Dados de conexao com o agente. Ficam salvos no aparelho, entao depois do
 * primeiro pareamento o app abre e conecta sozinho -- sem QR code, sem
 * reconfigurar nada.
 */
data class Config(
    val ip: String = "",
    val porta: Int = 8765,
    val token: String = "",
    val sensibilidade: Float = 1.5f,
) {
    val valida: Boolean
        get() = ip.isNotBlank() && porta in 1..65535 && token.length >= 8
}

class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("controle", Context.MODE_PRIVATE)

    fun carregar(): Config = Config(
        ip = sp.getString(CHAVE_IP, "") ?: "",
        porta = sp.getInt(CHAVE_PORTA, 8765),
        token = sp.getString(CHAVE_TOKEN, "") ?: "",
        sensibilidade = sp.getFloat(CHAVE_SENS, 1.5f),
    )

    fun salvar(c: Config) {
        sp.edit()
            .putString(CHAVE_IP, c.ip.trim())
            .putInt(CHAVE_PORTA, c.porta)
            .putString(CHAVE_TOKEN, c.token.trim())
            .putFloat(CHAVE_SENS, c.sensibilidade)
            .apply()
    }

    private companion object {
        const val CHAVE_IP = "ip"
        const val CHAVE_PORTA = "porta"
        const val CHAVE_TOKEN = "token"
        const val CHAVE_SENS = "sensibilidade"
    }
}
