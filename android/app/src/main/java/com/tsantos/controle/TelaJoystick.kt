package com.tsantos.controle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Aba joystick: o celular deitado vira um controle de PS5 para o PC.
 *
 * O que sai daqui nao e teclado nem mouse -- o agente cria um gamepad de
 * verdade via uinput, entao o jogo ve um controle, com dois analogicos,
 * gatilhos e direcional. Por isso esta aba e a unica que pede a tela na
 * horizontal.
 *
 * Disposicao igual a de um DualSense: ombros no topo, direcional e analogico
 * esquerdo do lado esquerdo, botoes de face e analogico direito do lado
 * direito, e os tres botoes centrais no meio.
 */
@Composable
fun TelaJoystick(
    conexao: Conexao,
    estado: Estado,
    controle: Int?,
    seletor: @Composable () -> Unit,
) {
    BoxWithConstraints(
        // os insets de sistema ja vem do Column da tela principal; repetir
        // aqui dobrava a faixa do notch em paisagem
        Modifier
            .fillMaxSize()
            .background(Fundo)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        val alturaOmbro = (maxHeight * 0.15f).coerceIn(44.dp, 60.dp)

        Column(Modifier.fillMaxSize()) {
            LinhaAbas(estado, controle, seletor)
            Spacer(Modifier.height(6.dp))
            LinhaComando(conexao, alturaOmbro)
            Spacer(Modifier.height(6.dp))

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val largura = maxWidth
                val altura = maxHeight

                // Os principais -- analogico esquerdo e os AOXO -- ficam
                // embaixo, nos cantos, que e onde os polegares caem sozinhos.
                // Os secundarios sobem e caminham para o centro, fora do
                // caminho do polegar.
                val principal = minOf(altura * 0.74f, largura * 0.24f).coerceIn(104.dp, 176.dp)
                val conjunto = minOf(altura * 0.80f, largura * 0.25f).coerceIn(112.dp, 190.dp)
                val secundario = minOf(altura * 0.60f, largura * 0.18f).coerceIn(82.dp, 140.dp)

                // folga entre cada secundario e o principal vizinho: a mesma
                // dos dois lados, entao o direcional e o analogico direito
                // ficam simetricos em relacao aos seus pares
                val folga = (largura * 0.26f - principal).coerceAtLeast(14.dp)
                val bordaEsquerda = largura * 0.03f + principal + folga
                val bordaDireita = conjunto + folga

                Analogo(
                    conexao, "l", "l3",
                    Modifier
                        .size(principal)
                        .align(Alignment.BottomStart)
                        .offset(x = largura * 0.03f),
                )
                BotoesFace(
                    conexao, conjunto,
                    Modifier.size(conjunto).align(Alignment.BottomEnd),
                )
                DirecionalJogo(
                    conexao,
                    Modifier
                        .size(secundario)
                        .align(Alignment.TopStart)
                        .offset(
                            x = bordaEsquerda,
                            y = altura * 0.24f,
                        ),
                )
                Analogo(
                    conexao, "r", "r3",
                    Modifier
                        .size(secundario)
                        .align(Alignment.TopEnd)
                        .offset(
                            x = -bordaDireita,
                            y = altura * 0.24f,
                        ),
                )
            }
        }
    }
}

/**
 * Linha das abas, no alto, com o ponto de conexao ao lado.
 *
 * Ficam aqui, e nao embaixo, porque em paisagem o polegar encosta na borda
 * inferior o tempo todo -- trocar de aba sem querer no meio de um jogo era o
 * pior efeito colateral do layout anterior.
 */
@Composable
private fun LinhaAbas(
    estado: Estado,
    controle: Int?,
    seletor: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val larguraAbas = minOf(maxWidth - 44.dp, 300.dp)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Selo(controle, estado)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(larguraAbas)) { seletor() }
        }
    }
}

/**
 * Ombros nas pontas e SELECT/PS/START no meio, na mesma linha logo abaixo das
 * abas: os ombros sairam da faixa das abas para nao disputar espaco com elas.
 */
@Composable
private fun LinhaComando(conexao: Conexao, altura: Dp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Ombro("L2", altura, Modifier.weight(1.3f)) { conexao.padBotao("l2", it) }
        Spacer(Modifier.width(6.dp))
        Ombro("L1", altura, Modifier.weight(1f)) { conexao.padBotao("l1", it) }
        Spacer(Modifier.width(12.dp))
        LinhaCentral(conexao)
        Spacer(Modifier.width(12.dp))
        Ombro("R1", altura, Modifier.weight(1f)) { conexao.padBotao("r1", it) }
        Spacer(Modifier.width(6.dp))
        Ombro("R2", altura, Modifier.weight(1.3f)) { conexao.padBotao("r2", it) }
    }
}

/**
 * Identificacao do celular na mesa: o numero do controle e, de quebra, o
 * estado da conexao (aro verde conectado, azul conectando, vermelho erro).
 *
 * Com dois celulares na sala, "sou o 2" precisa estar visivel sem abrir nada.
 * Sem numero (servidor antigo ou ainda conectando) volta a ser o ponto.
 */
@Composable
private fun Selo(controle: Int?, estado: Estado) {
    val cor = when (estado) {
        is Estado.Conectado -> Ok
        is Estado.Conectando -> Primaria
        is Estado.Falha -> Alerta
        else -> TextoFraco
    }

    if (controle == null) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(cor))
        return
    }

    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Superficie2)
            .border(1.5.dp, cor, CircleShape)
            .semantics { contentDescription = "Controle $controle" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$controle",
            color = Texto,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Ombro(
    rotulo: String,
    altura: Dp,
    modifier: Modifier,
    aoMudar: (Boolean) -> Unit,
) {
    BotaoPressao(
        modifier = modifier.height(altura),
        forma = RoundedCornerShape(10.dp),
        aoPressionar = { aoMudar(true) },
        aoSoltar = { aoMudar(false) },
    ) {
        Text(
            text = rotulo,
            color = Texto,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Os tres botoes centrais, na ordem do DualSense. */
@Composable
private fun LinhaCentral(conexao: Conexao, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotaoPressao(
            modifier = Modifier.size(width = 76.dp, height = 32.dp),
            forma = RoundedCornerShape(16.dp),
            aoPressionar = { conexao.padBotao("select", true) },
            aoSoltar = { conexao.padBotao("select", false) },
        ) {
            Text("SELECT", color = TextoFraco, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
        BotaoPressao(
            modifier = Modifier.size(38.dp),
            aoPressionar = { conexao.padBotao("ps", true) },
            aoSoltar = { conexao.padBotao("ps", false) },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ubuntu),
                contentDescription = "PS",
                tint = Texto,
                modifier = Modifier.size(20.dp),
            )
        }
        BotaoPressao(
            modifier = Modifier.size(width = 76.dp, height = 32.dp),
            forma = RoundedCornerShape(16.dp),
            aoPressionar = { conexao.padBotao("start", true) },
            aoSoltar = { conexao.padBotao("start", false) },
        ) {
            Text("START", color = TextoFraco, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Losango de △ ○ ✕ □.
 *
 * O diametro de cada botao e 36% do lado do conjunto. Com os 42% de antes a
 * distancia entre dois centros vizinhos ficava menor que o diametro, e os
 * circulos se encostavam: o grupo parecia um bloco so.
 */
@Composable
private fun BotoesFace(conexao: Conexao, lado: Dp, modifier: Modifier) {
    // 39% do lado do conjunto: o espacamento entre dois botoes vizinhos fica
    // em ~4% do conjunto, metade do que era antes
    val botao = lado * 0.39f
    // distancia do centro do conjunto ate o centro de cada botao
    val desvio = (lado - botao) / 2f

    Box(modifier) {
        BotaoSimbolo(
            Simbolo.Triangulo, "triangle", conexao, botao,
            Modifier.align(Alignment.Center).offset(y = -desvio),
        )
        BotaoSimbolo(
            Simbolo.Quadrado, "square", conexao, botao,
            Modifier.align(Alignment.Center).offset(x = -desvio),
        )
        BotaoSimbolo(
            Simbolo.Circulo, "circle", conexao, botao,
            Modifier.align(Alignment.Center).offset(x = desvio),
        )
        BotaoSimbolo(
            Simbolo.Cruz, "cross", conexao, botao,
            Modifier.align(Alignment.Center).offset(y = desvio),
        )
    }
}

@Composable
private fun BotaoSimbolo(
    simbolo: Simbolo,
    nome: String,
    conexao: Conexao,
    tamanho: Dp,
    modifier: Modifier,
) {
    BotaoPressao(
        modifier = modifier.size(tamanho),
        aoPressionar = { conexao.padBotao(nome, true) },
        aoSoltar = { conexao.padBotao(nome, false) },
    ) {
        Glifo(simbolo, Texto, Modifier.size(tamanho * 0.44f))
    }
}

private enum class Simbolo { Triangulo, Circulo, Cruz, Quadrado }

/** Glifos desenhados: os caracteres △○✕□ variam de fonte para fonte. */
@Composable
private fun Glifo(simbolo: Simbolo, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val e = size.minDimension
        val m = e / 2f
        val espessura = e * 0.11f
        val traco = Stroke(width = espessura, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (simbolo) {
            Simbolo.Triangulo -> {
                val meia = e * 0.46f
                val altura = e * 0.42f
                val p = Path().apply {
                    moveTo(m, m - altura)
                    lineTo(m + meia, m + altura * 0.72f)
                    lineTo(m - meia, m + altura * 0.72f)
                    close()
                }
                drawPath(p, cor, style = traco)
            }
            Simbolo.Circulo -> drawCircle(
                color = cor,
                radius = e * 0.34f,
                center = Offset(m, m),
                style = traco,
            )
            Simbolo.Cruz -> {
                val r = e * 0.32f
                drawLine(cor, Offset(m - r, m - r), Offset(m + r, m + r), espessura, StrokeCap.Round)
                drawLine(cor, Offset(m + r, m - r), Offset(m - r, m + r), espessura, StrokeCap.Round)
            }
            Simbolo.Quadrado -> {
                val r = e * 0.30f
                drawRect(
                    color = cor,
                    topLeft = Offset(m - r, m - r),
                    size = Size(r * 2, r * 2),
                    style = traco,
                )
            }
        }
    }
}

/**
 * Botao que distingue apertar de soltar.
 *
 * O gamepad precisa disso (o jogo le estado, nao pulso) e o retorno e o mesmo
 * das outras teclas: som, vibracao e destaque de 110 ms depois de soltar.
 */
@Composable
private fun BotaoPressao(
    modifier: Modifier,
    forma: Shape = CircleShape,
    aoPressionar: () -> Unit,
    aoSoltar: () -> Unit,
    conteudo: @Composable () -> Unit,
) {
    var pressionado by remember { mutableStateOf(false) }
    var aceso by remember { mutableStateOf(false) }

    LaunchedEffect(pressionado) {
        if (pressionado) {
            aceso = true
        } else {
            delay(110)
            aceso = false
        }
    }

    Box(
        modifier
            .clip(forma)
            .background(if (aceso) lerp(Superficie2, Primaria, 0.45f) else Superficie2)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val primeiro = awaitFirstDown()
                    primeiro.consume()
                    pressionado = true
                    retornoToque()
                    aoPressionar()
                    try {
                        while (true) {
                            val evento = awaitPointerEvent()
                            if (evento.changes.none { it.pressed }) break
                            evento.changes.forEach { it.consume() }
                        }
                    } finally {
                        pressionado = false
                        aoSoltar()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        conteudo()
    }
}

/** Fracao do raio que o miolo do analogico percorre; o resto e area morta. */
private const val CURSO = 0.62f

/** Abaixo disso o dedo conta como parado; evita deriva com o dedo apoiado. */
private const val ZONA_MORTA = 0.14f

/**
 * Analogico de verdade: manda o deslocamento a ~60 Hz enquanto o dedo se move
 * e volta ao centro ao soltar.
 *
 * Tocar sem arrastar dispara L3/R3, que no controle e apertar o proprio
 * analogico -- assim os dois botoes existem sem ocupar espaco na tela.
 */
@Composable
private fun Analogo(
    conexao: Conexao,
    grupo: String,
    botaoClique: String,
    modifier: Modifier,
) {
    var vetor by remember { mutableStateOf(Offset.Zero) }
    var ativo by remember { mutableStateOf(false) }

    Box(
        modifier.pointerInput(grupo) {
            awaitEachGesture {
                val primeiro = awaitFirstDown()
                primeiro.consume()
                val id = primeiro.id
                val centro = Offset(size.width / 2f, size.height / 2f)
                val raio = size.width / 2f
                val passo = raio * CURSO
                if (passo <= 0f) return@awaitEachGesture

                ativo = true
                retornoToque()

                fun paraVetor(posicao: Offset): Offset {
                    val d = posicao - centro
                    val dist = d.getDistance()
                    return if (dist <= passo) d else d * (passo / dist)
                }

                var deslocamento = paraVetor(primeiro.position)
                vetor = deslocamento

                var enviadoX = Int.MIN_VALUE
                var enviadoY = Int.MIN_VALUE

                fun publicar() {
                    val (x, y) = eixoNormalizado(deslocamento, passo)
                    if (x != enviadoX || y != enviadoY) {
                        conexao.eixo(grupo, x, y)
                        enviadoX = x
                        enviadoY = y
                    }
                }

                val inicio = System.currentTimeMillis()
                publicar()
                var ultimoEnvio = inicio
                var maiorMovimento = 0f

                try {
                    while (true) {
                        val evento = awaitPointerEvent()
                        if (evento.changes.none { it.pressed }) break
                        evento.changes.forEach { it.consume() }
                        val atual = evento.changes.firstOrNull { it.id == id } ?: continue
                        val novo = paraVetor(atual.position)
                        maiorMovimento = maxOf(maiorMovimento, (novo - deslocamento).getDistance())
                        deslocamento = novo
                        vetor = novo
                        // 16 ms: mais que isso nao melhora o jogo e so enche a
                        // rede de estados intermediarios
                        val agora = System.currentTimeMillis()
                        if (agora - ultimoEnvio >= 16L) {
                            publicar()
                            ultimoEnvio = agora
                        }
                    }
                } finally {
                    deslocamento = Offset.Zero
                    vetor = Offset.Zero
                    ativo = false
                    // centrar e obrigatorio: sem isto o jogo fica com o
                    // personagem andando sozinho depois de soltar
                    conexao.eixo(grupo, 0, 0)
                }

                val duracao = System.currentTimeMillis() - inicio
                if (maiorMovimento < raio * 0.06f && duracao < 260L) {
                    conexao.padBotao(botaoClique, true)
                    conexao.padBotao(botaoClique, false)
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val raio = size.minDimension / 2f
            val centro = center
            drawCircle(Superficie, radius = raio, center = centro)
            drawCircle(
                color = Superficie2,
                radius = raio - 1.dp.toPx(),
                center = centro,
                style = Stroke(width = 2.dp.toPx()),
            )
            val passo = raio * CURSO
            val fora = (vetor.getDistance() / passo).coerceIn(0f, 1f)
            val miolo = centro + vetor
            drawCircle(
                color = if (ativo) lerp(Superficie2, Primaria, 0.55f) else TextoFraco.copy(alpha = 0.30f),
                radius = raio * 0.33f,
                center = miolo,
            )
            drawCircle(
                color = if (ativo) Primaria.copy(alpha = 0.35f + 0.5f * fora)
                else TextoFraco.copy(alpha = 0.22f),
                radius = raio * 0.33f,
                center = miolo,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * Converte deslocamento em eixo -32768..32767 com zona morta.
 *
 * A zona morta e maior que a de um controle de verdade de proposito: no vidro
 * o dedo nao tem curso mecanico e o polegar apoiado treme alguns pixels.
 */
private fun eixoNormalizado(deslocamento: Offset, passo: Float): Pair<Int, Int> {
    if (passo <= 0f) return 0 to 0
    val nx = (deslocamento.x / passo).coerceIn(-1f, 1f)
    val ny = (deslocamento.y / passo).coerceIn(-1f, 1f)
    val m = hypot(nx, ny)
    if (m <= ZONA_MORTA) return 0 to 0
    val escala = ((m - ZONA_MORTA) / (1f - ZONA_MORTA)) / m
    val x = (nx * escala * 32767f).roundToInt().coerceIn(-32768, 32767)
    val y = (ny * escala * 32767f).roundToInt().coerceIn(-32768, 32767)
    return x to y
}
