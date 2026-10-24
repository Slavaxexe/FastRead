package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.SeekBar;
import java.util.List;

/**
 * Custom E-Ink styled SeekBar:
 * Sleek black track, rectangular thumb with crisp border, and chapter ticks.
 */
public final class ChapterSeekBar extends SeekBar {
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BookData bookData;
    private List<Integer> bookmarks;
    private final float density;

    public ChapterSeekBar(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        tickPaint.setColor(Color.BLACK);
        tickPaint.setStrokeWidth(3f * density);

        bmPaint.setColor(Color.BLACK);
        bmPaint.setStrokeWidth(5f * density);

        applyFlatStyling();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void applyFlatStyling() {
        // Flat track
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Color.BLACK);
        trackBg.setSize(-1, dp(5));

        GradientDrawable trackProgress = new GradientDrawable();
        trackProgress.setColor(Color.BLACK);
        trackProgress.setSize(-1, dp(5));
        ClipDrawable clipProgress = new ClipDrawable(trackProgress, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackBg, clipProgress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        setProgressDrawable(layers);

        // Flat rectangular thumb (18x24dp with 2dp border)
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(2), Color.BLACK);
        thumb.setCornerRadius(dp(2.5f));
        thumb.setSize(dp(18), dp(24));
        setThumb(thumb);
        setThumbOffset(dp(9));
    }

    public void setBookData(BookData data, List<Integer> bms) {
        this.bookData = data;
        this.bookmarks = bms;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (bookData != null && bookData.chapters != null && bookData.words != null && bookData.words.length > 1) {
            int total = bookData.words.length;
            int avail = getWidth() - getPaddingLeft() - getPaddingRight();
            int cy = getHeight() / 2;
            int tickH = dp(9);

            // Draw chapter ticks
            for (BookData.Chapter ch : bookData.chapters) {
                if (ch.start > 0 && ch.start < total) {
                    float x = getPaddingLeft() + ((float) ch.start / total) * avail;
                    canvas.drawLine(x, cy - tickH, x, cy + tickH, tickPaint);
                }
            }

            // Draw bookmark ticks
            if (bookmarks != null) {
                int bmH = dp(7);
                for (int bPos : bookmarks) {
                    if (bPos > 0 && bPos < total) {
                        float x = getPaddingLeft() + ((float) bPos / total) * avail;
                        canvas.drawLine(x, cy - bmH, x, cy + bmH, bmPaint);
                    }
                }
            }
        }
    }
}
