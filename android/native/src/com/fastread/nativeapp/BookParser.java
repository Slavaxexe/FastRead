package com.fastread.nativeapp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import org.json.*;

/** Import only: ZIP/XML/decoding runs on a worker, never in the playback loop. */
public final class BookParser {
    private static final int MAX_BYTES = 40 * 1024 * 1024;
    public static final class Parsed {
        public String title = "", text = "";
        public final ArrayList<BookData.Chapter> chapters = new ArrayList<BookData.Chapter>();
    }
    public static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Загрузка отменена");
            if (out.size() + n > limit) throw new IOException("Файл слишком большой. Разделите книгу на части.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    public static String decode(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 300), Charset.forName("US-ASCII"));
        Matcher encoding = Pattern.compile("encoding=[\"']([^\"']+)", Pattern.CASE_INSENSITIVE).matcher(head);
        if (encoding.find()) try { return new String(bytes, Charset.forName(encoding.group(1))); } catch (Exception ignored) {}
        try { return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception ignored) { return new String(bytes, Charset.forName("windows-1251")); }
    }
    private static void feature(SAXParserFactory factory, String feature, boolean value) { try { factory.setFeature(feature, value); } catch (Exception ignored) {} }
    private static void feature(DocumentBuilderFactory factory, String feature, boolean value) { try { factory.setFeature(feature, value); } catch (Exception ignored) {} }
    private static Document xml(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        feature(factory, "http://xml.org/sax/features/external-general-entities", false);
        feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(new EntityResolver() { public InputSource resolveEntity(String p, String s) { return new InputSource(new StringReader("")); } });
        return builder.parse(new InputSource(new StringReader(text)));
    }
    private static NodeList tags(Document document, String name) {
        NodeList nodes = document.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? document.getElementsByTagName(name) : nodes;
    }
    private static String attr(Node node, String name) { Node a = node.getAttributes().getNamedItem(name); return a == null ? "" : a.getNodeValue(); }
    private static String clean(String text) { return text.replace('\u00a0', ' ').replace("\u00ad", "").replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim(); }
    private static String fallbackHtml(String text) {
        return clean(text.replaceAll("(?is)<(script|style|head)\\b[^>]*>.*?</\\1>", "")
            .replaceAll("(?i)<(?:div|section|blockquote)[^>]*class=[\"'][^\"']*epigraph[^\"']*[\"'][^>]*>", "\n\n[[EPIGRAPH]]\n\n")
            .replaceAll("(?i)<epigraph\\b[^>]*>", "\n\n[[EPIGRAPH]]\n\n")
            .replaceAll("(?i)</(?:div|section|blockquote|epigraph)>", "\n\n[[/EPIGRAPH]]\n\n")
            .replaceAll("(?i)<(?:p|div|span|text-author)[^>]*class=[\"'][^\"']*(?:text-author|author)[^\"']*[\"'][^>]*>", "\n\n[[AUTHOR]]\n\n")
            .replaceAll("(?i)<text-author\\b[^>]*>", "\n\n[[AUTHOR]]\n\n")
            .replaceAll("(?i)<h[1-4]\\b[^>]*>", "\n\n[[CHAPTER]]\n\n")
            .replaceAll("(?i)</h[1-4]>", "\n\n[[/CHAPTER]]\n\n")
            .replaceAll("(?i)</?(p|div|h[1-6]|br|li|section|blockquote)\\b[^>]*>", "\n\n")
            .replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'"));
    }
    private static final class TextHandler extends DefaultHandler {
        final StringBuilder text = new StringBuilder();
        final StringBuilder title = new StringBuilder();
        final boolean fb2;
        int bodyDepth, depth, skipDepth, titleDepth, sectionTitleDepth;
        TextHandler(boolean fb2) { this.fb2 = fb2; }
        private void paragraph() { if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append("\n\n"); }
        public void startElement(String uri, String local, String name, Attributes attrs) {
            String tag = (local.length() > 0 ? local : name).toLowerCase(Locale.ROOT); depth++;
            if (tag.equals("book-title")) titleDepth = depth;
            if (skipDepth > 0) return;
            if (tag.equals("binary") || tag.equals("script") || tag.equals("style") || tag.equals("head") || (fb2 && tag.equals("body") && "notes".equals(attrs.getValue("name")))) { skipDepth = depth; return; }
            if (tag.equals("body")) bodyDepth = depth;
            if (tag.equals("epigraph")) { paragraph(); text.append("[[EPIGRAPH]]\n\n"); }
            else if (tag.equals("text-author")) { paragraph(); text.append("[[AUTHOR]]\n\n"); }
            else if (fb2 && tag.equals("title") && bodyDepth > 0) { sectionTitleDepth = depth; paragraph(); text.append("[[CHAPTER]]\n\n"); }
            else if (tag.matches("h[1-4]")) { paragraph(); text.append("[[CHAPTER]]\n\n"); }
            else if (tag.matches("p|v|div|h[1-6]|br|li|section|subtitle|blockquote")) paragraph();
        }
        public void endElement(String uri, String local, String name) {
            String tag = (local.length() > 0 ? local : name).toLowerCase(Locale.ROOT);
            if (skipDepth == depth) skipDepth = 0;
            else if (skipDepth == 0) {
                if (tag.equals("epigraph")) { paragraph(); text.append("[[/EPIGRAPH]]\n\n"); }
                else if (tag.equals("text-author")) { paragraph(); text.append("[[/AUTHOR]]\n\n"); }
                else if (fb2 && tag.equals("title") && sectionTitleDepth == depth) { sectionTitleDepth = 0; paragraph(); text.append("[[/CHAPTER]]\n\n"); }
                else if (tag.matches("h[1-4]")) { paragraph(); text.append("[[/CHAPTER]]\n\n"); }
                else if (tag.matches("p|v|div|h[1-6]|li|section|subtitle|blockquote")) paragraph();
            }
            if (bodyDepth == depth) bodyDepth = 0;
            if (titleDepth == depth) titleDepth = 0;
            depth--;
        }
        public void characters(char[] chars, int start, int length) {
            if (titleDepth > 0) title.append(chars, start, length);
            if (skipDepth == 0 && (!fb2 || bodyDepth > 0)) {
                if (text.length() + length > MAX_BYTES / 2) throw new IllegalArgumentException("Слишком много текста");
                // Collapse XML indentation locally; paragraph boundaries are explicit.
                for (int i = start; i < start + length; i++) {
                    char c = chars[i];
                    if (Character.isWhitespace(c)) {
                        if (text.length() > 0 && !Character.isWhitespace(text.charAt(text.length() - 1))) text.append(' ');
                    } else text.append(c);
                }
            }
        }
    }
    private static Parsed markup(String text, boolean fb2) throws Exception {
        TextHandler handler = new TextHandler(fb2);
        SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
        feature(factory, "http://xml.org/sax/features/external-general-entities", false);
        feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        XMLReader reader = factory.newSAXParser().getXMLReader(); reader.setContentHandler(handler);
        reader.setEntityResolver(new EntityResolver() { public InputSource resolveEntity(String p, String s) { return new InputSource(new StringReader("")); } });
        reader.parse(new InputSource(new StringReader(text.replace("&nbsp;", "&#160;")))) ;
        Parsed parsed = new Parsed(); parsed.text = clean(handler.text.toString()); parsed.title = clean(handler.title.toString()); return parsed;
    }
    private static String normalizePath(String path) {
        ArrayList<String> parts = new ArrayList<String>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else if (!part.equals(".") && part.length() > 0) parts.add(part);
        }
        StringBuilder result = new StringBuilder(); for (String part : parts) { if (result.length() > 0) result.append('/'); result.append(part); } return result.toString();
    }
    private static String entry(ZipFile zip, String name) throws IOException {
        ZipEntry found = zip.getEntry(name);
        if (found == null) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) { ZipEntry e = entries.nextElement(); if (e.getName().equalsIgnoreCase(name)) { found = e; break; } }
        }
        if (found == null) throw new IOException("В EPUB отсутствует " + name);
        InputStream input = zip.getInputStream(found); try { return decode(read(input, MAX_BYTES)); } finally { input.close(); }
    }
    private static Parsed epub(File file) throws Exception {
        ZipFile zip = new ZipFile(file);
        try {
            Document container = xml(entry(zip, "META-INF/container.xml"));
            NodeList roots = tags(container, "rootfile"); if (roots.getLength() == 0) throw new IOException("Некорректный EPUB: нет OPF");
            String opfPath = attr(roots.item(0), "full-path");
            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Document opf = xml(entry(zip, opfPath)); Parsed parsed = new Parsed();
            NodeList titles = tags(opf, "title"); if (titles.getLength() > 0) parsed.title = titles.item(0).getTextContent().trim();
            HashMap<String, String> manifest = new HashMap<String, String>();
            NodeList items = tags(opf, "item");
            for (int i = 0; i < items.getLength(); i++) manifest.put(attr(items.item(i), "id"), attr(items.item(i), "href"));
            NodeList spine = tags(opf, "itemref"); StringBuilder text = new StringBuilder();
            for (int i = 0; i < spine.getLength(); i++) {
                String href = manifest.get(attr(spine.item(i), "idref")); if (href == null) throw new IOException("Нарушен порядок глав EPUB");
                String path = normalizePath(base + java.net.URLDecoder.decode(href.split("#")[0].replace("+", "%2B"), "UTF-8"));
                String html = entry(zip, path); String chapter;
                try { chapter = markup(html, false).text; } catch (Exception e) { chapter = fallbackHtml(html); }
                if (chapter.length() == 0) continue;
                Matcher heading = Pattern.compile("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>").matcher(html);
                String name = heading.find() ? fallbackHtml(heading.group(1)) : "Раздел " + (i + 1);
                parsed.chapters.add(new BookData.Chapter(name, text.length()));
                text.append(chapter).append("\n\n");
                if (text.length() > MAX_BYTES / 2) throw new IOException("Текста слишком много. Разделите EPUB на части.");
            }
            parsed.text = text.toString(); return parsed;
        } finally { zip.close(); }
    }
    public static Parsed parse(File file, String name) throws Exception {
        String lower = name.toLowerCase(Locale.ROOT); Parsed parsed;
        if (lower.endsWith(".epub")) parsed = epub(file);
        else {
            InputStream input = new FileInputStream(file); String text;
            try { text = decode(read(input, MAX_BYTES)); } finally { input.close(); }
            if (lower.endsWith(".fb2")) parsed = markup(text, true);
            else if (lower.endsWith(".html") || lower.endsWith(".htm")) { try { parsed = markup(text, false); } catch (Exception e) { parsed = new Parsed(); parsed.text = fallbackHtml(text); } }
            else if (lower.endsWith(".json")) {
                JSONObject json = new JSONObject(text); parsed = new Parsed(); parsed.title = json.optString("title");
                JSONArray paragraphs = json.optJSONArray("paragraphs"); if (paragraphs == null) throw new IOException("JSON не является текстовым паком");
                StringBuilder out = new StringBuilder();
                for (int p = 0; p < paragraphs.length(); p++) {
                    Object item = paragraphs.get(p); JSONArray sentences = item instanceof JSONArray ? (JSONArray)item : ((JSONObject)item).optJSONArray("sentences");
                    if (sentences == null) continue;
                    for (int s = 0; s < sentences.length(); s++) out.append(sentences.getJSONObject(s).optString("text")).append(' ');
                    out.append("\n\n");
                }
                parsed.text = out.toString();
            } else { parsed = new Parsed(); parsed.text = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u00ad", ""); }
        }
        if (parsed.title.length() == 0) parsed.title = name.replaceFirst("\\.[^.]+$", "");
        if (parsed.text.trim().length() == 0) throw new IOException("В файле не найден текст");
        return parsed;
    }
}
