package com.fastread.nativeapp;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/** Text is written once on import; progress writes only a small JSON record. */
public final class BookStore {
    public static final class Record {
        public String id, title;
        public int pos, total;
        public long updated;
        public final ArrayList<Integer> bookmarks = new ArrayList<Integer>();
        public Record(String id, String title) { this.id = id; this.title = title; }
    }
    private final File directory;
    public BookStore(Context context) { directory = new File(context.getFilesDir(), "native-books"); directory.mkdirs(); }
    private File textFile(String id) { return new File(directory, id + ".txt"); }
    private File metadataFile(String id) { return new File(directory, id + ".json"); }
    private String read(File file) throws IOException {
        InputStream input = new FileInputStream(file);
        try { return new String(BookParser.read(input, 40 * 1024 * 1024), Charset.forName("UTF-8")); } finally { input.close(); }
    }
    public synchronized Record add(BookParser.Parsed parsed, BookData book) throws Exception {
        Record record = new Record(UUID.randomUUID().toString(), parsed.title); record.total = book.words.length;
        AtomicFile text = new AtomicFile(textFile(record.id)); FileOutputStream out = null;
        try { out = text.startWrite(); out.write(parsed.text.getBytes("UTF-8")); text.finishWrite(out); }
        catch (Exception e) { if (out != null) text.failWrite(out); throw e; }
        // Character offsets are retained so chapter mapping can be rebuilt exactly.
        JSONArray chapters = new JSONArray();
        for (BookData.Chapter chapter : parsed.chapters) { JSONObject c = new JSONObject(); c.put("title", chapter.title); c.put("start", chapter.start); chapters.put(c); }
        AtomicFile chapterFile = new AtomicFile(new File(directory, record.id + ".chapters")); out = null;
        try { out = chapterFile.startWrite(); out.write(chapters.toString().getBytes("UTF-8")); chapterFile.finishWrite(out); }
        catch (Exception e) { if (out != null) chapterFile.failWrite(out); throw e; }
        save(record); return record;
    }
    public synchronized void save(Record record) throws Exception {
        if (record == null || record.id.equals("demo")) return;
        record.updated = System.currentTimeMillis();
        JSONObject json = new JSONObject(); json.put("id", record.id); json.put("title", record.title);
        json.put("pos", record.pos); json.put("total", record.total); json.put("updated", record.updated);
        JSONArray marks = new JSONArray(); for (Integer mark : record.bookmarks) marks.put(mark); json.put("bookmarks", marks);
        AtomicFile file = new AtomicFile(metadataFile(record.id)); FileOutputStream output = null;
        try { output = file.startWrite(); output.write(json.toString().getBytes("UTF-8")); file.finishWrite(output); }
        catch (Exception e) { if (output != null) file.failWrite(output); throw e; }
    }
    public synchronized ArrayList<Record> list() {
        ArrayList<Record> records = new ArrayList<Record>(); File[] files = directory.listFiles(); if (files == null) return records;
        for (File file : files) if (file.getName().endsWith(".json")) try {
            JSONObject json = new JSONObject(read(file)); String id = json.getString("id");
            if (!id.matches("[a-fA-F0-9-]+") || !textFile(id).isFile()) continue;
            Record record = new Record(id, json.getString("title")); record.pos = json.optInt("pos"); record.total = json.optInt("total"); record.updated = json.optLong("updated");
            JSONArray marks = json.optJSONArray("bookmarks"); if (marks != null) for (int i = 0; i < marks.length(); i++) record.bookmarks.add(marks.getInt(i));
            records.add(record);
        } catch (Exception ignored) {}
        Collections.sort(records, new Comparator<Record>() { public int compare(Record a, Record b) { return a.updated == b.updated ? 0 : a.updated > b.updated ? -1 : 1; } }); return records;
    }
    public synchronized BookData load(Record record) throws Exception {
        String text = read(textFile(record.id)); ArrayList<BookData.Chapter> chapters = new ArrayList<BookData.Chapter>();
        File chapterFile = new File(directory, record.id + ".chapters");
        if (chapterFile.isFile()) {
            JSONArray json = new JSONArray(read(chapterFile));
            for (int i = 0; i < json.length(); i++) chapters.add(new BookData.Chapter(json.getJSONObject(i).getString("title"), json.getJSONObject(i).getInt("start")));
        }
        return BookData.tokenize(text, chapters);
    }
    public synchronized void delete(Record record) {
        new AtomicFile(textFile(record.id)).delete(); new AtomicFile(metadataFile(record.id)).delete();
        new AtomicFile(new File(directory, record.id + ".chapters")).delete();
    }
}
