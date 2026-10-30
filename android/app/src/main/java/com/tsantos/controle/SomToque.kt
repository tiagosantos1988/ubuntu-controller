package com.tsantos.controle

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * Som de tecla, compartilhado por todos os botoes do app.
 *
 * Historico (dois bugs corrigidos aqui):
 *
 * 1. Antes cada botao criava o seu proprio SoundPool -- cerca de vinte
 *    instancias carregando o mesmo arquivo. O do direcional perdia a corrida
 *    de carregamento e caia no fallback mudo, por isso so ele ficava em
 *    silencio. Agora existe uma instancia unica, criada na abertura do app.
 *
 * 2. O fallback era AudioManager.playSoundEffect, que fica mudo quando a opcao
 *    "sons de toque" esta desligada (era o caso do aparelho). Tocamos o
 *    arquivo do sistema direto, entao o som nao depende dessa opcao.
 *
 * O som agora e um WAV sintetizado aqui mesmo: 42 ms, fundamental em 190 Hz e
 * centroide espectral em ~790 Hz. O Effect_Tick do sistema e curto mas agudo
 * (na casa dos milhares de Hz); este e mais curto e bem mais grave, que foi o
 * pedido. Se o recurso faltar, cai para o Effect_Tick.
 */
object SomToque {

    /** Volume de 0 a 1. Baixo de proposito: e feedback, nao alerta. */
    private const val VOLUME = 0.62f

    private var pool: SoundPool? = null
    private var amostra = 0

    @Volatile
    private var pronto = false

    fun inicializar(contexto: Context) {
        if (pool != null) return

        val novo = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

        // o listener e registrado antes do load, para nao perder o aviso
        novo.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) pronto = true
        }

        amostra = carregarDoApp(contexto, novo)
        if (amostra == 0) amostra = carregarDoSistema(novo)
        pool = novo
    }

    /** Clique sintetizado: 42 ms, fundamental em 190 Hz -- curto e grave. */
    private fun carregarDoApp(contexto: Context, pool: SoundPool): Int =
        runCatching {
            contexto.resources.openRawResourceFd(R.raw.clique).use { afd ->
                pool.load(afd, 1)
            }
        }.getOrDefault(0)

    @Suppress("DEPRECATION")
    private fun carregarDoSistema(pool: SoundPool): Int {
        for (caminho in CAMINHOS) {
            if (!File(caminho).exists()) continue
            val id = runCatching { pool.load(caminho, 1) }.getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }

    fun tocar() {
        val atual = pool ?: return
        if (!pronto || amostra == 0) return
        runCatching { atual.play(amostra, VOLUME, VOLUME, 1, 0, 1f) }
    }

    private val CAMINHOS = listOf(
        "/system/media/audio/ui/Effect_Tick.ogg",
        "/product/media/audio/ui/Effect_Tick.ogg",
        "/system/media/audio/ui/KeypressStandard.ogg",
    )
}
