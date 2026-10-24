const fs = require('fs');
const html = fs.readFileSync('src/index.html', 'utf-8');
const ids = [
  'sFocus', 'sFont', 'sTheme', 'sHwKeys', 'sLevel', 'sSmart', 'sSplit',
  'sRamp', 'sCtx', 'sWpm', 'sSize', 'sOp', 'sDelay', 'sDens', 'sTr',
  'stage', 'word', 'wl', 'wp', 'wr', 'gloss', 'trline', 'pos', 'scrub',
  'left', 'modeSel', 'stats', 'sentBack', 'back10', 'back1', 'playBtn',
  'fwd1', 'fwd10', 'sentFwd', 'markBtn', 'slower', 'wpm', 'faster',
  'newModal', 'libModal', 'deckModal', 'setModal', 'toast', 'libList',
  'deckBody', 'textIn', 'titleIn', 'startBtn', 'newMsg', 'docTitle',
  'deckBadge', 'drop', 'file', 'examples', 'sMode'
];

let missing = 0;
ids.forEach(id => {
  if (!html.includes(`id="${id}"`)) {
    console.log('MISSING ID:', id);
    missing++;
  }
});
if (missing === 0) console.log('All IDs exist in HTML!');
