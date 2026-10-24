const fs = require('fs');
const JSZip = require('jszip');

// Extract parseEpub from src/app.js
const appJs = fs.readFileSync('src/app.js', 'utf8');

// We can run the parser inside a VM or extract the functions
const vm = require('vm');
const context = {
  console: console,
  JSZip: JSZip,
  DOMParser: class {
    parseFromString(str, type) {
      // Simple regex based XML parser for node test
      return {
        querySelector: (sel) => null,
        querySelectorAll: (sel) => []
      };
    }
  }
};
vm.createContext(context);

// Let's write a minimal test using the actual parseEpub in dist/index.html
const html = fs.readFileSync('dist/index.html', 'utf8');
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/g);
const mainScript = scriptMatch[scriptMatch.length - 1].replace(/<\/?script>/g, '');

const { JSDOM } = require('jsdom') || {};
console.log('Testing epub parsing with node...');
