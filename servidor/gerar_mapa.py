#!/usr/bin/env python3
"""Gera o mapa caractere -> sequencia de keycodes para o layout ativo.

O app Android envia texto ("á"), mas o kernel só aceita keycodes. Este script
descobre o layout ativo via libxkbcommon e resolves os caracteres compostos por
tecla morta (´ + a) usando o arquivo Compose do X11.

A saida e o modulo estatico `mapa_br.py`, usado em tempo de execucao pelo
agente. Depois de gerado, o agente nao precisa de xkbcommon.

Uso:
    python3 gerar_mapa.py                 # detecta o layout da sessao
    python3 gerar_mapa.py --layout br     # forca um layout
"""

from __future__ import annotations

import argparse
import ctypes
import ctypes.util
import os
import re
import sys

# --- flags de modificador (bitmask) -----------------------------------------
SEM_MOD = 0
SHIFT = 1
ALTGR = 2

MOD_NOME = {SHIFT: "shift", ALTGR: "altgr"}

# Compoem as 4 combinacoes de nivel do XKB: nivel 0..3
#   0 = nenhum, 1 = Shift, 2 = AltGr (Level3), 3 = Shift+AltGr
NIVEIS = [
    SEM_MOD,
    SHIFT,
    ALTGR,
    SHIFT | ALTGR,
]

COMPOSE_PADRAO = "/usr/share/X11/locale/en_US.UTF-8/Compose"


class Xkb:
    """Wrapper minimo de libxkbcommon via ctypes."""

    def __init__(self, layout: str, variant: str | None = None) -> None:
        nome = ctypes.util.find_library("xkbcommon")
        if not nome:
            raise RuntimeError("libxkbcommon nao encontrada")
        self.lib = ctypes.CDLL(nome)

        lib = self.lib
        lib.xkb_context_new.restype = ctypes.c_void_p
        lib.xkb_context_new.argtypes = [ctypes.c_int]

        class Regras(ctypes.Structure):
            _fields_ = [
                ("rules", ctypes.c_char_p),
                ("model", ctypes.c_char_p),
                ("layout", ctypes.c_char_p),
                ("variant", ctypes.c_char_p),
                ("options", ctypes.c_char_p),
            ]

        self.Regras = Regras
        lib.xkb_keymap_new_from_names.restype = ctypes.c_void_p
        lib.xkb_keymap_new_from_names.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(Regras),
            ctypes.c_int,
        ]

        lib.xkb_keymap_min_keycode.restype = ctypes.c_uint32
        lib.xkb_keymap_min_keycode.argtypes = [ctypes.c_void_p]
        lib.xkb_keymap_max_keycode.restype = ctypes.c_uint32
        lib.xkb_keymap_max_keycode.argtypes = [ctypes.c_void_p]

        lib.xkb_keymap_key_get_syms_by_level.restype = ctypes.c_int
        lib.xkb_keymap_key_get_syms_by_level.argtypes = [
            ctypes.c_void_p,
            ctypes.c_uint32,
            ctypes.c_uint32,
            ctypes.c_uint32,
            ctypes.POINTER(ctypes.POINTER(ctypes.c_uint32)),
        ]

        lib.xkb_keysym_to_utf32.restype = ctypes.c_uint32
        lib.xkb_keysym_to_utf32.argtypes = [ctypes.c_uint32]

        lib.xkb_keysym_get_name.restype = ctypes.c_int
        lib.xkb_keysym_get_name.argtypes = [
            ctypes.c_uint32,
            ctypes.c_char_p,
            ctypes.c_size_t,
        ]

        lib.xkb_keysym_from_name.restype = ctypes.c_uint32
        lib.xkb_keysym_from_name.argtypes = [ctypes.c_char_p, ctypes.c_int]

        ctx = lib.xkb_context_new(0)
        if not ctx:
            raise RuntimeError("xkb_context_new falhou")

        regras = Regras(None, None, layout.encode(), (variant or "").encode() or None, None)
        self.keymap = lib.xkb_keymap_new_from_names(ctx, ctypes.byref(regras), 0)
        if not self.keymap:
            raise RuntimeError(f"layout '{layout}' invalido para xkbcommon")
        self.ctx = ctx

    def niveis(self, keycode: int) -> list[list[int]]:
        """Keysyms por nivel da tecla. Lista vazia = nivel inexistente."""
        out: list[list[int]] = []
        for nivel in range(len(NIVEIS)):
            ptr = ctypes.POINTER(ctypes.c_uint32)()
            n = self.lib.xkb_keymap_key_get_syms_by_level(
                self.keymap, keycode, 0, nivel, ctypes.byref(ptr)
            )
            out.append([ptr[i] for i in range(n)] if n > 0 else [])
        return out

    def unicode(self, keysym: int) -> int:
        return self.lib.xkb_keysym_to_utf32(keysym)

    def nome(self, keysym: int) -> str:
        buf = ctypes.create_string_buffer(128)
        self.lib.xkb_keysym_get_name(keysym, buf, len(buf))
        return buf.value.decode(errors="replace")

    def simbolo(self, nome: str) -> int:
        return self.lib.xkb_keysym_from_name(nome.encode(), 0)

    def char_do_nome(self, nome: str) -> str | None:
        """Converte nome de keysym ('aacute', 'a') no caractere que ele produz."""
        sym = self.simbolo(nome)
        if not sym:
            return None
        cp = self.lib.xkb_keysym_to_utf32(sym)
        if not cp:
            return None
        try:
            return chr(cp)
        except ValueError:
            return None

    def faixa(self) -> tuple[int, int]:
        return (
            self.lib.xkb_keymap_min_keycode(self.keymap),
            self.lib.xkb_keymap_max_keycode(self.keymap),
        )


def construir_tabelas(xkb: Xkb):
    """Retorna (char -> [(codigo, mods)]), (nome_keysym -> (codigo, mods))."""
    direto: dict[str, tuple[int, int]] = {}
    simbolos: dict[str, tuple[int, int]] = {}

    minimo, maximo = xkb.faixa()
    for xkbcode in range(minimo, maximo + 1):
        evdev = xkbcode - 8  # XKB keycode = evdev code + 8
        if not 0 < evdev < 256:
            continue
        for nivel, nsyms in enumerate(xkb.niveis(xkbcode)):
            for sym in nsyms:
                nome = xkb.nome(sym)
                simbolos.setdefault(nome, (evdev, NIVEIS[nivel]))
                cp = xkb.unicode(sym)
                if cp:
                    try:
                        char = chr(cp)
                    except ValueError:
                        continue
                    # primeira ocorrencia vence: mantem o caminho mais simples
                    direto.setdefault(char, (evdev, NIVEIS[nivel]))
    return direto, simbolos


RE_COMPOSE = re.compile(r'^(?P<seq>(?:<[^>]+>\s*)+):\s*"(?P<txt>(?:[^"\\]|\\.)*)"')


def ler_compose(caminho: str = COMPOSE_PADRAO):
    """Le o Compose do X11 e devolve char -> (nome_keysym_morto, nome_base)."""
    if not os.path.exists(caminho):
        print(f"aviso: {caminho} nao encontrado; sem acentos compostos", file=sys.stderr)
        return {}

    chaves: dict[str, tuple[str, str]] = {}
    with open(caminho, encoding="utf-8", errors="replace") as fh:
        for linha in fh:
            if not linha.startswith("<"):
                continue
            m = RE_COMPOSE.match(linha)
            if not m:
                continue
            simbolos = re.findall(r"<([^>]+)>", m.group("seq"))
            if len(simbolos) != 2:
                continue  # ignora sequencias de 3+ keysyms
            texto = m.group("txt")
            # o Compose escreve o resultado literal entre aspas; so aceitamos
            # um caractere e descartamos escapes (raros e multi-caractere)
            if len(texto) != 1 or texto == "\\":
                continue
            chaves.setdefault(texto, (simbolos[0], simbolos[1]))
    return chaves


def resolver_base(nome: str, direto, xkb: Xkb) -> tuple[int, int] | None:
    """Encontra o keycode da tecla base a partir do nome do keysym."""
    char = xkb.char_do_nome(nome)
    if char is None and len(nome) == 1:
        char = nome
    if char is not None and char in direto:
        return direto[char]
    return None


def gerar(layout: str, variant: str | None, destino: str) -> int:
    xkb = Xkb(layout, variant)
    direto, simbolos = construir_tabelas(xkb)
    compose = ler_compose()

    mapa: dict[str, list[tuple[int, int]]] = {}
    for char, (codigo, mods) in direto.items():
        if char.isprintable():
            mapa[char] = [(codigo, mods)]
    # espaco nao vem do keysym de texto (e U+0020), mas e a tecla mais usada
    mapa.setdefault(" ", [(57, SEM_MOD)])

    # acentos: tecla morta + tecla base
    mortos: dict[str, list[tuple[int, int]]] = {}
    for char, (morto, base) in compose.items():
        if char in mapa:
            continue
        if morto not in simbolos:
            continue
        alvo = resolver_base(base, direto, xkb)
        if not alvo:
            continue
        passo1 = simbolos[morto]
        passo2 = alvo
        mortos[char] = [passo1, passo2]

    mapa.update(mortos)

    linhas = [
        '"""Mapa caractere -> sequencia de keycodes. GERADO -- nao editar.',
        "",
        f"Layout: {layout}{('/' + variant) if variant else ''}",
        "Gerado por servidor/gerar_mapa.py a partir de libxkbcommon + Compose do X11.",
        "",
        "Formato: char -> [(keycode_evdev, mods)] onde mods e um bitmask:",
        "  0 = nenhum | 1 = Shift | 2 = AltGr | 3 = Shift+AltGr",
        '"""',
        "",
        "SHIFT = 1",
        "ALTGR = 2",
        "",
        "MAPA = {",
    ]
    for char in sorted(mapa):
        passos = ", ".join(f"({c}, {m})" for c, m in mapa[char])
        linhas.append(f"    {char!r}: [{passos}],")
    linhas += [
        "}",
        "",
        f"TOTAL = {len(mapa)}",
        "",
    ]

    # Tabela de nome de keysym -> (codigo, mods), util para teclas especiais
    linhas += ["SIMBOLOS = {"]
    for nome in sorted(simbolos):
        c, m = simbolos[nome]
        linhas.append(f"    {nome!r}: ({c}, {m}),")
    linhas += ["}", ""]

    with open(destino, "w", encoding="utf-8") as fh:
        fh.write("\n".join(linhas))

    print(f"layout {layout}{('/' + variant) if variant else ''}: "
          f"{len(direto)} caracteres diretos, {len(mortos)} via tecla morta")
    print(f"gravado em {destino} ({os.path.getsize(destino)} bytes)")
    return len(mapa)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--layout", default=None, help="layout XKB (padrao: detectado)")
    ap.add_argument("--variant", default=None)
    ap.add_argument(
        "--destino",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "mapa_br.py"),
    )
    args = ap.parse_args()

    layout = args.layout
    if not layout:
        # usa o layout configurado no GNOME quando disponivel
        try:
            import subprocess

            saida = subprocess.run(
                ["gsettings", "get", "org.gnome.desktop.input-sources", "sources"],
                capture_output=True, text=True, timeout=5,
            ).stdout
            m = re.search(r"\('xkb', '([^']+)'\)", saida)
            layout = m.group(1) if m else "br"
        except Exception:
            layout = "br"

    gerar(layout, args.variant, args.destino)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
