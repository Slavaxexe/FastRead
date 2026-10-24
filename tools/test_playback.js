const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync('dist/index.html', 'utf8');

function reader(withFrames = true) {
  let now = 0, nextId = 1, frameRequests = 0;
  const timers = new Map(), frames = new Map();
  const dom = new JSDOM(html, {
    runScripts: 'dangerously', url: 'http://localhost/',
    beforeParse(w) {
      Object.defineProperty(w.performance, 'now', { value: () => now });
      w.setTimeout = (fn, ms = 0) => {
        const id = nextId++;
        timers.set(id, { due: now + Math.max(1, Number(ms)), fn });
        return id;
      };
      w.clearTimeout = id => timers.delete(id);
      if (withFrames) {
        w.requestAnimationFrame = () => { frameRequests++; throw new Error('Recovered playback must not request animation frames'); };
        w.cancelAnimationFrame = id => frames.delete(id);
      }
    }
  });
  const w = dom.window, app = w.FastRead;
  function advance(ms) {
    const end = now + ms;
    for (let guard = 0; guard < 10000; guard++) {
      const task = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
      if (!task || task[1].due > end) { now = end; return; }
      now = Math.max(now, task[1].due);
      timers.delete(task[0]);
      task[1].fn();
    }
    throw new Error('Timer loop did not settle');
  }
  function frame(ms = 16) {
    advance(ms);
    const pending = [...frames.values()];
    frames.clear();
    pending.forEach(fn => fn(now));
  }
  function load(text) {
    app.settings.smart = false;
    app.settings.ramp = false;
    app.settings.wpm = 300;
    app.loadDoc({ id: 'test', title: 'test', text, demo: true });
  }
  function finish() {
    for (let i = 0; app.S.playing && i < 10000; i++) frame();
    assert.equal(app.S.playing, false, 'playback must reach the end');
    assert.equal(frameRequests, 0);
  }
  return { w, app, advance, frame, load, finish, stall: ms => { now += ms; advance(0); }, close: () => w.close() };
}

// The real startup tokenizer, not only the separately exported tokenize helper.
{
  const r = reader();
  const words = r.app.S.doc.text.match(/[a-zA-Zа-яА-ЯёЁ0-9]+/g);
  const tokens = r.app.S.tokens.map(t => t.w).join(' ').match(/[a-zA-Zа-яА-ЯёЁ0-9]+/g);
  assert.deepEqual(Array.from(tokens), Array.from(words));
  for (const word of ['Onyx', 'не', 'глазу', 'центр', 'старта', 'назад']) {
    assert.ok(r.app.S.tokens.some(t => t.w === word), `demo must retain ${word}`);
  }
  r.app.settings.smart = false;
  r.app.settings.ramp = false;
  r.app.settings.wpm = 250;
  r.app.play();
  r.finish();
  const stats = r.app.getPlaybackDiagnostics();
  assert.deepEqual(Array.from(stats.samples, s => s.index), Array.from({ length: 71 }, (_, i) => i));
  assert.deepEqual(Array.from(stats.samples, s => s.word), Array.from(r.app.S.tokens, t => t.w));
  assert.ok(stats.samples.every(s => s.durationMs === 240));
  console.log('PASS: all 71 demo tokens follow source order at 250 WPM with 240 ms software intervals');
  r.close();
}

// Software scheduling only: this cannot establish physical E-Ink visibility.
{
  const r = reader();
  r.load('Один два три четыре пять шесть');
  r.app.play();
  r.advance(199);
  assert.equal(r.app.S.i, 0);
  r.advance(1);
  assert.equal(r.app.S.i, 1, 'full word interval on the original timer');
  r.stall(1500);
  assert.equal(r.app.S.i, 2, 'late timer advances exactly one token');
  r.advance(199);
  assert.equal(r.app.S.i, 2);
  r.advance(1);
  assert.equal(r.app.S.i, 3, 'full interval after a stall');
  r.finish();
  const stats = r.app.getPlaybackDiagnostics();
  assert.deepEqual(Array.from(stats.samples, s => s.index), [0, 1, 2, 3, 4, 5]);
  assert.ok(stats.samples.every(s => s.durationMs >= 200));
  assert.ok(stats.maxLateMs >= 1300);
  console.log('PASS: timer-only scheduling, token order and full intervals after late timers (software only)');
  r.close();
}

// Speed controls restart the pending timer deadline.
{
  const r = reader();
  r.load('Один два три четыре пять');
  r.app.play();
  r.advance(180);
  r.w.document.getElementById('faster').click(); // 325 WPM
  r.frame(20);
  assert.equal(r.app.S.i, 0, 'old timer must not advance after a speed change');
  r.finish();
  r.app.seek(0);
  r.app.settings.wpm = 300;
  r.app.play();
  r.finish();
  const slow = r.app.getPlaybackDiagnostics().observedTokenWpm;
  r.app.seek(0);
  r.app.settings.wpm = 600;
  r.app.play();
  r.finish();
  const fast = r.app.getPlaybackDiagnostics().observedTokenWpm;
  assert.ok(fast > slow * 1.5, `speed must increase: ${slow} -> ${fast}`);
  console.log(`PASS: requested speed changes software timer throughput (not display throughput) (${slow} -> ${fast} tokens/min)`);
  r.close();
}

// Repeated play, pause, seek, and stale callbacks cannot create parallel clocks.
{
  const r = reader();
  r.load('Один два три четыре пять');
  r.app.play(); r.app.play();
  r.advance(180);
  r.app.pause();
  r.frame(500);
  assert.equal(r.app.S.i, 0);
  r.app.play();
  r.advance(180);
  r.app.seek(2);
  r.frame(20);
  assert.equal(r.app.S.i, 2);
  r.finish();
  assert.deepEqual(Array.from(r.app.getPlaybackDiagnostics().samples, s => s.index), [2, 3, 4]);
  r.app.seek(0);
  r.w.clearTimeout = () => {}; // emulate an uncancellable old timer callback
  r.app.play();
  r.frame();
  r.advance(180);
  r.app.pause();
  r.app.play();
  r.frame(20);
  assert.equal(r.app.S.i, 0, 'generation guard must reject an obsolete callback');
  r.w.document.dispatchEvent(new r.w.Event('visibilitychange')); // JSDOM document is hidden
  assert.equal(r.app.S.playing, false, 'hidden documents pause playback');
  r.frame(1000);
  assert.equal(r.app.S.i, 0);
  console.log('PASS: pause, seek, and duplicate play cancel obsolete callbacks');
  r.close();
}

// Restore the DOM update path and surrounding UI behavior used before 1.1.
{
  const r = reader();
  r.load('Один два три четыре пять шесть семь восемь девять');
  const focus = r.w.document.getElementById('wp');
  const oldNode = focus.firstChild;
  r.app.render();
  assert.notEqual(focus.firstChild, oldNode, 'textContent replacement is restored');
  r.app.play(); r.advance(800);
  assert.equal(r.w.document.getElementById('pos').textContent, '5 / 9');
  r.app.pause();
  console.log('PASS: pre-1.1 text replacement and periodic counter updates are restored');
  r.close();
}

// Long sessions retain a bounded diagnostic trace and checkpoint the position.
{
  const r = reader();
  r.app.settings.smart = false;
  r.app.settings.ramp = false;
  r.app.loadDoc({ id: 'long-test', title: 'long', text: Array(181).fill('слово').join(' ') });
  r.app.play();
  for (let i = 0; i < 400; i++) r.frame();
  assert.ok(Number(r.w.localStorage.getItem('fr.pos.long-test')) > 0,
    'position must be checkpointed before the end of playback');
  r.finish();
  assert.equal(r.app.getPlaybackDiagnostics().completedTokens, 181);
  assert.equal(r.app.getPlaybackDiagnostics().samples.length, 120);
  assert.equal(r.w.localStorage.getItem('fr.pos.long-test'), '180');
  console.log('PASS: long playback checkpoints progress and bounds diagnostics to 120 samples');
  r.close();
}

// Sentence translation gets a separate dwell and then advances only once.
{
  const r = reader();
  r.app.settings.smart = false;
  r.app.settings.ramp = false;
  r.app.settings.mode = 'sentence';
  r.app.loadDoc({ id: 'pack', demo: true, pack: {
    format: 'fastread-pack/1', lang: 'en', gloss_lang: 'ru', title: 'test',
    paragraphs: [[{ text: 'One.', tr: 'Один.' }, { text: 'Two.', tr: 'Два.' }]]
  }});
  r.app.play();
  r.advance(200);
  assert.equal(r.app.S.i, 0);
  assert.equal(r.app.S.trShown, true);
  r.app.pause();
  r.frame(1000);
  assert.equal(r.app.S.i, 0);
  r.app.play();
  r.finish();
  assert.deepEqual(Array.from(r.app.getPlaybackDiagnostics().samples, s => s.index), [0, 1]);
  console.log('PASS: translation timing and cancellation preserve token order');
  r.close();
}

// No animation-frame API is required.
{
  const r = reader(false);
  r.load('Один два три');
  r.app.play();
  r.finish();
  assert.equal(r.app.getPlaybackDiagnostics().clock, 'timeout');
  assert.equal(r.app.getPlaybackDiagnostics().completedTokens, 3);
  console.log('PASS: timer fallback without animation-frame API');
  r.close();
}
