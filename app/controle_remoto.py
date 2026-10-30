#!/usr/bin/env python3
"""Aplicacao de bandeja do Controle Remoto (lado Ubuntu).

Roda o agente -- teclado e mouse virtuais via uinput -- dentro do proprio
processo, mantem um icone na barra do GNOME e uma janela com os dados que voce
preenche no celular (IP, porta e token).

Instancia unica: o app registra um nome no barramento da sessao. Abrir de novo
pelo menu de aplicativos nao cria um segundo processo; apenas ativa a janela da
instancia que ja esta rodando.

Fechar a janela nao encerra o app: ele volta para a bandeja. Para sair de vez,
use "Sair" no menu do icone (ou o botao na janela).

Sobre a bandeja: o AyatanaAppIndicator e uma biblioteca GTK3 e nao pode ser
carregada num processo GTK4, que e o que o libadwaita exige. Por isso o icone e
registrado direto no barramento da sessao pelo protocolo StatusNotifierItem
(com o menu em DBusMenu) -- exatamente o que a extensao de indicadores do
GNOME consome.
"""

from __future__ import annotations

import asyncio
import os
import sys
import threading
import warnings

# o PyGObject marca register_object como obsoleto sem oferecer alternativa
# pratica para closures vindas de Python
warnings.filterwarnings("ignore", category=DeprecationWarning)

import gi
from i18n import NOME, t

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Dbusmenu", "0.4")

from gi.repository import Adw, Dbusmenu, Gdk, Gio, GLib, Gtk  # noqa: E402

APP_ID = "br.com.tsantos.ControleRemoto"
NOME_ICONE = "controle-remoto"          # icone do aplicativo
NOME_ICONE_BANDEJA = "controle-remoto-tray"  # versao simplificada para 16 px
VARIAVEL_OCULTO = "CONTROLE_REMOTO_OCULTO"

RAIZ = os.path.dirname(os.path.abspath(__file__))
for candidato in (
    os.path.join(RAIZ, "servidor"),
    os.path.join(RAIZ, "..", "servidor"),
):
    if os.path.isdir(candidato):
        sys.path.insert(0, os.path.abspath(candidato))
        break

from agente import PORTA_PADRAO, Agente, carregar_token, ip_local  # noqa: E402
from injecao import ErroInjecao, Injetor  # noqa: E402

PASTA_ESTADO = os.path.join(GLib.get_user_state_dir(), "controle-remoto")


def preparar_log() -> str:
    """O app roda pela sessao grafica, sem terminal: manda o log para arquivo."""
    os.makedirs(PASTA_ESTADO, exist_ok=True)
    caminho = os.path.join(PASTA_ESTADO, "agente.log")
    try:
        arquivo = open(caminho, "a", buffering=1, encoding="utf-8")
        sys.stderr = arquivo
    except OSError:
        pass
    return caminho



ARQUIVO_APP = os.path.join(
    GLib.get_user_data_dir(), "applications", "controle-remoto.desktop"
)
ARQUIVO_AUTOSTART = os.path.join(
    GLib.get_user_config_dir(), "autostart", "controle-remoto.desktop"
)


def inicia_com_o_sistema() -> bool:
    return os.path.exists(ARQUIVO_AUTOSTART)


def definir_inicio_automatico(ativo: bool) -> bool:
    """Liga/desliga o inicio automatico mexendo no arquivo de autostart."""
    if not ativo:
        try:
            os.remove(ARQUIVO_AUTOSTART)
        except FileNotFoundError:
            pass
        except OSError:
            return False
        return True

    try:
        with open(ARQUIVO_APP, encoding="utf-8") as arquivo:
            linhas = arquivo.readlines()
    except OSError:
        return False

    saida = []
    for linha in linhas:
        if linha.startswith("Exec=") and "CONTROLE_REMOTO_OCULTO" not in linha:
            comando = linha[len("Exec="):].strip()
            saida.append(f"Exec=env CONTROLE_REMOTO_OCULTO=1 {comando}\n")
        else:
            saida.append(linha)

    try:
        os.makedirs(os.path.dirname(ARQUIVO_AUTOSTART), exist_ok=True)
        with open(ARQUIVO_AUTOSTART, "w", encoding="utf-8") as arquivo:
            arquivo.writelines(saida)
    except OSError:
        return False
    return True


class Servidor(threading.Thread):
    """Mantem o agente rodando num thread, reiniciando se ele cair."""

    def __init__(self, token: str) -> None:
        super().__init__(daemon=True, name="agente-controle-remoto")
        self.token = token
        self.agente: Agente | None = None
        self.erro: str | None = None
        self._parar = threading.Event()

    def run(self) -> None:
        while not self._parar.is_set():
            try:
                laco = asyncio.new_event_loop()
                asyncio.set_event_loop(laco)
                injetor = Injetor()
                self.agente = Agente(injetor, self.token, PORTA_PADRAO)
                self.erro = None
                laco.run_until_complete(self.agente.rodar())
                laco.close()
            except ErroInjecao as exc:
                self.erro = str(exc)
                self.agente = None
                self._parar.wait(6)
            except Exception as exc:  # noqa: BLE001
                self.erro = f"{exc!r}"
                self.agente = None
                self._parar.wait(3)

    def parar(self) -> None:
        self._parar.set()
        if self.agente is not None:
            self.agente.parar()

    @property
    def conectados(self) -> int:
        return self.agente.clientes if self.agente is not None else 0


class Bandeja:
    """Icone na barra do GNOME (StatusNotifierItem) com menu (DBusMenu)."""

    CAMINHO = "/org/ayatana/NotificationItem/controle_remoto"
    CAMINHO_MENU = CAMINHO + "/Menu"

    XML = """
    <node>
      <interface name="org.kde.StatusNotifierItem">
        <property name="Category" type="s" access="read"/>
        <property name="Id" type="s" access="read"/>
        <property name="Title" type="s" access="read"/>
        <property name="Status" type="s" access="read"/>
        <property name="IconName" type="s" access="read"/>
        <property name="Menu" type="o" access="read"/>
        <property name="ItemIsMenu" type="b" access="read"/>
        <method name="Activate">
          <arg name="x" type="i" direction="in"/>
          <arg name="y" type="i" direction="in"/>
        </method>
        <method name="SecondaryActivate">
          <arg name="x" type="i" direction="in"/>
          <arg name="y" type="i" direction="in"/>
        </method>
        <method name="ContextMenu">
          <arg name="x" type="i" direction="in"/>
          <arg name="y" type="i" direction="in"/>
        </method>
        <method name="Scroll">
          <arg name="delta" type="i" direction="in"/>
          <arg name="orientation" type="s" direction="in"/>
        </method>
      </interface>
    </node>
    """

    def __init__(self, conexao, ao_abrir, ao_copiar_token, ao_sair) -> None:
        self._conexao = conexao
        self._ao_abrir = ao_abrir

        self._menu = Dbusmenu.Server.new(self.CAMINHO_MENU)
        raiz = Dbusmenu.Menuitem.new()
        raiz.property_set(Dbusmenu.MENUITEM_PROP_LABEL, NOME)
        raiz.property_set_bool(Dbusmenu.MENUITEM_PROP_VISIBLE, True)
        self._adicionar_item(raiz, t("menu_abrir"), ao_abrir)
        self._adicionar_item(raiz, t("menu_copiar_token"), ao_copiar_token)
        self._adicionar_item(raiz, t("menu_sair"), ao_sair)
        raiz.set_root()
        self._menu.set_root(raiz)

        info = Gio.DBusNodeInfo.new_for_xml(self.XML)
        # register_object esta marcado como obsoleto no PyGObject, mas a
        # alternativa (register_object_with_closures2) exige GObject.Closure.
        # O aviso e silenciado no topo do arquivo.
        self._registro = conexao.register_object(
            self.CAMINHO,
            info.interfaces[0],
            self._ao_metodo,
            self._ao_propriedade,
            None,
        )

        try:
            conexao.call_sync(
                "org.kde.StatusNotifierWatcher",
                "/StatusNotifierWatcher",
                "org.kde.StatusNotifierWatcher",
                "RegisterStatusNotifierItem",
                GLib.Variant("(s)", (self.CAMINHO,)),
                None,
                Gio.DBusCallFlags.NONE,
                -1,
                None,
            )
        except GLib.Error as exc:
            print(f"sem watcher de indicadores: {exc.message}", file=sys.stderr)

    def _adicionar_item(self, raiz, rotulo: str, acao) -> None:
        item = Dbusmenu.Menuitem.new()
        item.property_set(Dbusmenu.MENUITEM_PROP_LABEL, rotulo)
        item.property_set_bool(Dbusmenu.MENUITEM_PROP_VISIBLE, True)
        item.property_set_bool(Dbusmenu.MENUITEM_PROP_ENABLED, True)
        item.connect("item-activated", lambda *_a, f=acao: f())
        raiz.child_append(item)

    # -- D-Bus -----------------------------------------------------------
    def _ao_propriedade(self, *_args):
        conexao, remetente, caminho, interface, nome = _args
        valores = {
            "Category": GLib.Variant("s", "ApplicationStatus"),
            "Id": GLib.Variant("s", "controle-remoto"),
            "Title": GLib.Variant("s", NOME),
            "Status": GLib.Variant("s", "Active"),
            "IconName": GLib.Variant("s", NOME_ICONE_BANDEJA),
            "Menu": GLib.Variant("o", self.CAMINHO_MENU),
            "ItemIsMenu": GLib.Variant("b", True),
        }
        return valores.get(nome)

    def _ao_metodo(self, conexao, remetente, caminho, interface, metodo, params, invocacao):
        if metodo in ("Activate", "SecondaryActivate", "ContextMenu"):
            self._ao_abrir()
        invocacao.return_value(None)



# Prefixo do conteudo do QR code: curto de proposito, porque payload menor gera
# menos modulos e um codigo mais facil de ler a distancia.
PREFIXO_QR = "CR1"


def matriz_qr(dados: str) -> list[list[bool]] | None:
    """Matriz de modulos do QR code, ou None se a biblioteca nao estiver."""
    try:
        import qrcode
    except ImportError:
        return None

    codigo = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    codigo.add_data(dados)
    codigo.make(fit=True)
    return codigo.get_matrix()


def textura_qr(dados: str, lado_alvo: int = 180):
    """Textura do QR para o Gtk.Picture (fundo branco, modulos pretos)."""
    matriz = matriz_qr(dados)
    if matriz is None:
        return None

    modulos = len(matriz)
    escala = max(2, lado_alvo // modulos)
    lado = modulos * escala
    buffer = bytearray(b"\xff" * (lado * lado * 3))
    preto = b"\x00" * (escala * 3)

    for y, linha in enumerate(matriz):
        for x, aceso in enumerate(linha):
            if not aceso:
                continue
            for dy in range(escala):
                inicio = ((y * escala + dy) * lado + x * escala) * 3
                buffer[inicio:inicio + escala * 3] = preto

    return Gdk.MemoryTexture.new(
        lado, lado, Gdk.MemoryFormat.R8G8B8, GLib.Bytes.new(bytes(buffer)), lado * 3
    )


class Janela(Adw.ApplicationWindow):
    def __init__(self, app: "Aplicativo") -> None:
        super().__init__(application=app, title=NOME)
        # altura -1: o GTK usa a altura natural do conteudo, para a
        # pagina nao abrir com barra de rolagem
        self.set_default_size(780, -1)
        self._app = app

        cabecalho = Adw.HeaderBar()
        copiar_tudo = Gtk.Button(label=t("copiar_tudo"))
        copiar_tudo.add_css_class("suggested-action")
        copiar_tudo.connect("clicked", lambda *_: app.copiar_tudo())
        cabecalho.pack_start(copiar_tudo)

        sair = Gtk.Button(label=t("menu_sair"))
        sair.add_css_class("flat")
        sair.set_tooltip_text(t("dica_sair"))
        sair.connect("clicked", lambda *_: app.sair())
        cabecalho.pack_end(sair)

        self._banner = Adw.Banner(revealed=False)

        pagina = Adw.PreferencesPage()

        grupo = Adw.PreferencesGroup(title=t("grupo_celular"))
        grupo.set_description(t("grupo_celular_desc"))

        self._linha_ip = self._linha_copiavel(grupo, "IP", app.ip)
        self._linha_porta = self._linha_copiavel(grupo, "Porta", str(PORTA_PADRAO))
        self._linha_token = self._linha_copiavel(grupo, "Token", app.token)
        pagina.add(grupo)

        grupo_status = Adw.PreferencesGroup(title=t("grupo_status"))
        self._linha_agente = Adw.ActionRow(title=t("linha_agente"))
        self._linha_conexoes = Adw.ActionRow(title=t("linha_conexoes"), subtitle="0")
        grupo_status.add(self._linha_agente)
        grupo_status.add(self._linha_conexoes)

        self._mudando_inicio = False
        self._linha_inicio = Adw.SwitchRow(
            title=t("iniciar_titulo"),
            subtitle=t("iniciar_sub"),
        )
        self._linha_inicio.set_active(inicia_com_o_sistema())
        self._linha_inicio.connect("notify::active", self._ao_mudar_inicio)
        grupo_status.add(self._linha_inicio)

        pagina.add(grupo_status)

        # coluna da direita: QR code, para a janela nao ficar comprida
        texto_qr = f"{PREFIXO_QR}|{app.ip}|{PORTA_PADRAO}|{app.token}"
        textura = textura_qr(texto_qr)
        coluna_qr = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        coluna_qr.set_valign(Gtk.Align.START)
        coluna_qr.set_hexpand(False)
        coluna_qr.set_size_request(210, -1)
        coluna_qr.set_margin_top(24)
        coluna_qr.set_margin_start(4)
        coluna_qr.set_margin_end(20)
        if textura is not None:
            figura = Gtk.Picture.new_for_paintable(textura)
            figura.set_size_request(165, 165)
            figura.set_halign(Gtk.Align.CENTER)
            coluna_qr.append(figura)
            dica = Gtk.Label(label=t("qr_dica"))
            dica.add_css_class("dim-label")
            dica.set_wrap(True)
            dica.set_justify(Gtk.Justification.CENTER)
            coluna_qr.append(dica)
        else:
            sem_qr = Gtk.Label(label=t("qr_indisponivel"))
            sem_qr.add_css_class("dim-label")
            sem_qr.set_wrap(True)
            coluna_qr.append(sem_qr)

        corpo = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        pagina.set_hexpand(True)
        corpo.append(pagina)
        corpo.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL))
        corpo.append(coluna_qr)

        caixa = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        caixa.append(cabecalho)
        caixa.append(self._banner)
        caixa.append(corpo)
        self.set_content(caixa)

        self.connect("close-request", self._ao_fechar)
        GLib.timeout_add_seconds(1, self._atualizar)
        self._atualizar()

    def _linha_copiavel(
        self, grupo: Adw.PreferencesGroup, titulo: str, valor: str
    ) -> Adw.ActionRow:
        linha = Adw.ActionRow(title=titulo, subtitle=valor)
        linha.set_subtitle_selectable(True)
        botao = Gtk.Button(icon_name="edit-copy-symbolic", valign=Gtk.Align.CENTER)
        botao.add_css_class("flat")
        botao.set_tooltip_text(t("copiar"))
        botao.connect("clicked", lambda *_: self._app.copiar(valor))
        linha.add_suffix(botao)
        grupo.add(linha)
        return linha

    def _ao_mudar_inicio(self, linha, _pspec) -> None:
        if self._mudando_inicio:
            return
        desejado = linha.get_active()
        if not definir_inicio_automatico(desejado):
            # nao conseguiu mexer no arquivo: volta o interruptor
            self._mudando_inicio = True
            linha.set_active(not desejado)
            self._mudando_inicio = False

    def _ao_fechar(self, *_args) -> bool:
        self.set_visible(False)
        return True

    def _atualizar(self) -> bool:
        servidor = self._app.servidor
        if servidor.erro:
            self._banner.set_title(t("banner_teclado"))
            self._banner.set_revealed(True)
            primeira = servidor.erro.strip().splitlines()[0]
            self._linha_agente.set_subtitle(t("agente_parado", motivo=primeira))
        else:
            self._banner.set_revealed(False)
            self._linha_agente.set_subtitle(t("agente_rodando", porta=PORTA_PADRAO))
        self._linha_conexoes.set_subtitle(str(servidor.conectados))
        return True


class Aplicativo(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id=APP_ID)
        self._oculto = os.environ.get(VARIAVEL_OCULTO) == "1"
        self._janela: Janela | None = None
        self._bandeja: Bandeja | None = None

        self.token = carregar_token()
        self.ip = ip_local()
        self.servidor = Servidor(self.token)

        for nome, funcao in (
            ("abrir", self.mostrar),
            ("copiar-token", self.copiar_token),
            ("sair", self.sair),
        ):
            acao = Gio.SimpleAction.new(nome, None)
            acao.connect("activate", lambda *_a, f=funcao: f())
            self.add_action(acao)

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)
        self.hold()  # sem janela aberta o app continua vivo na bandeja
        self.servidor.start()

        # a bandeja nunca deve impedir o app de funcionar
        try:
            conexao = self.get_dbus_connection()
            if conexao is not None:
                self._bandeja = Bandeja(
                    conexao,
                    ao_abrir=self.mostrar,
                    ao_copiar_token=self.copiar_token,
                    ao_sair=self.sair,
                )
        except Exception as exc:  # noqa: BLE001
            print(f"bandeja indisponivel: {exc!r}", file=sys.stderr)

    def do_activate(self) -> None:
        if self._janela is None:
            self._janela = Janela(self)
        if self._oculto:
            self._oculto = False  # so a primeira ativacao (autostart) fica oculta
        else:
            self.mostrar()

    def do_shutdown(self) -> None:
        self.servidor.parar()
        Adw.Application.do_shutdown(self)

    def mostrar(self) -> None:
        if self._janela is None:
            self._janela = Janela(self)
        self._janela.set_visible(True)
        self._janela.present()

    def copiar(self, texto: str) -> None:
        area = Gdk.Display.get_default()
        if area is not None:
            area.get_clipboard().set(texto)

    def copiar_token(self) -> None:
        self.copiar(self.token)

    def copiar_tudo(self) -> None:
        self.copiar(f"IP: {self.ip}\nPorta: {PORTA_PADRAO}\nToken: {self.token}")

    def sair(self) -> None:
        self.release()
        self.quit()


def main() -> int:
    preparar_log()
    app = Aplicativo()
    return app.run(sys.argv)


if __name__ == "__main__":
    raise SystemExit(main())
