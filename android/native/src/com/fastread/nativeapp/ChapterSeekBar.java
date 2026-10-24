package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.widget.SeekBar;
import java.util.List;

/**
 * Custom E-Ink styled SeekBar:
 * Sleek 3.5dp track, 14dp round thumb with crisp outline,
 * high-contrast chapter ticks (white on active, black on inactive with contrasting outlines),
 * and bookmark notches.
 */
public final class ChapterSeekBar extends SeekBar {
    private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackFgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BookData bookData;
    private List<Integer> bookmarks;
    private final float density;

    public ChapterSeekBar(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        // Prevent Android from drawing its default stretched drawables
        setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        // Use a transparent 1x1 drawable for thumb so SeekBar padding/touch logic still functions
        setThumb(new ColorDrawable(Color.TRANSPARENT));
        setThumbOffset(dp(7));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    public void setBookData(BookData data, List<Integer> bms) {
        this.bookData = data;
        this.bookmarks = bms;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int cy = h / 2;
        int padL = getPaddingLeft();
        int padR = getPaddingRight();
        int avail = w - padL - padR;
        if (avail <= 0) return;

        float trackH = dp(2.5f);
        int max = getMax();
        float ratio = max > 0 ? (float) getProgress() / max : 0f;
        ratio = Math.max(0f, Math.min(1f, ratio));
        float thumbX = padL + ratio * avail;

        // 1. Inactive background track (sleek light gray)
        trackBgPaint.setColor(Color.parseColor("#d8d8d8"));
        trackBgPaint.setStyle(Paint.Style.FILL);
        RectF bgRect = new RectF(padL, cy - trackH / 2f, padL + avail, cy + trackH / 2f);
        canvas.drawRoundRect(bgRect, trackH / 2f, trackH / 2f, trackBgPaint);

        // 2. Active progress track (crisp solid black)
        if (thumbX > padL) {
            trackFgPaint.setColor(Color.BLACK);
            trackFgPaint.setStyle(Paint.Style.FILL);
            RectF fgRect = new RectF(padL, cy - trackH / 2f, thumbX, cy + trackH / 2f);
            canvas.drawRoundRect(fgRect, trackH / 2f, trackH / 2f, trackFgPaint);
        }

        // 3. Chapter ticks
        if (bookData != null && bookData.chapters != null && bookData.words != null && bookData.words.length > 1) {
            int total = bookData.words.length;
            float tickH = dp(11f);
            float tickW = dp(2f);
            for (BookData.Chapter ch : bookData.chapters) {
                if (ch.start > 0 && ch.start < total) {
                    float x = padL + ((float) ch.start / total) * avail;
                    boolean overActive = x <= thumbX;

                    // Tick with crisp contrasting outline so it's visible anywhere
                    tickPaint.setStyle(Paint.Style.STROKE);
                    tickPaint.setStrokeWidth(tickW + dp(1.5f));
                    tickPaint.setColor(overActive ? Color.BLACK : Color.WHITE);
                    canvas.drawLine(x, cy - tickH / 2f, x, cy + tickH / 2f, tickPaint);

                    tickPaint.setStrokeWidth(tickW);
                    tickPaint.setColor(overActive ? Color.WHITE : Color.BLACK);
                    canvas.drawLine(x, cy - tickH / 2f, x, cy + tickH / 2f, tickPaint);
                }
            }

            // 4. Bookmark ticks
            if (bookmarks != null && !bookmarks.isEmpty()) {
                float bmH = dp(9f);
                float bmW = dp(3f);
                for (int i = 0; i < bookmarks.size(); i++) {
                    Integer bObj = bookmarks.get(i);
                    if (bObj == null) continue;
                    int bPos = bObj;
                    if (bPos > 0 && bPos < total) {
                        float x = padL + ((float) bPos / total) * avail;
                        boolean overActive = x <= thumbX;

                        tickPaint.setStyle(Paint.Style.STROKE);
                        tickPaint.setStrokeWidth(bmW + dp(1.5f));
                        tickPaint.setColor(overActive ? Color.BLACK : Color.WHITE);
                        canvas.drawLine(x, cy - bmH / 2f, x, cy + bmH / 2f, tickPaint);

                        tickPaint.setStrokeWidth(bmW);
                        tickPaint.setColor(overActive ? Color.WHITE : Color.BLACK);
                        canvas.drawLine(x, cy - bmH / 2f, x, cy + bmH / 2f, tickPaint);
                    }
                }
            }
        }

        // 5. Thumb: sleek round circular bead (black with crisp white ring)
        float thumbRadius = dp(6.5f);
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(Color.BLACK);
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);

        thumbPaint.setStyle(Paint.Style.STROKE);
        thumbPaint.setStrokeWidth(dp(1.8f));
        thumbPaint.setColor(Color.WHITE);
        canvas.drawCircle(thumbX, cy, thumbRadius - dp(0.9f), thumbPaint);
    }
}
