package com.fastread.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BookData {
    public final String[] words;
    public final float[] weights;
    public final boolean[] paragraphEnds;
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
    private BookData(String[] words, float[] weights, boolean[] ends, Chapter[] chapters) {
        this.words = words; this.weights = weights; this.paragraphEnds = ends; this.chapters = chapters;
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
        Matcher matcher = WORD.matcher(text);
        String prefix = ""; int prefixStart = 0, previousEnd = 0;
        while (matcher.find()) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Загрузка отменена");
            int newlines = 0;
            for (int i = previousEnd; i < matcher.start(); i++) if (text.charAt(i) == '\n') newlines++;
            boolean paragraph = newlines >= 2;
            if (paragraph && !list.isEmpty()) {
                if (prefix.length() > 0) { int last = list.size() - 1; list.set(last, list.get(last) + prefix.trim()); prefix = ""; }
                paragraphEnds.add(list.size() - 1);
            }
            String word = matcher.group(); previousEnd = matcher.end();
            if (!hasLetters(word)) {
                boolean dash = word.matches("[—–-]+");
                boolean opener = word.matches("[«“„\"'‘(\\[{]+") || (dash && (list.isEmpty() || paragraph));
                if (opener) { if (prefix.length() == 0) prefixStart = matcher.start(); prefix += word + (dash ? " " : ""); }
                else if (!list.isEmpty()) { int last = list.size() - 1; list.set(last, list.get(last) + (dash ? " " : "") + word); }
                continue;
            }
            offsets.add(prefix.length() > 0 ? prefixStart : matcher.start());
            list.add(prefix + word); prefix = "";
            if (list.size() > 700000) throw new IllegalArgumentException("В книге больше 700 000 слов. Разделите её на части.");
        }
        if (prefix.length() > 0 && !list.isEmpty()) { int last = list.size() - 1; list.set(last, list.get(last) + prefix.trim()); }
        String[] words = list.toArray(new String[list.size()]);
        boolean[] ends = new boolean[words.length];
        for (int i = 0; i < paragraphEnds.size; i++) ends[paragraphEnds.data[i]] = true;
        if (ends.length > 0) ends[ends.length - 1] = true;
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
        if (characterChapters != null) for (Chapter chapter : characterChapters) {
            int lo = 0, hi = offsets.size;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (offsets.data[mid] < chapter.start) lo = mid + 1; else hi = mid; }
            if (lo < words.length && (chapters.isEmpty() || chapters.get(chapters.size() - 1).start != lo)) chapters.add(new Chapter(chapter.title, lo));
        }
        return new BookData(words, weights, ends, chapters.toArray(new Chapter[chapters.size()]));
    }
    public int chapterAt(int index) {
        int lo = 0, hi = chapters.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (chapters[mid].start <= index) lo = mid + 1; else hi = mid; }
        return lo - 1;
    }
}
