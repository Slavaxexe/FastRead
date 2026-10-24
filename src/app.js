'use strict';

// On-screen diagnostic logger in case of unhandled errors
window.onerror = function(msg, url, line) {
  var b = document.getElementById('debugError');
  if (!b && document.body) {
    b = document.createElement('div');
    b.id = 'debugError';
    b.style.cssText = 'position:fixed;top:0;left:0;right:0;background:#ffffff;color:#000000;padding:12px;font-size:14px;font-weight:bold;z-index:999999;border-bottom:3px solid #000000;word-break:break-all;box-shadow:0 3px 0 #000000;';
    document.body.appendChild(b);
  }
  if (b) b.textContent = 'Ошибка JS: ' + msg + ' (стр. ' + line + ')';
};

/**
 * FastRead Core Application (Optimized for E-Ink and Chromium 30+)
 */

/* =====================================================================
   Settings & State
   ===================================================================== */
var DEFAULTS = {
  wpm: 300,
  smart: true,
  focus: 'center',
  focusStyle: 'box', // Box outline is 100% visible on Onyx BOOX in A2 mode
  split: true,
  ramp: false,       // ramp off by default for e-ink
  minWordMs: 0,      // optional panel pacing; 0 preserves the verified 1.2 timing
  context: true,
  font: 'serif',
  size: 56,
  theme: 'light',    // light theme default for E-Ink
  hwKeys: 'words',   // words | sentence | speed
  mode: 'full',
  glossOp: 80,       // higher opacity for e-ink readability
  glossDelay: 300,
  level: 3,
  density: 2,
  trTime: 100
};

var LS = {
  get: function(k, d) {
    try {
      var v = localStorage.getItem(k);
      return v == null ? d : JSON.parse(v);
    } catch (e) {
      return d;
    }
  },
  set: function(k, v) {
    try {
      localStorage.setItem(k, JSON.stringify(v));
    } catch (e) {}
  }
};

var settings = Object.assign({}, DEFAULTS, LS.get('fr.settings', {}));
var saveSettings = function() { LS.set('fr.settings', settings); };

var FONTS = {
  serif: 'Georgia, "Times New Roman", serif',
  sans:  '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
  mono:  'Menlo, Monaco, Consolas, "Courier New", monospace'
};

var MODES = [
  { id: 'plain',    name: 'Без перевода',          desc: 'Только текст. На паузе перевод текущего слова виден.' },
  { id: 'full',     name: 'Перевод под словом',    desc: 'Под каждым словом четкий перевод в контексте.' },
  { id: 'delayed',  name: 'Подсказки с задержкой', desc: 'Перевод проявляется через паузу у слов выбранного уровня.' },
  { id: 'sentence', name: 'Перевод предложений',   desc: 'После точки на мгновение показывается перевод предложения целиком.' },
  { id: 'weave',    name: 'Вкрапления',            desc: 'Часть слов заменена словами изучаемого языка.' }
];

var LV = { A1: 1, A2: 2, B1: 3, B2: 4, C1: 5, C2: 6 };

/* =====================================================================
   Tokenizer (ES5 & Chromium 30 Compatible without Unicode property regex)
   ===================================================================== */
var IS_WORDCHAR = /[a-zA-Z0-9\u00C0-\u024F\u0400-\u04FF\u0500-\u052F\u2DE0-\u2DFF\uA640-\uA69F]/;
var NOT_WORDCHAR_START = /^[^a-zA-Z0-9\u00C0-\u024F\u0400-\u04FF\u0500-\u052F\u2DE0-\u2DFF\uA640-\uA69F]+/;
var NOT_WORDCHAR_END = /[^a-zA-Z0-9\u00C0-\u024F\u0400-\u04FF\u0500-\u052F\u2DE0-\u2DFF\uA640-\uA69F]+$/;
var OPENERS = /^[«“„"'‘‚(\[{¿¡]+$/;
var DASHES  = /^[—–\-]+$/;
var CLOSERS_TAIL = /[»”"'’)\]}]+$/;
var VOWELS = /[аеёиоуыэюяaeiouy]/i;
var SOFT = /[ьъй]/i;

function coreKey(w) {
  if (!w) return '';
  var chars = String(w).split('');
  var out = '';
  for (var i = 0; i < chars.length; i++) {
    if (IS_WORDCHAR.test(chars[i])) out += chars[i];
  }
  return out.toLowerCase();
}

function coreText(w) {
  if (!w) return '';
  return String(w).replace(NOT_WORDCHAR_START, '').replace(NOT_WORDCHAR_END, '');
}

function coreLen(w) {
  var n = 0;
  var str = String(w || '');
  for (var i = 0; i < str.length; i++) {
    if (IS_WORDCHAR.test(str.charAt(i))) n++;
  }
  return n;
}

function splitParagraphs(text) {
  text = String(text || '').replace(/\r\n?/g, '\n').replace(/\u00AD/g, '');
  var lines = (text.match(/\n/g) || []).length;
  var blanks = (text.match(/\n[ \t]*\n/g) || []).length;
  var re = (lines > 5 && blanks < lines * 0.05) ? /\n+/ : /\n[ \t]*\n\s*/;
  return text.split(re).map(function(p) { return p.trim(); }).filter(Boolean);
}

function mergePunct(raw) {
  var words = [];
  var prefix = '';
  for (var i = 0; i < raw.length; i++) {
    var r = raw[i];
    if (IS_WORDCHAR.test(r)) {
      words.push(prefix + r);
      prefix = '';
      continue;
    }
    if (OPENERS.test(r)) {
      prefix += r;
      continue;
    }
    if (DASHES.test(r) && (words.length === 0 || prefix)) {
      prefix += r + ' ';
      continue;
    }
    if (words.length) {
      words[words.length - 1] += (DASHES.test(r) ? ' ' : '') + r;
    } else {
      prefix += r;
    }
  }
  if (prefix) {
    if (words.length) {
      words[words.length - 1] += prefix.trim();
    } else {
      words.push(prefix.trim());
    }
  }
  return words;
}

function splitLong(w) {
  if (coreLen(w) <= 13) return [w];
  if (w.indexOf('-') >= 0) {
    var parts = w.split('-');
    var allShort = true;
    for (var pIdx = 0; pIdx < parts.length; pIdx++) {
      if (coreLen(parts[pIdx]) > 13 || parts[pIdx].length === 0) {
        allShort = false;
        break;
      }
    }
    if (allShort) {
      return parts.map(function(p, i) { return i < parts.length - 1 ? p + '-' : p; });
    }
  }

  var ch = String(w).split('');
  var L = ch.length;
  var k = Math.ceil(L / 10);
  var size = L / k;
  var ok = function(j) {
    return j >= 3 && j <= L - 3 &&
           IS_WORDCHAR.test(ch[j - 1]) &&
           IS_WORDCHAR.test(ch[j]) &&
           !SOFT.test(ch[j]) &&
           !(ch[j + 1] && SOFT.test(ch[j + 1]) && !VOWELS.test(ch[j]));
  };
  var good = function(j) {
    return ok(j) && (VOWELS.test(ch[j - 1]) || SOFT.test(ch[j - 1]));
  };

  var cuts = [];
  var start = 0;
  for (var c = 1; c < k; c++) {
    var target = Math.round(c * size);
    var best = -1;
    var preds = [good, ok];
    for (var prIdx = 0; prIdx < preds.length; prIdx++) {
      var pred = preds[prIdx];
      for (var d = 0; d <= 4 && best < 0; d++) {
        var options = [target + d, target - d];
        for (var opIdx = 0; opIdx < options.length; opIdx++) {
          var j = options[opIdx];
          if (best < 0 && j > start + 2 && pred(j)) best = j;
        }
      }
      if (best >= 0) break;
    }
    if (best < 0) best = target;
    cuts.push(best);
    start = best;
  }

  var out = [];
  var a = 0;
  var allCuts = cuts.concat([L]);
  for (var cIdx = 0; cIdx < allCuts.length; cIdx++) {
    out.push(ch.slice(a, allCuts[cIdx]).join(''));
    a = allCuts[cIdx];
  }
  return out.map(function(p, i) { return i < out.length - 1 ? p + '-' : p; });
}

function endKind(w) {
  var s = String(w).replace(CLOSERS_TAIL, '');
  if (/[.!?…]$/.test(s)) return 'sent';
  if (/[,;:—–]$/.test(s) || / [—–-]$/.test(w)) return 'comma';
  return null;
}

function align(words, entries) {
  var out = [];
  var j = 0;
  for (var wIdx = 0; wIdx < words.length; wIdx++) {
    var w = words[wIdx];
    var k = coreKey(w);
    if (!k) {
      out.push(null);
      continue;
    }
    var found = -1;
    for (var d = 0; d < 4 && j + d < entries.length; d++) {
      if (coreKey(entries[j + d].w) === k) {
        found = j + d;
        break;
      }
    }
    if (found >= 0) {
      out.push(entries[found]);
      j = found + 1;
    } else {
      out.push(null);
    }
  }
  return out;
}

function swapCore(w, sw) {
  var ch = String(w).split('');
  var a = -1, b = -1;
  for (var k = 0; k < ch.length; k++) {
    if (IS_WORDCHAR.test(ch[k])) {
      if (a < 0) a = k;
      b = k;
    }
  }
  if (a < 0) return w;
  var first = ch[a];
  var rep = sw.replace(/ /g, ' ');
  if (first !== first.toLowerCase()) {
    rep = rep.charAt(0).toUpperCase() + rep.slice(1);
  }
  return ch.slice(0, a).join('') + rep + ch.slice(b + 1).join('');
}

function buildTokens(paras, opt) {
  var tokens = [];
  var sentStart = [0];
  var sentTr = [];
  var sentText = [];
  var sent = 0;

  paras.forEach(function(segs, pi) {
    segs.forEach(function(seg, si) {
      var words = mergePunct(seg.text.split(/\s+/).filter(Boolean));
      if (!words.length) return;
      var info = seg.words ? align(words, seg.words) : [];
      var lastSeg = (si === segs.length - 1);
      if (seg.forced) {
        sentTr[sent] = seg.tr || '';
        sentText[sent] = seg.text;
      }
      words.forEach(function(w, wi) {
        var inf = info[wi] || null;
        var disp = w;
        var swapped = false;
        if (opt.weave && inf && inf.sw && (inf.sd || 3) <= opt.density) {
          disp = swapCore(w, inf.sw);
          swapped = true;
        }
        var pieces = settings.split ? splitLong(disp) : [disp];
        var soft = pieces.length > 1 && pieces.map(function(x) { return x.replace(/-$/, ''); }).join('') === disp;
        var lastWord = (wi === words.length - 1);

        pieces.forEach(function(piece, k) {
          var lastPiece = (k === pieces.length - 1);
          var isLast = lastWord && lastPiece;
          var end = lastPiece ? endKind(piece) : null;
          if (seg.forced && end === 'sent' && !isLast) end = 'comma';
          if (isLast) {
            if (lastSeg) end = 'para';
            else if (seg.forced) end = 'sent';
          }
          tokens.push({
            w: piece,
            src: w,
            s: sent,
            p: pi,
            end: end,
            core: coreLen(piece),
            glue: !lastPiece,
            soft: soft && !lastPiece,
            num: /\d/.test(piece),
            g: (inf && inf.g) || '',
            lg: (inf && inf.lg) || '',
            lvl: (inf && inf.lvl) || '',
            lemma: (inf && inf.l) || '',
            sw: (inf && inf.sw) || '',
            swapped: swapped
          });
          if (end === 'sent' || end === 'para') {
            sent++;
            sentStart.push(tokens.length);
          }
        });
      });
    });
  });

  if (sentStart[sentStart.length - 1] >= tokens.length) {
    sentStart.pop();
  }
  return { tokens: tokens, sentStart: sentStart, sentTr: sentTr, sentText: sentText };
}

function tokenize(text) {
  return buildTokens(splitParagraphs(text).map(function(p) { return [{ text: p }]; }), {});
}

/* =====================================================================
   Focus Letter Calculation
   ===================================================================== */
function orpOffset(n) {
  return n <= 1 ? 0 : n <= 5 ? 1 : n <= 9 ? 2 : n <= 13 ? 3 : 4;
}

function splitWord(w) {
  var str = String(w || '');
  var a = -1, b = -1;
  for (var k = 0; k < str.length; k++) {
    if (IS_WORDCHAR.test(str.charAt(k))) {
      if (a < 0) a = k;
      b = k;
    }
  }
  if (a < 0) {
    var m = Math.floor((str.length - 1) / 2);
    return [str.slice(0, m), str.charAt(m), str.slice(m + 1)];
  }
  var n = b - a + 1;
  var off = settings.focus === 'orp' ? Math.min(orpOffset(n), n - 1) : Math.floor((n - 1) / 2);
  var p = a + off;
  return [str.slice(0, p), str.charAt(p), str.slice(p + 1)];
}

/* =====================================================================
   Application State
   ===================================================================== */
var $ = function(id) { return document.getElementById(id); };

var S = {
  doc: null,
  pack: null,
  hasSwap: false,
  tokens: [],
  sentStart: [],
  sentTr: [],
  sentText: [],
  cum: [0],
  i: 0,
  playing: false,
  timer: null,
  clockGeneration: 0,
  ramp: 0,
  trShown: false,
  shownAt: 0,
  glossT: null
};

var learnsTextLang = function() {
  return !S.pack || (S.pack.learn || S.pack.lang) === S.pack.lang;
};

function effMode() {
  if (!S.pack) return 'plain';
  if (settings.mode === 'weave' && !S.hasSwap) return 'full';
  return settings.mode;
}

function wantGloss(t) {
  var L = LV[t.lvl] || 0;
  if (!L || !t.g) return false;
  return learnsTextLang() ? L >= settings.level : L <= settings.level;
}

/* =====================================================================
   Timing
   ===================================================================== */
var baseMs = function() { return 60000 / settings.wpm; };
var playbackNow = function() {
  return (window.performance && performance.now) ? performance.now() : Date.now();
};

function mult(t) {
  if (!t) return 1;
  var w = typeof t === 'string' ? t : t.w;
  var m = 1;
  if (settings.smart && w) {
    var c = t.core !== undefined ? t.core : coreLen(w);
    if (c > 7) m += Math.min(0.7, (c - 7) * 0.07);
    if (t.num || /\d/.test(w)) m += 0.3;
    var end = t.end;
    if (!end) {
      if (/[.!?…]$/.test(w)) end = 'sent';
      else if (/[,;:—–]$/.test(w)) end = 'comma';
    }
    if (end === 'para') m += 1.6;
    else if (end === 'sent') m += 1.0;
    else if (end === 'comma') m += 0.45;
  }
  var mode = effMode();
  if (mode === 'full' && t.g) m += 0.5;
  else if (mode === 'delayed' && wantGloss(t)) m += settings.glossDelay / baseMs() + 0.9;
  else if (mode === 'weave' && t.swapped) m += 0.6;
  return m;
}

function trMult(t) {
  if (effMode() !== 'sentence' || !(t.end === 'sent' || t.end === 'para')) return 0;
  var tr = S.sentTr[t.s];
  if (!tr) return 0;
  return (tr.split(/\s+/).length + 1.5) * settings.trTime / 100;
}

function buildCum() {
  var c = new Float64Array(S.tokens.length + 1);
  for (var k = 0; k < S.tokens.length; k++) {
    c[k + 1] = c[k] + mult(S.tokens[k]) + trMult(S.tokens[k]);
  }
  S.cum = c;
}

/* =====================================================================
   Persistence (IndexedDB with memory fallback)
   ===================================================================== */
var DB = (function() {
  var dbp = null;
  var mem = { docs: {}, deck: {} };
  var idb = null;
  try {
    idb = window.indexedDB || window.webkitIndexedDB || window.mozIndexedDB;
  } catch (e) {}

  function open() {
    if (dbp) return dbp;
    dbp = new Promise(function(res) {
      if (!idb) return res(null);
      try {
        var r = idb.open('fastread', 2);
        r.onupgradeneeded = function() {
          var stores = ['docs', 'deck'];
          for (var sIdx = 0; sIdx < stores.length; sIdx++) {
            var s = stores[sIdx];
            if (!r.result.objectStoreNames.contains(s)) {
              r.result.createObjectStore(s, { keyPath: 'id' });
            }
          }
        };
        r.onsuccess = function() { res(r.result); };
        r.onerror = function() { res(null); };
        r.onblocked = function() { res(null); };
      } catch (e) {
        res(null);
      }
    });
    return dbp;
  }

  function tx(store, mode, fn) {
    return open().then(function(db) {
      if (!db) return fn(null, mem[store]);
      return new Promise(function(res, rej) {
        try {
          var t = db.transaction(store, mode);
          var st = t.objectStore(store);
          var r = fn(st, null);
          if (r && typeof r.then === 'function') {
            r.then(res, rej);
            return;
          }
          var done = false;
          var timer = setTimeout(function() {
            if (!done) { done = true; res(fn(null, mem[store])); }
          }, 3000);
          t.oncomplete = function() {
            if (!done) { done = true; clearTimeout(timer); res(r && r.result !== undefined ? r.result : r); }
          };
          t.onerror = function() {
            if (!done) { done = true; clearTimeout(timer); rej(t.error); }
          };
          t.onabort = function() {
            if (!done) { done = true; clearTimeout(timer); res(fn(null, mem[store])); }
          };
        } catch (err) {
          res(fn(null, mem[store]));
        }
      });
    });
  }

  return {
    put: function(s, o) {
      return tx(s, 'readwrite', function(st, m) {
        if (m) { m[o.id] = o; return o; }
        return st.put(o);
      });
    },
    get: function(s, id) {
      return tx(s, 'readonly', function(st, m) {
        if (m) return m[id] || null;
        return st.get(id);
      });
    },
    del: function(s, id) {
      return tx(s, 'readwrite', function(st, m) {
        if (m) { delete m[id]; return; }
        return st.delete(id);
      });
    },
    all: function(s) {
      return tx(s, 'readonly', function(st, m) {
        if (m) {
          var arr = [];
          for (var k in m) if (Object.prototype.hasOwnProperty.call(m, k)) arr.push(m[k]);
          return arr;
        }
        if (st.getAll) return st.getAll();
        return new Promise(function(resolve) {
          var resArr = [];
          var req = st.openCursor();
          req.onsuccess = function(e) {
            var cursor = e.target.result;
            if (cursor) {
              resArr.push(cursor.value);
              cursor.continue();
            } else {
              resolve(resArr);
            }
          };
          req.onerror = function() { resolve([]); };
        });
      });
    }
  };
})();

/* =====================================================================
   Document Loading
   ===================================================================== */
function isPack(o) {
  return o && typeof o === 'object' && String(o.format || '').indexOf('fastread-pack') === 0 && Array.isArray(o.paragraphs);
}

function stripHtmlTags(html) {
  if (!html) return '';
  return String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<(?:div|section|blockquote)[^>]*class=["'][^"']*epigraph[^"']*["'][^>]*>/gi, '\n\n[[EPIGRAPH]]\n\n')
    .replace(/<\/(?:div|section|blockquote)>/gi, '\n\n[[/EPIGRAPH]]\n\n')
    .replace(/<(?:p|div|span)[^>]*class=["'][^"']*text-author[^"']*["'][^>]*>/gi, '\n\n[[AUTHOR]]\n\n')
    .replace(/<\/(?:p|div|h[1-6]|li|blockquote|tr|section|article)>/gi, '\n\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, function(m, dec) { return String.fromCharCode(dec); })
    .replace(/<[^>]+>/g, '')
    .replace(/[ \t\u00A0]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function detectChaptersFromText(text) {
  var chapters = [];
  if (!text) return chapters;
  var rawParas = text.split(/\n{2,}/);
  var wordOffset = 0;
  for (var i = 0; i < rawParas.length; i++) {
    var p = rawParas[i].trim();
    if (!p) continue;
    var isCh = /^(?:(\d+\.\s+[^\n]{4,120})|(?:(?:глава|часть|пролог|эпилог|раздел|книга|act|chapter)\b[^\n]{0,80}))/i.test(p);
    if (isCh && p.length < 150) {
      chapters.push({
        title: p.replace(/\s+/g, ' ').slice(0, 100),
        startIndex: wordOffset
      });
    }
    var words = p.split(/\s+/).filter(Boolean);
    wordOffset += words.length;
  }
  return chapters;
}

function updateChapterTicks() {
  var ticksEl = $('chapterTicks');
  if (!ticksEl) return;
  ticksEl.innerHTML = '';
  if (!S.doc || !S.tokens.length) return;

  // loadDoc already detects chapters. Do not scan the entire book a second
  // time here when it legitimately has no chapter headings.

  var total = S.tokens.length;
  if (S.doc.chapters && S.doc.chapters.length) {
    S.doc.chapters.forEach(function(ch) {
      if (ch.startIndex > 0 && ch.startIndex < total) {
        var pct = (ch.startIndex / total) * 100;
        var tick = document.createElement('div');
        tick.className = 'ch-tick';
        tick.style.left = pct + '%';
        tick.title = ch.title;
        tick.onclick = function(e) {
          e.stopPropagation();
          seek(ch.startIndex);
        };
        ticksEl.appendChild(tick);
      }
    });
  }

  if (S.doc.bookmarks && S.doc.bookmarks.length) {
    S.doc.bookmarks.forEach(function(bPos) {
      var posNum = typeof bPos === 'object' ? bPos.pos : bPos;
      if (posNum > 0 && posNum < total) {
        var bPct = (posNum / total) * 100;
        var bTick = document.createElement('div');
        bTick.className = 'ch-tick bm-tick';
        bTick.style.left = bPct + '%';
        bTick.title = 'Закладка: слово ' + (posNum + 1);
        bTick.onclick = function(e) {
          e.stopPropagation();
          seek(posNum);
        };
        ticksEl.appendChild(bTick);
      }
    });
  }
}

function getChapterInfoAt(index) {
  if (!S.doc || !S.doc.chapters || !S.doc.chapters.length) return null;
  var chs = S.doc.chapters;
  var low = 0, high = chs.length;
  while (low < high) {
    var mid = Math.floor((low + high) / 2);
    if (chs[mid].startIndex <= index) low = mid + 1;
    else high = mid;
  }
  var chIdx = Math.max(0, low - 1);
  var curCh = chs[chIdx];
  var nextStart = (chIdx + 1 < chs.length) ? chs[chIdx + 1].startIndex : S.tokens.length;
  var wordsLeftInCh = Math.max(0, nextStart - index);
  return {
    title: curCh.title,
    index: chIdx + 1,
    total: chs.length,
    wordsLeft: wordsLeftInCh,
    estMin: wordsLeftInCh > 0 ? Math.max(1, Math.round(wordsLeftInCh * baseMs() / 60000)) : 0
  };
}

function computeTokens() {
  if (S.pack) {
    var paras = S.pack.paragraphs.map(function(p) {
      var sents = Array.isArray(p) ? p : p.sentences || [];
      return sents.map(function(s) {
        return { text: s.text || '', words: s.words, tr: s.tr, forced: true };
      });
    });
    var r = buildTokens(paras, { weave: effMode() === 'weave', density: settings.density });
    Object.assign(S, r);
  } else {
    // Ultra-fast clean tokenization for books with paragraph preservation & symbol merging
    var text = (S.doc && S.doc.text) || '';
    var rawParas = text.split(/\n{2,}/);
    var tokens = [];
    var sentStart = [];
    var lastSent = true;
    var pIdx = 0;
    var inEpigraph = false;

    for (var pi = 0; pi < rawParas.length; pi++) {
      var pText = rawParas[pi].trim();
      if (!pText) continue;

      var isAuthor = false;
      if (pText.indexOf('[[EPIGRAPH]]') >= 0) {
        inEpigraph = true;
        pText = pText.replace(/\[\[EPIGRAPH\]\]/g, '').trim();
      }
      if (pText.indexOf('[[AUTHOR]]') >= 0) {
        isAuthor = true;
        pText = pText.replace(/\[\[AUTHOR\]\]/g, '').trim();
      }
      var closeEpigraph = false;
      if (pText.indexOf('[[/EPIGRAPH]]') >= 0) {
        closeEpigraph = true;
        pText = pText.replace(/\[\[\/EPIGRAPH\]\]/g, '').trim();
      }
      if (!pText) {
        if (closeEpigraph) inEpigraph = false;
        continue;
      }

      var pType = isAuthor ? 'author' : (inEpigraph ? 'epigraph' : 'normal');
      var rawWords = pText.split(/\s+/);
      var prefix = '';

      for (var wi = 0; wi < rawWords.length; wi++) {
        var word = rawWords[wi];
        if (!word) continue;

        // Strip any residual HTML tags or attributes
        if (/<[^>]+>|class=["'][^"']*["']/i.test(word)) {
          word = word.replace(/<[^>]+>/g, '').replace(/\b(?:class|id|style)\s*=\s*["'][^"']*["']/gi, '').trim();
          if (!word) continue;
        }

        // Merge standalone punctuation (dashes, quotes, brackets)
        if (!/[a-zA-Zа-яА-ЯёЁ0-9]/.test(word)) {
          if (/^[—–\-«(„"']/.test(word)) {
            prefix = (prefix ? prefix + ' ' : '') + word;
            continue;
          } else if (tokens.length && /^[»)”’"']/.test(word)) {
            tokens[tokens.length - 1].w += word;
            continue;
          } else if (tokens.length && /^[—–\-]/.test(word)) {
            tokens[tokens.length - 1].w += ' ' + word;
            tokens[tokens.length - 1].end = 'comma';
            continue;
          }
          // Pure decoration like ***
          continue;
        }

        if (prefix) {
          word = prefix + ' ' + word;
          prefix = '';
        }

        if (lastSent) sentStart.push(tokens.length);

        var end = null;
        if (/[.!?…]$/.test(word)) {
          end = 'sent';
          lastSent = true;
        } else {
          lastSent = false;
          if (/[,;:—–]$/.test(word)) end = 'comma';
        }

        tokens.push({
          w: word,
          end: end,
          p: pIdx,
          pType: pType
        });
      }

      if (tokens.length && tokens[tokens.length - 1].p === pIdx) {
        tokens[tokens.length - 1].end = 'para';
        lastSent = true;
        pIdx++;
      }
      if (closeEpigraph || isAuthor) inEpigraph = false;
    }

    if (!sentStart.length) sentStart.push(0);
    Object.assign(S, {
      tokens: tokens,
      sentStart: sentStart,
      sentTr: [],
      sentText: []
    });
  }
  $('scrub').max = Math.max(0, S.tokens.length - 1);
  updateChapterTicks();
}

function loadDoc(doc) {
  pause();
  S.doc = doc;
  S.pack = isPack(doc.pack) ? doc.pack : null;
  S.hasSwap = !!(S.pack && S.pack.paragraphs.some(function(p) {
    var sents = Array.isArray(p) ? p : p.sentences || [];
    return sents.some(function(s) {
      return (s.words || []).some(function(w) { return w.sw; });
    });
  }));

  if (S.pack) {
    document.body.classList.add('has-pack');
  } else {
    document.body.classList.remove('has-pack');
  }

  // Auto-clean any residual HTML tags in doc.text
  if (doc.text && /<[^>]+>|class=["'][^"']*["']/i.test(doc.text)) {
    doc.text = stripHtmlTags(doc.text);
    try { DB.put('docs', doc); } catch(e) {}
  }

  // Auto-detect chapters if missing
  if ((!doc.chapters || !doc.chapters.length) && doc.text) {
    doc.chapters = detectChaptersFromText(doc.text);
    if (doc.chapters.length) {
      try { DB.put('docs', doc); } catch(e) {}
    }
  }

  computeTokens();
  var savedPos = LS.get('fr.pos.' + doc.id, null);
  var initialPos = (savedPos !== null && savedPos !== undefined && !isNaN(savedPos)) ? parseInt(savedPos, 10) : (doc.pos || 0);
  if (isNaN(initialPos) || initialPos < 0) initialPos = 0;
  S.i = Math.min(initialPos, Math.max(0, S.tokens.length - 1));
  $('docTitle').textContent = doc.title || '';
  document.title = doc.title ? doc.title + ' — FastRead' : 'FastRead';
  var bTitle = $('bookTitleDisplay');
  if (bTitle) bTitle.textContent = doc.title || 'FastRead';
  LS.set('fr.last', doc.id);
  syncModeUI();
  render();
}

function retokenize() {
  if (!S.doc) return;
  var frac = S.tokens.length ? S.i / S.tokens.length : 0;
  hideTr();
  computeTokens();
  S.i = Math.min(Math.round(frac * S.tokens.length), Math.max(0, S.tokens.length - 1));
  render();
}

function setMode(m) {
  settings.mode = m;
  saveSettings();
  retokenize();
  syncModeUI();
  if (S.pack) {
    var item = MODES.find(function(x) { return x.id === effMode(); });
    if (item) toast(item.name);
  }
}

/* =====================================================================
   Rendering Engine
   ===================================================================== */
function fmtTime(ms) {
  var s = Math.round(ms / 1000);
  if (s < 60) return s + ' с';
  var m = Math.round(s / 60);
  if (m < 60) return m + ' мин';
  return Math.floor(m / 60) + ' ч ' + (m % 60) + ' мин';
}

var lastUiUpdate = 0;

function render(forceFull) {
  var t = S.tokens[S.i];
  var wordStr = t ? (typeof t === 'string' ? t : t.w) : '';
  var parts = splitWord(wordStr);
  // Restore the pre-1.1 DOM update path together with its timer. The 1.1
  // changes regressed visibility on the user's BOOX; their effects are not isolated.
  $('wl').textContent = parts[0];
  $('wp').textContent = parts[1];
  $('wr').textContent = parts[2];

  if (t && t.swapped) {
    $('word').classList.add('swapped');
  } else {
    $('word').classList.remove('swapped');
  }

  renderGloss(t);
  S.shownAt = playbackNow();

  // Restore the pre-1.1 periodic UI updates. Their effect on this device's
  // refresh heuristics has not been isolated from the other 1.1 changes.
  var now = S.shownAt;
  if (!S.playing || forceFull || (now - lastUiUpdate >= 750)) {
    lastUiUpdate = now;
    var n = S.tokens.length;
    $('pos').textContent = (n ? S.i + 1 : 0) + ' / ' + n;
    $('scrub').value = S.i;

    var wordsLeft = Math.max(0, n - S.i);
    var estMs = wordsLeft * baseMs() * (settings.smart ? 1.15 : 1);
    $('left').textContent = n ? '≈ ' + fmtTime(estMs) : '—';
    $('stats').textContent = n ? Math.round(S.i / Math.max(1, n - 1) * 100) + '%' : '';

    // Chapter display
    var ch = getChapterInfoAt(S.i);
    var chDisplay = $('chapterTitleDisplay');
    var chSep = $('chapterSep');
    var chInfoEl = $('chInfo');
    if (ch && ch.title) {
      if (chDisplay) {
        chDisplay.textContent = ch.title;
        chDisplay.style.display = 'inline';
      }
      if (chSep) chSep.style.display = 'inline';
      if (chInfoEl) {
        chInfoEl.style.display = 'block';
        chInfoEl.textContent = 'Глава: осталось ' + ch.wordsLeft + ' сл. (~' + ch.estMin + ' мин)';
      }
    } else {
      if (chDisplay) {
        chDisplay.textContent = '';
        chDisplay.style.display = 'none';
      }
      if (chSep) chSep.style.display = 'none';
      if (chInfoEl) chInfoEl.style.display = 'none';
    }

    // Bookmark button active indicator
    var markBtn = $('markBtn');
    if (markBtn) {
      var curPos = S.i;
      var isBm = S.doc && S.doc.bookmarks && S.doc.bookmarks.some(function(b) {
        var p = typeof b === 'object' ? b.pos : b;
        return Math.abs(p - curPos) <= 3;
      });
      if (isBm) {
        markBtn.classList.add('bookmarked');
        markBtn.title = 'Закладка установлена (нажмите для снятия)';
      } else {
        markBtn.classList.remove('bookmarked');
        markBtn.title = 'Поставить закладку';
      }
    }

    if (!S.playing) renderContext();
    savePosSoon();
  }
}

function glossFor(t) {
  if (!t || !S.pack) return null;
  if (!S.playing) {
    if (t.swapped) return { text: coreText(t.src) };
    return t.g ? { text: t.g } : null;
  }
  var mode = effMode();
  if (mode === 'full') return t.g ? { text: t.g } : null;
  if (mode === 'delayed') return wantGloss(t) ? { text: t.g, delay: settings.glossDelay } : null;
  return null;
}

function renderGloss(t) {
  var el = $('gloss');
  clearTimeout(S.glossT);
  var gl = glossFor(t);
  if (!gl) {
    el.classList.remove('show');
    el.textContent = '';
    return;
  }
  el.textContent = gl.text;
  if (gl.delay) {
    el.classList.remove('show');
    S.glossT = setTimeout(function() { el.classList.add('show'); }, gl.delay);
  } else {
    el.classList.add('show');
  }
}

function showTr(text) {
  $('trline').textContent = text;
  $('stage').classList.add('tr-on');
}

function hideTr() {
  $('stage').classList.remove('tr-on');
  S.trShown = false;
}

function renderContext() {
  var box = $('context');
  if (!settings.context || !S.tokens.length) {
    box.hidden = true;
    return;
  }
  var t = S.tokens[S.i];
  var a = Math.max(0, S.i - 45);
  var b = Math.min(S.tokens.length, S.i + 65);

  var showTrInCtx = S.pack && effMode() === 'sentence';
  var frag = document.createDocumentFragment();
  var firstTk = S.tokens[a];
  var curP = document.createElement('p');
  if (firstTk && firstTk.pType === 'epigraph') {
    curP.className = 'ctx-epigraph';
  } else if (firstTk && firstTk.pType === 'author') {
    curP.className = 'ctx-author';
  } else {
    curP.className = 'ctx-p';
  }
  frag.appendChild(curP);

  var lastP = (firstTk && firstTk.p) !== undefined ? firstTk.p : 0;

  for (var k = a; k < b; k++) {
    var tk = S.tokens[k];
    if (!tk) continue;
    if (tk.p !== undefined && tk.p !== lastP) {
      curP = document.createElement('p');
      if (tk.pType === 'epigraph') {
        curP.className = 'ctx-epigraph';
      } else if (tk.pType === 'author') {
        curP.className = 'ctx-author';
      } else {
        curP.className = 'ctx-p';
      }
      frag.appendChild(curP);
      lastP = tk.p;
    }
    var wordText = typeof tk === 'string' ? tk : (tk.soft ? tk.w.replace(/-$/, '') : (tk.w || ''));
    var sp = document.createElement('span');
    sp.textContent = wordText;
    sp.setAttribute('data-i', k);
    if (tk.swapped) {
      sp.classList.add('sw');
      sp.title = coreText(tk.src);
    } else if (tk.g) {
      sp.title = tk.g;
    }
    if (k === S.i) sp.classList.add('cur');
    curP.appendChild(sp);
    if (!tk.glue) curP.appendChild(document.createTextNode(' '));

    if (showTrInCtx && t && (tk.end === 'sent' || tk.end === 'para') && S.sentTr && S.sentTr[tk.s] && tk.s < t.s) {
      var tr = document.createElement('span');
      tr.className = 'ctr';
      tr.textContent = S.sentTr[tk.s];
      curP.appendChild(tr);
    }
  }

  box.innerHTML = '';
  box.appendChild(frag);
  box.hidden = false;
  var cur = box.querySelector('.cur');
  if (cur) box.scrollTop = cur.offsetTop - box.offsetTop - box.clientHeight / 2 + 12;
}

/* =====================================================================
   Playback Controls
   ===================================================================== */
var ICON_PLAY = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z"/></svg>';
var ICON_PAUSE = '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>';

var playbackStats = null;
var lastCheckpoint = 0;

function resetPlaybackStats() {
  var now = playbackNow();
  playbackStats = { startedAt: now, wordAt: now, elapsedMs: 0, completedTokens: 0,
    maxLateMs: 0, samples: [], sampleNext: 0, sampleCount: 0 };
  // Recycle a bounded set of records instead of allocating and shifting an
  // array at every word. Snapshots are copied only when diagnostics is opened.
  for (var i = 0; i < 120; i++) playbackStats.samples.push({ index: 0, word: '', durationMs: 0 });
}

// Software timing only; this does not measure physical E-Ink visibility.
function getPlaybackDiagnostics() {
  var p = playbackStats;
  var samples = [];
  if (p) {
    var first = p.sampleCount === 120 ? p.sampleNext : 0;
    for (var i = 0; i < p.sampleCount; i++) {
      var sample = p.samples[(first + i) % 120];
      samples.push({ index: sample.index, word: sample.word, durationMs: sample.durationMs });
    }
  }
  return {
    requestedWpm: settings.wpm,
    smart: settings.smart,
    minWordMs: settings.minWordMs || 0,
    clock: 'timeout',
    completedTokens: p ? p.completedTokens : 0,
    elapsedMs: p ? p.elapsedMs : 0,
    observedTokenWpm: p && p.elapsedMs ? Math.round(p.completedTokens * 60000 / p.elapsedMs) : 0,
    maxLateMs: p ? p.maxLateMs : 0,
    samples: samples
  };
}

function nativeReading(active) {
  try {
    if (window.AndroidBridge && window.AndroidBridge.setReading) window.AndroidBridge.setReading(active);
  } catch (e) {}
}

function showPerformance() {
  var p = getPlaybackDiagnostics();
  var lines = [
    'Текущая настройка: ' + p.requestedWpm + ' сл/мин',
    p.completedTokens ? 'Последнее чтение: ' + p.observedTokenWpm + ' токенов/мин (' + p.completedTokens + ' завершено)' : 'Для замера сначала запустите чтение.',
    'Максимальное опоздание таймера: ' + Math.round(p.maxLateMs) + ' мс',
    'Минимум на слово: ' + (p.minWordMs ? p.minWordMs + ' мс' : 'выключен')
  ];
  try {
    if (window.AndroidBridge && window.AndroidBridge.getRenderStats) {
      var r = JSON.parse(window.AndroidBridge.getRenderStats());
      lines.push('Проходы Android: ' + r.drawCount + '; наибольший интервал — ' + r.maxGapMs.toFixed(1) + ' мс');
      lines.push('Устройство: ' + r.device + ', Android API ' + r.sdk);
    }
  } catch (e) {}
  lines.push('Это программный замер. Видимость каждого слова на E-Ink проверяется по экрану.');
  $('performanceReport').textContent = lines.join('\n');
}

function updateScreenCapabilities() {
  var button = $('screenRefreshBtn');
  button.disabled = true;
  try {
    if (window.AndroidBridge && window.AndroidBridge.getEinkCapabilities) {
      var caps = JSON.parse(window.AndroidBridge.getEinkCapabilities());
      button.disabled = !caps.fullRefresh;
      $('screenRefreshInfo').textContent = caps.fullRefresh
        ? 'Полная очистка по нажатию. Режим A2 автоматически не меняется.'
        : 'Прошивка не предоставляет приложению распознанную команду очистки. Используйте системную кнопку обновления.';
    }
  } catch (e) {}
}

function requestScreenRefresh() {
  if ($('screenRefreshBtn').disabled) return;
  pause();
  closeModals();
  try {
    window.AndroidBridge.requestFullRefresh();
  } catch (e) { toast('Не удалось отправить команду очистки экрана'); }
}

function onScreenRefresh(sent) {
  // Command delivery is not an acknowledgement of physical waveform completion.
  if (!sent) toast('Команда очистки недоступна на этой прошивке');
}

function cancelPlaybackClock() {
  clearTimeout(S.timer);
  S.timer = null;
  S.clockGeneration++;
}

function schedule() {
  cancelPlaybackClock();
  if (!S.playing) return;
  var d = (S.trShown ? trMult(S.tokens[S.i]) : mult(S.tokens[S.i])) * baseMs();
  if (!S.trShown && settings.ramp && S.ramp > 0) {
    d *= 1 + S.ramp * 0.12;
    S.ramp--;
  }
  if (d < 25) d = 25;
  if (!S.trShown) d = Math.max(d, Math.min(500, Math.max(0, Number(settings.minWordMs) || 0)));
  var due = playbackNow() + d;
  var generation = S.clockGeneration;
  function advance() {
    if (!S.playing || generation !== S.clockGeneration) return;
    var now = playbackNow();
    if (playbackStats) playbackStats.maxLateMs = Math.max(playbackStats.maxLateMs, now - due);
    // Restore the original full-duration setTimeout per token. In particular,
    // do not drive BOOX WebView rendering from requestAnimationFrame.
    tick();
  }
  S.timer = setTimeout(advance, d);
}

function tick() {
  if (!S.playing) return;
  var t = S.tokens[S.i];
  var tm = !S.trShown ? trMult(t) : 0;
  if (tm) {
    showTr(S.sentTr[t.s]);
    S.trShown = true;
    schedule();
    return;
  }
  hideTr();
  var now = playbackNow();
  if (playbackStats) {
    playbackStats.completedTokens++;
    playbackStats.elapsedMs += now - playbackStats.wordAt;
    var sample = playbackStats.samples[playbackStats.sampleNext];
    sample.index = S.i;
    sample.word = t.w;
    sample.durationMs = now - playbackStats.wordAt;
    playbackStats.sampleNext = (playbackStats.sampleNext + 1) % 120;
    playbackStats.sampleCount = Math.min(120, playbackStats.sampleCount + 1);
    playbackStats.wordAt = now;
  }
  if (S.i >= S.tokens.length - 1) {
    pause();
    toast('Конец текста');
    return;
  }
  S.i++;
  render();
  schedule();
  if (now - lastCheckpoint >= 5000) {
    lastCheckpoint = now;
    // No IndexedDB clone of the entire book in the playback loop.
    if (S.doc && !S.doc.demo) LS.set('fr.pos.' + S.doc.id, S.i);
  }
}

function play() {
  if (S.playing) return;
  if (!S.tokens.length) {
    openModal('newModal');
    return;
  }
  if (S.i >= S.tokens.length - 1) S.i = 0;
  hideTr();
  S.playing = true;
  S.ramp = 5;
  clearTimeout(saveT);
  resetPlaybackStats();
  lastCheckpoint = playbackNow();
  nativeReading(true);
  document.body.classList.add('playing');
  $('context').hidden = true;
  $('playBtn').innerHTML = ICON_PAUSE;
  render(true);
  schedule();
}

function pause() {
  S.playing = false;
  cancelPlaybackClock();
  nativeReading(false);
  hideTr();
  document.body.classList.remove('playing');
  $('playBtn').innerHTML = ICON_PLAY;
  if (S.tokens.length) {
    render(true);
  }
  savePos();
}

var toggle = function() { S.playing ? pause() : play(); };

function seek(i) {
  if (!S.tokens.length) return;
  hideTr();
  S.i = Math.max(0, Math.min(S.tokens.length - 1, i));
  render(true);
  if (S.playing) {
    S.ramp = 3;
    resetPlaybackStats();
    schedule();
  }
}

var step = function(d) { seek(S.i + d); };

function sentence(dir) {
  if (!S.tokens.length) return;
  if (!S.sentStart || !S.sentStart.length) {
    step(dir * 25);
    return;
  }
  if (dir < 0) {
    for (var k = S.sentStart.length - 1; k >= 0; k--) {
      if (S.sentStart[k] < S.i - 1) {
        seek(S.sentStart[k]);
        return;
      }
    }
    seek(0);
  } else {
    for (var j = 0; j < S.sentStart.length; j++) {
      if (S.sentStart[j] > S.i) {
        seek(S.sentStart[j]);
        return;
      }
    }
    seek(S.tokens.length - 1);
  }
}

function setWpm(v) {
  settings.wpm = Math.max(50, Math.min(1500, Math.round(v / 25) * 25));
  saveSettings();
  $('wpm').textContent = settings.wpm;
  $('sWpm').value = settings.wpm;
  $('sWpmV').textContent = settings.wpm;
  render(true);
  if (S.playing) {
    schedule();
  }
}

/* ---------- Save position ---------- */
var saveT = null;
var lastQueuedSaveDoc = null;
var lastQueuedSaveKey = '';
function savePosSoon() {
  clearTimeout(saveT);
  // Playback already checkpoints the small position key every five seconds.
  // A punctuation pause must not trigger cloning a multi-megabyte book.
  if (S.playing) return;
  saveT = setTimeout(savePos, 1500);
}
function savePos() {
  clearTimeout(saveT);
  if (!S.doc || S.doc.demo) return;
  S.doc.pos = S.i;
  S.doc.total = S.tokens.length;
  S.doc.updated = Date.now();
  LS.set('fr.pos.' + S.doc.id, S.i);
  LS.set('fr.total.' + S.doc.id, S.tokens.length);
  LS.set('fr.updated.' + S.doc.id, S.doc.updated);
  var doc = S.doc;
  var key = JSON.stringify([S.i, S.tokens.length, doc.bookmarks || [], doc.exampleId || '']);
  if (lastQueuedSaveDoc === doc && lastQueuedSaveKey === key) return;
  lastQueuedSaveDoc = doc;
  lastQueuedSaveKey = key;
  try {
    DB.put('docs', doc).catch(function() {
      if (lastQueuedSaveDoc === doc && lastQueuedSaveKey === key) lastQueuedSaveDoc = null;
    });
  } catch(e) { lastQueuedSaveDoc = null; }
}

document.addEventListener('visibilitychange', function() {
  if (document.hidden) {
    if (S.playing) pause();
    savePos();
  }
});
window.addEventListener('beforeunload', savePos);

/* =====================================================================
   Deck ("Не знаю" Leitner SRS)
   ===================================================================== */
var INTERVALS = [10 * 60e3, 864e5, 3 * 864e5, 7 * 864e5, 16 * 864e5, 35 * 864e5, 90 * 864e5];

function sentenceText(s) {
  if (S.sentText[s]) return S.sentText[s];
  var a = S.sentStart[s] || 0;
  var b = S.sentStart[s + 1] || S.tokens.length;
  var out = '';
  for (var k = a; k < b; k++) {
    var t = S.tokens[k];
    out += t.glue ? (t.soft ? t.w.replace(/-$/, '') : t.w) : t.w + ' ';
  }
  return out.trim();
}

function makeCard(t) {
  var src = coreText(t.src);
  var front, back;
  if (S.pack && !learnsTextLang()) {
    front = t.swapped ? t.sw : (t.lg || t.g || src);
    back = (t.swapped || t.g) ? (t.lemma || src) : '';
  } else {
    front = t.lemma || src.toLowerCase();
    back = t.lg || t.g || '';
  }
  return {
    id: front.toLowerCase(),
    front: front,
    back: back,
    word: src,
    ctx: sentenceText(t.s),
    ctxTr: S.sentTr[t.s] || '',
    doc: (S.doc && S.doc.title) || '',
    added: Date.now(),
    box: 0,
    due: Date.now()
  };
}

async function markUnknown() {
  if (!S.tokens.length) return;
  var i = S.i;
  var now = (window.performance && performance.now) ? performance.now() : Date.now();
  if (S.playing && now - S.shownAt < 250 && i > 0) i--;
  var card = makeCard(S.tokens[i]);
  if (!card.front) {
    toast('Тут нечего сохранять');
    return;
  }
  var existing = await DB.get('deck', card.id).catch(function() { return null; });
  if (existing) {
    toast('Уже в колоде: ' + card.front);
    return;
  }
  await DB.put('deck', card).catch(function() {});
  toast('+ ' + card.front + (card.back ? ' — ' + card.back : ''));
  updateDeckBadge();
}

async function updateDeckBadge() {
  var cards = (await DB.all('deck').catch(function() { return []; })) || [];
  if (!Array.isArray(cards)) cards = [];
  var due = cards.filter(function(c) { return c.due <= Date.now(); }).length;
  var badge = $('deckBadge');
  if (badge) badge.textContent = due ? due : '';
}

var R = { active: false, queue: [], idx: 0, revealed: false, practice: false, wrong: [] };

async function renderDeck() {
  R.active = false;
  var body = $('deckBody');
  var cards = (await DB.all('deck').catch(function() { return []; })) || [];
  if (!Array.isArray(cards)) cards = [];
  cards.sort(function(a, b) { return b.added - a.added; });
  var due = cards.filter(function(c) { return c.due <= Date.now(); });
  if (!cards.length) {
    body.innerHTML = '<div class="empty">Пока пусто.<br>Во время чтения жми кнопку <b>D</b> или закладку справа внизу, чтобы добавить слово.</div>';
    return;
  }
  var hard = cards.filter(function(c) { return (c.box || 0) <= 1; });
  body.innerHTML = '<div class="deck-top"><span class="msg">' + cards.length + ' слов · к повторению: ' + due.length + '</span>' +
    '<button class="ghost" id="exportBtn">Экспорт CSV</button>' +
    '<button class="primary" id="reviewBtn" ' + (due.length ? '' : 'disabled') + '>Повторить (' + due.length + ')</button></div>' +
    '<div class="deck-top"><span class="msg">Тренировка:</span>' +
    '<button class="ghost" id="hardBtn" ' + (hard.length ? '' : 'disabled') + '>Трудные (' + hard.length + ')</button>' +
    '<button class="ghost" id="allBtn">Все (' + cards.length + ')</button></div><div id="cardList"></div>';

  var list = body.querySelector('#cardList');
  cards.forEach(function(c) {
    var row = document.createElement('div');
    row.className = 'card-row';
    row.innerHTML = '<div class="f"></div><button class="del">Удалить</button>';
    var f = row.querySelector('.f');
    f.textContent = c.front + (c.back ? ' — ' + c.back : '');
    var ctxDiv = document.createElement('div');
    ctxDiv.className = 'c';
    ctxDiv.textContent = c.ctx;
    f.appendChild(ctxDiv);
    row.querySelector('.del').onclick = async function() {
      await DB.del('deck', c.id);
      renderDeck();
      updateDeckBadge();
    };
    list.appendChild(row);
  });

  body.querySelector('#exportBtn').onclick = function() { exportDeck(cards); };
  var start = function(queue, practice) {
    R.queue = queue;
    R.idx = 0;
    R.revealed = false;
    R.active = true;
    R.practice = practice;
    R.wrong = [];
    renderReview();
  };
  body.querySelector('#reviewBtn').onclick = function() {
    start(due.sort(function(a, b) { return a.due - b.due; }), false);
  };
  body.querySelector('#hardBtn').onclick = function() { start(shuffle(hard), true); };
  body.querySelector('#allBtn').onclick = function() { start(shuffle(cards.slice()), true); };
}

function shuffle(a) {
  for (var i = a.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var temp = a[i]; a[i] = a[j]; a[j] = temp;
  }
  return a;
}

function esc(s) {
  return String(s || '').replace(/[&<>"]/g, function(c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
  });
}

function highlight(ctx, word) {
  var e = esc(ctx);
  if (!word) return e;
  var i = ctx.toLowerCase().indexOf(word.toLowerCase());
  if (i < 0) return e;
  return esc(ctx.slice(0, i)) + '<b>[' + esc(ctx.slice(i, i + word.length)) + ']</b>' + esc(ctx.slice(i + word.length));
}

function renderReview() {
  var body = $('deckBody');
  if (R.idx >= R.queue.length) {
    R.active = false;
    var wrong = R.wrong || [];
    body.innerHTML = '<div class="empty">' + (R.practice ? 'Тренировка окончена' : 'Готово — на сегодня всё!') +
      (wrong.length ? '<br>Ошибок: ' + wrong.length : '') + '<br><br>' +
      (wrong.length ? '<button class="primary" id="againBtn">Повторить ошибки</button> ' : '') +
      '<button class="ghost" id="backDeck">К списку</button></div>';
    body.querySelector('#backDeck').onclick = renderDeck;
    var again = body.querySelector('#againBtn');
    if (again) {
      again.onclick = function() {
        R.queue = shuffle(wrong.slice());
        R.idx = 0;
        R.revealed = false;
        R.active = true;
        R.practice = true;
        R.wrong = [];
        renderReview();
      };
    }
    updateDeckBadge();
    return;
  }

  var c = R.queue[R.idx];
  body.innerHTML = '<div class="review"><div class="count">' + (R.practice ? 'тренировка · ' : '') + (R.idx + 1) + ' / ' + R.queue.length + '</div>' +
    '<div class="front">' + esc(c.front) + '</div>' +
    '<div class="ctx">' + highlight(c.ctx, c.word) + '</div>' +
    (R.revealed
      ? '<div class="back">' + esc(c.back || '—') + '</div>' + (c.ctxTr ? '<div class="ctxtr">' + esc(c.ctxTr) + '</div>' : '') +
        '<div class="btns"><button class="ghost" id="rNo">Не помню (1)</button><button class="primary" id="rYes">Помню (2)</button></div>'
      : '<div class="btns"><button class="primary" id="rShow">Показать ответ (Пробел)</button></div>'
    ) + '</div>';

  if (R.revealed) {
    body.querySelector('#rNo').onclick = function() { answer(false); };
    body.querySelector('#rYes').onclick = function() { answer(true); };
  } else {
    body.querySelector('#rShow').onclick = function() {
      R.revealed = true;
      renderReview();
    };
  }
}

async function answer(ok) {
  var c = R.queue[R.idx];
  if (!ok) (R.wrong || (R.wrong = [])).push(c);
  if (!R.practice) {
    c.box = ok ? Math.min(c.box + 1, INTERVALS.length - 1) : 0;
    c.due = Date.now() + INTERVALS[ok ? c.box : 0];
    await DB.put('deck', c).catch(function() {});
  }
  R.idx++;
  R.revealed = false;
  renderReview();
}

function exportDeck(cards) {
  var q = function(s) { return '"' + String(s || '').replace(/"/g, '""') + '"'; };
  var lines = cards.map(function(c) {
    return [c.front, c.back, c.ctx, c.ctxTr, c.doc].map(q).join(';');
  });
  var blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  var a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'fastread-words.csv';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(function() { URL.revokeObjectURL(a.href); }, 2000);
}

/* =====================================================================
   File Parsing (Offline EPUB, FB2, TXT, JSON)
   ===================================================================== */
function decodeText(buf) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf);
  } catch (e) {
    return new TextDecoder('windows-1251').decode(buf);
  }
}

function decodeXml(buf) {
  var head = new TextDecoder('ascii').decode(buf.slice(0, 400));
  var m = head.match(/encoding=["']([\w-]+)["']/i);
  if (m) {
    try {
      return new TextDecoder(m[1].toLowerCase()).decode(buf);
    } catch (e) {}
  }
  return decodeText(buf);
}

function fastHtmlToText(html) {
  if (!html) return '';
  return String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<(?:div|section|blockquote)[^>]*class=["'][^"']*epigraph[^"']*["'][^>]*>/gi, '\n\n[[EPIGRAPH]]\n\n')
    .replace(/<\/(?:div|section|blockquote)>/gi, '\n\n[[/EPIGRAPH]]\n\n')
    .replace(/<(?:p|div|span)[^>]*class=["'][^"']*text-author[^"']*["'][^>]*>/gi, '\n\n[[AUTHOR]]\n\n')
    .replace(/<\/(p|div|h[1-6]|li|blockquote|tr|section|article)>/gi, '\n\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, function(m, dec) { return String.fromCharCode(dec); })
    .replace(/<[^>]+>/g, '')
    .replace(/[ \t\u00A0]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function parseFb2(xml) {
  var doc = new DOMParser().parseFromString(xml, 'application/xml');
  var parseErr = doc.querySelector ? doc.querySelector('parsererror') : null;
  if (parseErr) {
    var pList = [];
    var pRe = /<(?:p|v|subtitle)[^>]*>([\s\S]*?)<\/(?:p|v|subtitle)>/gi;
    var pm;
    while ((pm = pRe.exec(xml)) !== null) {
      var clean = fastHtmlToText(pm[1]);
      if (clean) pList.push(clean);
    }
    var tm = xml.match(/<book-title[^>]*>([^<]+)<\/book-title>/i);
    return { title: tm ? tm[1].trim() : '', text: pList.join('\n\n') };
  }

  var titleEl = doc.getElementsByTagName('book-title')[0];
  var title = titleEl ? titleEl.textContent.trim() : '';
  var bodies = doc.getElementsByTagName('body');
  var parts = [];

  for (var b = 0; b < bodies.length; b++) {
    var body = bodies[b];
    if (body.getAttribute('name') === 'notes') continue;
    var all = body.getElementsByTagName('*');
    for (var elIdx = 0; elIdx < all.length; elIdx++) {
      var el = all[elIdx];
      var tag = (el.localName || el.nodeName || '').toLowerCase();
      if (tag === 'p' || tag === 'v' || tag === 'subtitle' || tag === 'text-author') {
        var t = el.textContent.replace(/\s+/g, ' ').trim();
        if (t) parts.push(t);
      }
    }
  }
  return { title: title, text: parts.join('\n\n') };
}

function normPath(p) {
  var segs = p.split('/');
  var out = [];
  for (var i = 0; i < segs.length; i++) {
    var seg = segs[i];
    if (seg === '..') out.pop();
    else if (seg && seg !== '.') out.push(seg);
  }
  return out.join('/');
}

async function parseEpub(buf) {
  if (!window.JSZip) throw new Error('JSZip недоступен');
  var zip = await window.JSZip.loadAsync(buf);
  var xml = function(s) { return new DOMParser().parseFromString(s, 'application/xml'); };

  var contFile = zip.file('META-INF/container.xml');
  if (!contFile) {
    zip.forEach(function(relPath, entry) {
      if (relPath.toLowerCase() === 'meta-inf/container.xml') contFile = entry;
    });
  }
  if (!contFile) throw new Error('Некорректный EPUB: container.xml не найден');

  var contXmlStr = await contFile.async('string');
  var cont = xml(contXmlStr);
  var rootfiles = cont.getElementsByTagName('rootfile');
  if (!rootfiles.length && cont.getElementsByTagNameNS) {
    rootfiles = cont.getElementsByTagNameNS('*', 'rootfile');
  }
  var opfPath = '';
  if (rootfiles && rootfiles.length) {
    opfPath = rootfiles[0].getAttribute('full-path') || '';
  }
  if (!opfPath) {
    var match = contXmlStr.match(/full-path\s*=\s*["']([^"']+)["']/i);
    if (match) opfPath = match[1];
  }
  if (!opfPath) throw new Error('Некорректный EPUB: отсутствует full-path в container.xml');

  opfPath = decodeURIComponent(opfPath).replace(/^\//, '').trim();
  var opfFile = zip.file(opfPath);
  if (!opfFile) {
    var lowerOpf = opfPath.toLowerCase();
    zip.forEach(function(relPath, entry) {
      if (relPath.toLowerCase() === lowerOpf) opfFile = entry;
    });
  }
  if (!opfFile) throw new Error('Некорректный EPUB: файл OPF не найден (' + opfPath + ')');

  var base = opfPath.indexOf('/') >= 0 ? opfPath.slice(0, opfPath.lastIndexOf('/') + 1) : '';
  var opfXmlStr = await opfFile.async('string');
  var opf = xml(opfXmlStr);

  var titleEl = (opf.getElementsByTagNameNS && opf.getElementsByTagNameNS('*', 'title')[0]) || opf.getElementsByTagName('title')[0] || opf.getElementsByTagName('dc:title')[0];
  var title = titleEl ? titleEl.textContent.trim() : '';
  if (!title) {
    var tm = opfXmlStr.match(/<dc:title[^>]*>([^<]+)<\/dc:title>/i);
    if (tm) title = tm[1].trim();
  }

  var manifest = {};
  var items = opf.getElementsByTagName('item');
  if (!items.length && opf.getElementsByTagNameNS) items = opf.getElementsByTagNameNS('*', 'item');
  if (!items.length) {
    var itemRe = /<(?:\w+:)?item\s+[^>]*\/?>/gi;
    var im;
    while ((im = itemRe.exec(opfXmlStr)) !== null) {
      var tagStr = im[0];
      var idM = tagStr.match(/\bid\s*=\s*["']([^"']+)["']/i);
      var hrefM = tagStr.match(/\bhref\s*=\s*["']([^"']+)["']/i);
      if (idM && hrefM) manifest[idM[1]] = hrefM[1];
    }
  } else {
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var id = it.getAttribute('id');
      var href = it.getAttribute('href');
      if (id && href) manifest[id] = href;
    }
  }

  var order = [];
  var itemrefs = opf.getElementsByTagName('itemref');
  if (!itemrefs.length && opf.getElementsByTagNameNS) itemrefs = opf.getElementsByTagNameNS('*', 'itemref');
  if (!itemrefs.length) {
    var refRe = /<(?:\w+:)?itemref\s+[^>]*\/?>/gi;
    var rm;
    while ((rm = refRe.exec(opfXmlStr)) !== null) {
      var rtagStr = rm[0];
      var idrefM = rtagStr.match(/\bidref\s*=\s*["']([^"']+)["']/i);
      if (idrefM) order.push(idrefM[1]);
    }
  } else {
    for (var j = 0; j < itemrefs.length; j++) {
      order.push(itemrefs[j].getAttribute('idref'));
    }
  }

  // Parse TOC if present (toc.ncx)
  var tocMap = {};
  var tocFile = zip.file('OPS/toc.ncx') || zip.file('toc.ncx');
  if (!tocFile) {
    zip.forEach(function(relPath, entry) {
      if (/toc\.ncx$/i.test(relPath)) tocFile = entry;
    });
  }
  if (tocFile) {
    var tocXmlStr = await tocFile.async('string');
    var navPointRe = /<navPoint[^>]*>[\s\S]*?<text>([\s\S]*?)<\/text>[\s\S]*?<content\s+src=["']([^"']+)["']/gi;
    var npm;
    while ((npm = navPointRe.exec(tocXmlStr)) !== null) {
      var chTitle = fastHtmlToText(npm[1]);
      var chSrc = decodeURIComponent(npm[2].split('#')[0]);
      var cleanSrc = normPath(base + chSrc).replace(/^\//, '').toLowerCase();
      if (chTitle && cleanSrc) tocMap[cleanSrc] = chTitle;
      var justFilename = chSrc.substring(chSrc.lastIndexOf('/') + 1).toLowerCase();
      if (chTitle && justFilename) tocMap[justFilename] = chTitle;
    }
  }

  var chapters = [];
  var out = [];
  var wordOffset = 0;

  for (var o = 0; o < order.length; o++) {
    var idref = order[o];
    var rawHref = manifest[idref];
    if (!rawHref) continue;
    var cleanHref = decodeURIComponent(rawHref.split('#')[0]);
    var fullPath = normPath(base + cleanHref).replace(/^\//, '');
    var f = zip.file(fullPath);
    if (!f) {
      var lowerPath = fullPath.toLowerCase();
      zip.forEach(function(relPath, entry) {
        if (relPath.toLowerCase() === lowerPath) f = entry;
      });
    }
    if (!f) continue;
    var htmlStr = await f.async('string');
    var t = fastHtmlToText(htmlStr);
    if (t) {
      var fileNameOnly = cleanHref.substring(cleanHref.lastIndexOf('/') + 1).toLowerCase();
      var cTitle = tocMap[fullPath.toLowerCase()] || tocMap[cleanHref.toLowerCase()] || tocMap[fileNameOnly] || '';
      if (!cTitle) {
        var firstL = t.split('\n')[0].trim();
        if (/^(?:глава|часть|пролог|эпилог|введение|\d+\.)/i.test(firstL) && firstL.length < 80) {
          cTitle = firstL;
        }
      }
      if (cTitle) {
        chapters.push({ title: cTitle, startIndex: wordOffset });
      }
      out.push(t);
      wordOffset += t.trim().split(/\s+/).length;
    }
  }

  if (!out.length) {
    for (var k in manifest) {
      var h = manifest[k];
      if (/\.x?html?$/i.test(h)) {
        var clh = decodeURIComponent(h.split('#')[0]);
        var fp = normPath(base + clh).replace(/^\//, '');
        var chFile = zip.file(fp);
        if (!chFile) {
          var lowFp = fp.toLowerCase();
          zip.forEach(function(relPath, entry) {
            if (relPath.toLowerCase() === lowFp) chFile = entry;
          });
        }
        if (chFile) {
          var hStr = await chFile.async('string');
          var txt = fastHtmlToText(hStr);
          if (txt) out.push(txt);
        }
      }
    }
  }

  // Free zip instance immediately
  zip = null;

  return { title: title, text: out.join('\n\n'), chapters: chapters };
}

function stripMarkdown(s) {
  return s.replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^>\s?/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/(\*\*|__|\*|_|`)/g, '');
}

async function readFile(file) {
  var name = file.name.toLowerCase();
  var stem = file.name.replace(/\.[^.]+$/, '');
  var buf = await file.arrayBuffer();
  var r;

  if (name.endsWith('.json')) {
    var obj;
    try {
      obj = JSON.parse(decodeText(buf));
    } catch (e) {
      throw new Error('Некорректный JSON файл');
    }
    if (!isPack(obj)) throw new Error('Это не FastRead-пак (нужно "format": "fastread-pack/1")');
    r = { title: obj.title, pack: obj };
  } else if (name.endsWith('.epub')) {
    r = await parseEpub(buf);
  } else if (name.endsWith('.fb2')) {
    r = parseFb2(decodeXml(buf));
  } else if (/\.html?$/.test(name)) {
    r = { text: fastHtmlToText(decodeText(buf)) };
  } else if (/\.(md|markdown)$/.test(name)) {
    r = { text: stripMarkdown(decodeText(buf)) };
  } else {
    r = { text: decodeText(buf) };
  }
  r.title = r.title || stem;
  return r;
}

/* =====================================================================
   UI Wiring & Modal Management
   ===================================================================== */
function openModal(id) {
  if (S.playing) pause();
  closeModals();
  $(id).classList.add('open');
  if (id === 'libModal') renderLib();
  if (id === 'deckModal') renderDeck();
  if (id === 'setModal') { showPerformance(); updateScreenCapabilities(); }
  if (id === 'newModal') setTimeout(function() { $('textIn').focus(); }, 50);
}

function closeModals() {
  R.active = false;
  var opened = document.querySelectorAll('.overlay.open');
  for (var i = 0; i < opened.length; i++) opened[i].classList.remove('open');
}

var overlays = document.querySelectorAll('.overlay');
for (var oIdx = 0; oIdx < overlays.length; oIdx++) {
  (function(o) {
    o.addEventListener('mousedown', function(e) {
      if (e.target === o) closeModals();
    });
    var closers = o.querySelectorAll('[data-close]');
    for (var cIdx = 0; cIdx < closers.length; cIdx++) {
      closers[cIdx].addEventListener('click', closeModals);
    }
  })(overlays[oIdx]);
}

var toastT;
function toast(msg, ms) {
  var t = $('toast');
  if (!t) return;
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastT);
  var duration = ms || (/ошибка|error/i.test(msg) ? 4500 : 2200);
  toastT = setTimeout(function() { t.classList.remove('show'); }, duration);
}

$('libBtn').onclick = function() { openModal('libModal'); };
var deckB = $('deckBtn');
if (deckB) deckB.onclick = function() { openModal('deckModal'); };
$('newBtn').onclick = function() { openModal('newModal'); };
$('setBtn').onclick = function() { openModal('setModal'); };
$('playBtn').onclick = toggle;

// 3-Zone Stage Click Handler (Left: Rewind | Center: Play/Pause | Right: Forward)
$('stage').onclick = function(e) {
  var rect = this.getBoundingClientRect();
  var x = e.clientX - rect.left;
  var pct = x / rect.width;
  if (pct < 0.25) {
    step(-10);
  } else if (pct > 0.75) {
    step(10);
  } else {
    toggle();
  }
};

$('back1').onclick = function() { step(-1); };
$('fwd1').onclick = function() { step(1); };
$('back10').onclick = function() { step(-10); };
$('fwd10').onclick = function() { step(10); };
$('sentBack').onclick = function() { sentence(-1); };
$('sentFwd').onclick = function() { sentence(1); };
$('slower').onclick = function() { setWpm(settings.wpm - 25); };
$('faster').onclick = function() { setWpm(settings.wpm + 25); };
$('markBtn').onclick = function() {
  if (S.pack) {
    markUnknown();
    return;
  }
  if (!S.doc || !S.tokens.length) return;
  if (!S.doc.bookmarks) S.doc.bookmarks = [];
  var curPos = S.i;
  var existingIdx = S.doc.bookmarks.findIndex(function(b) {
    var p = typeof b === 'object' ? b.pos : b;
    return Math.abs(p - curPos) <= 3;
  });
  if (existingIdx >= 0) {
    S.doc.bookmarks.splice(existingIdx, 1);
    toast('Закладка удалена');
  } else {
    var w = S.tokens[curPos] ? (S.tokens[curPos].w || '') : '';
    S.doc.bookmarks.push(curPos);
    toast('Закладка установлена (слово ' + (curPos + 1) + ')');
  }
  savePos();
  updateChapterTicks();
  render();
};
$('modeSel').onchange = function(e) {
  setMode(e.target.value);
  e.target.blur();
};
$('scrub').addEventListener('input', function(e) { seek(+e.target.value); });
$('context').addEventListener('click', function(e) {
  var i = e.target.getAttribute('data-i');
  if (i != null) seek(+i);
});

function cycleMode() {
  if (!S.pack) return;
  var ids = MODES.map(function(m) { return m.id; }).filter(function(id) {
    return id !== 'weave' || S.hasSwap;
  });
  setMode(ids[(ids.indexOf(effMode()) + 1) % ids.length]);
}

/* =====================================================================
   Keyboard & Hardware Button Handling (Onyx BOOX Livingstone)
   ===================================================================== */
document.addEventListener('keydown', function(e) {
  var tag = (e.target.tagName || '').toLowerCase();
  var k = e.key || '';
  var code = e.keyCode || 0;

  // Hardware Page Turn / Volume Buttons on e-readers
  // VolumeUp: 24, VolumeDown: 25, PageUp: 92 or 33, PageDown: 93 or 34
  if (code === 24 || code === 92 || code === 33) {
    e.preventDefault();
    if (settings.hwKeys === 'sentence') sentence(-1);
    else if (settings.hwKeys === 'speed') { setWpm(settings.wpm + 25); toast(settings.wpm + ' сл/мин'); }
    else step(-10);
    return;
  }
  if (code === 25 || code === 93 || code === 34) {
    e.preventDefault();
    if (settings.hwKeys === 'sentence') sentence(1);
    else if (settings.hwKeys === 'speed') { setWpm(settings.wpm - 25); toast(settings.wpm + ' сл/мин'); }
    else step(10);
    return;
  }

  if (k === 'Escape' || code === 27) { closeModals(); return; }
  if (tag === 'textarea' || (tag === 'input' && e.target.type !== 'range') || tag === 'select') return;
  if (e.metaKey || e.ctrlKey || e.altKey) return;

  if (R.active) {
    if (!R.revealed && (k === ' ' || k === 'Enter' || code === 32 || code === 13)) {
      e.preventDefault();
      R.revealed = true;
      renderReview();
    } else if (R.revealed && (k === '1' || code === 49)) {
      answer(false);
    } else if (R.revealed && (k === '2' || k === ' ' || k === 'Enter' || code === 50 || code === 32 || code === 13)) {
      e.preventDefault();
      answer(true);
    }
    return;
  }

  if (document.querySelector('.overlay.open')) return;

  if (k === ' ' || k === 'k' || k === 'л' || code === 32) {
    e.preventDefault();
    toggle();
  } else if (k === 'ArrowLeft' || code === 37) {
    e.preventDefault();
    step(e.shiftKey ? -10 : -1);
  } else if (k === 'ArrowRight' || code === 39) {
    e.preventDefault();
    step(e.shiftKey ? 10 : 1);
  } else if (k === 'ArrowUp' || code === 38) {
    e.preventDefault();
    setWpm(settings.wpm + 25);
    toast(settings.wpm + ' сл/мин');
  } else if (k === 'ArrowDown' || code === 40) {
    e.preventDefault();
    setWpm(settings.wpm - 25);
    toast(settings.wpm + ' сл/мин');
  } else if (k === '[' || k === 'х' || code === 219) {
    sentence(-1);
  } else if (k === ']' || k === 'ъ' || code === 221) {
    sentence(1);
  } else if (k === 'Home' || code === 36) {
    seek(0);
  } else if (k === 'd' || k === 'D' || k === 'в' || k === 'В' || code === 68) {
    markUnknown();
  } else if (k === 'm' || k === 'ь' || code === 77) {
    cycleMode();
  } else if (k === 'n' || k === 'т' || code === 78) {
    e.preventDefault();
    openModal('newModal');
  } else if (k === 'l' || k === 'д' || code === 76) {
    openModal('libModal');
  } else if (k === 'w' || k === 'ц' || code === 87) {
    openModal('deckModal');
  }
});

function base64ToArrayBuffer(base64) {
  var clean = base64.replace(/\s+/g, '');
  var binary_string = window.atob(clean);
  var len = binary_string.length;
  var bytes = new Uint8Array(len);
  for (var i = 0; i < len; i++) {
    bytes[i] = binary_string.charCodeAt(i);
  }
  return bytes.buffer;
}

function readLocalFileBuffer(path) {
  return new Promise(function(resolve, reject) {
    var xhr = new XMLHttpRequest();
    var cleanPath = path.indexOf('/') === 0 ? path : '/' + path;
    var url = 'file://' + encodeURI(cleanPath);
    xhr.open('GET', url, true);
    xhr.responseType = 'arraybuffer';
    xhr.onload = function() {
      if ((xhr.status === 200 || xhr.status === 0) && xhr.response && xhr.response.byteLength > 0) {
        resolve(xhr.response);
      } else {
        reject(new Error('XHR status ' + xhr.status));
      }
    };
    xhr.onerror = function() {
      reject(new Error('XHR error for ' + url));
    };
    xhr.send();
  });
}

window.openBookFromPath = async function(name, path) {
  try {
    toast('Открываю ' + name + '…', 1500);
    var buf = null;
    try {
      buf = await readLocalFileBuffer(path);
    } catch (e) {}
    if (!buf && window.AndroidBridge && window.AndroidBridge.readFileBase64) {
      var b64 = window.AndroidBridge.readFileBase64(path);
      if (b64 && b64.indexOf('ERROR:') === 0) throw new Error(b64);
      if (b64) buf = base64ToArrayBuffer(b64);
    }
    if (!buf) throw new Error('Не удалось прочитать файл: ' + path);
    var fileObj = {
      name: name,
      arrayBuffer: function() { return Promise.resolve(buf); }
    };
    var res = await readFile(fileObj);
    if (!res.pack && !(res.text || '').trim()) throw new Error('В файле не нашлось текста');
    await addDoc(res.title || name, res.text || '', res.pack, res.chapters);
  } catch (err) {
    toast('Ошибка: ' + (err.message || String(err)));
  }
};

window.openBookFromNative = async function(name, base64) {
  try {
    if (base64 && base64.indexOf('ERROR:') === 0) throw new Error(base64);
    var buf = base64ToArrayBuffer(base64);
    var fileObj = {
      name: name,
      arrayBuffer: function() { return Promise.resolve(buf); }
    };
    var res = await readFile(fileObj);
    if (!res.pack && !(res.text || '').trim()) throw new Error('В файле не нашлось текста');
    await addDoc(res.title || name, res.text || '', res.pack, res.chapters);
  } catch (err) {
    toast('Ошибка: ' + (err.message || String(err)));
  }
};

/* Native File Explorer */
var currentNativeDir = '/sdcard';
function initNativeExplorer() {
  if (!window.AndroidBridge) return;
  var wrap = $('nativeExplorer');
  if (!wrap) return;
  wrap.style.display = 'block';

  try {
    currentNativeDir = window.AndroidBridge.getRootPath() || '/sdcard';
  } catch (e) {}

  var qb = $('quickDirs');
  if (qb) {
    qb.onclick = function(e) {
      var rel = e.target.getAttribute('data-rel');
      if (rel !== null) {
        var base = (window.AndroidBridge && window.AndroidBridge.getRootPath()) || '/sdcard';
        var d = rel ? (base.replace(/\/+$/, '') + '/' + rel) : base;
        loadNativeDir(d);
      }
    };
  }
  loadNativeDir(currentNativeDir);
}

function loadNativeDir(path) {
  if (!window.AndroidBridge) return;
  currentNativeDir = path;
  $('dirCurrent').textContent = path;
  var list = $('dirList');
  list.innerHTML = 'Загрузка...';

  try {
    var jsonStr = window.AndroidBridge.listDirectory(path);
    var items = JSON.parse(jsonStr || '[]');
    list.innerHTML = '';

    if (path !== '/' && path !== '') {
      var up = document.createElement('div');
      up.className = 'lib-item';
      up.style.padding = '8px 10px';
      up.innerHTML = '<b>📁 .. (На уровень вверх)</b>';
      up.onclick = function() {
        var parent = path.substring(0, path.lastIndexOf('/')) || '/';
        loadNativeDir(parent);
      };
      list.appendChild(up);
    }

    if (!items.length) {
      var emp = document.createElement('div');
      emp.style.padding = '10px';
      emp.style.color = '#555';
      emp.textContent = 'В этой папке нет поддерживаемых книг (.epub, .fb2, .txt, .md, .json)';
      list.appendChild(emp);
      return;
    }

    items.forEach(function(item) {
      var row = document.createElement('div');
      row.className = 'lib-item';
      row.style.padding = '8px 10px';
      var icon = item.isDir ? '📁 ' : '📖 ';
      var sizeStr = item.isDir ? '' : ' (' + Math.round(item.size / 1024) + ' KB)';
      row.innerHTML = '<div><b>' + icon + esc(item.name) + '</b>' + sizeStr + '</div>';
      row.onclick = async function() {
        if (item.isDir) {
          loadNativeDir(item.path);
        } else {
          $('newMsg').textContent = 'Чтение ' + item.name + '…';
          try {
            var buf = null;
            // 1. Try reading directly via local XHR
            try {
              buf = await readLocalFileBuffer(item.path);
            } catch (xhrErr) {
              console.warn('XHR read failed, trying native bridge', xhrErr);
            }
            // 2. Fallback to bridge Base64
            if (!buf && window.AndroidBridge && window.AndroidBridge.readFileBase64) {
              var b64 = window.AndroidBridge.readFileBase64(item.path);
              if (b64 && b64.indexOf('ERROR:') === 0) throw new Error(b64);
              if (!b64) throw new Error('Не удалось прочитать файл');
              buf = base64ToArrayBuffer(b64);
            }
            if (!buf) throw new Error('Не удалось получить данные файла: ' + item.path);

            var fileObj = {
              name: item.name,
              arrayBuffer: function() { return Promise.resolve(buf); }
            };
            $('newMsg').textContent = 'Разбор ' + item.name + '…';
            var res = await readFile(fileObj);
            if (!res.pack && !(res.text || '').trim()) throw new Error('В книге не найдено читаемого текста');
            await addDoc(res.title || item.name, res.text || '', res.pack, res.chapters);
            $('newMsg').textContent = '';
          } catch (err) {
            var errMsg = err.message || String(err);
            $('newMsg').textContent = errMsg;
            toast('Ошибка: ' + errMsg);
          }
        }
      };
      list.appendChild(row);
    });
  } catch (err) {
    list.innerHTML = '<div style="color:red;padding:8px;">Ошибка: ' + esc(err.message || String(err)) + '</div>';
  }
}

/* ---------- Import Files ---------- */
var drop = $('drop'), fileIn = $('file');
drop.onclick = function() { fileIn.click(); };
fileIn.onchange = function() {
  if (fileIn.files && fileIn.files[0]) importFile(fileIn.files[0]);
  fileIn.value = '';
};
['dragenter', 'dragover'].forEach(function(ev) {
  drop.addEventListener(ev, function(e) { e.preventDefault(); drop.classList.add('over'); });
});
['dragleave', 'drop'].forEach(function(ev) {
  drop.addEventListener(ev, function(e) { e.preventDefault(); drop.classList.remove('over'); });
});
drop.addEventListener('drop', function(e) {
  var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
  if (f) importFile(f);
});

async function importFile(file) {
  $('newMsg').textContent = 'Читаю файл…';
  try {
    var res = await readFile(file);
    if (!res.pack && !(res.text || '').trim()) throw new Error('В файле не нашлось текста');
    await addDoc(res.title, res.text || '', res.pack, res.chapters);
    $('newMsg').textContent = '';
  } catch (err) {
    $('newMsg').textContent = err.message || String(err);
    openModal('newModal');
  }
}

$('startBtn').onclick = async function() {
  var text = $('textIn').value;
  if (!text.trim()) {
    $('newMsg').textContent = 'Вставь текст или выбери файл';
    return;
  }
  if (/^\s*\{/.test(text)) {
    try {
      var o = JSON.parse(text);
      if (isPack(o)) {
        await addDoc(o.title || 'Пак', '', o);
        $('textIn').value = '';
        return;
      }
    } catch (e) {}
  }
  var title = $('titleIn').value.trim() || text.trim().split(/\s+/).slice(0, 6).join(' ').slice(0, 60) + '…';
  await addDoc(title, text);
  $('textIn').value = '';
  $('titleIn').value = '';
  $('newMsg').textContent = '';
};

async function addDoc(title, text, pack, chapters) {
  var doc = {
    id: 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    title: title,
    text: text,
    pack: pack || null,
    chapters: chapters || [],
    pos: 0,
    total: 0,
    created: Date.now(),
    updated: Date.now()
  };
  closeModals();
  loadDoc(doc);
  doc.total = S.tokens.length;
  LS.set('fr.total.' + doc.id, S.tokens.length);
  toast(S.tokens.length + ' слов · Пробел — старт');
  setTimeout(function() {
    DB.put('docs', doc).catch(function(e) {
      console.warn('DB.put docs background save warning', e);
    });
  }, 200);
}

/* ---------- Library ---------- */
async function renderLib() {
  var list = $('libList');
  var docs = [];
  try {
    docs = (await DB.all('docs')) || [];
  } catch (e) {}
  if (!Array.isArray(docs)) docs = [];
  docs.sort(function(a, b) {
    return LS.get('fr.updated.' + b.id, b.updated || 0) - LS.get('fr.updated.' + a.id, a.updated || 0);
  });
  if (!docs.length) {
    list.innerHTML = '<div class="empty">Пока пусто. Добавьте книгу через «Добавить книгу».</div>';
    return;
  }
  list.innerHTML = '';
  docs.forEach(function(d) {
    var isCurrent = S.doc && S.doc.id === d.id;
    var total = isCurrent && S.tokens.length ? S.tokens.length : (d.total || parseInt(LS.get('fr.total.' + d.id, 0), 10) || (d.text ? d.text.trim().split(/\s+/).length : 0));
    var savedPos = LS.get('fr.pos.' + d.id, null);
    var pos = isCurrent ? S.i : ((savedPos !== null && savedPos !== undefined && !isNaN(savedPos)) ? parseInt(savedPos, 10) : (d.pos || 0));
    var pct = total > 0 ? Math.min(100, Math.round(pos / Math.max(1, total - 1) * 100)) : 0;

    if (!d.total && total > 0) {
      d.total = total;
      d.pos = pos;
      try { DB.put('docs', d); } catch(e) {}
    }

    var el = document.createElement('div');
    el.className = 'lib-item' + (isCurrent ? ' active' : '');
    el.innerHTML = '<div class="t"><div></div><div></div><div class="bar"><i style="width:' + pct + '%"></i></div></div><button class="del">Удалить</button>';
    var t0 = el.querySelector('.t div');
    t0.textContent = d.title || 'Без названия';
    if (isPack(d.pack)) {
      var tg = document.createElement('span');
      tg.className = 'tag';
      tg.textContent = ' ' + d.pack.lang + '→' + d.pack.gloss_lang;
      t0.appendChild(tg);
    }
    el.querySelector('.t div:nth-child(2)').textContent = (total ? total.toLocaleString('ru-RU') : 0) + ' слов · ' + pct + '%';
    el.onclick = async function() {
      var full = await DB.get('docs', d.id);
      if (full) {
        loadDoc(full);
        closeModals();
      }
    };
    el.querySelector('.del').onclick = async function(e) {
      e.stopPropagation();
      if (el.getAttribute('data-confirm') !== '1') {
        el.setAttribute('data-confirm', '1');
        e.target.textContent = 'Точно?';
        return;
      }
      await DB.del('docs', d.id);
      if (S.doc && S.doc.id === d.id) loadDemo();
      renderLib();
    };
    list.appendChild(el);
  });
}

/* ---------- Settings Management ---------- */
function syncModeUI() {
  var m = effMode();
  $('modeSel').value = m;
  var opts = $('modeSel').options;
  for (var i = 0; i < opts.length; i++) {
    opts[i].disabled = (opts[i].value === 'weave' && !S.hasSwap);
  }
  var mopts = $('sMode').querySelectorAll('.mopt');
  for (var j = 0; j < mopts.length; j++) {
    var b = mopts[j];
    var val = b.getAttribute('data-v');
    b.classList.toggle('on', S.pack ? val === m : val === settings.mode);
    b.disabled = (val === 'weave' && S.pack && !S.hasSwap);
  }
}

$('sMode').innerHTML = MODES.map(function(m) {
  return '<button class="mopt" data-v="' + m.id + '"><b>' + m.name + '</b><span>' + m.desc + '</span></button>';
}).join('');

$('sMode').addEventListener('click', function(e) {
  var b = e.target.closest('.mopt');
  if (b && !b.disabled) setMode(b.getAttribute('data-v'));
});

function applyLook() {
  var root = document.documentElement;
  var body = document.body;

  if (settings.theme === 'dark') {
    body.classList.add('theme-dark');
  } else {
    body.classList.remove('theme-dark');
  }

  body.classList.remove('focus-box', 'focus-markers', 'focus-underline', 'focus-bold', 'focus-invert');
  body.classList.add('focus-' + (settings.focusStyle || 'box'));

  $('stage').style.fontSize = Math.min(settings.size, 110) + 'px';
  $('stage').style.fontFamily = FONTS[settings.font] || FONTS.serif;
  $('gloss').style.opacity = (settings.glossOp / 100);
  $('wpm').textContent = settings.wpm;

  var vals = {
    sWpm: settings.wpm,
    sSize: settings.size,
    sMinWord: settings.minWordMs || 0,
    sOp: settings.glossOp,
    sDelay: settings.glossDelay,
    sDens: settings.density,
    sTr: settings.trTime
  };
  for (var id in vals) {
    if (Object.prototype.hasOwnProperty.call(vals, id) && $(id)) {
      $(id).value = vals[id];
    }
  }

  $('sWpmV').textContent = settings.wpm;
  $('sMinWordV').textContent = settings.minWordMs ? settings.minWordMs + ' мс' : 'выкл.';
  $('sOpV').textContent = settings.glossOp + '%';
  $('sDelayV').textContent = settings.glossDelay + ' мс';
  $('sDensV').textContent = settings.density + ' / 5';
  $('sTrV').textContent = '×' + (settings.trTime / 100).toFixed(1);

  var switches = [
    ['sSmart', 'smart'],
    ['sSplit', 'split'],
    ['sRamp', 'ramp'],
    ['sCtx', 'context']
  ];
  switches.forEach(function(item) {
    $(item[0]).classList.toggle('on', !!settings[item[1]]);
  });

  var segs = [
    ['sFocus', 'focus'],
    ['sFocusStyle', 'focusStyle'],
    ['sFont', 'font'],
    ['sTheme', 'theme'],
    ['sHwKeys', 'hwKeys'],
    ['sLevel', 'level']
  ];
  segs.forEach(function(item) {
    var btns = $(item[0]).querySelectorAll('button');
    for (var bIdx = 0; bIdx < btns.length; bIdx++) {
      btns[bIdx].classList.toggle('on', btns[bIdx].getAttribute('data-v') === String(settings[item[1]]));
    }
  });

  syncModeUI();
}

$('sWpm').addEventListener('input', function(e) { setWpm(+e.target.value); });

function bindRange(id, key, after) {
  $(id).addEventListener('input', function(e) {
    settings[key] = +e.target.value;
    saveSettings();
    applyLook();
    if (after) after();
  });
}
bindRange('sSize', 'size');
bindRange('sMinWord', 'minWordMs');
bindRange('sOp', 'glossOp');
bindRange('sDelay', 'glossDelay', function() { buildCum(); render(); });
bindRange('sDens', 'density', retokenize);
bindRange('sTr', 'trTime', function() { buildCum(); render(); });

function bindSwitch(id, key, after) {
  $(id).onclick = function() {
    settings[key] = !settings[key];
    saveSettings();
    applyLook();
    if (after) after();
  };
}
bindSwitch('sSmart', 'smart', function() { buildCum(); render(); });
bindSwitch('sSplit', 'split', retokenize);
bindSwitch('sRamp', 'ramp');
bindSwitch('sCtx', 'context', function() { renderContext(); });

function bindSeg(id, key, after, isNum) {
  $(id).addEventListener('click', function(e) {
    var v = e.target.getAttribute('data-v');
    if (!v) return;
    settings[key] = isNum ? +v : v;
    saveSettings();
    applyLook();
    if (after) after();
  });
}
bindSeg('sFocus', 'focus', render);
bindSeg('sFocusStyle', 'focusStyle', render);
bindSeg('sFont', 'font');
bindSeg('sTheme', 'theme');
bindSeg('sHwKeys', 'hwKeys');
bindSeg('sLevel', 'level', function() { buildCum(); render(); }, true);
$('performanceBtn').onclick = showPerformance;
$('screenRefreshBtn').onclick = requestScreenRefresh;

/* =====================================================================
   Examples Embedding Placeholder
   ===================================================================== */
var EXAMPLES = /*EXAMPLES:BEGIN*/[]/*EXAMPLES:END*/;

EXAMPLES.forEach(function(ex, i) {
  var b = document.createElement('button');
  b.className = 'chip';
  b.innerHTML = esc(ex.title) + ' <small>' + esc(ex.lang) + '→' + esc(ex.gloss_lang) + '</small>';
  b.onclick = async function() {
    var docs = (await DB.all('docs').catch(function() { return []; })) || [];
    var existing = docs.find(function(d) { return d.exampleId === 'ex' + i; });
    if (existing) {
      loadDoc(existing);
      closeModals();
      return;
    }
    await addDoc(ex.title, '', ex);
    S.doc.exampleId = 'ex' + i;
    savePos();
  };
  $('examples').appendChild(b);
});
if (!EXAMPLES.length) $('examples').hidden = true;

/* =====================================================================
   Boot
   ===================================================================== */
var DEMO = 'Привет! Это FastRead — скорочтение для электронных книг Onyx BOOX.\n\n' +
  'Смотри на выделенную букву и не двигай взглядом. Каждое слово выравнивается по центру, поэтому глазу не нужно прыгать по строке.\n\n' +
  'Нажми на центр экрана или Пробел для старта и паузы. Тап у левого края перематывает назад на 10 слов, тап у правого края — вперёд.\n\n' +
  'Кнопки громкости и листания на читалке также управляют чтением. Встретил интересное место — жми закладку слева, чтобы сохранить его.';

function loadDemo() {
  loadDoc({ id: 'demo', title: 'Инструкция', text: DEMO, pos: 0, demo: true });
}

(async function boot() {
  applyLook();
  $('playBtn').innerHTML = ICON_PLAY;
  initNativeExplorer();
  var last = LS.get('fr.last', null);
  var doc = null;
  if (last && last !== 'demo') {
    try { doc = await DB.get('docs', last); } catch (e) {}
  }
  if (doc) loadDoc(doc);
  else loadDemo();
  updateDeckBadge();
})();

window.FastRead = {
  tokenize: tokenize,
  buildTokens: buildTokens,
  splitWord: splitWord,
  splitLong: splitLong,
  parseEpub: parseEpub,
  parseFb2: parseFb2,
  readFile: readFile,
  loadDoc: loadDoc,
  addDoc: addDoc,
  seek: seek,
  play: play,
  pause: pause,
  toggle: toggle,
  S: S,
  settings: settings,
  setMode: setMode,
  effMode: effMode,
  render: render,
  showTr: showTr,
  hideTr: hideTr,
  applyLook: applyLook,
  getPlaybackDiagnostics: getPlaybackDiagnostics,
  onScreenRefresh: onScreenRefresh
};
