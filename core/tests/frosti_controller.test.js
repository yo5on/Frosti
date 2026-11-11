// Behaviour tests for frosti.js (run: node --test tests/frosti_controller.test.js; test_anim.py runs it too).
"use strict";
const test = require("node:test"), assert = require("node:assert/strict");
const path = require("node:path"), fs = require("node:fs");
const anim = path.join(__dirname, "..", "frosti_core", "anim");
const spec = JSON.parse(fs.readFileSync(path.join(anim, "frosti_anims.json"), "utf8"));
const { Controller } = require(path.join(anim, "frosti.js"));

const strip = f => { const { n, ...rest } = f; return JSON.stringify(rest); };
const REST = strip({ n: 0, ...spec.rest });

// A controller that records every animation it starts, and the frame it was on when it switched.
function make(opts = {}) {
  const c = new Controller(spec, { rand: () => 0.5, ...opts });
  c.log = []; c.seams = [];
  const start = c.start.bind(c);
  c.start = w => { if (c.cur) c.seams.push([c.cur.id, w.id, strip(c.cur.frame())]); start(w); c.log.push(w.id); };
  return c;
}
const run = (c, ms) => { for (let t = 0; t < ms; t += 50) c.tick(50); };
// a reaction or any of its variety siblings
const kin = id => [id, ...((spec.map.variety || {})[id] || [])];

test("a tool maps to its activity, with git and run_project sub-actions", () => {
  const c = make(); run(c, 500);
  c.activity("git", { action: "commit" }); run(c, 3000);
  assert.equal(c.cur.id, spec.map.tools["git:commit"]);
  c.reply({ tool: "git", seconds: 1 }); run(c, 6000);
  c.activity("run_project", { task: "test" }); run(c, 3000);
  assert.equal(c.cur.id, spec.map.tools["run_project:test"]);
  c.activity("open_app", {}); run(c, 4000);
  assert.equal(c.cur.id, spec.map.tools.open_app);
});

test("success plays only after the reply, never while the tool is still running", () => {
  const c = make(); run(c, 500);
  c.activity("run_project", { task: "build" }); run(c, 10000);
  assert.ok(!c.log.some(id => [...kin("done"), ...kin("celebrate")].includes(id)));
  c.reply({ tool: "run_project", seconds: 2 }); run(c, 6000);
  assert.ok(c.log.some(id => kin("done").includes(id)), c.log.join(" "));
  assert.equal(c.cur.id, "idle");
});

test("long tasks celebrate, failures look confused, plain replies hop", () => {
  for (const [r, want] of [[{ tool: "git", seconds: 7 }, spec.map.reactions.celebrate], [{ tool: "git", detail: "boom" }, spec.map.reactions.failed], [{}, spec.map.reactions.chat]]) {
    const c = make(); run(c, 500); c.reply(r); run(c, 6000);
    assert.ok(c.log.some(id => kin(want).includes(id)), `${JSON.stringify(r)} -> ${c.log}`);
  }
});

test("every switch between animations happens on the resting pose", () => {
  const c = make(); run(c, 500);
  const steps = [() => c.state("thinking"), () => c.activity("recall"), () => c.reply({ tool: "recall", seconds: 1 }),
    () => c.state("speaking"), () => c.state("idle"), () => c.poke(), () => c.notify(), () => c.confirm(),
    () => c.decided(true), () => c.sleep(true), () => c.sleep(false), () => c.state("listening"), () => c.state("idle")];
  for (const s of steps) { s(); run(c, 4000); }
  // an urgent cut (priority 90+) may end on rest with the covering blink still showing; nothing else differs
  for (const [from, to, f] of c.seams) assert.equal(f.replace('"eyes":"blink"', '"eyes":"open"'), REST, `${from} -> ${to} left on a non-rest frame`);
  assert.ok(c.seams.length >= 10);
});

test("permission: the sign goes up, and the answer picks the exit", () => {
  for (const [ok, part] of [[true, "exit_ok"], [false, "exit_no"]]) {
    const c = make(); run(c, 500);
    c.confirm(); run(c, 3000);
    assert.equal(c.cur.id, "permission");
    c.decided(ok);
    const seen = new Set(); for (let t = 0; t < 4000; t += 50) { c.tick(50); seen.add(`${c.cur.id}:${c.cur.part}`); }
    assert.ok(seen.has(`permission:${part}`), [...seen].join(" "));
    assert.equal(c.cur.id, "idle");
  }
});

test("an unanswered request still shows the permission animation after 60 s", () => {
  const c = make(); run(c, 500); c.confirm(); run(c, 61000);
  assert.equal(c.cur.id, spec.map.permission_wait);
});

test("thinking stays at the laptop, even past 5 s", () => {
  const c = make(); run(c, 500); c.state("thinking"); run(c, 2000);
  assert.equal(c.cur.id, "typing");
  const p = c.cur; run(c, 6000);
  assert.equal(c.cur, p);  // same player: no close-and-reopen of the laptop
});

test("urgent events cut in; one-shots finish their repeats otherwise", () => {
  const c = make(); run(c, 500);
  c.poke(); run(c, 300); c.state("thinking"); run(c, 300);
  assert.ok(kin(spec.map.reactions.poke).includes(c.cur.id), c.cur.id);  // thinking (40) waits for the poke reaction
  const t0 = c.now; c.setLink(false, true); run(c, 400);
  assert.ok([...kin(spec.map.reactions.glitch), "dizzy"].includes(c.cur.id), c.cur.id);  // the error cut in within 400 ms
  run(c, 3000); assert.equal(c.cur.id, "dizzy");
  c.setLink(true); run(c, 3000); assert.notEqual(c.cur.id, "dizzy");
});

test("idle pastimes come on their own, and only while idle", () => {
  const c = make(); run(c, 45000);
  assert.ok(c.log.some(id => spec.map.pastimes.includes(id)), c.log.join(" "));
  const b = make(); b.state("listening"); run(b, 45000);
  assert.ok(!b.log.some(id => spec.map.pastimes.includes(id)));
});

test("sleep, then wake through the sleep exit", () => {
  const c = make(); run(c, 500); c.sleep(true); run(c, 5000);
  assert.equal(c.cur.id, "sleep");
  c.sleep(false);
  let woke = false; for (let t = 0; t < 4000; t += 50) { c.tick(50); woke ||= c.cur.id === "sleep" && c.cur.part === "exit"; }
  assert.ok(woke); assert.equal(c.cur.id, "idle");
});

test("hover only moves the eyes while idle", () => {
  const c = make(); run(c, 500); c.hover(1); c.tick(50);
  assert.equal(c.frame().lx, 1);
  c.state("listening"); run(c, 2000);
  assert.equal(c.frame().lx, 0);
});

test("the link: walking in at start, going away, then offline", () => {
  const c = make({ walkIn: true }); run(c, 300);
  assert.equal(c.log[0], "walk_in");
  run(c, 6000); c.setLink(false); run(c, 8000);
  assert.ok(c.log.some(id => kin(spec.map.reactions.go_away).includes(id))); assert.equal(c.cur.id, "offline");
});

test("a reaction sometimes plays a variety sibling instead, original included", () => {
  const seen = new Set();
  for (const r of [0, 0.5, 0.99]) {
    const c = make({ rand: () => r }); run(c, 500); c.reply({ tool: "git", detail: "boom" }); run(c, 6000);
    c.log.filter(id => kin(spec.map.reactions.failed).includes(id)).forEach(id => seen.add(id));
  }
  assert.ok(kin(spec.map.reactions.failed).length > 1, "failures should have variety");
  assert.deepEqual([...seen].sort(), kin(spec.map.reactions.failed).sort());
});
