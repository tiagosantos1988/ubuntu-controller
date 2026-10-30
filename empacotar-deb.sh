#!/usr/bin/env bash
# Monta o pacote .deb do lado Ubuntu (app de bandeja + agente).
#
#   ./empacotar-deb.sh            usa a versao do arquivo VERSION
#   ./empacotar-deb.sh 1.2.0      forca outra versao
#
# O resultado vai para dist/ubuntu-controller_<versao>_all.deb e se instala com
#   sudo apt install ./dist/ubuntu-controller_<versao>_all.deb
#
# Sem debhelper de proposito: a arvore e montada a mao e empacotada com
# dpkg-deb, entao o script roda em qualquer Ubuntu sem instalar nada antes.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSAO="${1:-$(head -1 "$DIR/VERSION" | tr -d '[:space:]')}"
PACOTE="ubuntu-controller"
ARQ_SAIDA="$DIR/dist/${PACOTE}_${VERSAO}_all.deb"
ESTAGIO="$(mktemp -d)"
trap 'rm -rf "$ESTAGIO"' EXIT

echo "== montando $PACOTE $VERSAO"

# -- programa ------------------------------------------------------------
mkdir -p "$ESTAGIO/usr/lib/$PACOTE/servidor"
install -m 0755 "$DIR/app/controle_remoto.py" "$ESTAGIO/usr/lib/$PACOTE/"
install -m 0644 "$DIR/app/i18n.py" "$ESTAGIO/usr/lib/$PACOTE/"
for modulo in agente injecao teclas mapa_br; do
    install -m 0644 "$DIR/servidor/$modulo.py" "$ESTAGIO/usr/lib/$PACOTE/servidor/"
done

mkdir -p "$ESTAGIO/usr/bin"
cat > "$ESTAGIO/usr/bin/ubuntu-controller" <<'LAUNCHER'
#!/bin/sh
# Lancador do pacote: o codigo fica em /usr/lib/ubuntu-controller.
exec python3 /usr/lib/ubuntu-controller/controle_remoto.py "$@"
LAUNCHER
chmod 0755 "$ESTAGIO/usr/bin/ubuntu-controller"

# -- atalho e icones -----------------------------------------------------
mkdir -p "$ESTAGIO/usr/share/applications"
install -m 0644 "$DIR/app/controle-remoto.desktop" \
    "$ESTAGIO/usr/share/applications/controle-remoto.desktop"
sed -i "s|^Exec=.*|Exec=/usr/bin/ubuntu-controller|" \
    "$ESTAGIO/usr/share/applications/controle-remoto.desktop"

install -Dm644 "$DIR/app/controle-remoto.svg" \
    "$ESTAGIO/usr/share/icons/hicolor/scalable/apps/controle-remoto.svg"
install -Dm644 "$DIR/app/tray/controle-remoto-tray.svg" \
    "$ESTAGIO/usr/share/icons/hicolor/scalable/apps/controle-remoto-tray.svg"
for tamanho in 16 22 24 32 48 64; do
    install -Dm644 "$DIR/app/tray/controle-remoto-tray-$tamanho.png" \
        "$ESTAGIO/usr/share/icons/hicolor/${tamanho}x${tamanho}/apps/controle-remoto-tray.png"
done

# -- regra do uinput -----------------------------------------------------
install -Dm644 "$DIR/udev/60-controle-remoto.rules" \
    "$ESTAGIO/usr/lib/udev/rules.d/60-controle-remoto.rules"

# -- documentacao --------------------------------------------------------
mkdir -p "$ESTAGIO/usr/share/doc/$PACOTE"
cp "$DIR/README.md" "$ESTAGIO/usr/share/doc/$PACOTE/README.md"
cat > "$ESTAGIO/usr/share/doc/$PACOTE/copyright" <<'COPYRIGHT'
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: ubuntu-controller
Source: https://github.com/tiagosantos1988/ubuntu-controller

Files: *
Copyright: 2026 Tiago Santos
License: MIT

Files: android/app/src/main/res/drawable/ic_trackpad.xml
 android/app/src/main/res/drawable/ic_esports.xml
 android/app/src/main/res/drawable/ic_settings.xml
Copyright: Google
License: Apache-2.0
Comment: icones do conjunto Material Symbols
COPYRIGHT

# -- metadados -----------------------------------------------------------
mkdir -p "$ESTAGIO/DEBIAN"
TAMANHO="$(du -sk --exclude=DEBIAN "$ESTAGIO" | cut -f1)"
cat > "$ESTAGIO/DEBIAN/control" <<CONTROLE
Package: $PACOTE
Version: $VERSAO
Section: utils
Priority: optional
Architecture: all
Installed-Size: $TAMANHO
Maintainer: Tiago Santos <tiago.leucotron@gmail.com>
Homepage: https://github.com/tiagosantos1988/ubuntu-controller
Depends: python3 (>= 3.10), python3-evdev, python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1, gir1.2-dbusmenu-glib-0.4, libxkbcommon0, udev
Recommends: python3-qrcode, gnome-shell-extension-appindicator
Description: teclado, touchpad e gamepad do celular para o Ubuntu
 Roda um agente que cria teclado, mouse e gamepad virtuais via /dev/uinput,
 de modo que o computador aceita entrada do celular pela rede local como se
 fossem dispositivos fisicos.
 .
 Tem app de bandeja (GTK4/libadwaita) com QR code para parear o celular, e o
 aplicativo Android correspondente e publicado no GitHub.
CONTROLE

cat > "$ESTAGIO/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e

if [ "$1" = "configure" ]; then
    # a regra do uinput precisa valer na hora, sem pedir logout
    if command -v udevadm >/dev/null 2>&1; then
        udevadm control --reload-rules || true
        udevadm trigger --subsystem-match=misc --action=change || true
    fi
    command -v update-desktop-database >/dev/null 2>&1 && \
        update-desktop-database -q /usr/share/applications || true
    command -v gtk-update-icon-cache >/dev/null 2>&1 && \
        gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi

exit 0
POSTINST
chmod 0755 "$ESTAGIO/DEBIAN/postinst"

cat > "$ESTAGIO/DEBIAN/postrm" <<'POSTRM'
#!/bin/sh
set -e

if [ "$1" = "remove" ] || [ "$1" = "purge" ]; then
    if command -v udevadm >/dev/null 2>&1; then
        udevadm control --reload-rules || true
        udevadm trigger --subsystem-match=misc --action=change || true
    fi
    command -v update-desktop-database >/dev/null 2>&1 && \
        update-desktop-database -q /usr/share/applications || true
fi

exit 0
POSTRM
chmod 0755 "$ESTAGIO/DEBIAN/postrm"

# -- permissoes ----------------------------------------------------------
# o diretorio de estagio nasce 0700 (mktemp); o pacote precisa de 0755/0644
find "$ESTAGIO" -type d -exec chmod 0755 {} +
find "$ESTAGIO" -type f -exec chmod 0644 {} +
chmod 0755 "$ESTAGIO/usr/bin/ubuntu-controller" \
           "$ESTAGIO/usr/lib/$PACOTE/controle_remoto.py" \
           "$ESTAGIO/DEBIAN/postinst" \
           "$ESTAGIO/DEBIAN/postrm"

# -- empacotamento -------------------------------------------------------
mkdir -p "$DIR/dist"
dpkg-deb --root-owner-group --build "$ESTAGIO" "$ARQ_SAIDA" >/dev/null

echo "== pronto: ${ARQ_SAIDA#$DIR/} ($(du -h "$ARQ_SAIDA" | cut -f1))"
echo "   instale com: sudo apt install ./${ARQ_SAIDA#$DIR/}"
