package com.fastread.nativeapp;

/** All calls are on the UI thread. No word is consumed before its native draw. */
public final class PlaybackClock {
    public interface Scheduler { long now(); void post(Runnable task, long delay); void cancel(Runnable task); }
    public interface Display { void show(int index, long frameId); void stopped(boolean end, boolean drawTimeout); }
    private final Scheduler scheduler;
    private final Display display;
    private BookData book;
    private int index, wpm = 250, minimumMs;
    private boolean playing, awaitingDraw, smart = true;
    private long frameId, requestedAt, startedAt, lastCompletedAt;
    public long completed, elapsedMs, maxDrawWaitMs;
    private final Runnable advance = new Runnable() { @Override public void run() {
        if (!playing || awaitingDraw) return;
        long now = scheduler.now(); completed++; elapsedMs += now - lastCompletedAt; lastCompletedAt = now;
        if (index + 1 >= book.words.length) { stop(true, false); return; }
        index++; requestDraw();
    }};
    private final Runnable watchdog = new Runnable() { @Override public void run() { if (playing && awaitingDraw) stop(false, true); }};
    public PlaybackClock(Scheduler scheduler, Display display) { this.scheduler = scheduler; this.display = display; }
    public void setBook(BookData book, int position) { pause(); this.book = book; index = Math.max(0, Math.min(position, book.words.length - 1)); }
    public int index() { return index; }
    public boolean playing() { return playing; }
    public void configure(int wpm, boolean smart, int minimumMs) {
        this.wpm = Math.max(50, Math.min(1500, wpm)); this.smart = smart; this.minimumMs = Math.max(0, Math.min(500, minimumMs));
        if (playing) { scheduler.cancel(advance); scheduler.cancel(watchdog); requestDraw(); }
    }
    public void play() {
        if (playing || book == null || book.words.length == 0) return;
        if (index >= book.words.length - 1) index = 0;
        playing = true; completed = 0; elapsedMs = 0; maxDrawWaitMs = 0;
        startedAt = lastCompletedAt = scheduler.now(); requestDraw();
    }
    public void pause() { stop(false, false); }
    private void stop(boolean end, boolean timeout) {
        boolean wasPlaying = playing;
        playing = false; awaitingDraw = false; scheduler.cancel(advance); scheduler.cancel(watchdog);
        if (wasPlaying) display.stopped(end, timeout);
    }
    public void seek(int position) {
        if (book == null || book.words.length == 0) return;
        scheduler.cancel(advance); scheduler.cancel(watchdog);
        index = Math.max(0, Math.min(position, book.words.length - 1));
        if (playing) requestDraw(); else { awaitingDraw = false; display.show(index, ++frameId); }
    }
    private void requestDraw() {
        awaitingDraw = true; requestedAt = scheduler.now();
        scheduler.post(watchdog, 3000); display.show(index, ++frameId);
    }
    public void onDrawn(long id) {
        if (!playing || !awaitingDraw || id != frameId) return;
        awaitingDraw = false; scheduler.cancel(watchdog);
        maxDrawWaitMs = Math.max(maxDrawWaitMs, scheduler.now() - requestedAt);
        long duration = Math.max(minimumMs, Math.round(60000.0 / wpm * (smart ? book.weights[index] : 1)));
        scheduler.post(advance, Math.max(40, duration));
    }
    public long currentDurationMs() { return book == null || book.words.length == 0 ? 0 : Math.max(minimumMs, Math.round(60000.0 / wpm * (smart ? book.weights[index] : 1))); }
}
