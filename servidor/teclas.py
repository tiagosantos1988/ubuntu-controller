"""Teclas especiais: nomes usados pelo app -> codigos evdev.

O teclado virtual do Android nao tem Esc, Tab, setas, Super nem teclas de
midia. Esses botoes ficam na interface do app e chegam aqui por nome.
"""

from evdev.ecodes import (
    KEY_BACKSPACE, KEY_DELETE, KEY_DOWN, KEY_END, KEY_ENTER,
    KEY_ESC, KEY_F5, KEY_F11, KEY_HOME, KEY_LEFT, KEY_LEFTALT, KEY_LEFTCTRL,
    KEY_LEFTMETA, KEY_LEFTSHIFT, KEY_MUTE, KEY_NEXTSONG, KEY_PAGEDOWN,
    KEY_PAGEUP, KEY_PLAYPAUSE, KEY_PREVIOUSSONG, KEY_RIGHT, KEY_SPACE,
    KEY_STOPCD, KEY_TAB, KEY_UP, KEY_VOLUMEDOWN, KEY_VOLUMEUP, KEY_LEFTBRACE,
    KEY_RIGHTBRACE,
)

ESPECIAIS: dict[str, int] = {
    # navegacao
    "ESC": KEY_ESC,
    "TAB": KEY_TAB,
    "ENTER": KEY_ENTER,
    "SPACE": KEY_SPACE,
    "BACKSPACE": KEY_BACKSPACE,
    "DELETE": KEY_DELETE,
    "UP": KEY_UP,
    "DOWN": KEY_DOWN,
    "LEFT": KEY_LEFT,
    "RIGHT": KEY_RIGHT,
    "HOME": KEY_HOME,
    "END": KEY_END,
    "PAGEUP": KEY_PAGEUP,
    "PAGEDOWN": KEY_PAGEDOWN,
    # modificadores
    "SUPER": KEY_LEFTMETA,
    "CTRL": KEY_LEFTCTRL,
    "ALT": KEY_LEFTALT,
    "SHIFT": KEY_LEFTSHIFT,
    # atalhos de janela/tela
    "F5": KEY_F5,
    "F11": KEY_F11,
    "ALT_TAB": -1,  # tratado como combinacao no agente
    # midia / volume (funcionam no sistema todo, sem depender do app focado)
    "VOLUMEUP": KEY_VOLUMEUP,
    "VOLUMEDOWN": KEY_VOLUMEDOWN,
    "MUTE": KEY_MUTE,
    "PLAYPAUSE": KEY_PLAYPAUSE,
    "NEXT": KEY_NEXTSONG,
    "PREV": KEY_PREVIOUSSONG,
    "STOP": KEY_STOPCD,
}

# combinacoes prontas: nome -> sequencia de nomes de tecla
COMBOS: dict[str, list[str]] = {
    "ALT_TAB": ["ALT", "TAB"],
    "SUPER_D": ["SUPER", "D"],
    "SUPER_L": ["SUPER", "L"],
}

# botoes do mouse aceitos pelo protocolo
BOTOES_VALIDOS = {"left", "right", "middle"}

# caracteres que nao passam pelo mapa (nao existem no layout) mas o app pode
# querer mandar como tecla direta
EXTRAS: dict[str, int] = {
    "[": KEY_LEFTBRACE,
    "]": KEY_RIGHTBRACE,
}
