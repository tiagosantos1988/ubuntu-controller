"""Injecao de teclado e mouse via /dev/uinput.

Por que uinput e nao xdotool: a sessao desta maquina e Wayland. O XTEST so
alcanca janelas X11 (via Xwayland). Um dispositivo uinput e visto pelo kernel
como hardware real, entao funciona no GNOME, em apps nativos, em jogos e ate
na tela de bloqueio -- e nunca pede confirmacao ao usuario.
"""

from __future__ import annotations

import sys
import time

import evdev
from evdev import UInput, ecodes
from evdev.uinput import UInputError

import mapa_br
from teclas import COMBOS, ESPECIAIS, EXTRAS

SHIFT = 1
ALTGR = 2

# codigos dos modificadores usados para compor caracteres
MOD_CODIGO = {
    SHIFT: ecodes.KEY_LEFTSHIFT,
    ALTGR: ecodes.KEY_RIGHTALT,
}

BOTOES = {
    "left": ecodes.BTN_LEFT,
    "right": ecodes.BTN_RIGHT,
    "middle": ecodes.BTN_MIDDLE,
}

# teclas que so fazem sentido ficando pressionadas (Ctrl/Alt/Super/Shift)
MODIFICADORES = {"ALT", "CTRL", "SUPER", "SHIFT"}


# -- gamepad ---------------------------------------------------------------

# Nomes do app -> codigos. O kernel mapeia as posicoes como XInput: sul = ✕,
# leste = ○, norte = △, oeste = □ -- a mesma ordem fisica do DualSense.
BOTOES_PAD = {
    "cross": ecodes.BTN_SOUTH,
    "circle": ecodes.BTN_EAST,
    "triangle": ecodes.BTN_NORTH,
    "square": ecodes.BTN_WEST,
    "l1": ecodes.BTN_TL,
    "r1": ecodes.BTN_TR,
    "l2": ecodes.BTN_TL2,
    "r2": ecodes.BTN_TR2,
    "select": ecodes.BTN_SELECT,
    "start": ecodes.BTN_START,
    "ps": ecodes.BTN_MODE,
    "l3": ecodes.BTN_THUMBL,
    "r3": ecodes.BTN_THUMBR,
}

# Gatilho digital -> eixo analogico. Manda os dois: emulador costuma ler
# BTN_TL2, jogo de PC costuma ler ABS_Z, e assim nenhum dos dois fica sem.
GATILHO_EIXO = {"l2": ecodes.ABS_Z, "r2": ecodes.ABS_RZ}

# Grupos de eixo: nome curto no protocolo -> (eixo X, eixo Y)
GRUPOS_EIXO = {
    "l": (ecodes.ABS_X, ecodes.ABS_Y),
    "r": (ecodes.ABS_RX, ecodes.ABS_RY),
    "h": (ecodes.ABS_HAT0X, ecodes.ABS_HAT0Y),
}

# A tupla e (valor, min, max, fuzz, flat, resolucao) -- o valor vem primeiro.
# Com min/max trocados o kernel devolve so EINVAL, sem dizer o motivo.
# Sem esta lista o eixo nasce com faixa 0..255 e o analogico vira um botao.
ABSINFO_PAD = [
    (ecodes.ABS_X, (0, -32768, 32767, 0, 0, 0)),
    (ecodes.ABS_Y, (0, -32768, 32767, 0, 0, 0)),
    (ecodes.ABS_RX, (0, -32768, 32767, 0, 0, 0)),
    (ecodes.ABS_RY, (0, -32768, 32767, 0, 0, 0)),
    (ecodes.ABS_Z, (0, 0, 255, 0, 0, 0)),
    (ecodes.ABS_RZ, (0, 0, 255, 0, 0, 0)),
    (ecodes.ABS_HAT0X, (0, -1, 1, 0, 0, 0)),
    (ecodes.ABS_HAT0Y, (0, -1, 1, 0, 0, 0)),
]


def passos_do_char(ch: str) -> list[tuple[int, int]] | None:
    """Sequencia de keycodes que produz um caractere no layout atual."""
    passos = mapa_br.MAPA.get(ch)
    if passos is None and ch in EXTRAS:
        passos = [(EXTRAS[ch], 0)]
    return passos


def tecla_final_valida(nome: str) -> bool:
    """Aceita tecla nomeada (ESC, TAB...) ou um caractere do layout."""
    if ESPECIAIS.get(nome, -1) > 0:
        return True
    return len(nome) == 1 and bool(passos_do_char(nome))

# declarar o conjunto completo de teclas faz o dispositivo se apresentar como
# um teclado de verdade para o compositor
TODAS_TECLAS = list(range(1, 249))

# O compositor (via IBus) compoe teclas mortas de forma assincrona: se a letra
# base chegar junto com o acento, o resultado sai embaralhado ("ação" vira
# "ãçao"). Medido nesta maquina: 0 ms falha, 5 ms ja funciona. 15 ms da margem
# confortavel e e imperceptivel ao digitar.
PAUSA_MORTA = 0.015

MENSAGEM_PERMISSAO = """\
sem permissao de escrita em /dev/uinput.

O agente precisa de acesso ao uinput para criar o teclado e o mouse virtuais.
Rode uma unica vez (pede sua senha):

    sudo tee /etc/udev/rules.d/60-controle-remoto.rules >/dev/null <<'EOF'
    KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"
    EOF
    sudo modprobe uinput
    sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=uinput

Depois faca logout/login (ou reinicie) para a ACL valer."""


class ErroInjecao(RuntimeError):
    pass


class Injetor:
    """Cria os dispositivos virtuais e traduz comandos em eventos do kernel."""

    def __init__(self) -> None:
        try:
            self.teclado = UInput(
                {ecodes.EV_KEY: TODAS_TECLAS},
                name="Controle Remoto Teclado",
                version=0x0001,
            )
            self.ponteiro = UInput(
                {
                    ecodes.EV_REL: [
                        ecodes.REL_X,
                        ecodes.REL_Y,
                        ecodes.REL_WHEEL,
                        ecodes.REL_HWHEEL,
                    ],
                    ecodes.EV_KEY: [
                        ecodes.BTN_LEFT,
                        ecodes.BTN_RIGHT,
                        ecodes.BTN_MIDDLE,
                    ],
                },
                name="Controle Remoto Ponteiro",
                version=0x0001,
            )
            # O controle do slot 0 nasce junto com o agente, e nao quando o
            # celular conecta: assim um jogo aberto antes do pareamento ainda
            # encontra um controle na lista. Os demais nascem sob demanda.
            self.pad = self._criar_pad(0)
        except (PermissionError, OSError, UInputError) as exc:
            raise ErroInjecao(MENSAGEM_PERMISSAO) from exc

        # slot -> dispositivo. Cada celular ganha o seu, para dois jogadores
        # terem dois controles de verdade em vez de um compartilhado.
        self.pads: dict[int, UInput] = {0: self.pad}
        self._desconhecidos: set[str] = set()

    # -- gamepads --------------------------------------------------------
    def _criar_pad(self, slot: int) -> UInput:
        nome = "Controle Remoto Gamepad" if slot == 0 else f"Controle Remoto Gamepad {slot + 1}"
        return UInput(
            {
                ecodes.EV_KEY: list(BOTOES_PAD.values()),
                ecodes.EV_ABS: ABSINFO_PAD,
            },
            name=nome,
            version=0x0001,
            bustype=ecodes.BUS_USB,
        )

    def pad_do_slot(self, slot: int) -> UInput:
        if slot <= 0:
            return self.pad
        pad = self.pads.get(slot)
        if pad is None:
            try:
                pad = self._criar_pad(slot)
            except (PermissionError, OSError, UInputError) as exc:
                raise ErroInjecao(MENSAGEM_PERMISSAO) from exc
            self.pads[slot] = pad
        return pad

    # -- teclado ---------------------------------------------------------
    def _tecla(self, codigo: int, pressionar: bool) -> None:
        self.teclado.write(ecodes.EV_KEY, codigo, 1 if pressionar else 0)

    def tecla(self, nome: str, pressionar: bool) -> None:
        # modificador puro (Ctrl/Alt/Super) precisa ficar pressionado: quem
        # controla isso e o app, mandando down/up separados
        codigo = ESPECIAIS.get(nome)
        if codigo is None or codigo < 0:
            if nome in COMBOS:
                if pressionar:
                    self.combo(COMBOS[nome])
                return
            raise ErroInjecao(f"tecla desconhecida: {nome}")
        self._tecla(codigo, pressionar)
        self.teclado.syn()

    def tap(self, nome: str) -> None:
        codigo = ESPECIAIS.get(nome)
        if codigo is None or codigo < 0:
            if nome in COMBOS:
                self.combo(COMBOS[nome])
                return
            raise ErroInjecao(f"tecla desconhecida: {nome}")
        self._tecla(codigo, True)
        self._tecla(codigo, False)
        self.teclado.syn()

    def combo(self, nomes: list[str]) -> None:
        """Pressiona modificadores, digita uma tecla final e solta tudo.

        Ex.: ["CTRL", "ALT", "T"] ou ["SUPER", "D"].
        """
        mods = [ESPECIAIS[n] for n in nomes if n in MODIFICADORES]
        alvos = [n for n in nomes if n not in MODIFICADORES]
        if len(alvos) != 1:
            raise ErroInjecao("combo precisa de exatamente uma tecla final")
        alvo = alvos[0]

        if ESPECIAIS.get(alvo, -1) > 0:
            passos: list[tuple[int, int]] = [(ESPECIAIS[alvo], 0)]
        else:
            usados = passos_do_char(alvo)
            if not usados:
                raise ErroInjecao(f"tecla desconhecida: {alvo}")
            passos = usados

        for c in mods:
            self._tecla(c, True)
        for codigo, nivel in passos:
            ativos = [MOD_CODIGO[m] for m in (SHIFT, ALTGR) if nivel & m]
            for m in ativos:
                self._tecla(m, True)
            self._tecla(codigo, True)
            self._tecla(codigo, False)
            for m in reversed(ativos):
                self._tecla(m, False)
        for c in reversed(mods):
            self._tecla(c, False)
        self.teclado.syn()

    def backspace(self, vezes: int = 1) -> None:
        codigo = ESPECIAIS["BACKSPACE"]
        for _ in range(max(1, min(vezes, 200))):
            self._tecla(codigo, True)
            self._tecla(codigo, False)
        self.teclado.syn()

    def texto(self, s: str) -> None:
        """Digita caractere por caractere, resolvendo acentos por tecla morta."""
        for ch in s:
            if ch == "\n":
                self.tap("ENTER")
                continue
            if ch == "\t":
                self.tap("TAB")
                continue

            passos = passos_do_char(ch)
            if passos is None:
                if ch not in self._desconhecidos:
                    self._desconhecidos.add(ch)
                    print(
                        f"aviso: caractere sem tecla no layout br: {ch!r} "
                        f"(U+{ord(ch):04X}) -- ignorado",
                        file=sys.stderr,
                    )
                continue

            # cada passo e uma tecla, possivelmente com Shift/AltGr
            for indice, (codigo, mods) in enumerate(passos):
                if indice > 0:
                    # espera o compositor concluir a tecla morta antes da base
                    time.sleep(PAUSA_MORTA)
                ativos = [MOD_CODIGO[m] for m in (SHIFT, ALTGR) if mods & m]
                for m in ativos:
                    self._tecla(m, True)
                self._tecla(codigo, True)
                self._tecla(codigo, False)
                for m in reversed(ativos):
                    self._tecla(m, False)
                self.teclado.syn()

    # -- ponteiro --------------------------------------------------------
    def mover(self, dx: int, dy: int) -> None:
        if dx:
            self.ponteiro.write(ecodes.EV_REL, ecodes.REL_X, int(dx))
        if dy:
            self.ponteiro.write(ecodes.EV_REL, ecodes.REL_Y, int(dy))
        self.ponteiro.syn()

    def rolar(self, dy: int, dx: int = 0) -> None:
        if dy:
            self.ponteiro.write(ecodes.EV_REL, ecodes.REL_WHEEL, -int(dy))
        if dx:
            self.ponteiro.write(ecodes.EV_REL, ecodes.REL_HWHEEL, int(dx))
        self.ponteiro.syn()

    def botao(self, nome: str, pressionar: bool) -> None:
        codigo = BOTOES.get(nome)
        if codigo is None:
            raise ErroInjecao(f"botao desconhecido: {nome}")
        self.ponteiro.write(ecodes.EV_KEY, codigo, 1 if pressionar else 0)
        self.ponteiro.syn()

    def clique(self, nome: str = "left") -> None:
        self.botao(nome, True)
        self.botao(nome, False)

    # -- gamepad ---------------------------------------------------------
    def pad_botao(self, nome: str, pressionar: bool, slot: int = 0) -> None:
        codigo = BOTOES_PAD.get(nome)
        if codigo is None:
            raise ErroInjecao(f"botao de gamepad desconhecido: {nome}")
        pad = self.pad_do_slot(slot)
        pad.write(ecodes.EV_KEY, codigo, 1 if pressionar else 0)
        eixo_gatilho = GATILHO_EIXO.get(nome)
        if eixo_gatilho is not None:
            pad.write(ecodes.EV_ABS, eixo_gatilho, 255 if pressionar else 0)
        pad.syn()

    def eixo(self, grupo: str, x: int, y: int, slot: int = 0) -> None:
        par = GRUPOS_EIXO.get(grupo)
        if par is None:
            raise ErroInjecao(f"grupo de eixo desconhecido: {grupo}")
        if grupo == "h":
            # direcional e digital, nao analogico
            x = max(-1, min(1, int(x)))
            y = max(-1, min(1, int(y)))
        else:
            x = max(-32768, min(32767, int(x)))
            y = max(-32768, min(32767, int(y)))
        pad = self.pad_do_slot(slot)
        pad.write(ecodes.EV_ABS, par[0], x)
        pad.write(ecodes.EV_ABS, par[1], y)
        pad.syn()

    def soltar_tudo(self, slot: int = 0) -> None:
        """Solta todos os botoes e centra os eixos *deste* controle.

        Chamado quando o app desconecta. Sem isso, um botao que estava
        pressionado na hora da queda fica travado dentro do jogo ate alguem
        apertar de novo -- e se a conexao nao volta, ninguem aperta.

        Vale so para o slot do cliente que caiu: com dois celulares, soltar o
        controle do vizinho quando um deles desconecta seria pior que o bug.
        """
        pad = self.pad_do_slot(slot)
        for codigo in BOTOES_PAD.values():
            pad.write(ecodes.EV_KEY, codigo, 0)
        for eixo in (ecodes.ABS_Z, ecodes.ABS_RZ):
            pad.write(ecodes.EV_ABS, eixo, 0)
        for eixo_x, eixo_y in GRUPOS_EIXO.values():
            pad.write(ecodes.EV_ABS, eixo_x, 0)
            pad.write(ecodes.EV_ABS, eixo_y, 0)
        pad.syn()

    def liberar_extras(self) -> None:
        """Encerra os controles alem do primeiro.

        Chamado quando o ultimo celular desconecta. Sem isso um jogo aberto
        depois veria quatro controles, tres deles que ninguem esta segurando.
        """
        for slot in [s for s in self.pads if s != 0]:
            try:
                self.pads.pop(slot).close()
            except Exception:
                pass

    def fechar(self) -> None:
        for d in [self.teclado, self.ponteiro, *self.pads.values()]:
            try:
                d.close()
            except Exception:
                pass
