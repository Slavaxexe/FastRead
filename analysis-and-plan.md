# FastRead → BOOX Livingstone: Analysis & Plan

## ✅ Done: Fork Created
**Your fork:** [Slavaxexe/FastRead](https://github.com/Slavaxexe/FastRead)  
**Local clone:** `c:\Projects\FastReader`

---

## 📖 Project Analysis

FastRead is an **RSVP (Rapid Serial Visual Presentation) speed reader** — a single-file HTML app ([index.html](file:///c:/Projects/FastReader/index.html), 1344 lines) containing all HTML, CSS, and JS inline.

### Core Features
| Feature | How it works |
|---|---|
| **RSVP reading** | Words flash one-at-a-time, centered on a "focus letter" (red) so your eyes don't move |
| **File formats** | `.txt`, `.md`, `.fb2`, `.epub`, `.html`, `.json` packs |
| **EPUB parsing** | Uses [JSZip from CDN](file:///c:/Projects/FastReader/index.html#L1050-L1079) (loaded on first EPUB open, requires internet once) |
| **Bilingual modes** | Gloss under word, delayed hints, sentence translation, word-weaving (5 modes) |
| **Settings** | WPM (speed), font size, font family, focus style, theme (dark/light), word splitting, ramp-up, context panel |
| **Persistence** | [IndexedDB](file:///c:/Projects/FastReader/index.html#L645-L690) with in-memory fallback; localStorage for settings |
| **Flashcard deck** | "I don't know" button (D key) → spaced repetition review, Anki export |
| **Library** | Remembers position in each book, sorted by last read |

### External Dependencies
| Dependency | Source | Required? |
|---|---|---|
| **Google Fonts** (Literata, Inter, JetBrains Mono) | `fonts.googleapis.com` | Only for styling, graceful fallback |
| **JSZip 3.10.1** | `cdnjs.cloudflare.com` | Only for EPUB, loaded on demand |

### JavaScript Features Used (Compatibility Concerns)
The code uses **modern ES6+ features** extensively:
- `const` / `let` — ✅ Chrome 30 supports these
- Arrow functions (`=>`) — ⚠️ **Not supported in Chrome 30**
- Template literals (`` ` ``) — ⚠️ **Not supported in Chrome 30**
- `async` / `await` — ❌ **Not supported in Chrome 30** (requires Chrome 55+)
- `for...of` — ⚠️ **Partial in Chrome 30**
- Optional chaining (`?.`) — ❌ **Not supported** (Chrome 80+)
- Logical assignment (`||=`) — ❌ **Not supported** (Chrome 85+)
- `replaceChildren()` — ❌ **Not supported** (Chrome 86+)
- Unicode regex (`\p{L}`) — ❌ **Not supported** (Chrome 64+)
- `Float64Array` — ✅ Supported
- `IndexedDB` — ✅ Supported
- `DOMParser` — ✅ Supported

> [!WARNING]
> **The app WILL NOT work** in Android 4.4.4's stock WebView (Chromium 30) without significant transpilation. Approximately 60%+ of the JavaScript uses ES6+ features that Chromium 30 doesn't understand.

---

## 🎯 BOOX Livingstone Specs Recap

| Spec | Value |
|---|---|
| **OS** | Android 4.4.4 (KitKat) |
| **CPU** | Rockchip RK3128, Quad-core 1.2 GHz |
| **RAM** | 1 GB LPDDR3 |
| **Storage** | 8 GB (≈5 GB free) |
| **Display** | 6" E Ink Carta, 1072×1448, 300 PPI, 16 grays |
| **WebView** | Chromium 30 (frozen, no updates) |
| **Connectivity** | Wi-Fi b/g/n, Bluetooth 4.1, Micro-USB |

### E-Ink Considerations
- **Slow refresh** (~200–500ms full, ghosting on partial) → animations are bad
- **No color** → dark theme not ideal (inverted e-ink wastes contrast)
- **High PPI (300)** → text looks great at small sizes
- **Binary contrast is king** → bold blacks on white background

---

## 🚀 Solution Options

### Option A: Optimized Web App (PWA-like) — ⭐ RECOMMENDED

**What:** Adapt the existing HTML app for the Chromium 30 WebView, bundle JSZip inline, optimize for e-ink.

**Pros:**
- Fastest to build (the app already works as a single HTML file)
- Can be opened directly in the BOOX browser
- Can also be wrapped in a Cordova APK for a native-feel install
- No build server needed for updates — just replace the HTML file

**Work required:**
1. **Transpile JS to ES5** — Babel to convert arrow functions, async/await, template literals, optional chaining, etc.
2. **Bundle JSZip inline** — eliminate the CDN dependency (important for offline use)
3. **E-ink theme** — high-contrast black-on-white, no animations/transitions, no gradients
4. **Reduce CSS** — remove transitions, use simpler layout, larger touch targets
5. **Memory optimization** — large EPUBs could exceed 1GB RAM; stream chapters instead of loading everything

**Delivery options:**
- **Standalone HTML file** — just copy to the BOOX and open in browser
- **Cordova APK** — wrap in `cordova-android@8.1.0` for native app icon + file picker

### Option B: Native Android App (Java/Kotlin)

**What:** Build a lightweight native Android app from scratch targeting API 19.

**Pros:**
- Best performance on weak hardware
- Native file picker, proper e-ink refresh control
- Can call BOOX-specific SDK APIs (e-ink refresh modes)

**Cons:**
- Much more work (weeks vs days)
- Need to reimplement RSVP logic, EPUB parsing, UI from scratch
- Harder to maintain
- Kotlin may not target API 19 well; would need Java

### Option C: Hybrid (Recommended Implementation)

**What:** Option A with a thin Cordova wrapper for native install.

**Delivery:** An `.apk` file the user can sideload via USB or download.

**Architecture:**
```
┌────────────────────────────────────┐
│         Cordova APK Shell          │
│  (cordova-android@8.1.0, API 19)  │
│  ┌──────────────────────────────┐  │
│  │    index.html (ES5 build)   │  │
│  │  ┌────────┐ ┌─────────────┐ │  │
│  │  │ JSZip  │ │ FastRead JS │ │  │
│  │  │(inline)│ │ (transpiled)│ │  │
│  │  └────────┘ └─────────────┘ │  │
│  │  ┌─────────────────────────┐│  │
│  │  │  E-Ink Optimized CSS    ││  │
│  │  └─────────────────────────┘│  │
│  └──────────────────────────────┘  │
│  + Cordova File Plugin (native     │
│    file picker for .epub files)    │
└────────────────────────────────────┘
```

---

## 📋 Implementation Plan (Option C — Hybrid)

### Phase 1: E-Ink Web Adaptation *(2–3 hours)*
- [ ] Create build system (Babel + bundler) to transpile ES5
- [ ] Bundle JSZip inline (eliminate CDN dependency)
- [ ] Create e-ink optimized theme:
  - White background, black text, no gradients
  - Remove all CSS transitions and animations
  - Increase touch targets (44px minimum)
  - Optimize for 1072×1448 resolution
  - Simple, high-contrast UI
- [ ] Test in Chrome with Chromium 30 compatibility flags

### Phase 2: EPUB & Performance *(1–2 hours)*
- [ ] Stream EPUB chapters (don't load entire book to memory)
- [ ] Add progress indicator optimized for e-ink
- [ ] Reduce DOM updates (fewer repaints = less e-ink flicker)
- [ ] Bundle fallback fonts (no Google Fonts dependency)

### Phase 3: Cordova APK Wrapper *(1–2 hours)*
- [ ] Set up Cordova project with `cordova-android@8.1.0`
- [ ] Configure for API 19 (minSdkVersion)
- [ ] Add `cordova-plugin-file` for native file picker
- [ ] Build debug APK
- [ ] Test on device (or Android 4.4.4 emulator)

### Phase 4: Polish *(1 hour)*
- [ ] Add "Open EPUB" button with native file chooser
- [ ] Persist reading position per book
- [ ] Add page-turn button support (BOOX cover buttons)
- [ ] Final APK build (release-signed)

---

## ❓ Questions Before Starting

1. **Do you want to start with just the standalone HTML file** (Phase 1–2, testable immediately in browser) **or go straight to the full APK** (all phases)?

2. **Which bilingual features do you need?** The original app has 5 modes (plain, gloss, delayed, sentence translation, word-weaving). Do you want all of them, or just the basic RSVP reader for EPUB?

3. **Do you have Android SDK / JDK 8 installed**, or should I set up the Cordova build environment too?

4. **Can you install APKs on your BOOX** via USB sideloading or the built-in file manager?
