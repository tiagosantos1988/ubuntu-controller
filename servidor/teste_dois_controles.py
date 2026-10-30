#!/usr/bin/env python3
"""Mostra que cada celular tem o seu gamepad virtual.

Dois clientes no agente real:
    1. A conecta e segura o Xis       -> so o controle 1 pressiona
    2. B conecta e segura o Circulo   -> so o controle 2 pressiona
    3. B desconecta                   -> o controle 2 solta, o 1 continua
    4. A solta o Xis                  -> o controle 1 solta

Uso:
    python3 teste_dois_controles.py            # contra 127.0.0.1:8765
    python3 teste_dois_controles.py --porta 8799 --simular
"""
from __future__ import annotations

import argparse
import json
import os
import select
import socket
import time

try:
    import evdev
    from evdev import ecodes
except ImportError:  # pragma: no cover
    evdev = None


def token() -> str:
    caminho = os.path.expanduser("~/.config/controle-remoto/token")
    with open(caminho, encoding="utf-8") as fh:
        return fh.read().strip()


def achar_pads() -> dict[str, "evdev.InputDevice"]:
    achados = {}
    for caminho in evdev.list_devices():
        dev = evdev.InputDevice(caminho)
        if dev.name.startswith("Controle Remoto Gamepad"):
            achados[dev.name] = dev
    return achados


def ler(devs: dict, segundos: float = 0.7) -> dict[str, list[int]]:
    """Le todos os pads por um intervalo e devolve o que chegou em cada um."""
    eventos: dict[str, list[str]] = {nome: [] for nome in devs}
    fds = {dev.fd: nome for nome, dev in devs.items()}
    fim = time.time() + segundos
    while time.time() < fim:
        prontos, _, _ = select.select(list(fds), [], [], 0.05)
        for fd in prontos:
            for ev in devs[fds[fd]].read():
                if ev.type not in (ecodes.EV_KEY, ecodes.EV_ABS):
                    continue
                tabela = ecodes.bytype.get(ev.type, {})
                nome = tabela.get(ev.code)
                if isinstance(nome, (list, tuple)):
                    nome = nome[0]
                eventos[fds[fd]].append(f"{nome}={ev.value}")
    return eventos


def cliente(porta: int, nome: str):
    s = socket.create_connection(("127.0.0.1", porta), timeout=5)
    arq = s.makefile("rwb")
    arq.write((json.dumps({"t": "auth", "token": token()}) + "\n").encode())
    arq.flush()
    resposta = json.loads(arq.readline() or b"{}")
    print(f"{nome}: {resposta.get('t')} {resposta.get('msg', '')}".rstrip())
    return arq, resposta


def enviar(arq, obj: dict) -> None:
    arq.write((json.dumps(obj) + "\n").encode())
    arq.flush()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--porta", type=int, default=8765)
    ap.add_argument("--simular", action="store_true", help="nao le /dev/input")
    args = ap.parse_args()

    if not args.simular and evdev is None:
        print("python-evdev nao instalado: use --simular")
        return 1

    arq_a, _ = cliente(args.porta, "A")
    time.sleep(0.4)
    pads = {} if args.simular else achar_pads()
    print("controles no sistema:", list(pads) or "(nenhum, modo simulado)")

    enviar(arq_a, {"t": "pad", "b": "cross", "down": True})
    print("A segurou Xis      ->", ler(pads) if pads else "")

    arq_b, resp_b = cliente(args.porta, "B")
    time.sleep(0.4)
    if not args.simular:
        pads = achar_pads()
        print("controles no sistema:", list(pads))

    enviar(arq_b, {"t": "pad", "b": "circle", "down": True})
    print("B segurou Circulo  ->", ler(pads) if pads else "")

    arq_b.close()
    time.sleep(0.4)
    print("B desconectou      ->", ler(pads) if pads else "")

    enviar(arq_a, {"t": "pad", "b": "cross", "down": False})
    print("A soltou Xis       ->", ler(pads) if pads else "")

    arq_a.close()
    print("\nOK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
