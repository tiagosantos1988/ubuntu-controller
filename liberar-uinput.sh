#!/usr/bin/env bash
# Libera o /dev/uinput para o seu usuario.
#
# Rode UMA vez, com sudo:
#     sudo /home/tsantos/workspace/controle-remoto/liberar-uinput.sh
#
# Faz duas coisas, para nao depender de qual mecanismo o udev aplica primeiro:
#   1. ACL da sessao ativa (TAG+="uaccess") -- vale na hora, sem logout.
#   2. grupo "input" com modo 0660 -- vale no proximo login, como garantia.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGRA="$DIR/udev/60-controle-remoto.rules"
DESTINO="/etc/udev/rules.d/60-controle-remoto.rules"

if [ "$(id -u)" -ne 0 ]; then
    echo "erro: rode com sudo:" >&2
    echo "  sudo $0" >&2
    exit 1
fi

USUARIO="${SUDO_USER:-}"
if [ -z "$USUARIO" ] || [ "$USUARIO" = "root" ]; then
    echo "erro: rode como 'sudo ./liberar-uinput.sh' a partir do seu usuario," >&2
    echo "       para que eu saiba a quem conceder o acesso." >&2
    exit 1
fi

echo "== 1. instalando a regra udev =="
install -m 0644 "$REGRA" "$DESTINO"
echo "   $DESTINO"

echo "== 2. carregando o modulo uinput =="
modprobe uinput 2>/dev/null && echo "   modprobe ok" || echo "   ja embutido no kernel"

echo "== 3. recarregando as regras =="
udevadm control --reload-rules
udevadm trigger --name-match=uinput
udevadm settle --timeout=5 2>/dev/null || true
sleep 0.5

echo "== 4. adicionando $USUARIO ao grupo input (garantia para o proximo login) =="
usermod -aG input "$USUARIO"
echo "   ok"

echo
echo "== estado do dispositivo =="
ls -l /dev/uinput
echo
echo "ACL:"
getfacl -p /dev/uinput 2>/dev/null | sed 's/^/   /' || true

echo
# checa pela ACL (e nao por "sudo -u ... test -w", que da falso negativo)
if getfacl -p /dev/uinput 2>/dev/null | grep -q "^user:$USUARIO:rw"; then
    echo "RESULTADO: liberado AGORA -- nao precisa fazer logout."
elif id -nG "$USUARIO" | grep -qw input; then
    echo "RESULTADO: ACL nao listada, mas o grupo input foi aplicado."
    echo "Faca logout/login (ou reinicie) para valer nesta sessao."
else
    echo "RESULTADO: nao consegui confirmar. Verifique com:"
    echo "   getfacl -p /dev/uinput"
fi
