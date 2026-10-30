# Gamepad virtual — mapa dos botões

Referência para quem vai programar um jogo que usa o controle do **Ubuntu
Controller**. A ideia é que dá para falar "o botão ✕ faz pular, o △ abre o mapa"
e a outra parte saber exatamente de qual botão se trata.

Tudo aqui foi medido no aparelho e no SDL 2.32; os códigos são os do kernel
Linux (`/dev/input`, via `uinput`).

---

## 1. O dispositivo

| | |
|---|---|
| Nome | `Controle Remoto Gamepad` … `Controle Remoto Gamepad 4` |
| Quantos | **um por celular conectado** (até 4). O primeiro a conectar fica com o nome sem número |
| Tipo | gamepad genérico; o SDL reconhece como *game controller* |
| GUID SDL | `0300dd3e010000000100000001000000` |
| Fabricante/modelo | `bustype=USB`, vendor `0x0001`, product `0x0001` |

Dois celulares = dois dispositivos separados, cada um com o seu jogo de botões
e eixos. Para saber qual é o seu no código, compare o nome (`… 2` é o segundo).

---

## 2. Botões

A coluna **app** é o nome usado no app e no protocolo; **evdev** é o código do
kernel; **SDL/Godot/Unity** é o índice que a engine entrega (é por ele que o
jogo vai reagir).

| app | símbolo no app | evdev | índice bruto (SDL `b`) | SDL / Godot / Unity | nome Xbox | posição física |
|---|---|---|---|---|---|---|
| `cross` | ✕ | `BTN_SOUTH` (304) | b0 | **A** / `JOY_BUTTON_A` / `buttonSouth` | A | baixo |
| `circle` | ○ | `BTN_EAST` (305) | b1 | **B** / `JOY_BUTTON_B` / `buttonEast` | B | direita |
| `triangle` | △ | `BTN_NORTH` (307) | b2 | **X** / `JOY_BUTTON_X` / `buttonWest` | X | cima |
| `square` | □ | `BTN_WEST` (308) | b3 | **Y** / `JOY_BUTTON_Y` / `buttonNorth` | Y | esquerda |
| `l1` | L1 | `BTN_TL` (310) | b4 | `JOY_BUTTON_LEFT_SHOULDER` / `leftShoulder` | LB | ombro esq. |
| `r1` | R1 | `BTN_TR` (311) | b5 | `JOY_BUTTON_RIGHT_SHOULDER` / `rightShoulder` | RB | ombro dir. |
| `l2` | L2 | `BTN_TL2` (312) | b6 | — (vira **eixo**, ver §3) | LT | gatilho esq. |
| `r2` | R2 | `BTN_TR2` (313) | b7 | — (vira **eixo**, ver §3) | RT | gatilho dir. |
| `select` | SELECT | `BTN_SELECT` (314) | b8 | `JOY_BUTTON_BACK` / `selectButton` | Back | centro esq. |
| `start` | START | `BTN_START` (315) | b9 | `JOY_BUTTON_START` / `startButton` | Start | centro dir. |
| `ps` | símbolo do Ubuntu | `BTN_MODE` (316) | b10 | `JOY_BUTTON_GUIDE` / `buttonGuide` | Guide | centro |
| `l3` | — | `BTN_THUMBL` (317) | b11 | `JOY_BUTTON_LEFT_STICK` / `leftStickButton` | LS | analógico esq. |
| `r3` | — | `BTN_THUMBR` (318) | b12 | `JOY_BUTTON_RIGHT_STICK` / `rightStickButton` | RS | analógico dir. |

Para quem programa em Godot ou lê `button_index` cru, os números que a engine
usa são o enum do SDL: **A=0, B=1, X=2, Y=3, Back=4, Guide=5, Start=6,
LS=7, RS=8, LB=9, RB=10, D-pad cima=11, baixo=12, esquerda=13, direita=14**.

> ⚠️ **△ e □ chegam como X e Y no SDL.** O mapeamento genérico do SDL supõe que
> a ordem dos botões é A, B, X, Y; o kernel entrega A, B, **Y**, X. Então:
>
> - **△ (cima) → `X` / `buttonWest`**
> - **□ (esquerda) → `Y` / `buttonNorth`**
>
> O nome "West/North" do Unity fica, portanto, trocado em relação à posição. Se
> isso incomodar, dá para corrigir no jogo — veja §7.

---

## 3. Eixos

| o que é | protocolo | evdev | faixa | SDL controller (índice) | SDL joystick (índice) |
|---|---|---|---|---|---|
| analógico esquerdo X | `{"t":"eixo","e":"l","x":…}` | `ABS_X` | −32768 … 32767 | `leftx` — **0** | **0** |
| analógico esquerdo Y | idem | `ABS_Y` | −32768 … 32767 | `lefty` — **1** | **1** |
| gatilho esquerdo | `{"t":"pad","b":"l2"}` | `ABS_Z` | 0 … 255 | `lefttrigger` — **4** | **2** |
| analógico direito X | `{"t":"eixo","e":"r","x":…}` | `ABS_RX` | −32768 … 32767 | `rightx` — **2** | **3** |
| analógico direito Y | idem | `ABS_RY` | −32768 … 32767 | `righty` — **3** | **4** |
| gatilho direito | `{"t":"pad","b":"r2"}` | `ABS_RZ` | 0 … 255 | `righttrigger` — **5** | **5** |
| D-pad X | `{"t":"eixo","e":"h","x":…}` | `ABS_HAT0X` | −1 … 1 | vira botão (11–14) | hat 0 |
| D-pad Y | idem | `ABS_HAT0Y` | −1 … 1 | vira botão (11–14) | hat 0 |

**A ordem dos eixos muda conforme a API.** A API *controller* (Godot, Unity,
`SDL_GameControllerGetAxis`) usa a ordem da tabela acima; a API *joystick* crua
(`SDL_JoystickGetAxis`, `pygame.joystick`) entrega na ordem dos códigos evdev
(X, Y, Z, RX, RY, RZ). Se o seu jogo lê joystick cru, o gatilho esquerdo é o
eixo **2**, não o 4.

**Sinal dos analógicos**: Y negativo é **para cima** (igual ao `uinput`), e o app
manda `-32767` para cima e `+32767` para baixo.

**Gatilhos são digitais**: o app manda 0 (solto) ou 255 (apertado). O valor chega
ao jogo como **0 … 32767**, sem meio curso — não conte com gatilho analógico.

---

## 4. D-pad

O D-pad é um **hat** (`ABS_HAT0X`/`ABS_HAT0Y`), não quatro botões. O SDL o
apresenta como botões, então quem usa a API controller vê:

| direção | SDL / Godot / Unity |
|---|---|
| cima | `JOY_BUTTON_DPAD_UP` — índice **11** / `dpadUp` |
| baixo | `JOY_BUTTON_DPAD_DOWN` — **12** / `dpadDown` |
| esquerda | `JOY_BUTTON_DPAD_LEFT` — **13** / `dpadLeft` |
| direita | `JOY_BUTTON_DPAD_RIGHT` — **14** / `dpadRight` |

Dois botões do D-pad ao mesmo tempo (diagonal) funcionam: o hat manda X e Y
juntos, e o SDL acende dois botões.

---

## 5. Como ler em cada stack

**Godot 4** — usa o SDL por baixo, então os nomes são os da tabela:

```gdscript
func _input(evento):
    if evento is InputEventJoypadButton and evento.pressed:
        match evento.button_index:
            JOY_BUTTON_A: pular()          # ✕
            JOY_BUTTON_B: cancelar()       # ○
            JOY_BUTTON_X: abrir_mapa()     # △  (atenção: △ é X)
            JOY_BUTTON_Y: atacar()         # □
            JOY_BUTTON_LEFT_SHOULDER: ...
            JOY_BUTTON_START: pausar()

    if evento is InputEventJoypadMotion:
        # JOY_AXIS_LEFT_X (0), LEFT_Y (1), RIGHT_X (2), RIGHT_Y (3),
        # TRIGGER_LEFT (4), TRIGGER_RIGHT (5)
        pass
```

**Unity (Input System)** — `Gamepad.current`:

```csharp
using UnityEngine.InputSystem;

void Update() {
    var p = Gamepad.current;
    if (p == null) return;
    if (p.buttonSouth.wasPressedThisFrame) Pular();      // ✕
    if (p.buttonEast.wasPressedThisFrame)  Cancelar();   // ○
    if (p.buttonWest.wasPressedThisFrame)  AbrirMapa();  // △
    if (p.buttonNorth.wasPressedThisFrame) Atacar();     // □
    float x = p.leftStick.ReadValue().x;                 // −1 … 1
    float lt = p.leftTrigger.ReadValue();                // 0 … 1
}
```

**SDL2 / C**:

```c
SDL_CONTROLLER_BUTTON_A, _B, _X, _Y, _LEFTSHOULDER, _RIGHTSHOULDER,
_BACK, _START, _GUIDE, _LEFTSTICK, _RIGHTSTICK,
_DPAD_UP, _DPAD_DOWN, _DPAD_LEFT, _DPAD_RIGHT
SDL_CONTROLLER_AXIS_LEFTX, _LEFTY, _RIGHTX, _RIGHTY, _TRIGGERLEFT, _TRIGGERRIGHT
```

**evdev (Python)** — se o jogo lê `/dev/input` direto:

```python
import evdev
dev = next(d for d in map(evdev.InputDevice, evdev.list_devices())
           if d.name.startswith("Controle Remoto Gamepad"))
for ev in dev.read_loop():
    if ev.type == evdev.ecodes.EV_KEY and ev.code == evdev.ecodes.BTN_SOUTH:
        print("✕", "apertado" if ev.value else "solto")
```

---

## 6. Pegadinhas

1. **△/□ trocados** (§2) — o único ponto que costuma confundir.
2. **Gatilho é digital** — 0 ou 255, chega como 0 … 32767.
3. **Zona morta já aplicada** — o app descarta 14% do curso do analógico e
   reescala o resto. Os valores chegam "limpos": perto do centro é 0 de fato.
4. **`ps` (GUIDE) pode ser capturado** pelo Steam (overlay) ou pelo GNOME antes
   de chegar ao jogo. Não conte com ele para gameplay.
5. **`select`/`start`** = `Back`/`Start`. Em alguns engines `Back` abre menu do
   sistema.
6. **L3/R3** existem e são o clique do analógico, mas no app não têm botão na
   tela: disparam ao tocar o miolo do analógico sem arrastar.
7. **Dois celulares = dois dispositivos.** Se o jogo não trata multi-controle,
   ele pode pegar o primeiro que encontrar; numere pelo nome do dispositivo.
8. **O controle é criado quando o celular conecta** — abra o jogo depois disso,
   senão ele não enumera.
9. **Nada de force feedback.** O dispositivo não expõe `EV_FF`; `rumble()` vai
   falhar ou não fazer nada.

---

## 7. Se quiser o △/□ na posição certa

O mapeamento genérico do SDL troca X e Y (ver §2). Para corrigir sem mexer no
app, rode o jogo com esta variável de ambiente:

```bash
export SDL_GAMECONTROLLERCONFIG="0300dd3e010000000100000001000000,Controle Remoto Gamepad,\
a:b0,b:b1,x:b3,y:b2,back:b8,guide:b10,start:b9,leftstick:b11,rightstick:b12,\
leftshoulder:b4,rightshoulder:b5,dpup:h0.1,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,\
leftx:a0,lefty:a1,rightx:a3,righty:a4,lefttrigger:a2,righttrigger:a5,"
```

Com isso, △ passa a ser `Y` (cima) e □ passa a ser `X` (esquerda) — o de sempre
em qualquer controle.

---

## 8. Resumo para levar para a conversa do jogo

Ao descrever uma ação, use estes nomes — todos chegam à engine como **A, B, X,
Y, LB, RB, Back, Start, Guide, LS, RS, D-pad**:

| você diz | a engine recebe | posição na tela |
|---|---|---|
| ✕ / cruz (`cross`) | **A** (south) | baixo |
| ○ / bola (`circle`) | **B** (east) | direita |
| △ / triângulo (`triangle`) | **X** (west) | cima |
| □ / quadrado (`square`) | **Y** (north) | esquerda |
| L1 / R1 | **LB / RB** | ombros |
| L2 / R2 | **LT / RT** (gatilho, 0 ou 1) | gatilhos |
| SELECT / START | **Back / Start** | centro |
| PS (logo Ubuntu) | **Guide** | centro |
| D-pad | **D-pad** (4 botões) | esquerda |
| analógico esq./dir. | **Left/Right stick** (−1 … 1) | rodapé |
| L3 / R3 | **LS / RS** (clique do analógico) | rodapé |

Protocolo usado pelo app, se precisar falar com o agente direto:

```json
{"t":"pad","b":"cross","down":true}
{"t":"eixo","e":"l","x":0,"y":-32767}
{"t":"pad_reset"}
```

`b` aceita os nomes da §2; `e` aceita `l` (esquerdo), `r` (direito) e `h`
(D-pad). `pad_reset` solta tudo e centra os eixos.
