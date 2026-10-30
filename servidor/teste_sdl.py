"""Confere o gamepad virtual pelos olhos do SDL -- o mesmo caminho que jogos,
Lutris, RetroArch e Wine usam para achar um controle.

Cria os dispositivos via Injetor, inicializa o SDL e le eixos/botoes.
"""
import os
import sys
import time

os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
os.environ.setdefault("SDL_AUDIODRIVER", "dummy")

sys.path.insert(0, ".")

import pygame

from agente import aplicar
from injecao import Injetor


def main():
    inj = Injetor()
    print("devnode:", inj.pad.device.path, flush=True)

    pygame.joystick.init()
    pygame.display.init()
    pygame.display.set_mode((64, 64))
    pygame.event.pump()

    total = pygame.joystick.get_count()
    print("joysticks vistos pelo SDL:", total)
    for i in range(total):
        j = pygame.joystick.Joystick(i)
        print(f"  [{i}] nome={j.get_name()!r} guid={j.get_guid()} "
              f"eixos={j.get_numaxes()} botoes={j.get_numbuttons()} "
              f"hats={j.get_numhats()}")

    # controller API (o que jogo moderno usa)
    try:
        from pygame._sdl2 import controller

        controller.init()
        print("game controllers reconhecidos:", controller.get_count())
        for i in range(controller.get_count()):
            c = controller.Controller(i)
            print(f"  controller [{i}] nome={c.name!r} eixos={c.num_axes} botoes={c.num_buttons}")
    except Exception as exc:  # pragma: no cover
        print("controller api indisponivel:", exc)

    j = pygame.joystick.Joystick(0)
    aplicar(inj, {"t": "eixo", "e": "l", "x": 0, "y": -32767})
    aplicar(inj, {"t": "eixo", "e": "r", "x": 16000, "y": 0})
    aplicar(inj, {"t": "eixo", "e": "h", "x": -1, "y": 0})
    aplicar(inj, {"t": "pad", "b": "cross", "down": True})
    aplicar(inj, {"t": "pad", "b": "l2", "down": True})
    time.sleep(0.2)
    pygame.event.pump()
    eventos = pygame.event.get()
    tipos = [pygame.event.event_name(e.type) for e in eventos if "JOY" in pygame.event.event_name(e.type)]
    print("eventos de joystick:", sorted(set(tipos)))
    print("eixos lidos:", [round(j.get_axis(i), 3) for i in range(j.get_numaxes())])
    print("hat lido:", j.get_hat(0))
    print("botoes:", [i for i in range(j.get_numbuttons()) if j.get_button(i)])

    # solta tudo, como o agente faz quando o app cai
    aplicar(inj, {"t": "pad_reset"})
    time.sleep(0.2)
    pygame.event.pump()
    print("depois do reset, eixos:", [round(j.get_axis(i), 3) for i in range(j.get_numaxes())])
    print("depois do reset, botoes:", [i for i in range(j.get_numbuttons()) if j.get_button(i)])

    inj.fechar()
    pygame.quit()


main()
