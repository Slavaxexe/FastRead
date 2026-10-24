const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const jdk = path.join(root, 'tools/jdk/jdk-17.0.10+7/bin');
const androidJar = path.join(root, 'tools/android-sdk/platforms/android-28/android.jar');
const output = path.join(root, 'android/native-tests');
fs.mkdirSync(output, { recursive: true });
const source = path.join(root, 'android/native/src/com/fastread/nativeapp');
execFileSync(path.join(jdk, 'javac.exe'), ['-encoding', 'UTF-8', '-cp', androidJar, '-d', output,
  ...['BookData.java', 'BookParser.java', 'PlaybackClock.java'].map(file => path.join(source, file)),
  path.join(root, 'tools/native-tests/NativeTests.java')], { stdio: 'inherit', windowsHide: true });
execFileSync(path.join(jdk, 'java.exe'), ['-Xmx256m', '-cp', output + path.delimiter + androidJar, 'NativeTests', path.join(output, 'fixtures')], { stdio: 'inherit', windowsHide: true });
