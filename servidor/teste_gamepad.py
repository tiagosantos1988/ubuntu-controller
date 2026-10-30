"""Prova que o gamepad virtual entrega os eventos certos.

Cria os tres dispositivos, manda comandos pelo mesmo caminho do protocolo
(aplicar) e le de volta o que sai no /dev/input -- ou seja, o que o jogo veria.
"""
import select
import sys

sys.path.insert(0, ".")

from agente import aplicar
from injecao import Injetor

from evdev import ecodes


def nome_do_evento(ev):
    tabela = ecodes.bytype.get(ev.type, {})
    bruto = tabela.get(ev.code)
    if isinstance(bruto, (tuple, list)):
        return str(bruto[0])
    return str(bruto if bruto is not None else ev.code)


def testar():
    inj = Injetor()
    print("gamepad:", inj.pad.device.path, flush=True)
    print("abs_X:", inj.pad.device.absinfo(ecodes.ABS_X), flush=True)
    print("abs_Z:", inj.pad.device.absinfo(ecodes.ABS_Z), flush=True)

    eventos = []

    def drenar():
        dev = inj.pad.device
        while select.select([dev.fd], [], [], 0.08)[0]:
            eventos.extend(dev.read())

    aplicar(inj, {"t": "pad", "b": "cross", "down": True})
    aplicar(inj, {"t": "pad", "b": "cross", "down": False})
    aplicar(inj, {"t": "eixo", "e": "l", "x": 12345, "y": -20000})
    aplicar(inj, {"t": "eixo", "e": "h", "x": -1, "y": 1})
    aplicar(inj, {"t": "pad", "b": "l2", "down": True})
    drenar()
    aplicar(inj, {"t": "pad_reset"})
    drenar()
    inj.fechar()

    for ev in eventos:
        if ev.type == ecodes.EV_SYN:
            continue
        print(f"  {nome_do_evento(ev):<14} {ev.value:>7}")


testar()
