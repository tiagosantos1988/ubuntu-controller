# Estado do projeto — Controle Remoto

Resumo compacto para retomar o trabalho sem reler o histórico.

## O que é

**Ubuntu Controller** — celular como teclado/mouse/**gamepad** de um
Ubuntu conectado à TV, por Wi-Fi.
Dois lados: **app Android** (Kotlin/Compose) e **app de bandeja no Ubuntu**
(Python + GTK4/libadwaita), que roda o agente de injeção.
Três abas no app: Touchpad, Teclado e Joystick.

## Onde está

| peça | caminho |
|---|---|
| projeto | `~/workspace/controle-remoto/` |
| app Ubuntu instalado | `~/.local/share/controle-remoto/` |
| agente/virtuais | `servidor/` (agente.py, injecao.py, mapa_br.py, teclas.py) |
| app Android | `android/` (Kotlin + Compose) e `app/` (GTK4) |
| APK pronto | `controle-remoto.apk` |
| mapa do gamepad | `docs/GAMEPAD.md` (botões, eixos, SDL/Godot/Unity) |
| log do agente | `~/.local/state/controle-remoto/agente.log` |

Instaladores: `./liberar-uinput.sh` (uma vez, sudo) e `./instalar-app.sh` (app do Ubuntu).

## Estado agora

- agente escutando em `0.0.0.0:8765`, autostart **ligado**
- serviço systemd antigo **desabilitado** (o agente vive dentro do app gráfico)
- 3 celulares já pareados: `.6`, `.8`, `.9` (Xiaomi 2203129G, Galaxy A33, Galaxy S20 FE)
- A33 em depuracao sem fio: `adb mdns services` acha, IP pode mudar (MAC aleatorio)
- token: `~/.config/controle-remoto/token` (0600)

## Arquitetura em uma frase

App Android ↔ TCP + JSON por linha ↔ `agente.py` ↔ `/dev/uinput` ↔ GNOME (Wayland).

## Decisões que não são óbvias (e o motivo)

- **uinput, não xdotool**: a sessão é Wayland; XTEST só alcança Xwayland. O portal
  RemoteDesktop pediria consentimento a cada sessão.
- **Acentos**: o app manda *caractere*; o servidor traduz para keycodes via
  `mapa_br.py`, gerado do XKB real. Tecla morta exige **pausa de 15 ms** antes da
  base — sem isso o IBus embaralha (`ação` vira `ãçao`).
- **Backspace infinito**: o campo do IME carrega um espaço de preenchimento no
  início (não no fim, senão reordena o texto e gera backspace fantasma). Limpar o
  eco não apaga nada no PC: o app reescreve o campo, o IME não é acionado.
- **Bandeja sem AppIndicator**: `AyatanaAppIndicator3` é GTK3 e não carrega em
  processo GTK4. O ícone é registrado direto por D-Bus (`StatusNotifierItem` +
  `DBusMenu`). Hífen não é válido em caminho D-Bus: `.../controle_remoto`.
- **Pareamento por QR**: payload `CR1|ip|porta|token` (37 módulos, versão 5),
  lido com zxing no app. `pip` é bloqueado por PEP 668 → instalador usa
  `--user --break-system-packages`.
- **Dois ícones**: o da bandeja é desenhado para 16 px (azul claro sobre azul
  escuro, formas grossas) e distribuído em PNG 16/22/24/32/48/64.
- **Gamepad é uinput `EV_ABS`, não teclas**: jogo procura controle, e mouse
  andando não serve. Três dispositivos virtuais: teclado, ponteiro e gamepad
  (8 eixos, 13 botões). `absinfo` no python-evdev é
  `(código, valor, min, max, fuzz, flat, res)` — min/max trocados = `EINVAL`.
  Sem `absinfo` o eixo nasce 0..255 e o analógico vira botão. L2/R2 mandam
  `BTN_TL2` **e** `ABS_Z/RZ`=255. Ao desconectar, o agente solta tudo.

## Comandos úteis

```bash
# reiniciar o app do Ubuntu
kill $(ss -ltnp | grep 8765 | grep -o 'pid=[0-9]*' | cut -d= -f2)
~/.local/share/controle-remoto/controle_remoto.py &

# log ao vivo
tail -f ~/.local/state/controle-remoto/agente.log

# rebuild do APK
cd android && ~/.gradle/wrapper/dists/gradle-8.14.2-bin/*/gradle-8.14.2/bin/gradle assembleDebug

# instalar no celular (MIUI exige "Instalar via USB" ligado)
adb install -r controle-remoto.apk
```

## Verificações que valem repetir após mudanças

- teclas injetadas: capturar `/dev/input/event*` do "Controle Remoto Teclado"
- texto acentuado: injetar `ação coração ãõç ÀÉÎ` e conferir
- QR: decodificar o PNG gerado com zxing (mesmo motor do app)
- vibração: `adb shell dumpsys vibrator_manager | grep <pacote>`
- gamepad: `cd servidor && python3 teste_gamepad.py` (lê de volta do kernel) e
  `python3 teste_sdl.py` (o que o SDL/jogo enxerga)

## Pendências / ideias em aberto

- Leitura do QR na TV a ~3 m não foi validada por mim (só o código em si).
- Modo Joystick testado no emulador e no Galaxy A33 (SM-A336M) por depuracao
  Wi-Fi: L2/L1/triangulo/xis/PS/D-pad/analogico chegaram ao /dev/input do PC com
  os codigos certos, e a vibracao tocou. Falta a prova dentro de um jogo real.
- No joystick a barra de mídia fica oculta (espaço). Se fizer falta, dá para
  voltar como faixa compacta.
- Orientação por aba: só o Joystick é paisagem; Touchpad e Teclado, retrato.
- Abas são ícones, cada uma num chip com 6 dp de folga. Touchpad e joystick
  vêm do Material Symbols (`ic_trackpad.xml`, `ic_esports.xml`, viewBox -960
  com `translateY="960"`); o teclado é desenhado no Canvas.
- Botão PS usa o símbolo do Ubuntu (o mesmo da tecla Windows), não o da Sony.
- Nome do app: **Ubuntu Controller** (grafia correta; "Controll" seria errado).
  Os caminhos internos (`controle-remoto`) não mudaram, só o nome exibido.
- i18n: Android em `values/` (en) + `values-pt/`; Ubuntu em `app/i18n.py`
  (`LC_ALL`/`LANG`). Logs do servidor seguem em português.
- Abas ocupam a largura toda nos modos Touchpad e Teclado (no Joystick vivem
  na faixa de cima, onde a largura e' dividida com os ombros).
- Acesso a configuracao e' um icone de engrenagem (`ic_settings`, Material
  Symbols), nao mais o texto "Config".
- Textos de estado sao curtos de proposito ("can't connect", "sem conexao"):
  a barra de topo divide a linha com IP e Config e frases longas truncavam.
- Touchpad, arrasto: **um dedo parado + outro andando** segura o botao
  esquerdo (`botaoMouse("left", true/false)`). Ancorou quando o primeiro dedo
  andou < 12 dp e o segundo passou de 20 dp; o botao so sobe quando o dedo
  ancorado sai, entao da para arrastar em varias passadas (levantar o dedo que
  move nao solta). Duplo toque NAO serve: o sistema conta o segundo clique como
  "abrir" no pressionar (o libinput e' que adia isso num touchpad de notebook).
- `Injetor.pads` é um dict slot -> UInput. `soltar_tudo(slot)` mexe só no
  controle do cliente que caiu; `liberar_extras()` limpa os slots 1..3 quando
  `clientes` chega a zero.
  As abas no joystick ficam no topo (embaixo o polegar trocava de modo à toa).
- `adb install` no A33 às vezes falha com INSTALL_FAILED_VERIFICATION_FAILURE:
  é o Play Protect estourando o tempo (5 s) da verificação. Repetir resolve;
  desligar o Bloqueio automático do Samsung também.
- `Sair` no menu derruba o agente; se acionado sem querer, o app só volta no
  próximo login ou abrindo pelo menu.
- Vários celulares: cada um tem o **seu** gamepad (slot por conexão, até 4;
  extras encerrados quando o último sai). Teclado e ponteiro seguem únicos.
  O agente devolve `"controle": N` no `ola` e o app mostra esse número num
  selo ao lado das abas do modo joystick.
- Tab ainda presente na faixa do modo teclado (o usuário disse que não usa).
