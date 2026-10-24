#!/usr/bin/env node

/**
 * FastRead APK Compiler
 * Compiles a native Android 4.4+ (API 19) APK directly without Gradle.
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const JDK_BIN = path.join(ROOT, 'tools/jdk/jdk-17.0.10+7/bin');
const SDK_BUILD_TOOLS = path.join(ROOT, 'tools/android-sdk/build-tools/28.0.3');
const ANDROID_JAR = path.join(ROOT, 'tools/android-sdk/platforms/android-28/android.jar');

const JAVA = path.join(JDK_BIN, 'java.exe');
const JAVAC = path.join(JDK_BIN, 'javac.exe');
const KEYTOOL = path.join(JDK_BIN, 'keytool.exe');
const AAPT = path.join(SDK_BUILD_TOOLS, 'aapt.exe');
const D8_JAR = path.join(SDK_BUILD_TOOLS, 'lib/d8.jar');
const ZIPALIGN = path.join(SDK_BUILD_TOOLS, 'zipalign.exe');
const APKSIGNER_JAR = path.join(SDK_BUILD_TOOLS, 'lib/apksigner.jar');

const ANDROID_DIR = path.join(ROOT, 'android/app/src/main');
const BUILD_TMP = path.join(ROOT, 'android/build_tmp');
const DIST_APK = path.join(ROOT, 'dist/FastRead.apk');

console.log('=== Building FastRead Android APK (API 19 / BOOX Livingstone) ===');

// 1. Build latest dist/index.html and copy to assets
console.log('[1/7] Building and bundling dist/index.html...');
execSync('node tools/build.js', { cwd: ROOT, stdio: 'inherit' });
const assetsDir = path.join(ANDROID_DIR, 'assets');
if (!fs.existsSync(assetsDir)) fs.mkdirSync(assetsDir, { recursive: true });
fs.copyFileSync(path.join(ROOT, 'dist/index.html'), path.join(assetsDir, 'index.html'));

// 2. Prepare build temp folders
if (fs.existsSync(BUILD_TMP)) {
  fs.rmSync(BUILD_TMP, { recursive: true, force: true });
}
const genDir = path.join(BUILD_TMP, 'gen');
const classesDir = path.join(BUILD_TMP, 'classes');
const dexDir = path.join(BUILD_TMP, 'dex');
fs.mkdirSync(genDir, { recursive: true });
fs.mkdirSync(classesDir, { recursive: true });
fs.mkdirSync(dexDir, { recursive: true });

const manifestXml = path.join(ANDROID_DIR, 'AndroidManifest.xml');
const resDir = path.join(ANDROID_DIR, 'res');

// 3. Generate R.java with aapt
console.log('[2/7] Generating R.java with aapt...');
execSync(`"${AAPT}" package -f -m -J "${genDir}" -M "${manifestXml}" -S "${resDir}" -I "${ANDROID_JAR}"`, { stdio: 'inherit' });

// 4. Compile Java sources with javac
console.log('[3/7] Compiling Java classes with javac (source 1.8)...');
const javaFiles = [
  path.join(ANDROID_DIR, 'java/com/fastread/reader/MainActivity.java'),
  path.join(genDir, 'com/fastread/reader/R.java')
];
execSync(`"${JAVAC}" -source 1.8 -target 1.8 -bootclasspath "${ANDROID_JAR}" -cp "${ANDROID_JAR}" -d "${classesDir}" "${javaFiles[0]}" "${javaFiles[1]}"`, { stdio: 'inherit' });

// 5. Convert class files to classes.dex with d8
console.log('[4/7] Converting to classes.dex with D8 (min-api 19)...');
const env = Object.assign({}, process.env, {
  JAVA_HOME: path.join(ROOT, 'tools/jdk/jdk-17.0.10+7'),
  PATH: `${JDK_BIN};${process.env.PATH}`
});
function getAllClassFiles(dir) {
  let res = [];
  for (const item of fs.readdirSync(dir)) {
    const full = path.join(dir, item);
    if (fs.statSync(full).isDirectory()) {
      res = res.concat(getAllClassFiles(full));
    } else if (full.endsWith('.class')) {
      res.push(full);
    }
  }
  return res;
}

const classFiles = getAllClassFiles(classesDir);
execSync(`"${JAVA}" -cp "${D8_JAR}" com.android.tools.r8.D8 --min-api 19 --lib "${ANDROID_JAR}" --output "${dexDir}" ${classFiles.map(f => `"${f}"`).join(' ')}`, { stdio: 'inherit' });

// 6. Package APK with aapt
console.log('[5/7] Packaging resources and assets into unaligned APK...');
const unalignedApk = path.join(BUILD_TMP, 'unaligned.apk');
execSync(`"${AAPT}" package -f -M "${manifestXml}" -S "${resDir}" -A "${assetsDir}" -I "${ANDROID_JAR}" -F "${unalignedApk}"`, { stdio: 'inherit' });

// Add classes.dex into unaligned APK
execSync(`"${AAPT}" add -k "${unalignedApk}" "classes.dex"`, { cwd: dexDir, stdio: 'inherit' });

// 7. Align APK with zipalign
console.log('[6/7] Aligning APK with zipalign...');
const alignedApk = path.join(BUILD_TMP, 'aligned.apk');
execSync(`"${ZIPALIGN}" -f 4 "${unalignedApk}" "${alignedApk}"`, { stdio: 'inherit' });

// 8. Sign APK with debug keystore
console.log('[7/7] Signing APK with apksigner...');
const keystore = path.join(ROOT, 'android/debug.keystore');
if (!fs.existsSync(keystore)) {
  console.log('    Generating debug keystore...');
  execSync(`"${KEYTOOL}" -genkey -v -keystore "${keystore}" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=FastRead,O=FastRead,C=RU"`, { stdio: 'inherit' });
}

execSync(`"${JAVA}" -jar "${APKSIGNER_JAR}" sign --ks "${keystore}" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "${DIST_APK}" "${alignedApk}"`, { stdio: 'inherit' });

const stats = fs.statSync(DIST_APK);
const sizeMb = (stats.size / (1024 * 1024)).toFixed(2);
console.log('====================================================');
console.log(`[✓] SUCCESS: Built native Android APK!`);
console.log(`    Location: ${DIST_APK} (${sizeMb} MB)`);
console.log('====================================================');
