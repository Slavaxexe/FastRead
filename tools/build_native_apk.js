const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const JDK = path.join(ROOT, 'tools/jdk/jdk-17.0.10+7/bin');
const SDK = path.join(ROOT, 'tools/android-sdk/build-tools/28.0.3');
const ANDROID = path.join(ROOT, 'tools/android-sdk/platforms/android-28/android.jar');
const SOURCE = path.join(ROOT, 'android/native');
const buildRoot = path.resolve(ROOT, 'android/native-build');

function files(dir, suffix) {
  return fs.readdirSync(dir).flatMap(name => {
    const full = path.join(dir, name);
    return fs.statSync(full).isDirectory() ? files(full, suffix) : full.endsWith(suffix) ? [full] : [];
  });
}

function run(executable, args, cwd = ROOT) {
  execFileSync(executable, args, { cwd, stdio: 'inherit', windowsHide: true });
}

console.log('=== Building Native FastRead Android APK (API 19 / Universal + Onyx BOOX) ===');

const temp = path.resolve(buildRoot, 'fastread');
if (fs.existsSync(temp)) fs.rmSync(temp, { recursive: true, force: true });
for (const folder of ['gen', 'classes', 'dex']) fs.mkdirSync(path.join(temp, folder), { recursive: true });

const packageName = 'com.fastread.reader';
const label = 'FastRead';
const manifest = path.join(temp, 'AndroidManifest.xml');

fs.writeFileSync(
  manifest,
  fs.readFileSync(path.join(SOURCE, 'AndroidManifest.xml'), 'utf8')
    .replace('__PACKAGE__', packageName)
    .replace('__LABEL__', label),
  'utf8'
);

const res = path.join(ROOT, 'android/app/src/main/res');

console.log(`[1/6] Packaging resources and generating R.java for ${packageName}...`);
run(path.join(SDK, 'aapt.exe'), [
  'package', '-f', '-m',
  '-J', path.join(temp, 'gen'),
  '-M', manifest,
  '-S', res,
  '-I', ANDROID
]);

console.log('[2/6] Compiling Java classes with javac (Java 8 bytecode)...');
const javaFiles = files(path.join(SOURCE, 'src'), '.java').concat(files(path.join(temp, 'gen'), '.java'));
run(path.join(JDK, 'javac.exe'), [
  '-encoding', 'UTF-8',
  '-source', '1.8',
  '-target', '1.8',
  '-bootclasspath', ANDROID,
  '-cp', ANDROID,
  '-d', path.join(temp, 'classes'),
  ...javaFiles
]);

console.log('[3/6] Converting classes to DEX (min-api 19)...');
run(path.join(JDK, 'java.exe'), [
  '-cp', path.join(SDK, 'lib/d8.jar'),
  'com.android.tools.r8.D8',
  '--min-api', '19',
  '--lib', ANDROID,
  '--output', path.join(temp, 'dex'),
  ...files(path.join(temp, 'classes'), '.class')
]);

console.log('[4/6] Creating unaligned APK...');
const unaligned = path.join(temp, 'unaligned.apk');
const aligned = path.join(temp, 'aligned.apk');
run(path.join(SDK, 'aapt.exe'), [
  'package', '-f',
  '-M', manifest,
  '-S', res,
  '-I', ANDROID,
  '-F', unaligned
]);
run(path.join(SDK, 'aapt.exe'), ['add', '-k', unaligned, 'classes.dex'], path.join(temp, 'dex'));

console.log('[5/6] Aligning APK with zipalign...');
run(path.join(SDK, 'zipalign.exe'), ['-f', '4', unaligned, aligned]);

console.log('[6/6] Signing APK with apksigner (v1 + v2)...');
const output = path.join(ROOT, 'dist', 'FastRead.apk');
fs.mkdirSync(path.dirname(output), { recursive: true });

const key = path.join(ROOT, 'android/debug.keystore');
if (!fs.existsSync(key)) {
  run(path.join(JDK, 'keytool.exe'), [
    '-genkeypair', '-keystore', key,
    '-storepass', 'android',
    '-alias', 'androiddebugkey',
    '-keypass', 'android',
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
    '-dname', 'CN=FastRead,O=FastRead,C=RU'
  ]);
}

run(path.join(JDK, 'java.exe'), [
  '-jar', path.join(SDK, 'lib/apksigner.jar'),
  'sign',
  '--ks', key,
  '--ks-pass', 'pass:android',
  '--key-pass', 'pass:android',
  '--ks-key-alias', 'androiddebugkey',
  '--out', output,
  aligned
]);

run(path.join(JDK, 'java.exe'), [
  '-jar', path.join(SDK, 'lib/apksigner.jar'),
  'verify', '--verbose', output
]);

const sizeKb = (fs.statSync(output).size / 1024).toFixed(1);
console.log('====================================================');
console.log(`[✓] SUCCESS: Built native FastRead APK: ${output} (${sizeKb} KB)`);
console.log('====================================================');
