package com.tsantos.controle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Direcional no estilo DualSense: cruz de quatro botoes separados por emendas
 * diagonais finas, sem aro. Toque envia a seta; segurar repete.
 *
 * Tem o mesmo retorno das outras teclas: som, vibracao e destaque visual no
 * braco pressionado (que tambem fica aceso 110 ms depois de soltar).
 *
 * Usado no modo touchpad, onde as setas sao teclas de verdade (KEY_UP...).
 */
@Composable
internal fun Direcional(conexao: Conexao, modifier: Modifier = Modifier) {
    var direcaoAcesa by remember { mutableStateOf<String?>(null) }
    var pressionando by remember { mutableStateOf(false) }
    var aceso by remember { mutableStateOf(false) }

    LaunchedEffect(pressionando) {
        if (pressionando) {
            aceso = true
        } else {
            delay(110)
            aceso = false
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val primeiro = awaitFirstDown()
                primeiro.consume()
                val direcao = direcaoDoToque(primeiro.position, size.width.toFloat())
                    ?: return@awaitEachGesture

                direcaoAcesa = direcao
                pressionando = true
                retornoToque()
                conexao.tecla(direcao)

                val inicio = System.currentTimeMillis()
                var ultimoEnvio = inicio
                while (true) {
                    val evento = withTimeoutOrNull(50L) { awaitPointerEvent() }
                    if (evento != null) {
                        if (evento.changes.none { it.pressed }) break
                        evento.changes.forEach { it.consume() }
                    }
                    val agora = System.currentTimeMillis()
                    if (agora - inicio > 350L && agora - ultimoEnvio > 110L) {
                        conexao.tecla(direcao)
                        ultimoEnvio = agora
                    }
                }
                pressionando = false
            }
        }
    ) {
        desenharCruz(aceso, direcaoAcesa)
    }
}

/**
 * O mesmo direcional, mas mandando o estado do hat do gamepad.
 *
 * Diferenca de comportamento em relacao ao modo touchpad: aqui nao existe
 * repeticao -- um jogo le o estado (esquerda continua esquerda), nao pulsos.
 * Por isso so envia quando a direcao muda, e o polegar pode deslizar de um
 * braco para o outro sem soltar.
 */
@Composable
internal fun DirecionalJogo(conexao: Conexao, modifier: Modifier = Modifier) {
    var direcaoAcesa by remember { mutableStateOf<String?>(null) }
    var pressionando by remember { mutableStateOf(false) }
    var aceso by remember { mutableStateOf(false) }

    LaunchedEffect(pressionando) {
        if (pressionando) {
            aceso = true
        } else {
            delay(110)
            aceso = false
        }
    }

    fun mandar(direcao: String?) {
        when (direcao) {
            "UP" -> conexao.eixo("h", 0, -1)
            "DOWN" -> conexao.eixo("h", 0, 1)
            "LEFT" -> conexao.eixo("h", -1, 0)
            "RIGHT" -> conexao.eixo("h", 1, 0)
            else -> conexao.eixo("h", 0, 0)
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val primeiro = awaitFirstDown()
                primeiro.consume()
                val id = primeiro.id
                var direcao = direcaoDoToque(primeiro.position, size.width.toFloat())
                if (direcao != null) {
                    direcaoAcesa = direcao
                    pressionando = true
                    retornoToque()
                    mandar(direcao)
                }
                try {
                    while (true) {
                        val evento = awaitPointerEvent()
                        if (evento.changes.none { it.pressed }) break
                        evento.changes.forEach { it.consume() }
                        val atual = evento.changes.firstOrNull { it.id == id } ?: continue
                        val nova = direcaoDoToque(atual.position, size.width.toFloat())
                        if (nova != null && nova != direcao) {
                            direcao = nova
                            direcaoAcesa = nova
                            retornoToque()
                            mandar(nova)
                        }
                    }
                } finally {
                    pressionando = false
                    direcaoAcesa = null
                    mandar(null)
                }
            }
        }
    ) {
        desenharCruz(aceso, direcaoAcesa)
    }
}

/**
 * Desenho da cruz, compartilhado pelas duas versoes.
 *
 * Separado do toque de proposito: o mesmo desenho serve para enviar teclas ou
 * o hat do gamepad, e a diferenca fica so no manipulador de ponteiro.
 */
private fun DrawScope.desenharCruz(aceso: Boolean, direcaoAcesa: String?) {
    val raio = size.minDimension / 2f
    val centro = center
    val braco = raio * 0.42f
    val canto = CornerRadius(braco * 0.55f)

    // cruz: dois retangulos arredondados sobrepostos (sem aro)
    drawRoundRect(
        color = Superficie2,
        topLeft = Offset(centro.x - braco, centro.y - raio),
        size = Size(braco * 2, raio * 2),
        cornerRadius = canto,
    )
    drawRoundRect(
        color = Superficie2,
        topLeft = Offset(centro.x - raio, centro.y - braco),
        size = Size(raio * 2, braco * 2),
        cornerRadius = canto,
    )

    // destaque do braco pressionado, recortado pela propria cruz para
    // manter os cantos arredondados
    if (aceso && direcaoAcesa != null) {
        val cruz = Path().apply {
            addRoundRect(
                RoundRect(
                    centro.x - braco, centro.y - raio,
                    centro.x + braco, centro.y + raio,
                    canto.x, canto.y,
                )
            )
            addRoundRect(
                RoundRect(
                    centro.x - raio, centro.y - braco,
                    centro.x + raio, centro.y + braco,
                    canto.x, canto.y,
                )
            )
        }
        val angulo = when (direcaoAcesa) {
            "UP" -> 0f
            "RIGHT" -> 90f
            "DOWN" -> 180f
            else -> 270f
        }
        clipPath(cruz) {
            drawPath(
                petalaDoBraco(centro, braco, raio, angulo),
                Color.White.copy(alpha = 0.26f),
            )
        }
    }

    // Emendas diagonais na cor do fundo: e o que separa os quatro botoes,
    // como no DualSense (quatro botoes distintos, nao uma cruz macica).
    // As mesmas diagonais sao a fronteira usada no toque.
    val larguraEmenda = 3.dp.toPx()
    listOf(1f to 1f, 1f to -1f, -1f to 1f, -1f to -1f).forEach { (sx, sy) ->
        drawLine(
            color = Fundo,
            start = centro,
            end = Offset(
                centro.x + sx * braco * 1.02f,
                centro.y + sy * braco * 1.02f,
            ),
            strokeWidth = larguraEmenda,
            cap = StrokeCap.Round,
        )
    }

    // setas apontando para fora
    ANGULOS.forEach { angulo ->
        rotate(angulo, pivot = centro) {
            val ponta = raio * 0.62f
            val asa = raio * 0.115f
            val seta = Path().apply {
                moveTo(centro.x, centro.y - ponta - asa)
                lineTo(centro.x - asa, centro.y - ponta + asa)
                lineTo(centro.x + asa, centro.y - ponta + asa)
                close()
            }
            drawPath(seta, Texto)
        }
    }
}

/** Petala (quadrante da cruz) de um braco, usada no destaque de pressionado. */
private fun petalaDoBraco(centro: Offset, braco: Float, raio: Float, angulo: Float): Path {
    val rad = Math.toRadians(angulo.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()

    fun ponto(x: Float, y: Float) = Offset(
        centro.x + x * c - y * s,
        centro.y + x * s + y * c,
    )

    val superiorEsquerdo = ponto(-braco, -raio)
    val superiorDireito = ponto(braco, -raio)
    val internoDireito = ponto(braco, -braco)
    val internoEsquerdo = ponto(-braco, -braco)

    return Path().apply {
        moveTo(superiorEsquerdo.x, superiorEsquerdo.y)
        lineTo(superiorDireito.x, superiorDireito.y)
        lineTo(internoDireito.x, internoDireito.y)
        lineTo(centro.x, centro.y)
        lineTo(internoEsquerdo.x, internoEsquerdo.y)
        close()
    }
}

private val ANGULOS = listOf(0f, 90f, 180f, 270f)

/** Descobre qual braco do direcional foi tocado. */
private fun direcaoDoToque(ponto: Offset, lado: Float): String? {
    val centro = lado / 2f
    val dx = ponto.x - centro
    val dy = ponto.y - centro
    val distancia = hypot(dx, dy)
    if (distancia > centro * 0.97f || distancia < centro * 0.20f) return null
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) "RIGHT" else "LEFT"
    } else {
        if (dy > 0) "DOWN" else "UP"
    }
}
