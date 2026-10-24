const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const template = fs.readFileSync(path.join(root, 'src/index.html'), 'utf8');
const polyfills = fs.readFileSync(path.join(root, 'src/polyfills.js'), 'utf8');
const baseline = fs.readFileSync(path.join(__dirname, 'baselines/app-1.2.js'), 'utf8');
const current = fs.readFileSync(path.join(root, 'src/app.js'), 'utf8');

function createReader(source = current, bridge) {
  let now = 0, id = 0;
  const timers = new Map();
  const html = template.replace('/*APP_PLACEHOLDER*/', () => source)
    .replace('/*POLYFILLS_PLACEHOLDER*/', () => polyfills);
  const dom = new JSDOM(html, {
    runScripts: 'dangerously', url: 'http://localhost/',
    beforeParse(w) {
      Object.defineProperty(w.performance, 'now', { value: () => now });
      w.setTimeout = (fn, ms = 0) => { timers.set(++id, { fn, due: now + Math.max(1, Number(ms)) }); return id; };
      w.clearTimeout = key => timers.delete(key);
      w.requestAnimationFrame = () => { throw Error('Playback must retain the 1.2 timer'); };
      if (bridge) w.AndroidBridge = bridge;
    }
  });
  const w = dom.window;
  let afterTask = () => {};
  function advance(ms) {
    const end = now + ms;
    for (let i = 0; i < 10000; i++) {
      const next = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
      if (!next || next[1].due > end) { now = end; return; }
      now = Math.max(now, next[1].due);
      timers.delete(next[0]); next[1].fn(); afterTask(now);
    }
    throw Error('Timer loop did not settle');
  }
  return { w, app: w.FastRead, advance, now: () => now,
    onTask: fn => { afterTask = fn; }, close: () => w.close() };
}
module.exports = { createReader, baseline, current, root };
