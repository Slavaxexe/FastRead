package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** One opaque, fixed canvas. Changing a word invalidates only its word region. */
public final class WordView extends View {
    public interface DrawListener { void onDrawn(long frameId); }
    public interface TapListener { void onTap(int zone); }
    private final Paint normal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint decor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final Rect wordRegion = new Rect();
    private String word = "";
    private long frameId, acknowledgedId = -1;
    private int focus, background = Color.WHITE, foreground = Color.BLACK;
    private float sizeSp = 46, density;
    private boolean orp, dark;
    private String style = "box", font = "serif";
    private DrawListener drawListener;
    private TapListener tapListener;
    public long drawCount, maxDrawNanos;

    public WordView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
    }

    public void setListeners(DrawListener draw, TapListener tap) {
        drawListener = draw;
        tapListener = tap;
    }

    public void setMonochrome(boolean monochrome) {
        normal.setAntiAlias(!monochrome);
        focusPaint.setAntiAlias(!monochrome);
        decor.setAntiAlias(!monochrome);
        invalidate();
    }

    public void configure(float size, String font, String style, boolean orp, boolean dark) {
        sizeSp = size;
        this.font = font;
        this.style = style;
        this.orp = orp;
        this.dark = dark;
        background = dark ? Color.BLACK : Color.WHITE;
        foreground = dark ? Color.WHITE : Color.BLACK;
        setBackgroundColor(background);

        Typeface type;
        if (font.equals("sans")) {
            type = Typeface.SANS_SERIF;
        } else if (font.equals("mono")) {
            type = Typeface.MONOSPACE;
        } else {
            type = Typeface.SERIF;
        }

        normal.setTypeface(type);
        focusPaint.setTypeface(Typeface.create(type, Typeface.BOLD));
        focus = BookData.focusIndex(word, orp);
        invalidate();
    }

    public void showWord(String word, long id) {
        this.word = word == null ? "" : word;
        frameId = id;
        focus = BookData.focusIndex(this.word, orp);
        updateRegion();
        invalidate(wordRegion);
    }

    private void updateRegion() {
        int margin = Math.round(16 * density);
        wordRegion.set(0, margin, getWidth(), Math.max(margin, getHeight() - margin));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        updateRegion();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long started = System.nanoTime();
        super.onDraw(canvas);

        // 1. Solid clear of word stage
        decor.setColor(background);
        decor.setStyle(Paint.Style.FILL);
        canvas.drawRect(wordRegion, decor);

        // 2. Guide lines with ticks
        decor.setColor(foreground);
        decor.setStrokeWidth(2f * density);
        float center = getWidth() / 2f;
        float top = 16 * density;
        float bottom = getHeight() - 16 * density;

        canvas.drawLine(14 * density, top, getWidth() - 14 * density, top, decor);
        canvas.drawLine(14 * density, bottom, getWidth() - 14 * density, bottom, decor);
        canvas.drawLine(center, top, center, top + 10 * density, decor);
        canvas.drawLine(center, bottom - 10 * density, center, bottom, decor);

        // 3. Word typography & focus point
        if (word.length() > 0) {
            float size = sizeSp * getResources().getDisplayMetrics().scaledDensity;
            normal.setTextSize(size);
            focusPaint.setTextSize(size);

            float padding = (style.equals("box") ? 3.5f : style.equals("invert") ? 3f : 0f) * density;
            float fw = focusPaint.measureText(word, focus, focus + 1);
            float leftWidth = normal.measureText(word, 0, focus);
            float rightWidth = normal.measureText(word, focus + 1, word.length());
            float side = Math.max(leftWidth, rightWidth) + fw / 2f + padding;
            float available = getWidth() / 2f - 14 * density;

            if (side > available && side > 0) {
                float factor = available / side;
                size *= factor;
                normal.setTextSize(size);
                focusPaint.setTextSize(size);
                fw = focusPaint.measureText(word, focus, focus + 1);
                leftWidth = normal.measureText(word, 0, focus);
            }

            normal.getFontMetrics(metrics);
            float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
            float left = center - fw / 2f - padding;
            float right = center + fw / 2f + padding;

            normal.setColor(foreground);
            focusPaint.setColor(foreground);

            canvas.drawText(word, 0, focus, left - leftWidth, baseline, normal);
            canvas.drawText(word, focus + 1, word.length(), right, baseline, normal);

            Rect charBounds = new Rect();
            focusPaint.getTextBounds(word, focus, focus + 1, charBounds);
            float capHeight = Math.abs(metrics.ascent) * 0.74f;
            float topOffset = Math.min(-capHeight, (float) charBounds.top);
            float bottomOffset = Math.max(metrics.descent * 0.18f, (float) charBounds.bottom);

            float padY = 3f * density;
            float boxTop = baseline + topOffset - padY;
            float boxBottom = baseline + bottomOffset + padY;

            if (style.equals("invert")) {
                decor.setColor(foreground);
                decor.setStyle(Paint.Style.FILL);
                RectF invRect = new RectF(left, boxTop, right, boxBottom);
                canvas.drawRoundRect(invRect, 2.5f * density, 2.5f * density, decor);
                focusPaint.setColor(background);
            } else if (style.equals("box")) {
                decor.setStyle(Paint.Style.STROKE);
                decor.setStrokeWidth(2f * density);
                RectF boxRect = new RectF(left, boxTop, right, boxBottom);
                canvas.drawRoundRect(boxRect, 2.5f * density, 2.5f * density, decor);
                decor.setStyle(Paint.Style.FILL);
            } else if (style.equals("underline")) {
                decor.setStrokeWidth(3f * density);
                canvas.drawLine(left, boxBottom, right, boxBottom, decor);
            } else if (style.equals("markers")) {
                decor.setStrokeWidth(2f * density);
                canvas.drawLine(center - 5 * density, boxTop - 8 * density, center, boxTop - 2 * density, decor);
                canvas.drawLine(center + 5 * density, boxTop - 8 * density, center, boxTop - 2 * density, decor);
                canvas.drawLine(center - 5 * density, boxBottom + 8 * density, center, boxBottom + 2 * density, decor);
                canvas.drawLine(center + 5 * density, boxBottom + 8 * density, center, boxBottom + 2 * density, decor);
            }

            canvas.drawText(word, focus, focus + 1, center - fw / 2f, baseline, focusPaint);
        }

        drawCount++;
        maxDrawNanos = Math.max(maxDrawNanos, System.nanoTime() - started);
        if (frameId != acknowledgedId && drawListener != null) {
            acknowledgedId = frameId;
            drawListener.onDrawn(frameId);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performClick();
            if (tapListener != null) {
                tapListener.onTap(event.getX() < getWidth() * 0.25f ? -1 : event.getX() > getWidth() * 0.75f ? 1 : 0);
            }
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
