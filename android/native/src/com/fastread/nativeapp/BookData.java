package com.fastread.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BookData {
    public static final byte TYPE_NORMAL = 0;
    public static final byte TYPE_EPIGRAPH = 1;
    public static final byte TYPE_AUTHOR = 2;
    public static final byte TYPE_CHAPTER = 3;

    public final String[] words;
    public final float[] weights;
    public final boolean[] paragraphEnds;
    public final byte[] types;
    public final Chapter[] chapters;

    public static final class Chapter {
        public final String title;
        public final int start;
        public Chapter(String title, int start) { this.title = title; this.start = start; }
    }

    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final class Ints {
        int[] data = new int[1024]; int size;
        void add(int value) { if (size == data.length) data = Arrays.copyOf(data, data.length * 2); data[size++] = value; }
    }
    private static final class Bytes {
        byte[] data = new byte[1024]; int size;
        void add(byte value) { if (size == data.length) data = Arrays.copyOf(data, data.length * 2); data[size++] = value; }
    }

    public BookData(String[] words, float[] weights, boolean[] ends, byte[] types, Chapter[] chapters) {
        this.words = words; this.weights = weights; this.paragraphEnds = ends; this.types = types; this.chapters = chapters;
    }

    public BookData(String[] words, float[] weights, boolean[] ends, Chapter[] chapters) {
        this(words, weights, ends, new byte[words.length], chapters);
    }

    public static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isLetterOrDigit(text.charAt(i))) return true;
        return false;
    }

    public static int focusIndex(String word, boolean orp) {
        int first = -1, last = -1;
        for (int i = 0; i < word.length(); i++) if (Character.isLetterOrDigit(word.charAt(i))) { if (first < 0) first = i; last = i; }
        if (first < 0) return Math.max(0, (word.length() - 1) / 2);
        int n = last - first + 1;
        int offset = orp ? (n <= 1 ? 0 : n <= 5 ? 1 : n <= 9 ? 2 : n <= 13 ? 3 : 4) : (n - 1) / 2;
        return first + Math.min(offset, n - 1);
    }

    public static BookData tokenize(String text, ArrayList<Chapter> characterChapters) {
        ArrayList<String> list = new ArrayList<String>();
        Ints offsets = new Ints(); Ints paragraphEnds = new Ints();
        Bytes wordTypes = new Bytes();

        Matcher matcher = WORD.matcher(text);
        String prefix = ""; int prefixStart = 0, previousEnd = 0;
        byte currentType = TYPE_NORMAL;

        while (matcher.find()) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Загрузка отменена");
            String rawWord = matcher.group();

            if (rawWord.equals("[[EPIGRAPH]]")) { currentType = TYPE_EPIGRAPH; previousEnd = matcher.end(); continue; }
            if (rawWord.equals("[[/EPIGRAPH]]")) { currentType = TYPE_NORMAL; previousEnd = matcher.end(); continue; }
            if (rawWord.equals("[[AUTHOR]]")) { currentType = TYPE_AUTHOR; previousEnd = matcher.end(); continue; }
            if (rawWord.equals("[[/AUTHOR]]")) { currentType = TYPE_NORMAL; previousEnd = matcher.end(); continue; }
            if (rawWord.equals("[[CHAPTER]]")) { currentType = TYPE_CHAPTER; previousEnd = matcher.end(); continue; }
            if (rawWord.equals("[[/CHAPTER]]")) { currentType = TYPE_NORMAL; previousEnd = matcher.end(); continue; }

            int newlines = 0;
            for (int i = previousEnd; i < matcher.start(); i++) if (text.charAt(i) == '\n') newlines++;
            boolean paragraph = newlines >= 2;
            if (paragraph && !list.isEmpty()) {
                if (prefix.length() > 0) { int last = list.size() - 1; list.set(last, list.get(last) + prefix.trim()); prefix = ""; }
                paragraphEnds.add(list.size() - 1);
            }
            previousEnd = matcher.end();

            if (!hasLetters(rawWord)) {
                boolean dash = rawWord.matches("[—–-]+");
                boolean opener = rawWord.matches("[«“„\"'‘(\\[{]+") || (dash && (list.isEmpty() || paragraph));
                if (opener) { if (prefix.length() == 0) prefixStart = matcher.start(); prefix += rawWord + (dash ? " " : ""); }
                else if (!list.isEmpty()) { int last = list.size() - 1; list.set(last, list.get(last) + (dash ? " " : "") + rawWord); }
                continue;
            }

            offsets.add(prefix.length() > 0 ? prefixStart : matcher.start());
            list.add(prefix + rawWord); prefix = "";
            wordTypes.add(currentType);

            if (list.size() > 700000) throw new IllegalArgumentException("В книге больше 700 000 слов. Разделите её на части.");
        }
        if (prefix.length() > 0 && !list.isEmpty()) { int last = list.size() - 1; list.set(last, list.get(last) + prefix.trim()); }

        String[] words = list.toArray(new String[list.size()]);
        boolean[] ends = new boolean[words.length];
        for (int i = 0; i < paragraphEnds.size; i++) ends[paragraphEnds.data[i]] = true;
        if (ends.length > 0) ends[ends.length - 1] = true;

        byte[] types = new byte[words.length];
        for (int i = 0; i < words.length && i < wordTypes.size; i++) types[i] = wordTypes.data[i];

        // Post-pass: detect chapter titles, book header, and plain-text epigraph + author patterns
        ArrayList<int[]> paragraphs = new ArrayList<int[]>();
        int curPStart = 0;
        for (int i = 0; i < words.length; i++) {
            if (ends[i]) {
                paragraphs.add(new int[]{curPStart, i});
                curPStart = i + 1;
            }
        }

        ArrayList<Chapter> detectedChapters = new ArrayList<Chapter>();

        // 1. Detect chapter headings by text patterns
        for (int pi = 0; pi < paragraphs.size(); pi++) {
            int[] p = paragraphs.get(pi);
            int pLen = p[1] - p[0] + 1;
            if (pLen >= 1 && pLen <= 30 && types[p[0]] == TYPE_NORMAL) {
                StringBuilder pBuf = new StringBuilder();
                for (int k = p[0]; k <= p[1]; k++) pBuf.append(k == p[0] ? "" : " ").append(words[k]);
                String pText = pBuf.toString().trim();
                if (pText.matches("^(?:(?:\\d+|[IVXLCDM]+)\\.|(?:Глава|Часть|Раздел|Section|Chapter|ПРОЛОГ|ВВЕДЕНИЕ|ЭПИЛОГ)\\b)[\\s\\S]*")) {
                    for (int k = p[0]; k <= p[1]; k++) types[k] = TYPE_CHAPTER;
                    detectedChapters.add(new Chapter(pText, p[0]));
                }
            }
        }

        // 2. Book Title & Author header at start of book:
        // If the book starts with 1 or 2 short title/author lines before any epigraph or chapters,
        // highlight them as TYPE_CHAPTER so they are styled prominently like chapter headings
        if (paragraphs.size() >= 2) {
            int[] p0 = paragraphs.get(0);
            int len0 = p0[1] - p0[0] + 1;
            int[] p1 = paragraphs.get(1);
            int len1 = p1[1] - p1[0] + 1;
            if (types[p0[0]] == TYPE_NORMAL && types[p1[0]] == TYPE_NORMAL && len0 <= 8 && len1 <= 10) {
                for (int k = p0[0]; k <= p0[1]; k++) types[k] = TYPE_CHAPTER;
                for (int k = p1[0]; k <= p1[1]; k++) types[k] = TYPE_CHAPTER;
            } else if (types[p0[0]] == TYPE_NORMAL && len0 <= 8) {
                for (int k = p0[0]; k <= p0[1]; k++) types[k] = TYPE_CHAPTER;
            }
        } else if (paragraphs.size() == 1) {
            int[] p0 = paragraphs.get(0);
            int len0 = p0[1] - p0[0] + 1;
            if (types[p0[0]] == TYPE_NORMAL && len0 <= 8) {
                for (int k = p0[0]; k <= p0[1]; k++) types[k] = TYPE_CHAPTER;
            }
        }

        // 3. Epigraph + Author detection in plain text / books without explicit markup:
        // Matches a short quote (3-70 words) immediately followed by an author signature (1-6 words)
        for (int pi = 0; pi < paragraphs.size() - 1; pi++) {
            int[] curP = paragraphs.get(pi);
            if (types[curP[0]] != TYPE_NORMAL) continue;
            int pLen = curP[1] - curP[0] + 1;
            if (pLen < 3 || pLen > 70) continue;

            int[] nextP = paragraphs.get(pi + 1);
            if (types[nextP[0]] != TYPE_NORMAL && types[nextP[0]] != TYPE_AUTHOR) continue;
            int nextLen = nextP[1] - nextP[0] + 1;
            if (nextLen < 1 || nextLen > 6) continue;

            StringBuilder authorBuf = new StringBuilder();
            for (int k = nextP[0]; k <= nextP[1]; k++) authorBuf.append(k == nextP[0] ? "" : " ").append(words[k]);
            String authorLine = authorBuf.toString().trim();

            if (authorLine.matches("^(?:[—–-]\\s*)?[А-ЯЁA-Z][а-яёa-zА-ЯЁA-Z.\\s]{1,40}$") &&
                !authorLine.endsWith(",") && !authorLine.endsWith(":") && !authorLine.endsWith("?")) {
                for (int k = curP[0]; k <= curP[1]; k++) if (types[k] == TYPE_NORMAL) types[k] = TYPE_EPIGRAPH;
                for (int k = nextP[0]; k <= nextP[1]; k++) if (types[k] == TYPE_NORMAL) types[k] = TYPE_AUTHOR;
                pi++;
            }
        }

        float[] weights = new float[words.length];
        for (int i = 0; i < words.length; i++) {
            String w = words[i]; int letters = 0; boolean number = false;
            for (int j = 0; j < w.length(); j++) { char c = w.charAt(j); if (Character.isLetterOrDigit(c)) letters++; if (Character.isDigit(c)) number = true; }
            float m = 1f + Math.min(.7f, Math.max(0, letters - 7) * .07f) + (number ? .3f : 0);
            int last = w.length() - 1;
            while (last >= 0 && "»”\"'’) ]}".indexOf(w.charAt(last)) >= 0) last--;
            char tail = last >= 0 ? w.charAt(last) : ' ';
            if (ends[i]) m += 1.6f;
            else if (".!?…".indexOf(tail) >= 0) m += 1;
            else if (",;:—–".indexOf(tail) >= 0) m += .45f;
            weights[i] = m;
        }

        ArrayList<Chapter> chapters = new ArrayList<Chapter>();
        if (characterChapters != null && !characterChapters.isEmpty()) {
            for (Chapter chapter : characterChapters) {
                int lo = 0, hi = offsets.size;
                while (lo < hi) { int mid = (lo + hi) >>> 1; if (offsets.data[mid] < chapter.start) lo = mid + 1; else hi = mid; }
                String chapterTitle = chapter.title;
                // If chapter has generic title (like "Раздел 6"), check if we detected a real title nearby
                for (Chapter detected : detectedChapters) {
                    if (Math.abs(detected.start - lo) <= 15) {
                        chapterTitle = detected.title;
                        break;
                    }
                }
                if (lo < words.length && (chapters.isEmpty() || chapters.get(chapters.size() - 1).start != lo)) {
                    chapters.add(new Chapter(chapterTitle, lo));
                }
            }
        } else if (!detectedChapters.isEmpty()) {
            chapters.addAll(detectedChapters);
        }
        return new BookData(words, weights, ends, types, chapters.toArray(new Chapter[chapters.size()]));
    }

    public int chapterAt(int index) {
        int lo = 0, hi = chapters.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (chapters[mid].start <= index) lo = mid + 1; else hi = mid; }
        return lo - 1;
    }
}
