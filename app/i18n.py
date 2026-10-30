"""Textos do app do Ubuntu em portugues e ingles.

Sem gettext/.mo de proposito: sao poucas frases, o app e instalado como
arquivos soltos e um dicionario basta. O idioma segue o do sistema (LANG /
LC_ALL / LC_MESSAGES): qualquer coisa que comece com "pt" usa portugues,
o resto cai no ingles.
"""

from __future__ import annotations

import os

NOME = "Ubuntu Controller"

TEXTOS: dict[str, dict[str, str]] = {
    "pt": {
        "menu_abrir": "Abrir",
        "menu_copiar_token": "Copiar token",
        "menu_sair": "Sair",
        "copiar": "Copiar",
        "copiar_tudo": "Copiar tudo",
        "dica_sair": "Encerrar o app e o agente",
        "grupo_celular": "Configurar no celular",
        "grupo_celular_desc": "Abra o app do Android e preencha estes três campos.",
        "grupo_status": "Status",
        "linha_agente": "Agente",
        "linha_conexoes": "Aparelhos conectados",
        "agente_parado": "parado: {motivo}",
        "agente_rodando": "em execução, porta {porta}",
        "iniciar_titulo": "Iniciar com o Ubuntu",
        "iniciar_sub": "Abre na bandeja ao entrar na sessão",
        "qr_dica": "Aponte a câmera do app para este código",
        "qr_indisponivel": "QR code indisponível (falta a biblioteca qrcode)",
        "banner_teclado": "Teclado virtual indisponível — detalhes abaixo",
    },
    "en": {
        "menu_abrir": "Open",
        "menu_copiar_token": "Copy token",
        "menu_sair": "Quit",
        "copiar": "Copy",
        "copiar_tudo": "Copy all",
        "dica_sair": "Stop the app and the agent",
        "grupo_celular": "Set up on the phone",
        "grupo_celular_desc": "Open the Android app and fill in these three fields.",
        "grupo_status": "Status",
        "linha_agente": "Agent",
        "linha_conexoes": "Connected phones",
        "agente_parado": "stopped: {motivo}",
        "agente_rodando": "running, port {porta}",
        "iniciar_titulo": "Start with Ubuntu",
        "iniciar_sub": "Opens in the tray when the session starts",
        "qr_dica": "Point the phone camera at this code",
        "qr_indisponivel": "QR code unavailable (qrcode library missing)",
        "banner_teclado": "Virtual keyboard unavailable — details below",
    },
}


def idioma() -> str:
    bruto = (
        os.environ.get("LC_ALL")
        or os.environ.get("LC_MESSAGES")
        or os.environ.get("LANG")
        or ""
    ).lower()
    return "pt" if bruto.startswith("pt") else "en"


def t(chave: str, **campos: object) -> str:
    tabela = TEXTOS.get(idioma(), TEXTOS["en"])
    texto = tabela.get(chave) or TEXTOS["en"].get(chave, chave)
    return texto.format(**campos) if campos else texto
