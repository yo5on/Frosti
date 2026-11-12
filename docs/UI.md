# FROSTI interface: design system

FROSTI has two faces: the **phone Shell** (Galaxy J7 Pro, Android 9, a 720×1280 AMOLED panel) and the **laptop console**
(a page the Core serves at `http://127.0.0.1:8765/`). They share one identity, one set of screens and one
protocol, so the user moves between them without relearning anything.

## What we borrowed from Clawd_Handy (as ideas, not assets)
Clawd_Handy (vikas-meu) feels like a companion rather than an app. These are the patterns behind that, and how FROSTI uses each one:

| Clawd_Handy pattern | FROSTI's take |
|---|---|
| A character whose face reacts (eyes, moods) | **The Core**: an energy orb with two light "eyes" that blink, glance around, widen when listening, narrow when thinking, smile when speaking and close when asleep |
| A boot sequence before the first screen | Rings sweep in, the nucleus ignites, the eyes open, and a three-line boot log reports core, link and voice |
| One function per screen, behind a persistent header | Chat · Music · Processes · Performance · Setup behind an icon rail (link and battery at its foot) |
| An idle clock with the character asleep | An ambient clock that dims the screen, with the Core breathing and its eyes closed; the text stays still (Claude charcoal background; dimming is off by default because this AMOLED tints dark colours blue when dimmed) |
| Hint lines that name what each control does | A one-line caption under the Core that always says what a tap or hold will do |

FROSTI's mascot is an original pixel penguin, not Clawd.

## Tokens (Claude-style)
FROSTI uses the look of the Claude app and the Clawd_Handy screens, at the owner's request: clay accent, warm cream text, serif display type, a spark logo and a pixel creature.

| Token | Value | Use |
|---|---|---|
| `bg` | `#262624` | Claude's dark background |
| `rail` | `#1F1E1D` | Console sidebar |
| `surface` | `#30302E` | Raised: input field, dialogs, your chat bubbles, pressed states |
| `line` | `#3D3D3A` | Hairlines and meter tracks |
| `accent` | `#D97757` | Claude clay: active states, values, send button |
| `amber` | `#E8B931` | Alerts only: errors, confirmations, values over 85 % |
| `text` / `muted` / `dim` | `#FAF9F5` / `#9C9A92` / `#73726C` | Claude ivory and warm greys |
| `sleep` | `#262624` | The idle clock: same Claude charcoal as every screen, at normal brightness |

Type:
- **Serif** (Noto Serif on Android, Georgia on the laptop): titles, greeting and FROSTI's replies.
- **Thin sans:** clocks.
- **Mono, uppercase, tracked:** labels and navigation.

The phone app runs landscape by default (either way up); **Setup › Appearance › Orientation** switches it to portrait.

**Layout.** Both faces keep the Claude palette and type, arranged as follows:
- **Icon rail** on the left: no logo, just one icon over a small label for each of the five screens, the same on both faces: **Chat · Music · Processes · Performance · Setup**. Link status sits at the foot. The current screen sits on a raised pill with a clay icon. In portrait the phone has a slim header instead (wordmark, link and battery) and the same items as bottom tabs; the laptop does the same below 860 px.
- **Chat is the home screen.** With no messages it is the "new chat" page: the character, a serif greeting and starter chips. On the laptop the starters include your last three asks, which fill the box rather than send. Once you talk, the conversation takes over and the character moves into a header above it. Chat is one centred column on wide screens, with the input bar lifted off the bottom edge. **New chat** (first item in the **+** menu) clears the conversation and sends `{"type":"new"}`, which drops the Core's follow-up context.
- **Input bar** (like Claude's): **+** on the left opens a short quick-actions menu (phone: apps, calculator, timers, open on laptop; laptop: open on laptop or phone, timers, mute, PowerShell), then the text field. On the right the phone has a mic you hold to talk, which turns into send once you type; the laptop has send.
- **Music:** large album art beside one column (on the laptop, a card tinted by the blurred cover): title, artist, a progress bar and controls (shuffle, previous, play/pause, next, search Spotify) for whatever the laptop plays.
- **Processes:** the laptop's busiest programs (up to 12, same-name processes summed), each with a bar for its memory share; tap one to close it, after FROSTI confirms.
- **Performance:** tiles (one big number, a detail line, a meter; amber only where high is bad, so never battery), the laptop only: processor, memory, storage (system drive), battery, network and, on the console, Core uptime.
- **Sleep screen:** while the laptop plays something, a card with the album art, title and artist shows under the date; touching it opens Music.
- **Screen on:** the phone keeps its display on only while linked to the laptop; offline, its normal screen timeout applies.

## The character: states
It is a 16 × 8 pixel penguin: one chunky near-black block (`#141413`, so it reads against `#262624`) with flipper nubs, a small cream belly (`#F3F1EA`), tall light eyes, and an orange beak and feet (`#E8853D`). Its colours never change; the state colour below tints only the sound bars and spark; there is no glow behind it. The spark (12 uneven rays) is the logo.

| State | Tint and motion | Eyes / extras |
|---|---|---|
| idle | orange, slow bob | 1 × 2 eyes that blink and glance left and right |
| listening | orange, lifts with the mic level, feet tap | tall eyes; three sound bars on each side |
| thinking | orange | like Clawd's laptop emote: tosses a laptop onto the floor, turns side-on and sits, opens it and types with both flippers in turn (far one shaded) while the laptop bounces; closes it and flings it away when done. Stays at the laptop however long it thinks |
| speaking | orange, bounces with the speech | `^ ^` eyes; sound bars |
| offline | grey, still | `- -` eyes |
| error | amber, shudders | `x x` eyes |
| sleep (idle screen) | dark orange, slow breathing | `- -` eyes; floating z's |

On boot, the spark spins up, then the body builds up row by row and the eyes open.

### Only Clawd's moves
Apart from the core states above (idle, listening, speaking, offline, sleep, asking permission), Frosti only does what Clawd does in the Claude app, in its own penguin pixels: the motion was studied from Clawd's emotes, nothing was copied (`[C]` in `author.py`). It can face left as well as right (frame `side=-1`, the side-on shape mirrored).

| Where it plays | Animations |
|---|---|
| Thinking, `open_vscode`, `remember` | typing on a laptop |
| git, `run_project`, `run_powershell`, `power` (and any unmapped tool) | typing at a desktop computer |
| `open_app`, volume, brightness, `kill_process`, `set_timer` | pointing |
| `get_status`, `recall`, `describe_screen` | looking around |
| `forget` · `play_music` · `media_control` | turning around · hula · dancing |
| Idle pastimes (random) | lightbulb idea, dancing, looking around, running about, crab-walking, hula, waving, swaying, pointing, turning around, meditating, break dancing |
| Reactions | tool done: hop or excited · long task: confetti, jumping for joy or break dancing · plain reply and alarms: waving · tool failed: facepalm or disappointed · poke and wake word: excited · link lost or bad token, low battery: disappointed · CPU hot: meditating · start-up: walking in |
| Error (bad token) | dizzy |

A reaction listed with "or" picks one at random (`map.variety`).

Budget:
- only plain rectangles, with nothing allocated per frame
- 60 fps only while active; 25 fps idle, 15 fps asleep
- it stops when hidden

## Motion
- **Screen change:** fade plus a 12 dp rise, 180 ms, decelerating.
- **Pressed:** scale to 0.96.
- **Boot:** about 2.4 s; a tap skips it.
- **Idle:** shows the clock after the timeout (45 s by default, set in Setup). Any touch, the wake word or a confirmation request wakes it.
- **Reduce motion (Setup):** caps the Core at 20 fps, removes the rotation and skips the boot animation.

## Protocol additions (backwards compatible)
- **Stats:** phone → core `{"type":"stats","phone":{…}}` returns `{"type":"stats",…}`: laptop CPU, RAM, battery, network counters, voice and the phone link.
- **Memory:** `{"type":"memory"}` returns `{"type":"memory",facts,recent,history,projects,timers}`.
- **Activity:** core → clients `{"type":"activity","name","args"}` as a tool starts, and `reply` carries `tool`, so the character can act the tool out and react to how it went (`core/frosti_core/anim/`, played by `frosti.js` on the laptop and `CoreView.kt` on the phone).
- **Forgetting a fact:** `{"type":"forget","id"}` deletes one fact (and audits it), then returns `memory`.
- **The laptop console:** opens with `{"type":"hello","token","client":"desk"}`. It sees the phone's conversation mirrored, and its phone tools route to the phone.
