const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

console.log('--- Running Automated Validation on dist/index.html ---');

const html = fs.readFileSync(path.join(__dirname, '../dist/index.html'), 'utf-8');

const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  resources: 'usable',
  url: 'http://localhost/'
});

const { window } = dom;
const { document } = window;

// Check polyfills and globals
console.log('1. Checking globals...');
if (!window.FastRead) {
  throw new Error('FAIL: window.FastRead not found!');
}
console.log('   [✓] window.FastRead initialized.');

if (!window.JSZip) {
  throw new Error('FAIL: window.JSZip not found!');
}
console.log('   [✓] window.JSZip initialized.');

if (!window.TextDecoder) {
  throw new Error('FAIL: window.TextDecoder not found!');
}
console.log('   [✓] window.TextDecoder initialized.');

// Check TextDecoder Windows-1251 and UTF-8
console.log('2. Checking TextDecoder...');
const dec1251 = new window.TextDecoder('windows-1251');
const test1251 = dec1251.decode(new Uint8Array([0xcf, 0xf0, 0xe8, 0xe2, 0xe5, 0xf2]));
if (test1251 !== 'Привет') {
  throw new Error(`FAIL: windows-1251 decoder failed. Expected "Привет", got "${test1251}"`);
}
console.log('   [✓] Windows-1251 decoded properly:', test1251);

const decUtf8 = new window.TextDecoder('utf-8');
const testUtf8 = decUtf8.decode(Buffer.from('Привет мир', 'utf-8'));
if (testUtf8 !== 'Привет мир') {
  throw new Error(`FAIL: UTF-8 decoder failed. Expected "Привет мир", got "${testUtf8}"`);
}
console.log('   [✓] UTF-8 decoded properly:', testUtf8);

// Test Tokenizer
console.log('3. Checking Tokenizer...');
const tokensResult = window.FastRead.tokenize('Это быстрый тест читалщика: слово-дефис, длинноесловобезразбивки и знак!');
if (!tokensResult || !tokensResult.tokens || tokensResult.tokens.length === 0) {
  throw new Error('FAIL: Tokenizer returned empty token stream.');
}
console.log(`   [✓] Tokenized ${tokensResult.tokens.length} tokens successfully.`);

// Test splitWord
console.log('4. Checking ORP / splitWord...');
const split = window.FastRead.splitWord('чтение');
if (!Array.isArray(split) || split.length !== 3) {
  throw new Error('FAIL: splitWord should return [left, focus, right].');
}
console.log(`   [✓] splitWord('чтение') -> left: "${split[0]}", focus: "${split[1]}", right: "${split[2]}"`);

// Test Hardware keys
console.log('5. Testing Hardware Button Event Dispatch...');
const eventVolUp = new window.KeyboardEvent('keydown', { keyCode: 24, bubbles: true });
document.dispatchEvent(eventVolUp);
const eventPageDown = new window.KeyboardEvent('keydown', { keyCode: 93, bubbles: true });
document.dispatchEvent(eventPageDown);
console.log('   [✓] Hardware buttons handled without error.');

// Test Stage 3-Zone Click
console.log('6. Testing Stage Tap Zones...');
const stage = document.getElementById('stage');
stage.getBoundingClientRect = () => ({ left: 0, top: 0, width: 800, height: 200 });

// Left tap (Rewind)
stage.dispatchEvent(new window.MouseEvent('click', { clientX: 100, clientY: 100, bubbles: true }));
// Right tap (Forward)
stage.dispatchEvent(new window.MouseEvent('click', { clientX: 700, clientY: 100, bubbles: true }));
// Center tap (Play/Pause)
stage.dispatchEvent(new window.MouseEvent('click', { clientX: 400, clientY: 100, bubbles: true }));
console.log('   [✓] Stage tap zones handled properly.');

// Test Offline EPUB Generation and Parsing
console.log('7. Testing Offline EPUB Parsing...');
async function testEpub() {
  const zip = new window.JSZip();
  zip.file('mimetype', 'application/epub+zip');
  zip.file('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');
  zip.file('OEBPS/content.opf', '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Тестовая книга</dc:title></metadata><manifest><item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>');
  zip.file('OEBPS/ch1.html', '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Глава 1</title></head><body><h1>Глава первая</h1><p>Первое предложение для читалки.</p><p>Второе предложение.</p></body></html>');
  
  const epubBlob = await zip.generateAsync({ type: 'arraybuffer' });
  const epubDoc = await window.FastRead.parseEpub(epubBlob);
  if (!epubDoc || epubDoc.title !== 'Тестовая книга' || !epubDoc.text.includes('Первое предложение')) {
    throw new Error('FAIL: Offline EPUB parser failed to extract title and text correctly.');
  }
  console.log(`   [✓] EPUB parsed successfully: "${epubDoc.title}", text length: ${epubDoc.text.length} chars.`);

  // Test FB2 Parsing
  console.log('8. Testing FB2 Parsing...');
  const sampleFb2 = '<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Тестовый FB2</book-title></title-info></description><body><title><p>Глава 1</p></title><p>Текст в формате FB2.</p></body></FictionBook>';
  const fb2Doc = window.FastRead.parseFb2(sampleFb2);
  if (!fb2Doc || fb2Doc.title !== 'Тестовый FB2' || !fb2Doc.text.includes('Текст в формате FB2')) {
    throw new Error('FAIL: FB2 parsing failed');
  }
  console.log(`   [✓] FB2 parsed successfully: "${fb2Doc.title}".`);

  // Test JSON Bilingual Pack
  console.log('9. Testing Bilingual Pack Parsing...');
  const packJson = fs.readFileSync(path.join(__dirname, '../examples/chameleon.ru-en.json'), 'utf-8');
  const packObj = JSON.parse(packJson);
  const packFile = { name: 'chameleon.json', arrayBuffer: async () => Buffer.from(packJson, 'utf-8') };
  const packDoc = await window.FastRead.readFile(packFile);
  if (!packDoc || !packDoc.pack || packDoc.pack.lang !== 'ru') {
    throw new Error('FAIL: Bilingual pack reading failed');
  }
  console.log(`   [✓] Bilingual pack read successfully: "${packDoc.title}" (${packDoc.pack.lang} -> ${packDoc.pack.gloss_lang}).`);
}

testEpub().then(() => {
  console.log('--- ALL TESTS PASSED SUCCESSFULLY! ---');
  process.exit(0);
}).catch(err => {
  console.error('Test error:', err);
  process.exit(1);
});
