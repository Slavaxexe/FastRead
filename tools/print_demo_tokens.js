const fs = require('fs');

const DEMO = 'Привет! Это FastRead — скорочтение для электронных книг Onyx BOOX.\n\n' +
  'Смотри на выделенную букву и не двигай взглядом. Каждое слово выравнивается по центру, поэтому глазу не нужно прыгать по строке.\n\n' +
  'Нажми на центр экрана или Пробел для старта и паузы. Тап у левого края перематывает назад на 10 слов, тап у правого края — вперёд.\n\n' +
  'Кнопки громкости и листания на читалке также управляют чтением. Встретил интересное место — жми закладку слева, чтобы сохранить его.';

const rawParas = DEMO.split(/\n{2,}/);
const tokens = [];
let prefix = '';
for (let pi = 0; pi < rawParas.length; pi++) {
  const pText = rawParas[pi].trim();
  const rawWords = pText.split(/\s+/);
  for (let wi = 0; wi < rawWords.length; wi++) {
    let word = rawWords[wi];
    if (!word) continue;
    if (!/[a-zA-Zа-яА-ЯёЁ0-9]/.test(word)) {
      if (/^[—–\-«(„"']/.test(word)) {
        prefix = (prefix ? prefix + ' ' : '') + word;
        continue;
      } else if (tokens.length && /^[»)”’"']/.test(word)) {
        tokens[tokens.length - 1].w += word;
        continue;
      } else if (tokens.length && /^[—–\-]/.test(word)) {
        tokens[tokens.length - 1].w += ' ' + word;
        continue;
      }
      continue;
    }
    if (prefix) {
      word = prefix + ' ' + word;
      prefix = '';
    }
    tokens.push({ w: word, p: pi });
  }
}

tokens.forEach((t, i) => {
  console.log(`${i}: ${t.w}`);
});
