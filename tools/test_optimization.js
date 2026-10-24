const assert = require('node:assert/strict');
const { createReader, baseline } = require('./optimization_harness');

// Differential checks use the source preserved from the user-verified APK 1.2.
const old = createReader(baseline), next = createReader();
let seed = 42;
function random(n) { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed % n; }
const alphabet = Array.from('абвёьABCxyz19—-«».,!?() éΩ中😀');
const words = ['', '—', '«слово!»', '😀', 'Москва —', '— Привет', 'electro-magnetic'];
for (let i = 0; i < 3000; i++) {
  let s = '';
  for (let j = 0, n = random(40); j < n; j++) s += alphabet[random(alphabet.length)];
  words.push(s);
}
for (const focus of ['center', 'orp']) {
  old.app.settings.focus = next.app.settings.focus = focus;
  for (const word of words) assert.deepEqual(Array.from(next.app.splitWord(word)), Array.from(old.app.splitWord(word)));
}
console.log('PASS: focus splitting matches 1.2 for 6,014 mixed-language/punctuation cases');

for (const r of [old, next]) {
  r.app.S.doc = { chapters: Array.from({ length: 10000 }, (_, i) => ({ title: 'Глава ' + i, startIndex: i * 10 + 3 })) };
  r.app.S.tokens = new Array(100010);
}
for (const index of [-1, 0, 3, 4, 12, 13, 99992, 99993, 100010]) {
  assert.equal(JSON.stringify(next.w.getChapterInfoAt(index)), JSON.stringify(old.w.getChapterInfoAt(index)));
}
let reads = 0;
next.app.S.doc.chapters = Array.from({ length: 10000 }, (_, i) => {
  const chapter = { title: String(i) };
  Object.defineProperty(chapter, 'startIndex', { get() { reads++; return i * 10; } });
  return chapter;
});
next.w.getChapterInfoAt(99990);
assert.ok(reads <= 16, `chapter search should be logarithmic, used ${reads} reads`);
console.log(`PASS: chapter boundaries match 1.2; 10,000 chapters need at most ${reads} index reads`);
old.close(); next.close();

function playbackTrace(source, focus, text) {
  const r = createReader(source);
  r.app.settings.wpm = 250; r.app.settings.focus = focus;
  r.app.loadDoc({ id: 'trace', title: 'trace', text, demo: true });
  const observer = new r.w.MutationObserver(() => {});
  observer.observe(r.w.document.body, { subtree: true, childList: true, characterData: true, attributes: true });
  const trace = [];
  function collect() {
    const mutations = observer.takeRecords().map(m => [m.type, m.target.id || m.target.nodeName,
      m.attributeName, m.addedNodes.length, m.removedNodes.length]);
    if (!mutations.length) return;
    const d = r.w.document;
    trace.push({ time: r.now(), index: r.app.S.i, playing: r.app.S.playing,
      word: ['wl', 'wp', 'wr'].map(id => d.getElementById(id).textContent),
      pos: d.getElementById('pos').textContent, left: d.getElementById('left').textContent,
      scrub: d.getElementById('scrub').value, mutations });
  }
  r.onTask(collect); r.app.play(); collect();
  for (let i = 0; r.app.S.playing && i < 10000; i++) r.advance(25);
  assert.equal(r.app.S.playing, false); observer.disconnect(); r.close();
  return trace;
}
const text = 'Привет! Это FastRead — скорочтение для электронных книг Onyx BOOX.\n\n' +
  'Смотри на выделенную букву и не двигай взглядом. Каждое слово выравнивается по центру, поэтому глазу не нужно прыгать по строке.\n\n' +
  'Число 42, «слово!» и длинноесловобезразбивки.';
for (const focus of ['center', 'orp']) {
  assert.deepEqual(playbackTrace(undefined, focus, text), playbackTrace(baseline, focus, text));
}
console.log('PASS: default word timing, order and DOM mutation traces match working 1.2');

{
  const r = createReader();
  r.app.settings.smart = false; r.app.settings.ramp = false; r.app.settings.wpm = 600;
  r.app.loadDoc({ id: 'floor', demo: true, text: 'Раз два три' });
  const slider = r.w.document.getElementById('sMinWord');
  slider.value = '150'; slider.dispatchEvent(new r.w.Event('input'));
  assert.equal(JSON.parse(r.w.localStorage.getItem('fr.settings')).minWordMs, 150);
  r.app.play(); r.advance(149); assert.equal(r.app.S.i, 0);
  r.advance(1); assert.equal(r.app.S.i, 1);
  r.advance(300); assert.equal(r.app.S.playing, false);
  assert.ok(r.app.getPlaybackDiagnostics().samples.every(s => s.durationMs === 150));
  r.app.seek(0); r.app.settings.wpm = 250;
  r.app.play(); r.advance(720);
  assert.ok(r.app.getPlaybackDiagnostics().samples.every(s => s.durationMs === 240));
  r.close();
  console.log('PASS: optional screen pacing persists, clamps short words and preserves longer intervals');
}

{
  const r = createReader();
  r.app.settings.wpm = 50; r.app.settings.smart = true;
  r.app.loadDoc({ id: 'large', text: 'Электрификация.' });
  const writes = [];
  r.w.DB.put = (store, doc) => { writes.push({ store, pos: doc.pos, marks: (doc.bookmarks || []).slice() }); return r.w.Promise.resolve(doc); };
  r.app.play(); r.advance(1500);
  assert.equal(r.app.S.playing, true);
  assert.equal(writes.length, 0, 'long punctuation pauses must not serialize the entire book');
  r.app.pause(); assert.equal(writes.length, 1);
  r.app.pause(); assert.equal(writes.length, 1, 'unchanged repeated saves must not clone the book');
  r.app.S.doc.bookmarks = [0]; r.w.savePos();
  assert.equal(writes.length, 2, 'bookmark changes must still be persisted');
  r.close();
  console.log('PASS: no full-book write during long word holds; duplicate saves skipped, bookmarks retained');
}

{
  const calls = [];
  const r = createReader(undefined, {
    setReading: active => calls.push(active),
    getRenderStats: () => JSON.stringify({ drawCount: 17, maxGapMs: 255, device: 'test', sdk: 19 })
  });
  calls.length = 0;
  r.app.settings.smart = false;
  r.app.loadDoc({ id: 'ring', demo: true, text: new Array(181).fill('слово').join(' ') });
  calls.length = 0;
  r.app.play(); r.advance(36200);
  assert.deepEqual(calls, [true, false]);
  const snapshot = r.app.getPlaybackDiagnostics();
  assert.deepEqual(Array.from(snapshot.samples, s => s.index), Array.from({ length: 120 }, (_, i) => i + 61));
  snapshot.samples[0].word = 'changed';
  assert.equal(r.app.getPlaybackDiagnostics().samples[0].word, 'слово');
  r.w.document.getElementById('performanceBtn').click();
  assert.ok(r.w.document.getElementById('performanceReport').textContent.includes('17'));
  r.close();
  console.log('PASS: recycled diagnostics preserve order and snapshots; Android start/stop bridge is paired');
}

// Vendor commands must be explicit, feature-gated, and absent during playback.
for (const supported of [false, true]) {
  let requests = 0;
  const r = createReader(undefined, {
    getEinkCapabilities: () => JSON.stringify({ fullRefresh: supported }),
    requestFullRefresh: () => { requests++; }
  });
  r.app.play(); r.advance(1000);
  assert.equal(requests, 0, 'ordinary reading must not issue EPD commands');
  r.w.document.getElementById('setBtn').click();
  const button = r.w.document.getElementById('screenRefreshBtn');
  assert.equal(button.disabled, !supported);
  button.click();
  assert.equal(requests, supported ? 1 : 0);
  assert.equal(r.app.S.playing, false);
  r.close();
}
{
  const r = createReader(undefined, { getEinkCapabilities: () => { throw Error('blocked'); } });
  r.w.document.getElementById('setBtn').click();
  assert.equal(r.w.document.getElementById('screenRefreshBtn').disabled, true);
  r.close();
}
console.log('PASS: full-refresh command is capability-gated, manual-only and fails closed');
