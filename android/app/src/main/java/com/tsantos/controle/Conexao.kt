package com.tsantos.controle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

sealed interface Estado {
    data object Desconectado : Estado
    data object Conectando : Estado
    data object Conectado : Estado
    data class Falha(val motivo: String) : Estado
}

/**
 * Cliente TCP do agente: uma linha JSON por comando.
 *
 * Reconecta sozinho com espera crescente, entao o app pode ficar aberto e
 * voltar a funcionar quando o wifi oscila ou o servidor reinicia.
 */
class Conexao(
    private val escopo: CoroutineScope,
    private val contexto: Context,
) {

    private val _estado = MutableStateFlow<Estado>(Estado.Desconectado)
    val estado: StateFlow<Estado> = _estado

    /**
     * Numero deste celular como controle (1, 2, 3...).
     *
     * Vem do agente na autenticacao: com mais de um celular conectado, cada um
     * tem o seu gamepad virtual e este numero diz qual.
     */
    private val _controle = MutableStateFlow<Int?>(null)
    val controle: StateFlow<Int?> = _controle

    private val fila = Channel<String>(Channel.UNLIMITED)
    private var trabalho: Job? = null
    private var ultimaConfig: Config? = null

    val conectado: Boolean
        get() = _estado.value == Estado.Conectado

    fun conectar(cfg: Config) {
        ultimaConfig = cfg
        if (trabalho?.isActive == true && _estado.value != Estado.Desconectado) {
            // ja existe tentativa em andamento para a mesma configuracao
            if (ultimaConfig == cfg) return
        }
        trabalho?.cancel()
        trabalho = escopo.launch(Dispatchers.IO) { laco(cfg) }
    }

    fun reconectar() {
        ultimaConfig?.let { conectar(it) }
    }

    fun desconectar() {
        trabalho?.cancel()
        trabalho = null
        _estado.value = Estado.Desconectado
    }

    private suspend fun laco(cfg: Config) {
        var espera = 600L

        while (currentCoroutineContext().isActive) {
            var socket: Socket? = null
            try {
                _estado.value = Estado.Conectando
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(cfg.ip.trim(), cfg.porta), 4000)

                val saida = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val entrada = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                saida.write("{\"t\":\"auth\",\"token\":${jsonStr(cfg.token)}}\n")
                saida.flush()

                val resposta = entrada.readLine() ?: ""
                if (!resposta.contains("\"ola\"")) {
                    val semControle = resposta.contains("sem_controle")
                    _estado.value = Estado.Falha(
                        contexto.getString(
                            when {
                                resposta.contains("token") -> R.string.erro_token
                                semControle -> R.string.erro_sem_controle
                                else -> R.string.erro_servidor
                            }
                        )
                    )
                    // Token errado depende de alguem mexer na configuracao:
                    // insistir so faria barulho. As outras recusas (vagas
                    // ocupadas, resposta estranha) podem passar sozinhas, entao
                    // volta para o laco em vez de desistir de vez -- era isso
                    // que deixava a tela parada em "nao e o agente".
                    if (resposta.contains("token")) return
                    delay(if (semControle) 3000 else 1000)
                    continue
                }

                _controle.value = Regex("\"controle\"\\s*:\\s*(\\d+)")
                    .find(resposta)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()

                // limpa mensagens antigas de uma sessao anterior
                while (fila.tryReceive().isSuccess) { /* descarta */ }

                _estado.value = Estado.Conectado
                espera = 600L

                coroutineScope {
                    var vivo = true
                    val leitor = launch(Dispatchers.IO) {
                        try {
                            while (true) {
                                val linha = entrada.readLine() ?: break
                                // o agente pergunta se ainda estamos aqui; a
                                // resposta vai pela mesma fila dos comandos para
                                // nao escrever no socket de dois lugares
                                if (linha.contains("\"ping\"")) {
                                    fila.trySend("{\"t\":\"pong\"}")
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            vivo = false
                        }
                    }
                    // um comando a cada 25 s mantem a sessao viva mesmo quando
                    // ninguem esta tocando a tela
                    val pulso = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(25_000L)
                            fila.trySend("{\"t\":\"ping\"}")
                        }
                    }

                    try {
                        while (vivo && isActive) {
                            val msg = withTimeoutOrNull(1000L) { fila.receive() } ?: continue
                            saida.write(msg)
                            saida.write("\n")
                            saida.flush()
                        }
                    } finally {
                        leitor.cancel()
                        pulso.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.value = Estado.Falha(descrever(e))
            } finally {
                runCatching { socket?.close() }
                _controle.value = null
            }

            if (!currentCoroutineContext().isActive) break
            if (_estado.value is Estado.Falha) {
                delay(espera)
                espera = (espera * 2).coerceAtMost(6000L)
            } else {
                _estado.value = Estado.Conectando
                delay(300)
            }
        }
    }

    private fun descrever(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> contexto.getString(R.string.erro_sem_resposta)
        is java.net.ConnectException -> contexto.getString(R.string.erro_conectar)
        is java.net.UnknownHostException -> contexto.getString(R.string.erro_endereco)
        else -> e.message?.take(60) ?: contexto.getString(R.string.erro_rede)
    }

    // -- comandos ---------------------------------------------------------

    fun enviar(json: String) {
        if (conectado) fila.trySend(json)
    }

    fun mover(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        enviar("{\"t\":\"mover\",\"dx\":$dx,\"dy\":$dy}")
    }

    fun rolar(dy: Int, dx: Int = 0) {
        if (dy == 0 && dx == 0) return
        enviar("{\"t\":\"rolar\",\"dy\":$dy,\"dx\":$dx}")
    }

    fun clique(botao: String = "left") = enviar("{\"t\":\"clique\",\"b\":\"$botao\"}")

    fun botaoMouse(botao: String, down: Boolean) =
        enviar("{\"t\":\"botao\",\"b\":\"$botao\",\"down\":$down}")

    fun tecla(nome: String) = enviar("{\"t\":\"tecla\",\"k\":\"$nome\"}")

    fun teclaDown(nome: String) = enviar("{\"t\":\"tecla\",\"k\":\"$nome\",\"acao\":\"down\"}")

    fun teclaUp(nome: String) = enviar("{\"t\":\"tecla\",\"k\":\"$nome\",\"acao\":\"up\"}")

    fun combo(vararg teclas: String) {
        val lista = teclas.joinToString(",") { jsonStr(it) }
        enviar("{\"t\":\"combo\",\"keys\":[$lista]}")
    }

    fun texto(s: String) {
        if (s.isEmpty()) return
        enviar("{\"t\":\"texto\",\"s\":${jsonStr(s)}}")
    }

    fun backspace(n: Int) {
        if (n <= 0) return
        enviar("{\"t\":\"backspace\",\"n\":$n}")
    }

    // -- gamepad ----------------------------------------------------------

    /** Botao do gamepad (cross, circle, l1, l2...), com pressao e soltura. */
    fun padBotao(nome: String, down: Boolean) =
        enviar("{\"t\":\"pad\",\"b\":\"$nome\",\"down\":$down}")

    /** Grupo de eixos: "l"/"r" sao analogicos (-32768..32767), "h" e o hat. */
    fun eixo(grupo: String, x: Int, y: Int) =
        enviar("{\"t\":\"eixo\",\"e\":\"$grupo\",\"x\":$x,\"y\":$y}")

    /** Solta tudo: usado ao trocar de aba, para nao deixar botao travado. */
    fun padReset() = enviar("{\"t\":\"pad_reset\"}")

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
