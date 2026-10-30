#!/usr/bin/env python3
"""Gera os PNGs do icone de bandeja a partir do SVG.

O tema de icones do GNOME prefere PNGs em tamanhos exatos para o icone da
barra; o SVG fica como versao escalavel. Rode depois de editar o SVG:

    python3 app/tray/gerar-pngs.py
"""

import os

import gi

gi.require_version("GdkPixbuf", "2.0")
from gi.repository import GdkPixbuf  # noqa: E402

AQUI = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(AQUI, "controle-remoto-tray.svg")
TAMANHOS = (16, 22, 24, 32, 48, 64)


def main() -> int:
    for tamanho in TAMANHOS:
        destino = os.path.join(AQUI, f"controle-remoto-tray-{tamanho}.png")
        pixbuf = GdkPixbuf.Pixbuf.new_from_file_at_size(SVG, tamanho, tamanho)
        pixbuf.savev(destino, "png", [], [])
        print(f"{destino} ({pixbuf.get_width()}x{pixbuf.get_height()})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
