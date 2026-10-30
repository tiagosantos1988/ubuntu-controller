package com.tsantos.controle

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

/**
 * Preenchimento mantido no INICIO do campo de texto do modo teclado.
 *
 * Dois problemas do teclado do Android que isto resolve:
 *
 * 1. Ele so entrega evento de apagar quando existe algo no campo. Sem o
 *    preenchimento, ao esvaziar o espelho local o backspace parava de chegar
 *    ao Ubuntu.
 * 2. O preenchimento fica no inicio (e nao no fim) de proposito: assim digitar
 *    apenas acrescenta no fim e o app nunca precisa reescrever o texto, o que
 *    dessincronizava o IME e gerava um backspace fantasma por caractere.
 *
 * E um espaco de verdade, nao um caractere invisivel: o IME do Samsung ignora
 * zero-width space na hora de apagar.
 */
private const val PREENCHIMENTO = " "

@Composable
fun TelaControle(
    config: Config,
    conexao: Conexao,
    aoAbrirConfig: () -> Unit,
) {
    val estado by conexao.estado.collectAsState()
    val numeroControle by conexao.controle.collectAsState()
    var modo by rememberSaveable { mutableIntStateOf(0) }
    val focoTeclado = remember { FocusRequester() }
    val atividade = LocalContext.current as? Activity

    // Só o joystick vive deitado; touchpad e teclado são desenhados para
    // retrato, e deitados ficavam espremidos. O manifesto já trata a troca
    // sem recriar a activity (configChanges), então a conexão não cai ao girar.
    val orientacao = when (modo) {
        2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    LaunchedEffect(modo) {
        atividade?.requestedOrientation = orientacao
    }

    // ao sair do joystick, botao apertado nao pode ficar travado no PC
    DisposableEffect(modo) {
        onDispose { if (modo == 2) conexao.padReset() }
    }

    // ao deixar a tela (ex.: abrir a configuracao), o sensor volta a mandar
    DisposableEffect(Unit) {
        onDispose {
            atividade?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Fundo)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // no modo joystick a tela inteira e area de jogo: sem barra de topo,
        // sem midia, e as abas sobem para a faixa dos ombros
        if (modo != 2) {
            BarraTopo(estado, config, aoAbrirConfig)
            Spacer(Modifier.height(8.dp))

            // barra de audio/video fixa no topo, visivel nos dois modos
            BarraMidia(conexao)
            Spacer(Modifier.height(10.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (modo) {
                0 -> Touchpad(Modifier.fillMaxSize(), conexao, config.sensibilidade)
                1 -> TecladoRemoto(Modifier.fillMaxSize(), conexao, focoTeclado, config.sensibilidade)
                else -> TelaJoystick(conexao, estado, numeroControle) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SeletorModo(modo, Modifier.widthIn(max = 300.dp)) { modo = it }
                    }
                }
            }
        }

        if (modo == 0) {
            Spacer(Modifier.height(10.dp))
            BarraInferiorTouchpad(conexao)
        } else if (modo == 1) {
            Spacer(Modifier.height(10.dp))
            FaixaTeclas(conexao) { runCatching { focoTeclado.requestFocus() } }
        }

        if (modo != 2) {
            Spacer(Modifier.height(10.dp))
            SeletorModo(modo, Modifier.fillMaxWidth()) { modo = it }
        }
    }
}

@Composable
private fun BarraTopo(estado: Estado, config: Config, aoAbrirConfig: () -> Unit) {
    val atual = estado
    val cor = when (atual) {
        is Estado.Conectado -> Ok
        is Estado.Conectando -> Primaria
        is Estado.Falha -> Alerta
        else -> TextoFraco
    }
    val texto = when (atual) {
        is Estado.Conectado -> stringResource(R.string.status_conectado)
        is Estado.Conectando -> stringResource(R.string.status_conectando)
        is Estado.Falha -> atual.motivo
        else -> stringResource(R.string.status_desconectado)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(cor))
        Spacer(Modifier.width(7.dp))
        Text(
            text = texto,
            color = cor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // fill = true: o texto ocupa a sobra inteira, entao o fim da linha
            // (endereco e engrenagem) encosta mesmo na borda. Com fill = false
            // sobrava um resto que empurrava a engrenagem para dentro.
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${config.ip}:${config.porta}",
            color = TextoFraco,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.config),
            tint = Primaria,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { aoAbrirConfig() }
                .padding(6.dp)
                .size(20.dp),
        )
        // 1 dp: fecha a folga ate a borda e a deixa igual a folga ate o
        // endereco (o resto vem do padding interno do proprio icone)
        Spacer(Modifier.width(1.dp))
    }
}

/**
 * Barra fixa de midia/volume: fica no topo e nao muda com o modo, porque
 * volume e play/pause sao uteis a qualquer momento.
 */
@Composable
private fun BarraMidia(conexao: Conexao) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Superficie)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MidiaBotao("⏯") { conexao.tecla("PLAYPAUSE") }
        MidiaBotao("🔇") { conexao.tecla("MUTE") }
        MidiaBotao("🔉") { conexao.tecla("VOLUMEDOWN") }
        MidiaBotao("🔊") { conexao.tecla("VOLUMEUP") }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MidiaBotao(
    rotulo: String,
    aoClicar: () -> Unit,
) {
    BaseBotao(Modifier.weight(1f), altura = 42.dp, aoClicar = aoClicar) {
        Text(rotulo, color = Texto, fontSize = 17.sp, maxLines = 1)
    }
}

/**
 * Parte inferior do modo touchpad: barra de espaco em cima, Esc/Windows/Enter
 * embaixo e o direcional no canto inferior direito.
 */
@Composable
private fun BarraInferiorTouchpad(conexao: Conexao) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BaseBotao(
                Modifier.fillMaxWidth(),
                altura = 48.dp,
                aoClicar = { conexao.tecla("SPACE") },
            ) {
                // barra em branco: um traco discreto so para indicar que e tocavel
                Box(
                    Modifier
                        .width(54.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextoFraco.copy(alpha = 0.35f))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BaseBotao(Modifier.weight(1f), altura = 48.dp, aoClicar = { conexao.tecla("ESC") }) {
                    Text("Esc", color = Texto, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                BaseBotao(Modifier.weight(1f), altura = 48.dp, aoClicar = { conexao.tecla("SUPER") }) {
                    IconeUbuntu()
                }
                BaseBotao(Modifier.weight(1f), altura = 48.dp, aoClicar = { conexao.tecla("ENTER") }) {
                    IconeEnter()
                }
            }
        }
        Direcional(conexao, Modifier.size(110.dp))
    }
}



/**
 * Icone de Enter desenhado: uma seta que desce e volta para a esquerda.
 *
 * O caractere ⏎ (U+23CE) e um glifo fino e vazado, que em 19 sp virou um risco
 * quase invisivel. Aqui a forma e desenhada com traco grosso e ponta cheia.
 */
@Composable
private fun IconeEnter(modifier: Modifier = Modifier, cor: Color = Texto) {
    Canvas(modifier.size(19.dp)) {
        val espessura = 2.3.dp.toPx()
        val direita = size.width * 0.80f
        val topo = size.height * 0.18f
        val base = size.height * 0.74f
        val esquerda = size.width * 0.14f
        val ponta = 5.2.dp.toPx()

        // corpo: desce pela direita e volta para a esquerda
        val caminho = Path().apply {
            moveTo(direita, topo)
            lineTo(direita, base)
            lineTo(esquerda + ponta * 0.7f, base)
        }
        drawPath(
            caminho,
            cor,
            style = Stroke(width = espessura, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // cabeca da seta, apontando para a esquerda
        val seta = Path().apply {
            moveTo(esquerda, base)
            lineTo(esquerda + ponta, base - ponta * 0.75f)
            lineTo(esquerda + ponta, base + ponta * 0.75f)
            close()
        }
        drawPath(seta, cor)
    }
}

/**
 * Simbolo do Ubuntu, usado no botao da tecla Super.
 *
 * Vetor em res/drawable/ic_ubuntu.xml, extraido do logo do sistema; tingido
 * com a cor do conteudo do botao para acompanhar o estado pressionado.
 */
@Composable
private fun IconeUbuntu(modifier: Modifier = Modifier, cor: Color = Texto) {
    Icon(
        painter = painterResource(R.drawable.ic_ubuntu),
        contentDescription = stringResource(R.string.tecla_super),
        tint = cor,
        modifier = modifier.size(19.dp),
    )
}

/**
 * Gestos de touchpad: arrastar move, tocar clica, dois dedos rolam (e o toque
 * curto com dois dedos e clique direito).
 *
 * Fica num modificador proprio porque a area de texto do modo teclado tambem
 * funciona como mini touchpad -- mesmo comportamento, sem mudar o desenho.
 */
private fun Modifier.gestosTouchpad(
    conexao: Conexao,
    sensibilidade: Float,
    aoTocar: (() -> Unit)? = null,
    aoInteragir: (() -> Unit)? = null,
): Modifier = pointerInput(sensibilidade) {
    var restoX = 0f
    var restoY = 0f
    var restoRolagem = 0f

    /** Tela de toque e' fina: converte pixels em passos do ponteiro. */
    fun moverPor(dx: Float, dy: Float, somarMovimento: Boolean, movimentoAtual: Float): Float {
        var total = movimentoAtual
        if (somarMovimento) total += abs(dx) + abs(dy)
        restoX += dx * sensibilidade
        restoY += dy * sensibilidade
        val ix = restoX.toInt()
        val iy = restoY.toInt()
        if (ix != 0 || iy != 0) {
            conexao.mover(ix.coerceIn(-150, 150), iy.coerceIn(-150, 150))
            restoX -= ix
            restoY -= iy
        }
        return total
    }

    awaitEachGesture {
        val primeiro = awaitFirstDown(requireUnconsumed = false)
        primeiro.consume()
        aoInteragir?.invoke()
        val inicio = System.currentTimeMillis()
        val posicaoInicial = primeiro.position
        var movimento = 0f
        var doisDedos = false
        var rolagem = 0f

        // Ancora: um dedo fica parado e o outro anda = segura o botao esquerdo
        // e deixa o dedo que anda arrastar. E' o que permite mover um icone --
        // o duplo toque nao serve, porque o sistema conta o segundo clique como
        // "abrir" antes de qualquer movimento.
        var arrastando = false
        var idAncora: PointerId? = null
        var movPrimeiro = 0f
        var movSegundo = 0f

        var liberado = false

        while (true) {
            val evento = awaitPointerEvent()
            val ativos = evento.changes.filter { it.pressed }
            if (ativos.isEmpty()) break

            if (arrastando) {
                // Quem manda no botao e' o dedo ancorado: soltar *ele* termina o
                // arrasto. O outro dedo pode ir e voltar quantas vezes precisar
                // (arrasto em duas, tres etapas) que o botao continua apertado.
                val ancoraSoltou = evento.changes.any { it.id == idAncora && !it.pressed }
                if (ancoraSoltou) {
                    conexao.botaoMouse("left", false)
                    arrastando = false
                    liberado = true
                    break
                }

                var dx = 0f
                var dy = 0f
                evento.changes.forEach { c ->
                    if (c.pressed && c.id != idAncora) {
                        val d = c.position - c.previousPosition
                        dx += d.x
                        dy += d.y
                    }
                }
                if (dx != 0f || dy != 0f) {
                    moverPor(dx, dy, somarMovimento = false, movimentoAtual = movimento)
                }
                evento.changes.forEach { it.consume() }
                continue
            }

            if (ativos.size >= 2 && !arrastando) {
                evento.changes.forEach { c ->
                    if (!c.pressed) return@forEach
                    val passo = (c.position - c.previousPosition).getDistance()
                    if (c.id == primeiro.id) movPrimeiro += passo else movSegundo += passo
                }
                val ancorou = movPrimeiro < 12.dp.toPx() && movSegundo >= 20.dp.toPx()
                if (ancorou) {
                    arrastando = true
                    idAncora = primeiro.id
                    conexao.botaoMouse("left", true)
                    Toque.vibrar()
                    // nao deixa esse quadro virar rolagem: o gesto ja virou arrasto
                    continue
                }
            }

            if (ativos.size >= 2) {
                doisDedos = true
                val dy = ativos.map { it.position.y - it.previousPosition.y }
                    .average().toFloat()
                rolagem += dy
                restoRolagem += dy * 0.02f * sensibilidade
                val passo = restoRolagem.toInt()
                if (passo != 0) {
                    conexao.rolar(passo)
                    restoRolagem -= passo
                }
                ativos.forEach { it.consume() }
            } else {
                val atual = ativos.first()
                val delta = atual.position - atual.previousPosition
                movimento = moverPor(
                    delta.x, delta.y,
                    somarMovimento = true,
                    movimentoAtual = movimento,
                )
                atual.consume()
            }
        }

        val duracao = System.currentTimeMillis() - inicio
        if (arrastando) {
            conexao.botaoMouse("left", false)
        } else if (liberado) {
            // o arrasto ja foi encerrado no laco; nao vira clique
        } else if (doisDedos) {
            // toque curto com dois dedos = clique direito
            if (abs(rolagem) < 18f && duracao < 320) {
                conexao.clique("right")
                Toque.vibrar()
            }
        } else if (movimento < 22f && duracao < 260) {
            conexao.clique("left")
            Toque.vibrar()
            aoTocar?.invoke()
        }
    }
}

@Composable
private fun Touchpad(modifier: Modifier, conexao: Conexao, sensibilidade: Float) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Superficie)
            .gestosTouchpad(conexao, sensibilidade)
    ) {
        Text(
            text = stringResource(R.string.touchpad_dica),
            color = TextoFraco.copy(alpha = 0.40f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp, start = 16.dp, end = 16.dp),
        )
    }
}

@Composable
private fun TecladoRemoto(
    modifier: Modifier,
    conexao: Conexao,
    foco: FocusRequester,
    sensibilidade: Float,
) {
    val teclado = LocalSoftwareKeyboardController.current
    val densidade = LocalDensity.current
    val imeAberto = WindowInsets.ime.getBottom(densidade) > 0

    // `conteudo` e o espelho do que ja foi enviado (sem o preenchimento);
    // `campo` e o valor real do BasicTextField, sempre comecando pelo
    // PREENCHIMENTO. A selecao e controlada para ficar sempre no fim: assim
    // digitar so acrescenta e o preenchimento nunca deixa de ser prefixo.
    var conteudo by remember { mutableStateOf("") }
    var campo by remember {
        mutableStateOf(
            TextFieldValue(
                text = PREENCHIMENTO,
                selection = TextRange(PREENCHIMENTO.length),
            )
        )
    }
    // repor o preenchimento faz o IME chamar onValueChange outra vez poucos
    // milissegundos depois; sem coalescer, um toque virava dois backspaces
    var ultimoBackspaceVazio by remember { mutableStateOf(0L) }
    // cada edicao reinicia a contagem que limpa o eco
    var versao by remember { mutableIntStateOf(0) }

    // Zera o eco sem tocar no computador: o campo e reescrito pelo proprio app,
    // entao onValueChange nao dispara e nenhum backspace e enviado -- o texto
    // que ja esta no Ubuntu continua la.
    fun limparEco() {
        conteudo = ""
        campo = TextFieldValue(PREENCHIMENTO, TextRange(PREENCHIMENTO.length))
        versao++
    }

    // 10 segundos sem digitar: some com o eco
    LaunchedEffect(versao) {
        if (conteudo.isNotEmpty()) {
            delay(10_000)
            limparEco()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { foco.requestFocus() }
        teclado?.show()
    }

    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Superficie)) {
        BasicTextField(
            value = campo,
            onValueChange = { novo ->
                val texto = novo.text
                val semPreenchimento = texto.removePrefix(PREENCHIMENTO)

                if (semPreenchimento == conteudo) {
                    // o conteudo nao mudou: o teclado apagou o preenchimento.
                    // Isso quer dizer que o usuario apertou apagar com o visor
                    // vazio -- repassa o comando de qualquer forma.
                    if (!texto.startsWith(PREENCHIMENTO)) {
                        val agora = System.currentTimeMillis()
                        if (agora - ultimoBackspaceVazio > 40L) {
                            conexao.backspace(1)
                            conteudo = conteudo.dropLast(1)
                            ultimoBackspaceVazio = agora
                        }
                        val alvo = PREENCHIMENTO + conteudo
                        campo = TextFieldValue(alvo, TextRange(alvo.length))
                    } else {
                        campo = novo
                    }
                } else {
                    var comum = 0
                    val minimo = minOf(conteudo.length, semPreenchimento.length)
                    while (comum < minimo && conteudo[comum] == semPreenchimento[comum]) comum++

                    if (comum < conteudo.length) {
                        conexao.backspace(conteudo.length - comum)
                    }
                    val inserido = semPreenchimento.substring(comum)
                    if (inserido.isNotEmpty()) conexao.texto(inserido)

                    if (inserido.contains('\n')) {
                        // Enter: envia o comando e ja limpa o eco
                        limparEco()
                    } else {
                        conteudo = semPreenchimento

                        // mantem o espelho curto para nao crescer sem limite
                        if (conteudo.length > 200) {
                            conteudo = ""
                            campo = TextFieldValue(
                                PREENCHIMENTO,
                                TextRange(PREENCHIMENTO.length),
                            )
                        } else {
                            // digitar acrescenta no fim: o valor ja esta
                            // correto e nao mexemos no campo (mexer
                            // dessincroniza o IME)
                            val alvo = PREENCHIMENTO + conteudo
                            campo = if (alvo == texto) {
                                novo
                            } else {
                                TextFieldValue(alvo, TextRange(alvo.length))
                            }
                        }
                        versao++
                    }
                }
            },
            modifier = Modifier.fillMaxSize().focusRequester(foco),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 18.sp),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
        )

        Column(
            Modifier
                .fillMaxSize()
                // a area de texto tambem funciona como mini touchpad
                .gestosTouchpad(
                    conexao = conexao,
                    sensibilidade = sensibilidade,
                    aoTocar = {
                        runCatching { foco.requestFocus() }
                        teclado?.show()
                    },
                    aoInteragir = {
                        // usar o touchpad tambem limpa o eco
                        if (conteudo.isNotEmpty()) limparEco()
                    },
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (conteudo.isEmpty()) {
                Text(
                    stringResource(R.string.teclado_titulo),
                    color = Texto,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.teclado_dica),
                    color = TextoFraco,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                // eco do que já foi enviado: ajuda quando você não está
                // olhando para a TV
                Text(
                    text = conteudo.takeLast(160),
                    color = TextoFraco,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "enviado ao Ubuntu",
                    color = TextoFraco.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }
        }

        // O teclado do Android tem um botao que o esconde. Sem isto, estando na
        // aba de teclado nao havia como reabrir sem trocar de aba.
        if (!imeAberto) {
            BaseBotao(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
                altura = 46.dp,
                fundo = Primaria,
                aoClicar = {
                    runCatching { foco.requestFocus() }
                    teclado?.show()
                },
            ) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        stringResource(R.string.abrir_teclado),
                        color = Color(0xFF04121C),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun FaixaTeclas(conexao: Conexao, aoUsar: () -> Unit) {
    var armado by remember { mutableStateOf<String?>(null) }
    // duas fileiras fixas: todas as teclas visiveis, sem rolagem escondida
    val linhas = listOf(
        listOf(
            "Esc" to "ESC",
            "Tab" to "TAB",
            "Ctrl" to "CTRL",
            "Alt" to "ALT",
            "F11" to "F11",
            "⌫" to "BACKSPACE",
        ),
        listOf(
            "" to "SUPER",
            "←" to "LEFT",
            "↑" to "UP",
            "↓" to "DOWN",
            "→" to "RIGHT",
            "⏎" to "ENTER",
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        linhas.forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                linha.forEach { (rotulo, nome) ->
                    val modificador = nome == "CTRL" || nome == "ALT"
                    BotaoTecla(
                        rotulo = rotulo,
                        modifier = Modifier.weight(1f),
                        ativo = armado == nome,
                    ) {
                        if (modificador) {
                            armado = if (armado == nome) null else nome
                        } else {
                            val mod = armado
                            when {
                                mod != null -> {
                                    conexao.combo(mod, nome)
                                    armado = null
                                }
                                nome == "BACKSPACE" -> conexao.backspace(1)
                                else -> conexao.tecla(nome)
                            }
                            aoUsar()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotaoTecla(
    rotulo: String,
    modifier: Modifier = Modifier,
    ativo: Boolean = false,
    aoClicar: () -> Unit,
) {
    val corConteudo = if (ativo) Color(0xFF04121C) else Texto
    BaseBotao(
        modifier = modifier,
        altura = 44.dp,
        fundo = if (ativo) Primaria else Superficie2,
        aoClicar = aoClicar,
    ) {
        if (rotulo.isEmpty()) {
            IconeUbuntu(cor = corConteudo)
        } else if (rotulo == "⏎") {
            IconeEnter(cor = corConteudo)
        } else {
            Text(
                text = rotulo,
                color = corConteudo,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BaseBotao(
    modifier: Modifier = Modifier,
    altura: Dp = 50.dp,
    fundo: Color = Superficie2,
    aoClicar: () -> Unit,
    conteudo: @Composable () -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    val pressionado by interacao.collectIsPressedAsState()

    // O destaque fica aceso por um instante depois de soltar: num toque rapido
    // o estado "pressionado" dura poucos milissegundos e passava despercebido.
    var aceso by remember { mutableStateOf(false) }
    LaunchedEffect(pressionado) {
        if (pressionado) {
            aceso = true
        } else {
            delay(110)
            aceso = false
        }
    }

    val escala by animateFloatAsState(
        targetValue = if (aceso) 0.92f else 1f,
        animationSpec = tween(durationMillis = if (aceso) 60 else 140),
        label = "escala-botao",
    )
    val cor = if (aceso) lerp(fundo, Color.White, 0.26f) else fundo

    Box(
        modifier
            .height(altura)
            .graphicsLayer {
                scaleX = escala
                scaleY = escala
            }
            .clip(RoundedCornerShape(13.dp))
            .background(cor)
            .clickable(
                interactionSource = interacao,
                indication = ripple(color = Color.White.copy(alpha = 0.14f)),
            ) {
                retornoToque()
                aoClicar()
            },
        contentAlignment = Alignment.Center,
    ) {
        conteudo()
    }
}

/**
 * Icone de cada aba: sem texto, o desenho tem de dizer tudo sozinho.
 *
 * Touchpad e joystick usam desenhos do Material Symbols (Apache 2.0), que ja
 * sao o vocabulario que o usuario reconhece; o teclado continua desenhado
 * aqui porque o traco ficou mais legivel que o glifo equivalente.
 */
private enum class IconeAba(val rotulo: Int, val recurso: Int?) {
    Ponteiro(R.string.aba_touchpad, R.drawable.ic_trackpad),
    Teclado(R.string.aba_teclado, null),
    Joystick(R.string.aba_joystick, R.drawable.ic_esports),
}

@Composable
private fun SeletorModo(
    modo: Int,
    modifier: Modifier = Modifier,
    aoMudar: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Superficie)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconeAba.entries.forEachIndexed { indice, icone ->
            AbaModo(
                icone = icone,
                ativo = modo == indice,
                modifier = Modifier.weight(1f),
            ) {
                aoMudar(indice)
            }
        }
    }
}

/**
 * Aba do seletor, com o mesmo retorno (som, vibracao e destaque) das teclas.
 *
 * So o icone, sem rotulo: o nome continua em `rotulo` para o leitor de tela.
 */
@Composable
private fun AbaModo(
    icone: IconeAba,
    ativo: Boolean,
    modifier: Modifier = Modifier,
    aoClicar: () -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    val pressionado by interacao.collectIsPressedAsState()

    var aceso by remember { mutableStateOf(false) }
    LaunchedEffect(pressionado) {
        if (pressionado) {
            aceso = true
        } else {
            delay(110)
            aceso = false
        }
    }

    val escala by animateFloatAsState(
        targetValue = if (aceso) 0.96f else 1f,
        animationSpec = tween(durationMillis = if (aceso) 60 else 140),
        label = "escala-aba",
    )

    Box(
        modifier
            .height(44.dp)
            .graphicsLayer {
                scaleX = escala
                scaleY = escala
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (ativo) Primaria else Superficie2)
            .clickable(
                interactionSource = interacao,
                indication = ripple(color = Color.White.copy(alpha = 0.14f)),
            ) {
                retornoToque()
                aoClicar()
            },
        contentAlignment = Alignment.Center,
    ) {
        // branco por cima do fundo da aba: clareia tanto a azul quanto a vazia
        if (aceso) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = 0.18f))
            )
        }
        val cor = if (ativo) Color(0xFF04121C) else Texto
        val descricao = stringResource(icone.rotulo)
        Box(
            Modifier
                .size(26.dp)
                .semantics { contentDescription = descricao }
        ) {
            val recurso = icone.recurso
            if (recurso != null) {
                Icon(
                    painter = painterResource(recurso),
                    contentDescription = null,
                    tint = cor,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Canvas(Modifier.fillMaxSize()) { desenharIconeTeclado(cor) }
            }
        }
    }
}

/**
 * Icone do teclado, desenhado: e o unico dos tres que nao veio do Material
 * Symbols, porque neste tamanho o traco proprio (retangulo + teclas) ficou mais
 * legivel que o glifo equivalente.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.desenharIconeTeclado(cor: Color) {
    val e = size.minDimension
    val traco = Stroke(
        width = e * 0.10f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    drawRoundRect(
        color = cor,
        topLeft = Offset(e * 0.10f, e * 0.24f),
        size = Size(e * 0.80f, e * 0.52f),
        cornerRadius = CornerRadius(e * 0.12f),
        style = traco,
    )
    val tecla = e * 0.10f
    for (coluna in 0 until 3) {
        drawRect(
            color = cor,
            topLeft = Offset(e * 0.22f + coluna * e * 0.19f, e * 0.35f),
            size = Size(tecla, tecla),
        )
    }
    for (coluna in 0 until 2) {
        drawRect(
            color = cor,
            topLeft = Offset(e * 0.22f + coluna * e * 0.19f, e * 0.53f),
            size = Size(tecla, tecla),
        )
    }
    // barra de espaco
    drawRoundRect(
        color = cor,
        topLeft = Offset(e * 0.60f, e * 0.53f),
        size = Size(e * 0.19f, tecla),
        cornerRadius = CornerRadius(tecla * 0.5f),
    )
}
