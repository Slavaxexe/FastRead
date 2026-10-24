const fs = require('node:fs');
const { performance } = require('node:perf_hooks');
const { createReader, baseline } = require('./optimization_harness');
const old = createReader(baseline), next = createReader();
const words = ['выделенную', 'электронных', '«скорочтение!»', 'FastRead', 'слово,', 'длинноесловобезразбивки'];
function medianRun(fn) {
  const times = [];
  for (let run = 0; run < 9; run++) {
    const started = performance.now(); fn();
    if (run > 1) times.push(performance.now() - started);
  }
  return times.sort((a, b) => a - b)[Math.floor(times.length / 2)];
}
const splitOld = medianRun(() => { for (let i = 0; i < 100000; i++) old.app.splitWord(words[i % words.length]); });
const splitNew = medianRun(() => { for (let i = 0; i < 100000; i++) next.app.splitWord(words[i % words.length]); });
for (const r of [old, next]) {
  r.app.S.doc = { chapters: Array.from({ length: 10000 }, (_, i) => ({ title: String(i), startIndex: i * 10 })) };
  r.app.S.tokens = new Array(100000);
}
const chapterOld = medianRun(() => { for (let i = 0; i < 10000; i++) old.w.getChapterInfoAt((i * 7919) % 100000); });
const chapterNew = medianRun(() => { for (let i = 0; i < 10000; i++) next.w.getChapterInfoAt((i * 7919) % 100000); });
const results = { environment: 'Desktop Node/V8 + JSDOM; NOT a BOOX or E-Ink benchmark',
  splitWord100k: { baselineMs: splitOld, optimizedMs: splitNew, speedup: splitOld / splitNew },
  chapter10kQueriesIn10kChapters: { baselineMs: chapterOld, optimizedMs: chapterNew, speedup: chapterOld / chapterNew } };
console.log(JSON.stringify(results, null, 2));
if (process.argv[2]) fs.writeFileSync(process.argv[2], JSON.stringify(results, null, 2) + '\n', 'utf8');
old.close(); next.close();
