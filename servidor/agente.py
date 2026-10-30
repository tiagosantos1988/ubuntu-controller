#!/usr/bin/env python3
"""Agente de controle remoto: recebe comandos do celular e injeta no Ubuntu.

Protocolo: TCP + JSON por linha (NDJSON). Escolha deliberada: nao precisa de
HTTP, TLS nem bibliotecas externas do lado do app -- o cliente Android fala
socket puro, o que reduz o APK e o consumo de bateria.

A primeira linha de toda conexao precisa ser a autenticacao:

    {"t": "auth", "token": "<token>"}

Depois disso, cada linha e um comando. O token fica em
~/.config/controle-remoto/token (0600) e e mostrado na tela de configuracao do
app. Sem ele, nada e aceito.
"""

from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import os
import secrets
import signal
import socket
import sys

from injecao import (
    BOTOES_PAD, GRUPOS_EIXO, MODIFICADORES, ErroInjecao, Injetor,
    tecla_final_valida,
)
from teclas import BOTOES_VALIDOS, COMBOS, ESPECIAIS

DIR_CONFIG = os.path.expanduser("~/.config/controle-remoto")
ARQ_TOKEN = os.path.join(DIR_CONFIG, "token")

PORTA_PADRAO = 8765
VERSAO = 1
MAX_LINHA = 64 * 1024
LIMITE_MOV = 400          # px por mensagem; evita saltos absurdos
LIMITE_TEXTO = 512        # caracteres por mensagem
TIMEOUT_AUTH = 10.0
LIMITE_CONTROLES = 4   # um gamepad virtual por celular conectado


def _log(msg: str) -> None:
    print(f"[agente] {msg}", file=sys.stderr, flush=True)


def carregar_token() -> str:
    os.makedirs(DIR_CONFIG, exist_ok=True)
    if os.path.exists(ARQ_TOKEN):
        try:
            with open(ARQ_TOKEN, encoding="utf-8") as fh:
                t = fh.read().strip()
            if len(t) >= 16:
                return t
        except OSError:
            pass
    token = secrets.token_hex(16)
    fd = os.open(ARQ_TOKEN, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(token)
    _log(f"token novo gerado em {ARQ_TOKEN}")
    return token


def ip_local() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.0.1", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def _limite(valor, teto: int = LIMITE_MOV) -> int:
    try:
        v = int(valor)
    except (TypeError, ValueError):
        return 0
    return max(-teto, min(teto, v))


class InjetorFalso:
    """Usado em --simular: valida o protocolo sem /dev/uinput (sem sudo)."""

    def __init__(self) -> None:
        _log("modo SIMULADO: nada sera injetado de verdade")

    def mover(self, dx, dy):
        _log(f"mover dx={dx} dy={dy}")

    def rolar(self, dy, dx=0):
        _log(f"rolar dy={dy} dx={dx}")

    def clique(self, nome="left"):
        _log(f"clique {nome}")

    def botao(self, nome, pressionar):
        _log(f"botao {nome} {'down' if pressionar else 'up'}")

    def tecla(self, nome, pressionar):
        _log(f"tecla {nome} {'down' if pressionar else 'up'}")

    def tap(self, nome):
        _log(f"tap {nome}")

    def combo(self, nomes):
        _log(f"combo {nomes}")

    def texto(self, s):
        _log(f"texto {s!r}")

    def backspace(self, vezes=1):
        _log(f"backspace x{vezes}")

    def pad_botao(self, nome, pressionar, slot=0):
        _log(f"pad[{slot}] {nome} {'down' if pressionar else 'up'}")

    def eixo(self, grupo, x, y, slot=0):
        _log(f"eixo[{slot}] {grupo} x={x} y={y}")

    def soltar_tudo(self, slot=0):
        _log(f"soltar tudo[{slot}]")

    def pad_do_slot(self, slot=0):
        return None

    def liberar_extras(self):
        _log("liberar controles extras")

    def fechar(self):
        pass


def _nome_tecla(bruto) -> str:
    """Valida o nome de uma tecla especial contra a tabela conhecida."""
    nome = str(bruto).upper()
    if nome in COMBOS:
        return nome
    if ESPECIAIS.get(nome, -1) > 0:
        return nome
    raise ErroInjecao(f"tecla desconhecida: {nome!r}")


def _norm_combo(bruto) -> str:
    """Nomeias de combo sao maiusculas, mas caracteres preservam o original."""
    s = str(bruto)
    maiusculo = s.upper()
    if maiusculo in ESPECIAIS or maiusculo in MODIFICADORES:
        return maiusculo
    return s


def _nome_botao(bruto) -> str:
    nome = str(bruto or "left").lower()
    if nome not in BOTOES_VALIDOS:
        raise ErroInjecao(f"botao desconhecido: {nome!r}")
    return nome


def _nome_pad(bruto) -> str:
    nome = str(bruto or "").lower()
    if nome not in BOTOES_PAD:
        raise ErroInjecao(f"botao de gamepad desconhecido: {nome!r}")
    return nome


def aplicar(injetor, msg: dict, slot: int = 0) -> dict | None:
    """Executa um comando. Levanta ErroInjecao em comando invalido.

    `slot` e o gamepad virtual do cliente que mandou o comando: teclado e
    ponteiro sao compartilhados, cada controle e de quem mandou.
    """
    tipo = msg.get("t")

    if tipo == "mover":
        injetor.mover(_limite(msg.get("dx")), _limite(msg.get("dy")))
    elif tipo == "rolar":
        injetor.rolar(_limite(msg.get("dy"), 50), _limite(msg.get("dx"), 50))
    elif tipo == "clique":
        injetor.clique(_nome_botao(msg.get("b")))
    elif tipo == "botao":
        injetor.botao(_nome_botao(msg.get("b")), bool(msg.get("down")))
    elif tipo == "tecla":
        nome = _nome_tecla(msg.get("k", ""))
        acao = msg.get("acao")
        if acao == "down":
            injetor.tecla(nome, True)
        elif acao == "up":
            injetor.tecla(nome, False)
        else:
            injetor.tap(nome)
    elif tipo == "combo":
        teclas = msg.get("keys") or []
        if not isinstance(teclas, list) or not teclas:
            raise ErroInjecao("combo vazio")
        nomes = [_norm_combo(k) for k in teclas][:6]
        finais = [n for n in nomes if n not in MODIFICADORES]
        if len(finais) != 1 or not tecla_final_valida(finais[0]):
            raise ErroInjecao(f"combo invalido: {nomes}")
        injetor.combo(nomes)
    elif tipo == "texto":
        s = str(msg.get("s", ""))
        if s:
            injetor.texto(s[:LIMITE_TEXTO])
    elif tipo == "backspace":
        injetor.backspace(_limite(msg.get("n", 1), 100))
    elif tipo == "pad":
        injetor.pad_botao(_nome_pad(msg.get("b")), bool(msg.get("down")), slot)
    elif tipo == "eixo":
        grupo = str(msg.get("e", "")).lower()
        if grupo not in GRUPOS_EIXO:
            raise ErroInjecao(f"grupo de eixo desconhecido: {grupo!r}")
        injetor.eixo(
            grupo,
            _limite(msg.get("x"), 32767),
            _limite(msg.get("y"), 32767),
            slot,
        )
    elif tipo == "pad_reset":
        injetor.soltar_tudo(slot)
    elif tipo == "ping":
        return {"t": "pong"}
    else:
        raise ErroInjecao(f"comando desconhecido: {tipo!r}")


class Agente:
    def __init__(self, injetor, token: str, porta: int) -> None:
        self.injetor = injetor
        self.token = token
        self.porta = porta
        self.clientes = 0
        # Um gamepad virtual por celular: cada conexao fica com um slot, e o
        # slot e liberado quando ela cai. Teclado e ponteiro seguem unicos.
        self._slots_livres: set[int] = set(range(LIMITE_CONTROLES))
        # criado fora do loop para que o app grafico possa pedir a parada
        # a partir de outro thread
        self._parar = asyncio.Event()
        self._laco: asyncio.AbstractEventLoop | None = None

    def _reservar_slot(self) -> int | None:
        if not self._slots_livres:
            return None
        return min(self._slots_livres)

    def parar(self) -> None:
        """Pedido de parada seguro entre threads."""
        if self._laco is not None:
            self._laco.call_soon_threadsafe(self._parar.set)

    async def tratar(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        endereco = f"{peer[0]}:{peer[1]}" if peer else "?"
        self.clientes += 1
        slot: int | None = None
        _log(f"conexao de {endereco}")

        # Um celular pode sumir sem fechar o socket (app desinstalado, saiu do
        # alcance do Wi-Fi). Sem isso a vaga do controle ficava presa para
        # sempre e o proximo aparelho ouvia "sem controle livre". O keepalive e'
        # respondido pelo kernel do celular, entao um cliente vivo mas parado
        # nao e' derrubado -- so o que realmente desapareceu.
        try:
            sock = writer.get_extra_info("socket")
            if sock is not None:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 45)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 10)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 4)
        except OSError:
            pass

        async def enviar(obj: dict) -> None:
            writer.write((json.dumps(obj, ensure_ascii=False) + "\n").encode())
            await writer.drain()

        try:
            # -- autenticacao -------------------------------------------------
            try:
                linha = await asyncio.wait_for(reader.readline(), timeout=TIMEOUT_AUTH)
            except asyncio.TimeoutError:
                await enviar({"t": "erro", "msg": "timeout de autenticacao"})
                return
            if not linha:
                return
            try:
                msg = json.loads(linha)
            except json.JSONDecodeError:
                await enviar({"t": "erro", "msg": "json invalido"})
                return
            if msg.get("t") != "auth" or not hmac.compare_digest(
                str(msg.get("token", "")), self.token
            ):
                _log(f"token recusado para {endereco}")
                await enviar({"t": "erro", "msg": "token invalido"})
                return

            slot = self._reservar_slot()
            if slot is None:
                _log(f"sem controle livre para {endereco}")
                await enviar(
                    {
                        "t": "erro",
                        # o app usa o motivo para dizer "todos em uso" em vez de
                        # "nao e o agente", que nao ajudava em nada
                        "motivo": "sem_controle",
                        "msg": f"limite de {LIMITE_CONTROLES} controles",
                    }
                )
                return
            self._slots_livres.discard(slot)

            # cria o controle agora, e nao no primeiro comando: o jogo lista os
            # controles quando abre, e um dispositivo que aparece depois de o
            # jogo ja estar rodando normalmente nao e enumerado
            try:
                self.injetor.pad_do_slot(slot)
            except Exception as exc:
                _log(f"aviso: controle {slot + 1} nao pode ser criado: {exc}")

            await enviar(
                {
                    "t": "ola",
                    "versao": VERSAO,
                    "porta": self.porta,
                    # qual controle e este, para o app se identificar na tela
                    "controle": slot + 1,
                }
            )
            _log(f"autenticado: {endereco} (controle {slot + 1})")

            # -- comandos -----------------------------------------------------
            while True:
                linha = await reader.readline()
                if not linha:
                    break
                if len(linha) > MAX_LINHA:
                    await enviar({"t": "erro", "msg": "mensagem grande"})
                    break
                try:
                    msg = json.loads(linha)
                except json.JSONDecodeError:
                    await enviar({"t": "erro", "msg": "json invalido"})
                    continue
                if not isinstance(msg, dict):
                    continue
                try:
                    resposta = aplicar(self.injetor, msg, slot)
                except ErroInjecao as exc:
                    _log(f"comando invalido: {exc}")
                    await enviar({"t": "erro", "msg": str(exc)})
                    continue
                if resposta:
                    await enviar(resposta)
        except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError):
            pass
        except Exception as exc:  # pragma: no cover - rede
            _log(f"erro inesperado com {endereco}: {exc!r}")
        finally:
            self.clientes -= 1
            _log(f"desconectado: {endereco}")
            if slot is not None:
                self._slots_livres.add(slot)
                # botao apertado na hora da queda ficaria travado no jogo --
                # mas so o controle deste celular, nao o do vizinho
                try:
                    self.injetor.soltar_tudo(slot)
                    if self.clientes <= 0:
                        # ninguem mais conectado: nao deixa controle pendurado
                        self.injetor.liberar_extras()
                except Exception:
                    pass
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    async def rodar(self) -> None:
        servidor = await asyncio.start_server(
            self.tratar, host="0.0.0.0", port=self.porta, limit=MAX_LINHA
        )
        ip = ip_local()
        _log(f"escutando em {ip}:{self.porta} (0.0.0.0:{self.porta})")
        _log(f"token: {self.token}")
        _log("configure o app com IP, porta e token acima")

        self._laco = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                self._laco.add_signal_handler(sig, self._parar.set)
            except (NotImplementedError, ValueError, RuntimeError):
                # fora do thread principal (app grafico) nao da para instalar
                # tratador de sinal; a parada vem por parar()
                pass

        async with servidor:
            await self._parar.wait()
        _log("encerrando")


def main() -> int:
    ap = argparse.ArgumentParser(description="Agente de controle remoto (teclado/mouse)")
    ap.add_argument("--porta", type=int, default=PORTA_PADRAO)
    ap.add_argument("--simular", action="store_true", help="nao injeta; so registra os comandos")
    ap.add_argument("--mostrar-token", action="store_true", help="imprime o token e sai")
    args = ap.parse_args()

    token = carregar_token()
    if args.mostrar_token:
        print(token)
        return 0

    if args.simular:
        injetor = InjetorFalso()
    else:
        try:
            injetor = Injetor()
        except ErroInjecao as exc:
            print(exc, file=sys.stderr)
            return 1

    try:
        asyncio.run(Agente(injetor, token, args.porta).rodar())
    finally:
        injetor.fechar()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
