#!/usr/bin/env bash
# Instala o Controle Remoto como aplicacao do usuario:
#   - arquivos em ~/.local/share/controle-remoto
#   - icone no tema do sistema
#   - atalho no menu de aplicativos
#   - inicio automatico junto com a sessao (sem abrir a janela)
#
# Nao precisa de sudo.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HOME/.local/share/controle-remoto"
ICONE="$HOME/.local/share/icons/hicolor/scalable/apps/controle-remoto.svg"
DESKTOP="$HOME/.local/share/applications/controle-remoto.desktop"
AUTOSTART="$HOME/.config/autostart/controle-remoto.desktop"

echo "== arquivos do app em $DEST"
mkdir -p "$DEST/servidor"
install -m 0755 "$DIR/app/controle_remoto.py" "$DEST/controle_remoto.py"
install -m 0644 "$DIR/app/i18n.py" "$DEST/i18n.py"
install -m 0644 "$DIR/servidor/"*.py "$DEST/servidor/"
rm -rf "$DEST/servidor/__pycache__"

echo "== icones"
# icone do aplicativo (menu e janela)
install -Dm644 "$DIR/app/controle-remoto.svg" "$ICONE"
# icone da bandeja: versao simplificada para ~16 px, com PNGs nos tamanhos
# exatos que o tema de icones procura
install -Dm644 "$DIR/app/tray/controle-remoto-tray.svg" \
    "$HOME/.local/share/icons/hicolor/scalable/apps/controle-remoto-tray.svg"
for tamanho in 16 22 24 32 48 64; do
    install -Dm644 "$DIR/app/tray/controle-remoto-tray-$tamanho.png" \
        "$HOME/.local/share/icons/hicolor/${tamanho}x${tamanho}/apps/controle-remoto-tray.png"
done
gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" >/dev/null 2>&1 || true

echo "== atalho no menu de aplicativos"
install -Dm644 "$DIR/app/controle-remoto.desktop" "$DESKTOP"
sed -i "s|^Exec=.*|Exec=$DEST/controle_remoto.py|" "$DESKTOP"

echo "== inicio automatico com a sessao (oculto, so na bandeja)"
mkdir -p "$(dirname "$AUTOSTART")"
sed "s|^Exec=.*|Exec=env CONTROLE_REMOTO_OCULTO=1 $DEST/controle_remoto.py|" \
    "$DESKTOP" > "$AUTOSTART"

update-desktop-database "$HOME/.local/share/applications" >/dev/null 2>&1 || true

echo "== dependencia do QR code"
if python3 -c "import qrcode" 2>/dev/null; then
    echo "   qrcode ja disponivel"
else
    # --break-system-packages: o Ubuntu bloqueia pip fora de venv (PEP 668);
    # a instalacao vai para ~/.local e nao mexe nos pacotes do sistema
    if python3 -m pip install --user --break-system-packages qrcode >/dev/null 2>&1; then
        echo "   qrcode instalado em ~/.local"
    else
        echo "   aviso: sem qrcode; a janela mostrara os dados sem QR code"
    fi
fi

echo "== desativando o servico systemd antigo (o agente agora vive no app)"
systemctl --user disable --now controle-remoto.service >/dev/null 2>&1 || true

echo
echo "Pronto."
echo "  - Abra pelo menu de aplicativos: Controle Remoto"
echo "  - O icone fica na barra superior do GNOME"
echo "  - Fechar a janela nao encerra: volta para a bandeja"
echo "  - Log do agente: ~/.local/state/controle-remoto/agente.log"
