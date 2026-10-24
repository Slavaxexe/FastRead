<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/banner-light.png">
    <img alt="FastRead" src="docs/banner-dark.png" width="720">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/Slavaxexe/FastRead/raw/android-webview/dist/FastRead-1.2.apk">
    <img src="https://img.shields.io/badge/📥_СКАЧАТЬ_APK-v1.2_(92_КБ)-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Скачать APK">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Onyx_BOOX-Livingstone_%26_E--Ink-121315?style=flat-square&labelColor=2c2e33" alt="Onyx BOOX">
  <img src="https://img.shields.io/badge/Android-4.4%2B_(API_19%2B)-121315?style=flat-square&labelColor=2c2e33" alt="Android 4.4+">
  <img src="https://img.shields.io/badge/Офлайн-100%25_без_интернета-121315?style=flat-square&labelColor=0078d4" alt="Офлайн">
  <img src="https://img.shields.io/badge/Книги-EPUB_·_FB2_·_TXT_·_MD-121315?style=flat-square&labelColor=ff4b4e" alt="Форматы">
  <a href="LICENSE"><img src="https://img.shields.io/badge/Лицензия-MIT-121315?style=flat-square&labelColor=2c2e33" alt="MIT"></a>
</p>

<p align="center">
  <b>FastRead</b> — скоростная читалка методом <b>RSVP</b> (Rapid Serial Visual Presentation) для электронных книг <b>Onyx BOOX</b> и Android-устройств.<br>
  Слова показываются по одному в центре экрана с фиксацией взгляда на опорной букве (ORP). Глазам не нужно бегать по строкам — чтение ускоряется в 2–3 раза без усталости.
</p>

---

### 📥 Установка в один клик

* 📱 **[Скачать FastRead-1.2.apk](https://github.com/Slavaxexe/FastRead/raw/android-webview/dist/FastRead-1.2.apk)** *(прямая ссылка на готовый APK, 92 КБ)*.
* 🌐 **[Открыть веб-версию dist/index.html](dist/index.html)** *(работает офлайн в браузере на ПК или телефоне)*.

> Скопируйте APK в память ридера через USB или BooxDrop и откройте файл в диспетчере файлов для установки.

---

### ✨ Главные возможности

* **Идеально для E-Ink**: чистый чёрно-белый интерфейс 100% контраста без лишних анимаций и перерисовок. Опорная буква выделяется чёткой рамкой.
* **Стабильный движок (v1.2)**: точный темп без пропусков слов даже на слабых процессорах (Rockchip RK3128).
* **Форматы книг**: поддержка `.epub`, `.fb2` (с автоопределением кодировок UTF-8 и Windows-1251), `.txt`, `.md`.
* **100% автономность**: встроенный парсер `JSZip` — всё работает без интернета.
* **Аппаратные кнопки**: листание физическими кнопками на чехле-обложке BOOX и качельками громкости.
* **Контекст на паузе**: при остановке виден окружающий абзац текста. Клик по любому слову продолжает чтение с него.
* **Память прогресса**: читалка помнит позицию для каждой книги в библиотеке.

---

### 🎮 Управление

* **Тап по экрану**:
  * 👈 **Слева** — назад на 10 слов
  * 👆 **Центр** — Старт / Пауза
  * 👉 **Справа** — вперёд на 10 слов
* **Физические кнопки чехла / громкости**: листание назад / вперёд.
* **Клавиатура**: <kbd>Пробел</kbd> — пауза · <kbd>←</kbd> <kbd>→</kbd> — шаг на 1 слово · <kbd>↑</kbd> <kbd>↓</kbd> — скорость (WPM) · <kbd>L</kbd> — библиотека.

---

### 🛠️ Сборка

```bash
npm run build:web-apk   # собрать dist/FastRead.apk
npm run build           # собрать автономный dist/index.html
npm test                # запуск тестов совместимости и парсеров
```

---

<p align="center"><sub><a href="LICENSE">MIT License</a> © 2026 Вячеслав (Slavaxexe) & Herman Edelkin.</sub></p>
