const fs = require('fs');
const { JSDOM } = require('jsdom');
const html = fs.readFileSync('dist/index.html', 'utf8');
const dom = new JSDOM(html, { runScripts: 'dangerously' });
const w = dom.window;

const buf = fs.readFileSync('c:/Users/miste/Downloads/Telegram Desktop/Пикник на обочине.epub');
w.FastRead.parseEpub(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength)).then(res => {
  console.log('Title:', res.title);
  console.log('Text length:', res.text.length);
  console.log('Chapters found:', res.chapters ? res.chapters.length : 0);
  if (res.chapters) console.log(res.chapters);
});
