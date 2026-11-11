"""Authoring source for FROSTI's animations. Run it to regenerate frosti_anims.json, which the phone (CoreView)
and the laptop (frosti.js) both play. Edit here, never the JSON.

Model (borrowed from MyClawd's set rules): every animation is enter -> loop (repeated) -> exit, and every one starts
and ends on REST, so any animation can follow any other without a jump. A loop starts and ends on the animation's own
`pose` (its seam: props out, eyes set). Frames last `n` ticks of 100 ms and are deltas over that pose.

Coordinates are sprite cells. (0, 0) is the top-left of penguin E (16 x 8: flippers at x 0-1 and 14-15, feet on
row 7). The canvas is 32 x 14 cells with the penguin at (8, 5), so props can sit at x -8..23 and y -5..8.
Held props sit beside the right flipper (x 16+), since a penguin flipper can't reach its own belly.
"""
import json
from pathlib import Path

TICK = 100
CANVAS = [32, 14]
ORIGIN = [8, 5]
PALETTE = {"K": "#141413", "W": "#f3f1ea", "O": "#e8853d", "L": "#c8c2b8", "G": "#8c867d", "D": "#4a4640",
           "I": "#8fd0ff", "B": "#4f86d9", "Y": "#e8b931", "R": "#e0604f", "N": "#7cc46c"}
# penguin E, painted in order: body, flippers (moved by fl), belly, beak (drawn by the rig), feet (moved by ft)
BODY = {"body": [2, 0, 12, 7], "flippers": [[0, 3, 2, 2], [14, 3, 2, 2]], "belly": [5, 5, 6, 2],
        "beak": [7, 4, 2, 1], "feet": [[4, 7, 2, 1], [10, 7, 2, 1]], "eyes": [4, 11]}
# side-on (facing right), used while frame side=1: one eye, beak, and both flippers reaching right; fl[0] is the
# far flipper (shaded "fc" D, lower, reaching further) and fl[1] the near one, like Clawd's lit and shaded arms
BODY["side"] = {"body": [3, 0, 9, 7], "flippers": [[13, 5, 3, 1], [12, 4, 2, 1]], "fc": ["D", "K"], "belly": [10, 4, 2, 3],
                "beak": [12, 3, 2, 1], "feet": [[6, 7, 2, 1], [9, 7, 2, 1]], "eyes": [8]}
REST = dict(side=0, dx=0, dy=0, crouch=0, lx=0, ly=0, eyes="open", fl=[0, 0], ft=[0, 0], beak=0, props=[], fx=[])
EYES = ["open", "blink", "half", "tall", "up", "down", "happy", "closed", "x", "wide", "wink", "confused"]

def bubble(dots, w=6):
    mid = "W" + "".join("D" if i % 2 == 1 and i // 2 < dots else "W" for i in range(w - 2)) + "W"
    return ["." + "W" * (w - 2) + ".", mid, "." + "W" * (w - 2) + "."]

PROPS = {
    # thinking
    "dot": ["W"], "bub0": bubble(0), "bub1": bubble(1), "bub2": bubble(2), "bub3": bubble(3),
    # Clawd-style: a little laptop on the floor, lid leaning back like "/", plain line art
    "lap_open": [".....LL", "....LL.", "...LL..", "..LL...", "LLLL..."], "lap_half": ["...LL", "..LL.", "LLLL."],
    "lap_shut": ["LLLL"],
    # desktop computer (side-on): monitor on a stand, keyboard and desk
    "monitor_side": ["GGGGGG", "GIBIIG", "GIIBIG", "GBIIIG", "GGGGGG", "..GG..", "..GG.."],
    "monitor_side2": ["GGGGGG", "GIIBIG", "GBIIIG", "GIBIBG", "GGGGGG", "..GG..", "..GG.."],
    "keys": ["LLL"], "desk_top": ["DDDDDDDDD", ".D.....D."],
    # dances and moods
    "skirt": ["NYNNYNNYNNYN", "N.N.N..N.N.N"], "skirt2": ["YNNYNNYNNYNN", ".N.N.NN.N.N."],
    "flower": ["R.R", ".Y.", "R.R"], "arm_out": ["KK"], "palm": ["KKKK", "KKKK"],
    # offline
    "plug": ["LLG.GL"],
    # permission
    "sign": ["LLLLL", "LDDWL", "LYYYL", "LYKYL", "LLLLL", "..G..", "..G.."],
    "sign_open": ["LLLLL", "LWDDL", "LYYYL", "LYKYL", "LLLLL", "..G..", "..G.."],
    "sign_no": ["LLLLL", "LRWRL", "LWRWL", "LRWRL", "LLLLL", "..G..", "..G.."],
    "key": ["YY..", "YYYY", "Y..."],
    "bell_l": ["Y..", "YYY", ".YY", ".D."], "bell_r": ["..Y", "YYY", "YY.", ".D."],
    # reactions
    "tick": ["...N", "N.N.", ".N.."], "bang": ["Y", "Y", ".", "Y"],
    "bulb": [".Y.", "YYY", "YYY", ".G."], "bulb_off": [".G.", "GGG", "GGG", ".G."],
    # z is 4x4: at 3x3 its diagonal is one centre pixel and reads as "I"
    "z": ["LLLL", "..L.", ".L..", "LLLL"], "star": [".Y.", "YYY", ".Y."],
    "steam": ["L.", ".L", "L."], "note": ["YY", "Y."],
}

def F(n=1, **kw):
    for k in ("fl", "ft"):
        if k in kw:
            kw[k] = list(kw[k])
    if "props" in kw:
        kw["props"] = [list(p) for p in kw["props"]]
    return dict(n=n, **kw)

def rest(n=1):
    return F(n, **REST)

ANIMS, ORDER = {}, []

def anim(id, label, group, prio, *, pose=None, enter=(), loop=(), exit=(), repeat=None, glow="O", src="", trigger="",
         mapped=True, offstage=False, **variants):
    """repeat=None: a base state that loops until something else wins; an int: a one-shot that loops that often."""
    pose = {**REST, **(pose or {})}
    parts = {"enter": list(enter), "loop": list(loop), "exit": list(exit), **{k: list(v) for k, v in variants.items()}}
    ANIMS[id] = dict(label=label, group=group, prio=prio, pose=pose, parts=parts, repeat=repeat, glow=glow, src=src,
                     trigger=trigger, mapped=mapped, offstage=offstage)
    ORDER.append(id)

def seal(a):
    """Pin every seam: enter REST -> pose, loop pose -> pose, every exit pose -> REST (adds a 1-tick hold only if needed)."""
    pose, p = a["pose"], a["parts"]
    full = lambda f: {**pose, **f}
    same = lambda f, g: {k: v for k, v in f.items() if k != "n"} == {k: v for k, v in g.items() if k != "n"}
    restf = {**REST}
    if pose != REST or p["enter"]:
        if not a["offstage"] and (not p["enter"] or not same(full(p["enter"][0]), restf)):
            p["enter"].insert(0, rest(1))
        if not same(full(p["enter"][-1]), pose):
            p["enter"].append(F(1))
    if not p["loop"]:
        p["loop"] = [F(4)]
    if not same(full(p["loop"][0]), pose):
        p["loop"].insert(0, F(1))
    if not same(full(p["loop"][-1]), pose):
        p["loop"].append(F(1))
    for k in p:
        if k == "exit" or k.startswith("exit_"):
            if pose != REST and (not p[k] or not same(full(p[k][0]), pose)):
                p[k].insert(0, F(1))
            if pose != REST or p[k]:
                if not p[k] or not same(full(p[k][-1]), restf):
                    p[k].append(rest(1))

H = "h"   # prop flag: moves with the body (held)
B = "b"   # prop flag: drawn behind the body
def P(name, x, y, *flags):
    return [name, x, y, *flags]

def walk(x0, x1, step=1.0, n=1):
    """Waddle from dx=x0 to dx=x1, lifting feet in turn."""
    out, x, i = [], x0, 0
    d = step if x1 > x0 else -step
    while (x1 - x) * d > 0:
        x = x + d if abs(x1 - x) > abs(d) else x1
        out.append(F(n, dx=x, ft=(-0.5, 0) if i % 2 == 0 else (0, -0.5), dy=-0.25 if i % 2 == 0 else 0))
        i += 1
    return out

# ---------------------------------------------------------------- base states
anim("idle", "Idle", "state", 10, src="[B][T][M] blinks and glances around while waiting", trigger="state idle",
     loop=[F(18), F(1, eyes="blink"), F(14), F(5, lx=-1), F(10), F(1, eyes="blink"), F(12), F(3, crouch=0.25),
           F(3), F(5, lx=1), F(8), F(1, eyes="blink"), F(16), F(4, ly=-0.5), F(6)])

anim("listening", "Listening", "state", 80, src="FROSTI only (voice)", trigger="state listening",
     pose=dict(eyes="tall", fx=[["bars"]]),
     enter=[F(1, eyes="wide", fx=[]), F(2, dy=-0.5, eyes="wide", fx=[])],
     loop=[F(2), F(2, ft=(-0.5, 0)), F(2), F(2, ft=(0, -0.5)), F(2), F(1, eyes="blink"), F(2, ft=(-0.5, 0)), F(2),
           F(2, ft=(0, -0.5)), F(2)],
     exit=[F(1, eyes="open", fx=[])])

anim("speaking", "Speaking", "state", 70, src="FROSTI only (voice)", trigger="state speaking",
     pose=dict(eyes="happy", beak=-1, fx=[["bars"]]),
     enter=[F(1, eyes="open", beak=0, fx=[])],
     loop=[F(3), F(3, dy=-0.25), F(3), F(3, dy=-0.25), F(3), F(3, dy=-0.25, fl=(-0.5, -0.5)), F(3), F(3, dy=-0.25)],
     exit=[F(1, eyes="open", beak=0, fx=[])])

d1, d2 = P("dot", 14, -1), P("dot", 15, -2)
anim("thinking", "Thinking", "state", 40, mapped=False, src="[B] thought dots, [T] Thinking on prompt submit",
     trigger="none: the laptop (typing) replaced it for the thinking state",
     pose=dict(eyes="up", lx=1, props=[d1, d2, P("bub0", 16, -5)]),
     enter=[F(2, eyes="open", lx=1, props=[]), F(2, props=[d1]), F(2, props=[d1, d2])],
     loop=[F(3), F(3, props=[d1, d2, P("bub1", 16, -5)]), F(3, props=[d1, d2, P("bub2", 16, -5)]),
           F(3, props=[d1, d2, P("bub3", 16, -5)]), F(1, eyes="blink", props=[d1, d2, P("bub3", 16, -5)]),
           F(3, props=[d1, d2, P("bub3", 16, -5)])],
     exit=[F(2, props=[d1, d2]), F(2, eyes="open", props=[d1]), F(1, eyes="open", lx=0, props=[])])

anim("offline", "Offline", "state", 30, glow="G", src="[T] Disconnected, [M] offline", trigger="no link to the Core",
     pose=dict(eyes="closed", crouch=0.5, fl=(0.5, 0.5), props=[P("plug", 17, 7)]),
     enter=[F(2, eyes="half", props=[]), F(2, eyes="half"), F(3, eyes="closed", crouch=0.25)],
     loop=[F(30), F(10, crouch=0.75), F(20)],
     exit=[F(2, eyes="half", crouch=0.25), F(1, eyes="blink", crouch=0, fl=(0, 0), props=[])])

anim("dizzy", "Dizzy", "state", 100, glow="Y", src="[T] Dizzy on API error", trigger="link error (bad token)",
     pose=dict(eyes="x", fx=[["orbit", "star", 7, -2, 6, 1.5, 16, 3]]),
     enter=[F(1, dx=0.5, fx=[]), F(1, dx=-0.5, fx=[]), F(1, dx=0.5, fx=[]), F(1, dx=-0.5, fx=[])],
     loop=[F(4), F(4, dx=0.25), F(4), F(3, dx=-0.25)],
     exit=[F(2, eyes="closed", fx=[]), F(1, eyes="blink", fx=[])])

anim("sleep", "Sleeping (exit: waking up)", "state", 5, glow="D", src="[T] Sleeping, Wake (unused); [B] dozing",
     trigger="idle screen; exit plays on touch or wake word",
     pose=dict(eyes="closed", crouch=0.5, fl=(0.5, 0.5), fx=[["rise", "z", 13, 0, 3, -8, 20, 2]]),
     enter=[F(4, eyes="half", fx=[]), F(2, eyes="closed", beak=1, fl=(-1, -1), fx=[]),
            F(4, eyes="closed", beak=1, fl=(-1, -1), fx=[]), F(3, eyes="half", fx=[], crouch=0),
            F(3, eyes="closed", fx=[], crouch=0.25), F(2, eyes="half", fx=[], crouch=0), F(4, fx=[])],
     loop=[F(9), F(10, crouch=0.75)],
     exit=[F(3, fx=[]), F(2, eyes="half", fx=[]), F(1, eyes="blink", crouch=0.25, fx=[]),
           F(3, eyes="closed", crouch=0, fl=(-2, -2), beak=1, fx=[]), F(3, eyes="happy", fl=(-2, -2), beak=0.5, fx=[]),
           F(2, eyes="open", fl=(0, 0), dx=0.25, fx=[]), F(2, dx=-0.25, eyes="open", fl=(0, 0), fx=[]),
           F(1, eyes="blink", fl=(0, 0), crouch=0, fx=[])])

sign = lambda y, name="sign": P(name, 16, y, H)
bell_ring = [F(1, props=[sign(0), P("bell_l", -5, -1)]), F(1, props=[sign(0), P("bell_r", -5, -1)]),
             F(1, props=[sign(0), P("bell_l", -5, -1)]), F(1, props=[sign(0), P("bell_r", -5, -1)])]
anim("permission", "Asking permission", "state", 90, glow="Y", src="[T] Alert on permission prompt, [M] login (key in lock)",
     trigger="confirm request; exit_ok / exit_no on the answer",
     pose=dict(lx=1, fl=(0, -2), props=[sign(0)]),
     enter=[F(1, lx=0, fl=(0, 0), props=[sign(2)]), F(1, fl=(0, -1), props=[sign(1)]), F(1, eyes="wide", props=[sign(0)]),
            *bell_ring],
     loop=[F(8), F(1, eyes="blink"), F(6, lx=0), F(2, dy=-0.25, props=[sign(-1)]), F(2), F(6, lx=0)],
     exit=[F(2, fl=(0, -1), props=[sign(1)]), F(2, fl=(0, 0), lx=0, props=[sign(2)])],
     exit_ok=[F(2, props=[sign(0), P("key", 13, 3)]), F(2, props=[sign(0), P("key", 14, 3)]),
              F(2, eyes="happy", props=[sign(0, "sign_open")]), F(3, eyes="happy", dy=-0.5, props=[sign(0, "sign_open")]),
              F(2, eyes="happy", fl=(0, -1), props=[sign(2, "sign_open")]), F(1, eyes="happy", fl=(0, 0), lx=0, props=[])],
     exit_no=[F(3, eyes="half", props=[sign(0, "sign_no")]), F(2, fl=(0, -1), eyes="half", props=[sign(2, "sign_no")]),
              F(4, fl=(-1, -1), lx=0, eyes="half", props=[]), F(2, fl=(0, 0), lx=0, eyes="blink", props=[])])

# Like Clawd's laptop emote: pull the laptop out and toss it so it lands on the floor, turn side-on and sit, open the
# lid, then hammer the keys with the two flippers in turn while the laptop bounces; on exit close it, stand and fling it.
lap = lambda y=3: P("lap_open", 13, y)
shut = lambda x, y: P("lap_shut", x, y)
front = dict(side=0, crouch=0)
strike_near, strike_far = dict(fl=(0, 1), props=[lap(2.5)]), dict(fl=(1, -0.5), props=[lap(2.5)])
anim("typing", "Typing on a laptop", "state", 40,
     src="[C] Clawd's laptop emote in the Claude app: toss, sit, two-handed typing, laptop bouncing, fling",
     trigger="state thinking (language model working), the whole time, like Clawd at its laptop",
     pose=dict(side=1, crouch=0.5, props=[lap()]),
     enter=[F(1, **front, eyes="blink", props=[]), F(1, **front, props=[shut(14, 4)]), F(1, **front, props=[shut(15, 0)]),
            F(1, **front, eyes="up", props=[shut(16, -2)]), F(1, **front, eyes="up", props=[shut(15, 2)]),
            F(1, side=1, crouch=0.25, props=[shut(13, 7)]), F(2, props=[shut(13, 7)]), F(1, props=[P("lap_half", 13, 5)]),
            F(1, eyes="wide")],
     loop=[F(1), F(1, **strike_near), F(1), F(1, **strike_far), F(1), F(1, **strike_near), F(1), F(1, **strike_far), F(1),
           F(1, **strike_near), F(1, eyes="blink"), F(1)],
     exit=[F(1, props=[P("lap_half", 13, 5)]), F(1, props=[shut(13, 7)]), F(2, crouch=0, props=[shut(13, 7)]),
           F(1, **front, props=[shut(15, 3)]), F(1, **front, eyes="happy", props=[shut(18, -1)]),
           F(1, **front, eyes="happy", props=[shut(20, -4)]), F(1, **front, props=[])])

# ---------------------------------------------------------------- one-shot reactions
tk = P("tick", 6, -4)
anim("done", "Done", "reaction", 75, repeat=1, src="[T] Happy, [M] done", trigger="reply after a tool succeeded",
     loop=[F(1, dy=-0.5, eyes="happy"), F(2, dy=-1, eyes="happy", props=[tk]), F(1, dy=-0.5, eyes="happy", props=[tk]),
           F(4, eyes="happy", props=[tk]), F(2, props=[tk])])

anim("celebrate", "Celebrating", "reaction", 76, repeat=2, src="[B] party or fireworks after answers over 5 s",
     trigger="reply after a tool succeeded and took more than 5 s",
     loop=[F(2, fl=(-1, -1), dy=-0.5, eyes="happy", fx=[["burst", -3, -3, 8, 8]]),
           F(2, fl=(-2, -2), dy=-1, eyes="happy", fx=[["burst", -3, -3, 8, 8]]),
           F(2, fl=(-1, -1), dy=-0.5, eyes="happy", fx=[["burst", 19, -3, 8, 8]]),
           F(2, fl=(0, 0), dy=0, eyes="happy", fx=[["burst", 19, -3, 8, 8]])])

anim("walk_in", "Walking in", "reaction", 60, repeat=1, offstage=True, src="[T] Walking (a new session walks in)",
     trigger="first frame after start-up",
     enter=[F(1, dx=-24), *walk(-24, 0, 2)],
     loop=[F(2, fl=(0, -2), eyes="happy"), F(2, fl=(0, -1), eyes="happy"), F(2, fl=(0, -2), eyes="happy"),
           F(2, fl=(0, -1), eyes="happy"), F(1)])

# ---------------------------------------------------------------- idle pastimes

anim("eureka", "Eureka", "pastime", 20, repeat=1, src="[T] Eureka (unused)", trigger="idle pastime",
     loop=[F(4, eyes="up", props=[P("bulb_off", 7, -5)]), F(1, eyes="wide", dy=-0.5, props=[P("bulb", 7, -5)]),
           F(3, eyes="wide", dy=-1, fl=(-1, -1), props=[P("bulb", 7, -5)]), F(4, eyes="happy", props=[P("bulb", 7, -5)]),
           F(2, eyes="happy")])

anim("dance", "Little dance", "pastime", 20, repeat=2, src="[T] Grooving (unused), [B] music mode",
     trigger="idle pastime",
     loop=[F(2, dx=-0.5, fl=(-1, 0), ft=(-0.5, 0), eyes="happy", fx=[["rise", "note", -2, 0, -1, -4, 8, 1]]),
           F(2, dx=0, eyes="happy", fx=[["rise", "note", -2, 0, -1, -4, 8, 1]]),
           F(2, dx=0.5, fl=(0, -1), ft=(0, -0.5), eyes="happy", fx=[["rise", "note", -2, 0, -1, -4, 8, 1]]),
           F(2, dx=0, eyes="happy", fx=[["rise", "note", -2, 0, -1, -4, 8, 1]])])

anim("look_around", "Looking around", "pastime", 20, repeat=1, src="[B] glances around while waiting",
     trigger="idle pastime",
     loop=[F(4, lx=-1), F(4, lx=-1, ly=-0.5), F(2), F(4, lx=1), F(4, lx=1, ly=0.5), F(1, eyes="blink"), F(3)])

# ---------------------------------------------------------------- Frosti's versions of the Claude app's Clawd emotes
# Motion studied from Clawd's animations in the Claude app ([C]), drawn fresh for the penguin; no pixels copied.
def run_to(x0, x1, side, step=1.0):
    """Run (faster than walk): side-on, a full cell per tick, hopping every other frame."""
    out, x, i = [], x0, 0
    d = step if x1 > x0 else -step
    while (x1 - x) * d > 0:
        x = x + d if abs(x1 - x) > abs(d) else x1
        out.append(F(1, side=side, dx=x, ft=(-1, 0) if i % 2 == 0 else (0, -1), dy=-0.5 if i % 2 == 0 else 0,
                     fl=(-0.5, 0.5) if i % 2 == 0 else (0.5, -0.5)))
        i += 1
    return out

anim("waddle_run", "Running about", "pastime", 20, repeat=1, src="[C] Running", trigger="idle pastime",
     loop=[F(1, side=1), *run_to(0, 6, 1), F(1, side=0, dx=6, eyes="wide"), F(1, side=-1, dx=6),
           *run_to(6, -6, -1), F(1, side=0, dx=-6, eyes="wide"), F(1, side=1, dx=-6), *run_to(-6, 0, 1), F(1, side=0)])

shuffle = lambda x, i: F(1, dx=x, fl=(-1, -1), ft=(-0.5, 0) if i % 2 == 0 else (0, -0.5), eyes="happy")
anim("side_shuffle", "Crab-walking", "pastime", 20, repeat=1, src="[C] CrabWalking (a penguin shuffle, flippers up)",
     trigger="idle pastime",
     loop=[F(1, fl=(-1, -1)), *[shuffle(-0.5 * (i + 1), i) for i in range(6)],
           *[shuffle(-3 + 0.5 * (i + 1), i) for i in range(12)], *[shuffle(3 - 0.5 * (i + 1), i) for i in range(6)],
           F(1, fl=(-1, -1))])

sk = lambda name, x: P(name, 2 + x, 6, H)
fw = lambda: P("flower", 11, -2, H)
anim("hula", "Hula dancing", "pastime", 20, repeat=3, src="[C] Hula: grass skirt, swinging hips",
     trigger="idle pastime",
     pose=dict(eyes="happy", props=[sk("skirt", 0), fw()], fx=[["rise", "note", -3, 0, -1, -4, 8, 1]]),
     enter=[F(1, eyes="open", props=[sk("skirt", 0)], fx=[])],
     loop=[F(1), F(2, dx=-0.5, fl=(-1, 0), props=[sk("skirt", 0.5), fw()]), F(2, props=[sk("skirt2", 0), fw()]),
           F(2, dx=0.5, fl=(0, -1), props=[sk("skirt", -0.5), fw()]), F(2, props=[sk("skirt2", 0), fw()]),
           F(2, dx=-0.5, fl=(-1, 0), props=[sk("skirt", 0.5), fw()]), F(2, props=[sk("skirt2", 0), fw()]),
           F(2, dx=0.5, fl=(0, -1), props=[sk("skirt", -0.5), fw()]), F(1)])

anim("wave", "Waving", "reaction", 65, repeat=2, src="[C] Waving",
     trigger="plain reply (sometimes, instead of the hop); idle pastime",
     pose=dict(fl=(0, -3), eyes="happy", dx=0.25),
     enter=[F(1, fl=(0, -1), eyes="open", dx=0), F(1, fl=(0, -2), dx=0.25)],
     loop=[F(2), F(2, fl=(0, -2)), F(2), F(2, fl=(0, -2), eyes="wink"), F(1)],
     exit=[F(1, fl=(0, -2), eyes="open"), F(1, fl=(0, -1), eyes="open", dx=0)])

mon = lambda name="monitor_side": P(name, 15, -1)
desk = [P("desk_top", 12, 6), P("keys", 13, 5)]
type_near, type_far = dict(fl=(0, 0.5)), dict(fl=(0.5, -0.5))
anim("desktop", "Typing at a desktop", "activity", 50, src="[C] Desktop: turns and sits at a desktop computer",
     trigger="tool run_powershell",
     pose=dict(side=1, crouch=0.5, eyes="half", props=[*desk, mon()]),
     enter=[F(1, side=0, crouch=0, eyes="open", props=[*desk]), F(1, side=1, crouch=0.25, eyes="open", props=[*desk]),
            F(2, eyes="wide")],
     loop=[F(1), F(1, **type_near, props=[*desk, mon("monitor_side2")]), F(1), F(1, **type_far),
           F(1, **type_near), F(1, props=[*desk, mon("monitor_side2")]), F(1, **type_far), F(1),
           F(1, **type_near, props=[*desk, mon("monitor_side2")]), F(1, eyes="blink"), F(1, **type_far), F(1)],
     exit=[F(1, eyes="open", props=[*desk]), F(1, side=0, crouch=0, eyes="open", props=[*desk])])

anim("sway", "Swaying", "pastime", 20, repeat=2, src="[C] Swaying", trigger="idle pastime",
     pose=dict(eyes="closed"),
     enter=[F(2, eyes="half")],
     loop=[F(3), F(3, dx=-0.5, fl=(-0.5, 0.5)), F(3, dx=-0.75, fl=(-0.5, 0.5), ly=0.25), F(3, dx=-0.5),
           F(3), F(3, dx=0.5, fl=(0.5, -0.5)), F(3, dx=0.75, fl=(0.5, -0.5), ly=0.25), F(3, dx=0.5), F(3)],
     exit=[F(2, eyes="half")])

arm = lambda: P("arm_out", 16, 3, H)
anim("point", "Pointing", "pastime", 20, repeat=1, src="[C] Pointing: points off to the side, glances back",
     trigger="idle pastime",
     loop=[F(2, lx=1), F(2, lx=1, eyes="wide", props=[arm()]), F(6, lx=1, props=[arm()]), F(3, lx=0, props=[arm()]),
           F(1, lx=0, eyes="blink", props=[arm()]), F(3, lx=0, props=[arm()]), F(4, lx=1, dy=-0.25, props=[arm()]),
           F(3, lx=1, props=[arm()]), F(2, lx=1), F(2)])

anim("turn_around", "Turning around", "pastime", 20, repeat=1, src="[C] Turning", trigger="idle pastime",
     loop=[F(1, eyes="blink"), F(6, side=1), F(1, side=1, eyes="blink"), F(2, side=0), F(1, eyes="blink"),
           F(6, side=-1), F(1, side=-1, eyes="blink"), F(2, side=0), F(3, eyes="happy"), F(1)])

zen = [["ping", 7.5, -3, 10]]
anim("meditate", "Meditating", "pastime", 20, repeat=1, src="[C] Meditating: sits, floats, breathes",
     trigger="idle pastime",
     pose=dict(eyes="closed", crouch=0.5, fl=(-1, -1)),
     enter=[F(2, eyes="half", crouch=0.25), F(2, eyes="closed", fl=(-0.5, -0.5))],
     loop=[F(5), F(5, dy=-0.5), F(10, dy=-1, fx=zen), F(10, dy=-1, crouch=0.25, fx=zen), F(5, dy=-0.5), F(5)],
     exit=[F(2, eyes="half", fl=(-0.5, -0.5)), F(2, eyes="open", crouch=0.25, fl=(0, 0))])

anim("jump_joy", "Jumping for joy", "reaction", 76, repeat=3, src="[C] JumpingHappy",
     trigger="long task done (sometimes, instead of the party)",
     loop=[F(2, crouch=0.5, eyes="happy"), F(1, dy=-1, fl=(-2, -2), eyes="happy"),
           F(2, dy=-2, fl=(-3, -3), eyes="happy", fx=[["burst", 7, -4, 2, 6]]), F(1, dy=-1, fl=(-2, -2), eyes="happy"),
           F(1, crouch=0.25, eyes="happy"), F(1)])

bang = lambda: P("bang", 15, -3)
anim("excited", "Excited", "reaction", 75, repeat=2, src="[C] Excited: bouncing on the spot, flapping",
     trigger="tool done (sometimes, instead of the hop)",
     pose=dict(eyes="wide"),
     loop=[F(1), F(1, dy=-0.5, fl=(-1, 0)), F(1, fl=(0, -1)), F(1, dy=-0.5, fl=(-1, 0)), F(1, fl=(0, -1)),
           F(1, dy=-0.5, fl=(-1, -1), props=[bang()]), F(1, props=[bang()]), F(1, dy=-0.5, fl=(-1, 0), props=[bang()]),
           F(1)])

palm = lambda: P("palm", 10, 2, H)
cover = dict(fl=(0, -1), eyes="closed", crouch=0.5)
anim("facepalm", "Facepalm", "reaction", 77, repeat=1, src="[C] Facepalm: flipper to the face, head shake",
     trigger="tool failed (sometimes, instead of the confused look)",
     loop=[F(2, eyes="half"), F(1, fl=(0, -1), eyes="half"), F(2, fl=(0, -1), eyes="closed", crouch=0.25, props=[palm()]),
           F(3, **cover, dx=-0.25, props=[palm()]), F(3, **cover, dx=0.25, props=[palm()]),
           F(3, **cover, dx=-0.25, props=[palm()]), F(2, fl=(0, -1), eyes="closed", crouch=0.25, props=[palm()]),
           F(2, fl=(0, -1), eyes="half"), F(3, eyes="half", beak=0.5), F(1)])

sigh = [["rise", "steam", 9, -1, 1, -3, 8, 1]]
anim("disappointed", "Disappointed", "reaction", 77, repeat=1, src="[C] Disappointed: slumps, sighs",
     trigger="tool failed (sometimes, instead of the confused look)",
     pose=dict(eyes="down", crouch=0.5, fl=(1, 1), ly=0.5),
     enter=[F(2, eyes="half", crouch=0.25, fl=(0.5, 0.5), ly=0), F(2, eyes="down")],
     loop=[F(4), F(3, dx=-0.25), F(3, dx=0.25), F(2), F(4, beak=0.5, fx=sigh), F(4, crouch=0.75, fx=sigh), F(4)],
     exit=[F(2, eyes="half", crouch=0.25, fl=(0.5, 0.5), ly=0), F(1, eyes="open", crouch=0, fl=(0, 0), ly=0)])

spin = [dict(side=1), dict(side=0, eyes="happy"), dict(side=-1), dict(side=0, eyes="happy")]
freeze = dict(eyes="happy", fl=(1, -3), ft=(-1, 0), dx=-0.5)
anim("breakdance", "Break dancing", "reaction", 76, repeat=1, src="[C] BreakDancing: spins low, kicks, freezes",
     trigger="long task done (sometimes, instead of the party); idle pastime",
     pose=dict(crouch=0.5, fl=(1, 1)),
     enter=[F(2, crouch=0.25, fl=(0.5, 0.5), eyes="happy")],
     loop=[F(1), *[F(1, **f, ft=(-1, 0) if i % 2 else (0, -1), dy=0.25) for i, f in enumerate(spin * 3)],
           F(6, **freeze, fx=[["burst", 7, -3, 3, 8]]), F(2, **{**freeze, "eyes": "wink"}), F(1)],
     exit=[F(2, crouch=0.25, fl=(0.5, 0.5), eyes="happy"), F(1, crouch=0, fl=(0, 0), eyes="happy")])

seal_all = [seal(ANIMS[k]) for k in ORDER]

MAP = {
    "states": {"idle": "idle", "listening": "listening", "thinking": "typing", "speaking": "speaking",
               "offline": "offline", "error": "dizzy", "sleep": "sleep"},
    # tool -> activity, all Clawd moves; "tool:arg" entries win (git action, run_project task)
    "tools": {"open_app": "point", "get_status": "look_around", "set_brightness": "point", "set_volume": "point",
              "kill_process": "point", "open_vscode": "typing", "git": "desktop", "git:pull": "desktop",
              "git:commit": "desktop", "git:push": "desktop", "run_project": "desktop", "run_project:test": "desktop",
              "run_project:build": "desktop", "run_powershell": "desktop", "set_timer": "point",
              "remember": "typing", "recall": "look_around", "describe_screen": "look_around", "forget": "turn_around",
              "play_music": "hula", "media_control": "dance"},
    "reactions": {"done": "done", "celebrate": "celebrate", "chat": "wave", "failed": "facepalm",
                  "notify": "wave", "poke": "excited", "surprised": "excited", "glitch": "disappointed",
                  "go_away": "disappointed", "walk_in": "walk_in", "overheated": "meditate", "low_battery": "disappointed"},
    "permission": "permission", "permission_wait": "permission", "think_hard": "typing",
    "pastimes": ["eureka", "dance", "look_around", "waddle_run", "side_shuffle", "hula", "wave", "sway", "point",
                 "turn_around", "meditate", "breakdance"],
    # a reaction may play one of these instead (picked at random, the original included)
    "variety": {"done": ["excited"], "celebrate": ["jump_joy", "breakdance"], "facepalm": ["disappointed"]},
    "timing": {"think_hard_ms": 5000, "permission_wait_ms": 60000, "celebrate_s": 5,
               "pastime_min_ms": 15000, "pastime_max_ms": 40000},
}

def build():
    return {"version": 1, "tick": TICK, "canvas": CANVAS, "origin": ORIGIN, "palette": PALETTE, "body": BODY,
            "rest": REST, "eyes": EYES, "props": PROPS, "order": ORDER, "anims": ANIMS, "map": MAP}

OUT = Path(__file__).with_name("frosti_anims.json")

def dump():
    return json.dumps(build(), separators=(",", ":"))

if __name__ == "__main__":
    OUT.write_text(dump(), encoding="utf-8")
    print(f"wrote {OUT.name}: {len(ORDER)} animations, {len(PROPS)} props, {OUT.stat().st_size // 1024} KB")
