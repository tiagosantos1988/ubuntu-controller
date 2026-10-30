# Ubuntu Controller — celular como teclado, mouse e joystick do Ubuntu

Controla o Ubuntu da sala pelo celular, via Wi-Fi: touchpad, teclado (usando o
teclado do Android, tecla a tecla), botões de mídia/volume e um **gamepad** para
jogos. Feito para TV a distância, com ícone próprio no Android e conexão
automática — sem QR code a cada uso.

```
 celular (app Android)  ──TCP/JSON por linha──►  agente.py  ──/dev/uinput──►  GNOME (Wayland)
```

## Por que uinput e não xdotool

A sessão é Wayland (GNOME 50). `xdotool`/XTEST só alcança janelas X11 via
Xwayland. Um dispositivo `uinput` é visto pelo kernel como hardware real, então
funciona em apps nativos, jogos e na tela de bloqueio — e nunca pede confirmação
ao usuário (o portal RemoteDesktop pediria consentimento a cada sessão).

## Estrutura

```
servidor/
  agente.py          servidor TCP + autenticação por token (stdlib apenas)
  injecao.py         teclado, mouse e gamepad virtuais via /dev/uinput
  teclas.py          teclas especiais e combos
  mapa_br.py         GERADO: caractere -> sequência de keycodes (ABNT2)
  gerar_mapa.py      gera o mapa a partir de libxkbcommon + Compose do X11
  teste_protocolo.py teste de fumaça (roda sem permissão de uinput)
  teste_gamepad.py   lê de volta do /dev/input o que o gamepad virtual emite
  teste_sdl.py       confere o gamepad pelos olhos do SDL (precisa de pygame)
android/             app nativo Kotlin + Jetpack Compose
app/                 app de bandeja do Ubuntu (GTK4 + libadwaita)
udev/                regra que libera /dev/uinput para a sessão
```

## Ícone

São **dois** ícones, de propósito:

- **aplicativo** (`controle-remoto`) — um controle remoto quase quadrado (não o
  retângulo estreito do modelo físico), corpo escuro com contorno azul, o
  **símbolo do Ubuntu** (Círculo de Amigos) no lugar do direcional e **três
  botões em fila** embaixo, separados. O ponto laranja do emissor de
  infravermelho fica **na linha da borda de cima**, metade para fora — é o
  detalhe que faz o desenho parecer um controle de verdade e não uma caixa.
  Usado no menu de aplicativos, na janela e no launcher do Android;
- **bandeja** (`controle-remoto-tray`) — mesma paleta do app (azul claro sobre
  azul escuro) e a mesma composição, simplificada para os ~16 px da barra do
  GNOME: corpo, um círculo no lugar do símbolo e as três teclas em fila. Sem
  contorno fino: o ícone detalhado virava borrão nesse tamanho.

A geometria é uma só, em `app/controle-remoto.svg`, e o vetor do launcher
Android (`ic_launcher_foreground.xml`) repete os mesmos números — se mudar um,
mude o outro.

O ícone de bandeja é distribuído em SVG e em PNGs de 16, 22, 24, 32, 48 e 64 px
(gerados por `app/tray/gerar-pngs.py`), porque o tema de ícones procura o
tamanho exato e escalonar um SVG de 108 px para 16 px perde nitidez.

## Instalação

### 1. Liberar /dev/uinput (uma vez, pede senha)

```bash
sudo ./liberar-uinput.sh
```

O script instala a regra udev, carrega o módulo, recarrega as regras e adiciona
seu usuário ao grupo `input` como garantia (a ACL da sessão costuma valer na
hora, sem logout). No fim ele mesmo verifica se funcionou.

Para conferir depois:

```bash
test -w /dev/uinput && echo liberado
```

### 2. App do Ubuntu (sem sudo)

```bash
./instalar-app.sh
```

Instala o app do lado Ubuntu e o coloca para iniciar junto com a sessão:

| o quê | onde |
|---|---|
| programa | `~/.local/share/controle-remoto/` |
| ícone | `~/.local/share/icons/hicolor/scalable/apps/controle-remoto.svg` |
| atalho no menu | `~/.local/share/applications/controle-remoto.desktop` |
| início automático | `~/.config/autostart/controle-remoto.desktop` |
| log do agente | `~/.local/state/controle-remoto/agente.log` |

O instalador também **desativa o serviço systemd** antigo: o agente agora roda
dentro do próprio app.

Para valer no login, saia e entre na sessão (ou reinicie). Para usar agora,
abra **Controle Remoto** no menu de aplicativos.

#### Pareamento por QR code

![](docs/tela-config-qr.png)


A janela do app é dividida em duas colunas: à esquerda as informações
(IP, porta, token, status) e à direita o **QR code** (165 px) com
`CR1|ip|porta|token` — assim ela não fica comprida na vertical. Na tela de
configuração do app Android há o botão **Escanear QR code do Ubuntu**: lendo o
código, os três campos são preenchidos de uma vez, sem digitar nem copiar
token.

O formato é compacto de propósito: um payload menor gera um QR de 37 módulos
(versão 5), mais fácil de ler a distância do que um de 45 módulos.

Se a biblioteca `qrcode` não estiver disponível, a janela mostra os dados sem o
código e o pareamento continua manual. O instalador tenta instalar a
dependência em `~/.local` (sem tocar nos pacotes do sistema).

#### O que o app faz

- **Roda em segundo plano** e sobe junto com a sessão gráfica, sem abrir janela.
- **Ícone na barra superior** (StatusNotifierItem + DBusMenu, que é o protocolo
  que a extensão de indicadores do GNOME consome). O menu tem *Abrir*,
  *Copiar token* e *Sair*.
- **Janela** com IP, porta, token (cada um com botão de copiar), status do
  agente e número de aparelhos conectados.
- **Instância única**: abrir pelo menu de aplicativos com o app já rodando
  apenas traz a janela para frente — não cria um segundo processo nem uma
  segunda porta.
- **Fechar a janela não encerra**: volta para a bandeja. Para sair de vez, use
  *Sair* (no menu do ícone ou na janela).
- **Iniciar com o Ubuntu**: interruptor na janela liga/desliga o início
  automático, mexendo em `~/.config/autostart/controle-remoto.desktop`.

O `AyatanaAppIndicator3` não é usado porque é uma biblioteca **GTK3** e não pode
ser carregada num processo GTK4 (que o libadwaita exige). O item de bandeja é
registrado direto no barramento da sessão.

### 3. App no celular

```bash
cd android
gradle assembleDebug        # ou o gradle do Android Studio
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sem cabo: copie o APK para o celular e abra (permita "instalar de fontes
desconhecidas"). O APK é assinado com a chave de debug.

Na primeira abertura, preencha IP, porta e token. Fica salvo — depois é só abrir
o ícone e usar.

## Uso

**Barra de mídia** — fixa no topo, visível no touchpad e no teclado: ⏯
(play/pause) e volume (🔇 mudo, 🔉 abaixar, 🔊 aumentar). No modo joystick ela
sai de cena para dar a tela inteira ao jogo; os botões voltam ao trocar de aba.

Os ícones das teclas especiais são desenhados (não são caracteres Unicode): o
símbolo do Ubuntu e a seta de retorno do Enter usam traço grosso e ponta cheia,
porque glifos finos como `⏎` ficavam quase invisíveis no tamanho dos botões.

**Modo touchpad** — arraste para mover, toque para clique esquerdo, dois dedos
rolam, toque curto com dois dedos = clique direito.

![](docs/tela-touchpad.png)

**Arrastar item** (mover arquivo, redimensionar janela, selecionar texto):
**um dedo parado + outro dedo andando**. O dedo parado é a âncora: ele segura o
botão esquerdo enquanto estiver na tela, e o outro dedo move o ponteiro.

O truque está em quem solta o botão: **só a âncora**. Isso permite arrastar por
distâncias maiores que o touchpad — dá a primeira passada, levanta o dedo que
move, pousa de novo mais à frente e repete quantas vezes quiser; o item fica
grudado no cursor até você levantar o dedo parado.

O duplo toque **não** serve para arrastar aqui: o sistema conta o segundo clique
como "abrir" no instante do pressionar, antes de qualquer movimento (num
touchpad de notebook quem adia essa decisão é o libinput; nós mandamos eventos
de mouse crus). Duplo toque rápido segue sendo duplo clique, e tocar e segurar
parado vira um clique longo.

O botão da tecla Super usa o símbolo do Ubuntu (Círculo de Amigos), extraído do
logo do sistema.

Na parte inferior: barra de espaço em cima, Esc · Windows · Enter embaixo e, no
canto direito, um **direcional** que envia ↑ ↓ ← →. Segurar um braço repete o
comando (a partir de 350 ms, ~6 por segundo), útil para percorrer listas.

O direcional segue o padrão do DualSense (PS5): quatro botões separados por
emendas diagonais finas, sem aro em volta — a mesma diagonal que separa os
botões é a fronteira usada no toque, então o miolo não dispara nada.

**Modo teclado** — abre o teclado do Android; cada tecla vai para o Ubuntu na
hora (nada de digitar e apertar enviar).

![](docs/tela-teclado.png)
 Ctrl e Alt são "grudados": toque para
armar e a próxima tecla sai em combo. Abaixo do campo há duas fileiras com Esc,
Tab, Ctrl, Alt, Windows, apagar, setas, Enter e F11.

O eco do texto enviado **some sozinho** em três situações: depois de 10
segundos sem digitar, ao apertar Enter, ou ao usar a área como touchpad. Limpar
o eco não apaga nada no Ubuntu — o campo é reescrito pelo próprio app, então
nenhum backspace é enviado.

Se o teclado do Android for ocultado (o botão de esconder do próprio teclado),
aparece um botão **Abrir teclado** na área de texto para trazê-lo de volta.

A área de texto acima das teclas também funciona como **mini touchpad**: os
mesmos gestos da aba Touchpad (arrastar move, tocar clica, dois dedos rolam),
sem nenhuma mudança visual. Os gestos vêm do mesmo modificador
(`Modifier.gestosTouchpad`), então as duas áreas não podem divergir.

**Modo joystick** — o celular deitado vira um controle de PS5 para o PC
(veja [Gamepad](#gamepad-quando-o-jogo-precisa-de-um-controle)). A tela gira
sozinha ao entrar na aba e volta ao normal ao sair.

As três abas têm **ícone no lugar de texto**, cada uma num "chip" próprio com
6 dp entre eles — sem isso as três áreas se misturavam no mesmo fundo. O
touchpad (`trackpad_input`) e o controle (`sports_esports`) vêm do
[Material Symbols](https://fonts.google.com/icons) como vetor
(`res/drawable/ic_trackpad.xml`, `ic_esports.xml`); o teclado continua desenhado
no próprio app, porque neste tamanho o traço próprio ficou mais legível que o
glifo equivalente. O nome de cada aba fica no `contentDescription`.

O botão **PS** usa o **símbolo do Ubuntu** — o mesmo da tecla Windows/Super no
modo touchpad: quem aperta ali quer o menu do sistema, e o Círculo de Amigos diz
isso melhor que o monograma do PlayStation.

Só o **Joystick** fica em paisagem. **Touchpad** e **Teclado** pedem retrato —
deitados os dois ficavam espremidos.

![](docs/tela-joystick.png)

A disposição não é a do DualSense, e sim a que funciona no vidro, em duas
faixas no alto e um campo embaixo:

- **1ª faixa:** as abas, com o ponto de conexão;
- **2ª faixa:** `L2 · L1` à esquerda, `SELECT · PS · START` no meio e `R1 · R2`
  à direita — os ombros desceram para não disputar a linha das abas;
- **campo:** analógico esquerdo e **△ ○ ✕ □** embaixo, nos cantos, onde os
  polegares caem sozinhos; direcional e analógico direito mais acima e puxados
  para o centro, fora do caminho do polegar.

As abas saíram de baixo do campo de propósito: na borda inferior o polegar
encostava nelas e trocava de modo sem querer no meio do jogo. Tocar no miolo de um analógico sem arrastar dispara **L3/R3**, como
apertar o próprio analógico.

Todos os botões dão o mesmo retorno das teclas: som, vibração e destaque de
110 ms. Os analógicos não fazem som a cada movimento (seria um metralhadora) —
só no toque inicial.

### Gamepad: quando o jogo precisa de um controle

Um jogo não aceita "o mouse andando": ele procura um **controle**. Por isso o
agente cria um terceiro dispositivo uinput — além do teclado e do ponteiro — com
eixos (`EV_ABS`) e botões de gamepad. É o mesmo mecanismo do teclado virtual:
o kernel o apresenta como hardware, então funciona em jogo nativo, emulador
(RetroArch), Lutris/Wine e Steam, sem portal nem confirmação.

Duas decisões que valem registro:

- **faixa dos eixos.** Um uinput sem `absinfo` nasce com faixa 0..255, e aí o
  analógico vira um botão de três posições. Os analógicos são declarados em
  −32768..32767 e os gatilhos em 0..255. A armadilha é a ordem da tupla no
  python-evdev: `(código, valor, mínimo, máximo, fuzz, flat, resolução)` — com
  mínimo e máximo trocados o kernel responde apenas `EINVAL`, sem dizer o
  motivo;
- **gatilho manda as duas coisas.** L2/R2 apertam `BTN_TL2` e também levam
  `ABS_Z`/`ABS_RZ` a 255. Emulador costuma ler o botão, jogo de PC costuma ler
  o eixo; assim nenhum dos dois fica sem.

O mapa completo dos botões e eixos — nome no app, código evdev, índice no
SDL/Godot/Unity e as pegadinhas (uma delas: △ e □ chegam como X e Y) — está em
[`docs/GAMEPAD.md`](docs/GAMEPAD.md). É o arquivo para levar a uma conversa de
desenvolvimento de jogo.

E um cuidado que não aparece na tela: quando o app desconecta, o agente **solta
todos os botões e centra os eixos** (`pad_reset`). Sem isso, um botão apertado
na hora da queda fica travado dentro do jogo — e como a conexão não voltou,
ninguém aperta de novo. O app também manda `pad_reset` ao sair da aba.

### Dois celulares, dois controles

Cada celular conectado ganha o **seu** gamepad virtual: o primeiro é "Controle
Remoto Gamepad", o segundo "Controle Remoto Gamepad 2", até quatro. É o que
permite co-op local de verdade — dois analógicos independentes, não um
compartilhado.

O teclado e o ponteiro continuam **únicos e compartilhados**: para o PC são um
teclado só, então duas pessoas digitando ao mesmo tempo escrevem no mesmo lugar.

O que vale saber:

- ao lado das abas, no modo joystick, um **selo redondo** mostra qual controle
  é este celular (1, 2, 3...) — o número vem do agente na autenticação, e o aro
  do selo também indica o estado da conexão (verde conectado, azul conectando,
  vermelho erro);
- o controle é criado quando o celular **autentica**, e não no primeiro toque no
  joystick, porque o jogo enumera os controles ao abrir;
- quando o celular desconecta, solta **só o próprio** controle — antes, a queda
  de qualquer um soltava os botões do outro;
- quando o **último** celular desconecta, os controles extras são encerrados,
  para o próximo jogo não listar controles que ninguém está segurando;
- **abra o jogo depois** de conectar os celulares: controle que aparece com o
  jogo já rodando normalmente não é enumerado;
- acima de quatro celulares o quinto é recusado.

### Feedback ao toque

Cada tecla — inclusive os quatro braços do direcional, os botões do joystick e
as três abas — dá três retornos:

- **som** — clique sintetizado (`res/raw/clique.wav`): 42 ms, fundamental em
  190 Hz e centroide espectral em ~790 Hz. O `Effect_Tick` do sistema é curto
  mas agudo (milhares de Hz); este é mais curto e mais grave;
- **vibração** — 45 ms pelo serviço `Vibrator`, com `DEFAULT_AMPLITUDE`;
- **efeito visual** — clareia 26% em direção ao branco (a tecla encolhe para
  92%; no direcional, o braço pressionado é destacado) e permanece aceso por
  110 ms depois de soltar.

Quatro armadilhas resolvidas aqui, todas medidas no aparelho:

1. **Um `SoundPool` por botão.** Eram ~20 instâncias carregando o mesmo
   arquivo; a do direcional perdia a corrida de carregamento e caía no fallback
   mudo. Agora é instância única, criada na abertura do app.
2. **`AudioManager.playSoundEffect` fica mudo** quando a opção "sons de toque"
   está desligada (era o caso). Por isso o arquivo é tocado direto.
3. **O direcional não herdava nada.** Ele é um `Canvas` com tratamento de toque
   próprio, então não passava pelo `BaseBotao` — não tinha nem som nem destaque.
   Hoje ele desenha a pétala pressionada e chama o mesmo `retornoToque()`.
4. **O motor de vibração do A33 não modula amplitude**
   (`MOTOR_COIN_DC`, `intensityControl: false`). Passar amplitude 0,55 fazia o
   sistema converter em PWM e enfraquecer; o próprio clique do sistema usa
   `amplitude=-1` (potência total). Com 14 ms o motor não girava; 45 ms com
   `DEFAULT_AMPLITUDE` produz o mesmo padrão que o sistema envia
   (`Step=45ms(amplitude=-1,00)`).

Segurar um braço do direcional repete o comando: a primeira pressão faz som e
vibração, as repetições (a cada ~160 ms) vão em silêncio.

### Backspace sem limite

O teclado do Android só entrega um evento de apagar quando existe texto no
campo. Por isso o modo teclado mantém **um espaço de preenchimento** no início
do campo, invisível para você: ele é o que o backspace apaga quando o visor
esvazia, e o app repassa o comando de qualquer forma.

O preenchimento fica no início (e não no fim) de propósito: assim digitar
apenas acrescenta no fim e o app nunca reescreve o texto por baixo do IME — o
que dessincronizava o teclado do Samsung e gerava um backspace fantasma por
caractere. Também há um coalescimento de 40 ms para que a reposição do
preenchimento não conte como um segundo toque.

## Idioma

Os textos saem em **português ou inglês**, seguindo o idioma do sistema — não há
seletor no app.

- **Android**: `res/values/strings.xml` (inglês, padrão) e `res/values-pt/`
  (português). Quem estiver em pt-BR, pt-PT ou qualquer outro pt vê a tradução;
  o resto cai no inglês. O Android 13+ ainda permite trocar o idioma só deste
  app em Configurações → Apps → Ubuntu Controller → Idioma.
- **Ubuntu**: `app/i18n.py` decide por `LC_ALL`/`LANG`. Sem gettext nem `.mo`
  de propósito: são poucas frases e o app é instalado como arquivos soltos.
- **Servidor**: os logs e as mensagens de erro de protocolo seguem em português,
  porque o público é quem está depurando o agente, não quem usa o controle.

## IP fixo (recomendado)

O endereço vem por DHCP e pode mudar. Duas opções:

- **Reserva no roteador** (mais seguro): amarre o MAC da máquina a um IP.
- **Estático na máquina**:
  ```bash
  sudo nmcli con mod netplan-enp4s0 ipv4.method manual \
      ipv4.addresses 192.168.10.16/24 ipv4.gateway 192.168.10.1 \
      ipv4.dns "8.8.8.8 8.8.4.4"
  sudo nmcli con up netplan-enp4s0
  ```

Se mudar mesmo assim, o app tem o ícone de engrenagem no topo para
reescrever o IP.

## Acentuação

O teclado manda **caracteres**, não keycodes. O servidor converte usando
`mapa_br.py`, gerado do layout real da máquina: `á` vira `´` + `a`, `ã` vira
`~` + `a`, `ç` vira a tecla própria do ABNT2. Caractere sem tecla no layout
(emoji, por exemplo) é ignorado com aviso no log.

### Por que existe uma pausa de 15 ms entre a tecla morta e a letra

O IBus (input method do GNOME) compõe teclas mortas de forma **assíncrona**. Se
a letra base chegar no mesmo instante que o acento, o resultado sai
embaralhado — `ação` vira `ãçao`.

Medido nesta máquina: com 0 ms falha sempre; com 5 ms já sai correto. O agente
usa 15 ms (`PAUSA_MORTA` em `injecao.py`) para ter margem, e a pausa só existe
em caracteres acentuados, então digitar texto normal não fica mais lento.

Sem essa pausa, um teste real capturou:

```
esperado: ação coração ãõç ÀÉÎ
obtido:   ããõÀÉÎaço ç
```

Se o layout mudar:

```bash
cd servidor && python3 gerar_mapa.py            # detecta o layout do GNOME
python3 gerar_mapa.py --layout us              # ou força um layout
```

## Testes

```bash
cd servidor
python3 agente.py --simular --porta 8799 &     # não injeta, só registra
python3 teste_protocolo.py --porta 8799
```

Para conferir o gamepad de verdade, sem jogo e sem depender de rede:

```bash
cd servidor
python3 teste_gamepad.py    # lê de volta do /dev/input (só precisa de uinput)
python3 teste_sdl.py        # o que o SDL vê: nome, eixos, botões (precisa de pygame)
```

## Protocolo

TCP, uma linha JSON por mensagem. Primeira linha:

```json
{"t": "auth", "token": "<token>"}
```

Resposta `{"t":"ola",...}` ou `{"t":"erro",...}`. Depois:

| comando | exemplo |
|---|---|
| movimento relativo | `{"t":"mover","dx":12,"dy":-4}` |
| rolagem | `{"t":"rolar","dy":3}` |
| clique / botão | `{"t":"clique","b":"left"}` · `{"t":"botao","b":"right","down":true}` |
| tecla | `{"t":"tecla","k":"ESC"}` · `{"t":"tecla","k":"ALT","acao":"down"}` |
| combo | `{"t":"combo","keys":["ALT","TAB"]}` |
| texto | `{"t":"texto","s":"ação"}` |
| apagar | `{"t":"backspace","n":2}` |
| botão do gamepad | `{"t":"pad","b":"cross","down":true}` |
| eixos | `{"t":"eixo","e":"l","x":0,"y":-32767}` |
| soltar tudo | `{"t":"pad_reset"}` |
| ping | `{"t":"ping"}` → `{"t":"pong"}` |

Botões do gamepad: `cross`, `circle`, `triangle`, `square`, `l1`, `r1`, `l2`,
`r2`, `select`, `start`, `ps`, `l3`, `r3`. Grupos de eixo: `l` e `r`
(−32768..32767) e `h` (direcional, −1..1).

Limites no servidor: 400 px por mensagem de movimento, 512 caracteres por texto,
e nomes de tecla/botão validados contra a tabela.

## Segurança

O agente escuta em `0.0.0.0:8765` e exige token de 32 hex (arquivo `0600`).
Quem tiver o token na mesma rede controla a máquina como se fosse o teclado
físico — **não exponha essa porta na internet**. Para trocar o token, apague
`~/.config/controle-remoto/token` e reinicie o serviço; o app pedirá o novo.

## Problemas comuns

- **"Nao consegui conectar"** — IP errado, agente parado
  (`systemctl --user status controle-remoto`), ou o roteador tem isolamento de
  clientes (AP isolation) bloqueando celular ↔ computador.
- **Conecta mas nada acontece** — falta a regra udev / logout.
  Veja `journalctl --user -u controle-remoto -f`.
- **Cursor lento ou rápido demais** — ajuste a sensibilidade na engrenagem do topo.
- **Acentos errados** — regenerar o mapa (layout diferente do que gerou a
  tabela).
- **Tecla de mídia não responde** — o GNOME usa o player ativo (MPRIS); se nada
  estiver tocando, play/pause não tem efeito.
- **O jogo não lista o controle** — confira se o gamepad virtual apareceu:
  `grep -A3 "Controle Remoto Gamepad" /proc/bus/input/devices` ou
  `python3 teste_sdl.py`. Se o jogo abriu antes do app conectar, ele não
  reenumera sozinho: feche e abra o jogo depois de entrar na aba Joystick.
- **Botão travado dentro do jogo** — saia da aba (o app manda `pad_reset`) ou
  reinicie o agente; ele solta tudo ao desconectar.
