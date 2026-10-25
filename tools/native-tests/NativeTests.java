import com.fastread.nativeapp.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class NativeTests {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static class Time implements PlaybackClock.Scheduler {
        long now; final LinkedHashMap<Runnable, Long> tasks = new LinkedHashMap<Runnable, Long>();
        public long now() { return now; }
        public void post(Runnable task, long delay) { tasks.put(task, now + delay); }
        public void cancel(Runnable task) { tasks.remove(task); }
        void advance(long ms) {
            long end = now + ms;
            for (int guard = 0; guard < 10000; guard++) {
                Runnable first = null; long due = Long.MAX_VALUE;
                for (Map.Entry<Runnable, Long> item : tasks.entrySet()) if (item.getValue() < due) { due = item.getValue(); first = item.getKey(); }
                if (first == null || due > end) { now = end; return; }
                now = Math.max(now, due); tasks.remove(first); first.run();
            }
            throw new AssertionError("scheduler loop");
        }
    }
    static class Display implements PlaybackClock.Display {
        long id; int index; int stopped; boolean timeout, ended; final ArrayList<Integer> shown = new ArrayList<Integer>();
        public void show(int index, long id) { this.index = index; this.id = id; shown.add(index); }
        public void stopped(boolean end, boolean timeout) { stopped++; this.ended = end; this.timeout = timeout; }
    }
    static void zip(ZipOutputStream zip, String path, String contents) throws Exception { zip.putNextEntry(new ZipEntry(path)); zip.write(contents.getBytes("UTF-8")); zip.closeEntry(); }
    public static void main(String[] args) throws Exception {
        String demo = "Привет! Это FastRead — скорочтение для электронных книг Onyx BOOX.\n\nСмотри на выделенную букву и не двигай взглядом. Каждое слово выравнивается по центру, поэтому глазу не нужно прыгать по строке.\n\nНажми на центр экрана или Пробел для старта и паузы. Тап у левого края перематывает назад на 10 слов, тап у правого края — вперёд.\n\nКнопки громкости и листания на читалке также управляют чтением. Встретил интересное место — жми закладку слева, чтобы сохранить его.";
        BookData book = BookData.tokenize(demo, null);
        check(book.words.length == 71, "all 71 demo words retained");
        String lexicalSource = demo.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        StringBuilder out = new StringBuilder(); for (String word : book.words) out.append(word).append(' ');
        check(lexicalSource.equals(out.toString().replaceAll("[^\\p{L}\\p{N}]+", " ").trim()), "tokenizer preserves lexical order");
        check(BookData.focusIndex("«чтение!»", false) == 3, "punctuation does not shift focus");
        check(BookData.focusIndex("электронных", true) == 3, "ORP for long word");
        check(BookData.tokenize("déjà vu 中文 naïve", null).words.length == 4, "unicode letters are retained");
        System.out.println("PASS native: all demo words, punctuation, Unicode and focus calculation");

        Time time = new Time(); Display display = new Display(); PlaybackClock clock = new PlaybackClock(time, display);
        clock.setBook(book, 0); clock.configure(250, false, 0); clock.play();
        time.advance(1500); check(clock.index() == 0 && clock.playing(), "no advance before native draw");
        clock.onDrawn(display.id); clock.onDrawn(display.id); time.advance(239); check(clock.index() == 0, "full 240ms dwell");
        time.advance(1); check(clock.index() == 1, "exactly one advance");
        time.advance(1000); check(clock.index() == 1, "hold next word until it is drawn");
        clock.onDrawn(display.id);
        while (clock.playing()) { time.advance(240); if (clock.playing()) clock.onDrawn(display.id); }
        check(clock.completed == 71 && display.ended && display.shown.size() == 71, "all tokens finish once");
        for (int i = 0; i < display.shown.size(); i++) check(display.shown.get(i) == i, "sequential indices");
        System.out.println("PASS native: draw acknowledgement, long draw stalls, duplicate acknowledgements and all 71 indices");

        clock.seek(0); clock.play(); long stale = display.id; clock.seek(4); clock.onDrawn(stale);
        time.advance(1000); check(clock.index() == 4, "stale draw cannot arm the next word");
        clock.onDrawn(display.id); clock.pause(); time.advance(1000); check(clock.index() == 4, "pause cancels dwell");
        clock.play(); time.advance(3000); check(!clock.playing() && display.timeout && clock.index() == 4, "missing draw stops without losing index");
        clock.seek(0); clock.configure(600, false, 150); clock.play(); clock.onDrawn(display.id);
        time.advance(149); check(clock.index() == 0, "minimum screen hold"); time.advance(1); check(clock.index() == 1, "minimum completes"); clock.pause();
        clock.seek(0); clock.configure(600, false, 0); clock.play(); clock.onDrawn(display.id); time.advance(100); check(clock.index() == 1, "600 WPM becomes 100ms"); clock.pause();
        clock.seek(0); clock.configure(300, false, 0); clock.play();
        time.advance(40);
        clock.onDrawn(display.id);
        check(clock.averageDrawLatencyMs() == 40, "draw latency tracked");
        time.advance(199); check(clock.index() == 0, "full 200ms dwell after draw");
        time.advance(1); check(clock.index() == 1, "advance after full 200ms dwell");
        clock.pause();
        System.out.println("PASS native: stale callbacks, seek, pause, draw timeout, speed changes and optional minimum dwell");

        File dir = new File(args[0]); dir.mkdirs();
        File fb2 = new File(dir, "sample.fb2");
        String xml = "<?xml version='1.0' encoding='windows-1251'?><FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'><description><title-info><book-title>Тест</book-title></title-info></description><body><section><p>Первое <emphasis>слово</emphasis>.</p><p>Второе предложение.</p></section></body><body name='notes'><p>Сноски не читать</p></body></FictionBook>";
        Files.write(fb2.toPath(), xml.getBytes(Charset.forName("windows-1251")));
        BookParser.Parsed parsed = BookParser.parse(fb2, "sample.fb2");
        check(parsed.title.equals("Тест"), "FB2 title/encoding");
        check(parsed.text.contains("Первое слово.") && parsed.text.contains("Второе предложение.") && !parsed.text.contains("Сноски"), "FB2 paragraphs/notes");
        check(BookData.tokenize(parsed.text, parsed.chapters).words.length == 4, "FB2 exact token count");

        File epub = new File(dir, "sample.epub"); ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(epub));
        zip(zip, "META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>");
        zip(zip, "OPS/book.opf", "<package xmlns='http://www.idpf.org/2007/opf'><metadata><dc:title xmlns:dc='http://purl.org/dc/elements/1.1/'>EPUB тест</dc:title></metadata><manifest><item id='two' href='two.xhtml'/><item id='one' href='part/../one.xhtml'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>");
        zip(zip, "OPS/one.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Не читать</title></head><body><h1>Начало</h1><p>Первый&nbsp;текст.</p></body></html>");
        zip(zip, "OPS/two.xhtml", "<html><body><h1>Продолжение</h1><p>Второй текст.</p></body></html>"); zip.close();
        parsed = BookParser.parse(epub, "sample.epub"); book = BookData.tokenize(parsed.text, parsed.chapters);
        check(parsed.title.equals("EPUB тест"), "EPUB title");
        check(Arrays.equals(book.words, new String[]{"Начало", "Первый", "текст.", "Продолжение", "Второй", "текст."}), "EPUB follows spine and keeps all words");
        check(book.chapters.length == 2 && book.chapters[0].start == 0 && book.chapters[1].start == 3, "chapter offsets map to real tokens");
        check(book.chapterAt(2) == 0 && book.chapterAt(3) == 1, "chapter binary search boundary");
        System.out.println("PASS native: real FB2/windows-1251, EPUB ZIP/spine/path resolution and chapter boundaries");

        StringBuilder large = new StringBuilder(); for (int i = 0; i < 200000; i++) large.append("слово ");
        long started = System.nanoTime(); book = BookData.tokenize(large.toString(), null);
        check(book.words.length == 200000, "large book token count");
        System.out.println("PASS native: 200,000-word book tokenized in " + ((System.nanoTime() - started) / 1000000) + " ms on desktop JVM (not BOOX)");
        System.out.println("ALL NATIVE CORE TESTS PASSED; Android UI and physical E-Ink require device validation");
    }
}
