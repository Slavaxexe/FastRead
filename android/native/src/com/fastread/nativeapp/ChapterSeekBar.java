package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.SeekBar;
import java.util.List;

/**
 * Modern colorful ChapterSeekBar:
 * Razor-thin 2.5dp track with accent fill (#ff4b4e / #de2328), neat 11dp circular thumb, chapter ticks, and bookmark dots.
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
        tickPaint.setStrokeWidth(1.2f * density);

        bmPaint.setColor(accentColor);
        bmPaint.setStrokeWidth(2f * density);

        // Razor-thin 2.5dp track with rounded corners
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(trackBgColor);
        trackBg.setCornerRadius(dp(1.25f));
        trackBg.setSize(-1, dp(2.5f));

        GradientDrawable trackProgress = new GradientDrawable();
        trackProgress.setColor(accentColor);
        trackProgress.setCornerRadius(dp(1.25f));
        trackProgress.setSize(-1, dp(2.5f));
        ClipDrawable clipProgress = new ClipDrawable(trackProgress, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackBg, clipProgress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        setProgressDrawable(layers);

        // Neat 11dp circular thumb with subtle border
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(thumbColor);
        thumb.setStroke(dp(1.2f), trackBgColor);
        thumb.setSize(dp(11), dp(11));
        setThumb(thumb);
        setThumbOffset(dp(5.5f));
        setPadding(dp(12), dp(8), dp(12), dp(8));
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
            int tickH = dp(3.5f);

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
                int bmH = dp(5f);
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
