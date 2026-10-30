#!/usr/bin/env python3
"""Teste de fumaca do protocolo: valida autenticacao e comandos sem injetar.

Uso:
    python3 agente.py --simular --porta 8799 &
    python3 teste_protocolo.py --porta 8799
"""

from __future__ import annotations

import argparse
import json
import socket
import sys


def conectar(porta: int, token: str, host: str = "127.0.0.1"):
    s = socket.create_connection((host, porta), timeout=5)
    arq = s.makefile("rwb")

    def enviar(obj: dict) -> None:
        arq.write((json.dumps(obj, ensure_ascii=False) + "\n").encode())
        arq.flush()

    def ler() -> dict | None:
        linha = arq.readline()
        return json.loads(linha) if linha else None

    enviar({"t": "auth", "token": token})
    return s, arq, enviar, ler


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--porta", type=int, default=8765)
    ap.add_argument("--token", default=None)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()

    token = args.token
    if not token:
        import os

        caminho = os.path.expanduser("~/.config/controle-remoto/token")
        token = open(caminho, encoding="utf-8").read().strip()

    falhas = 0

    # 1) token errado deve ser recusado
    s, arq, enviar, ler = conectar(args.porta, "0" * 32, args.host)
    resp = ler()
    if resp and resp.get("t") == "erro":
        print("ok   token invalido recusado")
    else:
        print("FALHA token invalido aceito:", resp)
        falhas += 1
    s.close()

    # 2) token correto
    s, arq, enviar, ler = conectar(args.porta, token, args.host)
    resp = ler()
    if resp and resp.get("t") == "ola":
        print("ok   autenticado:", resp)
    else:
        print("FALHA autenticacao:", resp)
        return 1

    comandos = [
        ({"t": "mover", "dx": 12, "dy": -7}, None),
        ({"t": "rolar", "dy": 3}, None),
        ({"t": "clique", "b": "left"}, None),
        ({"t": "botao", "b": "right", "down": True}, None),
        ({"t": "botao", "b": "right", "down": False}, None),
        ({"t": "tecla", "k": "ESC"}, None),
        ({"t": "tecla", "k": "ALT", "acao": "down"}, None),
        ({"t": "tecla", "k": "TAB"}, None),
        ({"t": "tecla", "k": "ALT", "acao": "up"}, None),
        ({"t": "combo", "keys": ["SUPER", "D"]}, None),
        ({"t": "texto", "s": "Ola, ação! ÑçÜ"}, None),
        ({"t": "backspace", "n": 2}, None),
        ({"t": "ping"}, {"t": "pong"}),
        # limites: deve ser truncado, nao rejeitado
        ({"t": "mover", "dx": 99999, "dy": -99999}, None),
        # gamepad
        ({"t": "pad", "b": "cross", "down": True}, None),
        ({"t": "pad", "b": "cross", "down": False}, None),
        ({"t": "pad", "b": "l2", "down": True}, None),
        ({"t": "pad", "b": "l2", "down": False}, None),
        ({"t": "eixo", "e": "l", "x": 0, "y": -32768}, None),
        ({"t": "eixo", "e": "r", "x": 20000, "y": 20000}, None),
        ({"t": "eixo", "e": "h", "x": -1, "y": 0}, None),
        ({"t": "eixo", "e": "h", "x": 0, "y": 0}, None),
        # valores fora da faixa devem ser truncados, nao recusados
        ({"t": "eixo", "e": "l", "x": 99999, "y": -99999}, None),
        ({"t": "pad_reset"}, None),
        # comando invalido: erro mas conexao continua
        ({"t": "inventado"}, {"t": "erro"}),
        ({"t": "tecla", "k": "NAO_EXISTE"}, {"t": "erro"}),
        ({"t": "pad", "b": "nao_existe", "down": True}, {"t": "erro"}),
        ({"t": "eixo", "e": "z", "x": 1, "y": 1}, {"t": "erro"}),
        ({"t": "ping"}, {"t": "pong"}),
    ]

    for cmd, esperado in comandos:
        enviar(cmd)
        if esperado:
            resp = ler()
            if resp and resp.get("t") == esperado["t"]:
                print(f"ok   {cmd['t']} -> {resp['t']}")
            else:
                print(f"FALHA {cmd['t']} esperava {esperado['t']}, veio {resp}")
                falhas += 1
        else:
            print(f"ok   enviado {cmd['t']}")

    s.close()
    print(f"\n{'TUDO OK' if falhas == 0 else str(falhas) + ' FALHA(S)'}")
    return 0 if falhas == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
