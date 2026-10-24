#!/usr/bin/env node

/**
 * FastRead Build Pipeline for BOOX Livingstone (E-Ink / Android 4.4 / Chromium 30)
 */

const fs = require('fs');
const path = require('path');
const babel = require('@babel/core');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const DIST = path.join(ROOT, 'dist');

console.log('--- Building FastRead Optimized Web App ---');

// 1. Ensure dist directory exists
if (!fs.existsSync(DIST)) {
  fs.mkdirSync(DIST, { recursive: true });
}

// 2. Read template, styles, polyfills, JSZip, and app source
const templateHtml = fs.readFileSync(path.join(SRC, 'index.html'), 'utf-8');
const stylesCss = fs.readFileSync(path.join(SRC, 'eink.css'), 'utf-8');

const promisePolyfill = fs.readFileSync(path.join(ROOT, 'node_modules/promise-polyfill/dist/polyfill.min.js'), 'utf-8');
const customPolyfills = fs.readFileSync(path.join(SRC, 'polyfills.js'), 'utf-8');
const jszipCode = fs.readFileSync(path.join(ROOT, 'node_modules/jszip/dist/jszip.min.js'), 'utf-8');

let appJs = fs.readFileSync(path.join(SRC, 'app.js'), 'utf-8');

// 3. Embed examples from examples/*.json
const examplesDir = path.join(ROOT, 'examples');
let embeddedExamples = [];
if (fs.existsSync(examplesDir)) {
  const exampleFiles = fs.readdirSync(examplesDir).filter(f => f.endsWith('.json')).sort();
  for (const f of exampleFiles) {
    try {
      const data = JSON.parse(fs.readFileSync(path.join(examplesDir, f), 'utf-8'));
      embeddedExamples.push(data);
    } catch (err) {
      console.warn(`Warning: Could not parse example ${f}:`, err.message);
    }
  }
}
const examplesBlob = JSON.stringify(embeddedExamples).replace(/<\//g, '<\\/');
appJs = appJs.replace(
  /\/\*EXAMPLES:BEGIN\*\/[\s\S]*?\/\*EXAMPLES:END\*\//,
  `/*EXAMPLES:BEGIN*/${examplesBlob}/*EXAMPLES:END*/`
);
console.log(`[+] Embedded ${embeddedExamples.length} example pack(s).`);

// 4. Transpile polyfills with Babel targeting Chrome 30 / Android 4.4
const transpiledPolyfills = babel.transformSync(customPolyfills, {
  presets: [
    ['@babel/preset-env', {
      targets: { chrome: '30', android: '4.4' },
      modules: false
    }]
  ],
  compact: true
}).code;

const allPolyfills = promisePolyfill + '\n' + transpiledPolyfills;

// 5. Transpile core application logic with Babel
console.log('[*] Transpiling app.js with Babel for Chromium 30 (ES5)...');
const transpiledApp = babel.transformSync(appJs, {
  presets: [
    ['@babel/preset-env', {
      targets: { chrome: '30', android: '4.4' },
      modules: false
    }]
  ],
  compact: false
}).code;

// 6. Assemble standalone index.html
let outputHtml = templateHtml
  .replace('/*STYLES_PLACEHOLDER*/', stylesCss)
  .replace('/*POLYFILLS_PLACEHOLDER*/', allPolyfills)
  .replace('/*JSZIP_PLACEHOLDER*/', jszipCode)
  .replace('/*APP_PLACEHOLDER*/', transpiledApp);

// 7. Write to dist/index.html
const outPath = path.join(DIST, 'index.html');
fs.writeFileSync(outPath, outputHtml, 'utf-8');
const stats = fs.statSync(outPath);
const sizeKb = (stats.size / 1024).toFixed(1);

console.log(`[✓] Successfully built standalone app: ${outPath} (${sizeKb} KB)`);

// 8. Compatibility sanity checks on output bundle
const checks = [
  { name: 'Arrow functions', regex: /[a-zA-Z0-9_]\s*=>/ },
  { name: 'Async keyword outside polyfill', regex: /\basync\s+function\b/ },
  { name: 'Unicode property escapes', regex: /\\p\{[LN]\}/ },
  { name: 'Optional chaining', regex: /\?\./ },
  { name: 'Logical assignment', regex: /\|\|=|\&\&=|\?\?=/ }
];

let warnings = 0;
// Check within app code section
for (const check of checks) {
  if (check.regex.test(transpiledApp)) {
    console.warn(`[!] Warning: ${check.name} detected in transpiled app code!`);
    warnings++;
  }
}

if (warnings === 0) {
  console.log('[✓] ES5 compatibility check passed with 0 warnings.');
} else {
  console.log(`[!] Built with ${warnings} compatibility warning(s).`);
}
