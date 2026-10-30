package com.tsantos.controle

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibracao curta e leve ao tocar nas teclas.
 *
 * Medido no aparelho de destino (Galaxy A33): o motor e MOTOR_COIN_DC com
 * "intensityControl: false", ou seja, a amplitude e ignorada -- o unico
 * controle real de intensidade e a duracao. Com 14 ms o motor nem chegava a
 * girar e a vibracao era imperceptivel; 40 ms da um "toc" seco e curto.
 *
 * Alem disso o sistema aplica escala LOW a categoria TOUCH neste aparelho,
 * o que reduz ainda mais o efeito.
 */
object Toque {

    private const val DURACAO_MS = 45L

    /**
     * DEFAULT_AMPLITUDE (-1) = potencia total. Medido no dumpsys: o proprio
     * clique do sistema usa amplitude=-1, enquanto o nosso ia com 0,55 -- em
     * motor sem controle de amplitude isso e convertido em PWM e enfraquece.
     */
    private const val AMPLITUDE = VibrationEffect.DEFAULT_AMPLITUDE

    private var vibrador: Vibrator? = null

    fun inicializar(contexto: Context) {
        if (vibrador != null) return
        vibrador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val gerenciador = contexto.getSystemService(VibratorManager::class.java)
            gerenciador?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            contexto.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun vibrar() {
        val v = vibrador ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(DURACAO_MS, AMPLITUDE))
        }
    }
}

/** Os dois retornos fisicos de uma tecla, sempre juntos. */
fun retornoToque() {
    Toque.vibrar()
    SomToque.tocar()
}
