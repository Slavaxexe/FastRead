package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.SeekBar;
import java.util.List;

/**
 * Custom E-Ink styled SeekBar:
 * Sleek 3.5dp track, rectangular thumb with crisp border,
 * and high-contrast chapter ticks with white outlines.
 */
public final class ChapterSeekBar extends SeekBar {
    private final Paint tickFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BookData bookData;
    private List<Integer> bookmarks;
    private final float density;

    public ChapterSeekBar(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        tickFill.setColor(Color.BLACK);
        tickFill.setStyle(Paint.Style.FILL);

        tickOutline.setColor(Color.WHITE);
        tickOutline.setStyle(Paint.Style.STROKE);
        tickOutline.setStrokeWidth(dp(1.5f));

        bmFill.setColor(Color.BLACK);
        bmFill.setStyle(Paint.Style.FILL);

        bmOutline.setColor(Color.WHITE);
        bmOutline.setStyle(Paint.Style.STROKE);
        bmOutline.setStrokeWidth(dp(1.5f));

        applyFlatStyling();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void applyFlatStyling() {
        // Sleek flat track: 3.5dp high
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Color.parseColor("#c8c8c8"));
        trackBg.setSize(-1, dp(3.5f));

        GradientDrawable trackProgress = new GradientDrawable();
        trackProgress.setColor(Color.BLACK);
        trackProgress.setSize(-1, dp(3.5f));
        ClipDrawable clipProgress = new ClipDrawable(trackProgress, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{trackBg, clipProgress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        setProgressDrawable(layers);

        // Crisp flat rectangular thumb (18x22dp with 2dp border)
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(Color.WHITE);
        thumb.setStroke(dp(2), Color.BLACK);
        thumb.setCornerRadius(dp(2f));
        thumb.setSize(dp(18), dp(22));
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
            int tickH = dp(16);
            float tickHalfW = dp(1.8f);

            // Draw chapter ticks with high-contrast white outline + black notch
            for (BookData.Chapter ch : bookData.chapters) {
                if (ch.start > 0 && ch.start < total) {
                    float x = getPaddingLeft() + ((float) ch.start / total) * avail;
                    RectF outRect = new RectF(x - tickHalfW - dp(1.2f), cy - tickH / 2f - dp(1.2f),
                                              x + tickHalfW + dp(1.2f), cy + tickH / 2f + dp(1.2f));
                    RectF inRect = new RectF(x - tickHalfW, cy - tickH / 2f,
                                             x + tickHalfW, cy + tickH / 2f);
                    canvas.drawRect(outRect, tickOutline);
                    canvas.drawRect(inRect, tickFill);
                }
            }

            // Draw bookmark ticks with high-contrast notch
            if (bookmarks != null) {
                int bmH = dp(12);
                float bmHalfW = dp(3.2f);
                for (int bPos : bookmarks) {
                    if (bPos > 0 && bPos < total) {
                        float x = getPaddingLeft() + ((float) bPos / total) * avail;
                        RectF outBm = new RectF(x - bmHalfW - dp(1.2f), cy - bmH / 2f - dp(1.2f),
                                               x + bmHalfW + dp(1.2f), cy + bmH / 2f + dp(1.2f));
                        RectF inBm = new RectF(x - bmHalfW, cy - bmH / 2f,
                                              x + bmHalfW, cy + bmH / 2f);
                        canvas.drawRoundRect(outBm, dp(2), dp(2), bmOutline);
                        canvas.drawRoundRect(inBm, dp(1.5f), dp(1.5f), bmFill);
                    }
                }
            }
        }
    }
}
