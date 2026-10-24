package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.SeekBar;
import java.util.List;

/**
 * Modern colorful ChapterSeekBar:
 * Sleek 4dp track with accent fill (#ff4b4e / #de2328), circular thumb, chapter ticks, and bookmark dots.
 */
public final class ChapterSeekBar extends SeekBar {
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BookData bookData;
    private List<Integer> bookmarks;
    private final float density;

    private int trackBgColor = Color.parseColor("#2c2e33");
    private int accentColor = Color.parseColor("#ff4b4e");
    private int thumbColor = Color.parseColor("#e9e6df");
    private int tickColor = Color.parseColor("#8a8c92");

    public ChapterSeekBar(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        applyStyling();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    public void setThemeColors(int trackBg, int accent, int thumb, int tick) {
        this.trackBgColor = trackBg;
        this.accentColor = accent;
        this.thumbColor = thumb;
        this.tickColor = tick;
        applyStyling();
        invalidate();
    }

    private void applyStyling() {
        tickPaint.setColor(tickColor);
        tickPaint.setStrokeWidth(2f * density);

        bmPaint.setColor(accentColor);
        bmPaint.setStrokeWidth(3.5f * density);

        // Sleek 4dp track with rounded corners
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(trackBgColor);
        trackBg.setCornerRadius(dp(2));
        trackBg.setSize(-1, dp(4));

        GradientDrawable trackProgress = new GradientDrawable();
        trackProgress.setColor(accentColor);
        trackProgress.setCornerRadius(dp(2));
        trackProgress.setSize(-1, dp(4));
        ClipDrawable clipProgress = new ClipDrawable(trackProgress, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackBg, clipProgress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        setProgressDrawable(layers);

        // 16dp circular thumb with crisp border
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(thumbColor);
        thumb.setStroke(dp(1.5f), trackBgColor);
        thumb.setSize(dp(16), dp(16));
        setThumb(thumb);
        setThumbOffset(dp(8));
    }

    public void setBookData(BookData data, List<Integer> bms) {
        this.bookData = data;
        this.bookmarks = bms;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (bookData != null && bookData.words != null && bookData.words.length > 1) {
            int total = bookData.words.length;
            int avail = getWidth() - getPaddingLeft() - getPaddingRight();
            int cy = getHeight() / 2;
            int tickH = dp(6);

            // Draw chapter ticks
            if (bookData.chapters != null) {
                for (BookData.Chapter ch : bookData.chapters) {
                    if (ch.start <= 0 || ch.start >= total) continue;
                    float ratio = (float) ch.start / (float) total;
                    float x = getPaddingLeft() + ratio * avail;
                    canvas.drawLine(x, cy - tickH, x, cy + tickH, tickPaint);
                }
            }

            // Draw bookmarks
            if (bookmarks != null && !bookmarks.isEmpty()) {
                int bmH = dp(8);
                for (Integer bm : bookmarks) {
                    if (bm < 0 || bm >= total) continue;
                    float ratio = (float) bm / (float) total;
                    float x = getPaddingLeft() + ratio * avail;
                    canvas.drawLine(x, cy - bmH, x, cy + bmH, bmPaint);
                }
            }
        }
    }
}
