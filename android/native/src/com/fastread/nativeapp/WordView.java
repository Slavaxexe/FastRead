package com.fastread.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/**
 * High-performance RSVP WordView for modern Android devices (phones and tablets).
 * Implements Herman Edelkin's original design aesthetics:
 * - Rich color themes: Modern Dark (#121315), Warm Paper (#f5f2ec), and AMOLED (#000000)
 * - Vibrant Red ORP focus letter (#ff4b4e / #de2328)
 * - Top & bottom guide lines with center accent ticks
 * - Customizable focus styling (Red letter, Box, Markers, Underline, Invert)
 */
public final class WordView extends View {
    public interface DrawListener { void onDrawn(long frameId); }
    public interface TapListener { void onTap(int zone); }

    private final Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint decorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final Rect wordRegion = new Rect();

    private String word = "";
    private long frameId, acknowledgedId = -1;
    private int focus = 0;

    // Herman's color palette
    private int bgColor = Color.parseColor("#121315");
    private int textColor = Color.parseColor("#e9e6df");
    private int lineColor = Color.parseColor("#2c2e33");
    private int accentColor = Color.parseColor("#ff4b4e");

    private float sizeSp = 50f;
    private float density;
    private boolean orp = false;
    private String style = "box"; // "box", "markers", "underline", "invert", "red"
    private String font = "serif";
    private String themeName = "dark";

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
        // Universal phone version always uses full-color anti-aliasing
    }

    public void configureTheme(String theme) {
        this.themeName = theme;
        if ("light".equals(theme)) {
            bgColor = Color.parseColor("#f5f2ec");      // Warm Paper
            textColor = Color.parseColor("#1c1c1e");    // Near Black
            lineColor = Color.parseColor("#ddd8cd");    // Soft warm border
            accentColor = Color.parseColor("#de2328");  // Book Red
        } else if ("amoled".equals(theme)) {
            bgColor = Color.parseColor("#000000");      // Pure AMOLED Black
            textColor = Color.parseColor("#ffffff");    // Pure White
            lineColor = Color.parseColor("#252528");
            accentColor = Color.parseColor("#ff4b4e");  // Neon Red
        } else { // default dark
            bgColor = Color.parseColor("#121315");      // Herman Dark
            textColor = Color.parseColor("#e9e6df");    // Warm White
            lineColor = Color.parseColor("#2c2e33");    // Subtle border
            accentColor = Color.parseColor("#ff4b4e");  // Vibrant Red
        }
        setBackgroundColor(bgColor);
        invalidate();
    }

    public int getBgColor() { return bgColor; }
    public int getTextColor() { return textColor; }
    public int getLineColor() { return lineColor; }
    public int getAccentColor() { return accentColor; }

    public void configure(float size, String font, String style, boolean orp, boolean dark) {
        this.sizeSp = size;
        this.font = font;
        this.style = style;
        this.orp = orp;
        configureTheme(dark ? "dark" : themeName);

        Typeface type;
        if ("sans".equals(font)) {
            type = Typeface.create("sans-serif", Typeface.NORMAL);
        } else if ("mono".equals(font)) {
            type = Typeface.MONOSPACE;
        } else {
            type = Typeface.SERIF;
        }

        normalPaint.setTypeface(type);
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
        int margin = Math.round(14 * density);
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

        // 1. Background fill
        decorPaint.setColor(bgColor);
        decorPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(wordRegion, decorPaint);

        float center = getWidth() / 2f;
        float top = 18 * density;
        float bottom = getHeight() - 18 * density;

        // 2. Guide lines (Herman's guides with center accent red notches)
        decorPaint.setColor(lineColor);
        decorPaint.setStrokeWidth(1.5f * density);
        decorPaint.setStyle(Paint.Style.STROKE);
        float lineInset = getWidth() * 0.08f;
        canvas.drawLine(lineInset, top, getWidth() - lineInset, top, decorPaint);
        canvas.drawLine(lineInset, bottom, getWidth() - lineInset, bottom, decorPaint);

        // Center vertical accent notch at top & bottom guide
        decorPaint.setColor(accentColor);
        decorPaint.setStrokeWidth(2.5f * density);
        float tickLen = 12 * density;
        canvas.drawLine(center, top, center, top + tickLen, decorPaint);
        canvas.drawLine(center, bottom - tickLen, center, bottom, decorPaint);

        // 3. Word typography & focus point
        if (word.length() > 0) {
            float size = sizeSp * getResources().getDisplayMetrics().scaledDensity;
            normalPaint.setTextSize(size);
            focusPaint.setTextSize(size);

            float padding = (style.equals("box") ? 6f : style.equals("invert") ? 5f : 0f) * density;
            float fw = focusPaint.measureText(word, focus, focus + 1);
            float leftWidth = normalPaint.measureText(word, 0, focus);
            float rightWidth = normalPaint.measureText(word, focus + 1, word.length());
            float side = Math.max(leftWidth, rightWidth) + fw / 2f + padding;
            float available = getWidth() / 2f - lineInset;

            if (side > available && side > 0) {
                float factor = available / side;
                size *= factor;
                normalPaint.setTextSize(size);
                focusPaint.setTextSize(size);
                fw = focusPaint.measureText(word, focus, focus + 1);
                leftWidth = normalPaint.measureText(word, 0, focus);
            }

            normalPaint.getFontMetrics(metrics);
            float baseline = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
            float left = center - fw / 2f - padding;
            float right = center + fw / 2f + padding;

            normalPaint.setColor(textColor);
            focusPaint.setColor(accentColor); // Focus letter is vibrant accent red!

            // Draw left and right word segments
            canvas.drawText(word, 0, focus, left - leftWidth, baseline, normalPaint);
            canvas.drawText(word, focus + 1, word.length(), right, baseline, normalPaint);

            float boxTop = baseline + metrics.ascent - 3f * density;
            float boxBottom = baseline + metrics.descent + 3f * density;

            // Draw focus indicator decorations
            if (style.equals("invert")) {
                decorPaint.setColor(accentColor);
                decorPaint.setStyle(Paint.Style.FILL);
                RectF invRect = new RectF(left, boxTop, right, boxBottom);
                canvas.drawRoundRect(invRect, 4f * density, 4f * density, decorPaint);
                focusPaint.setColor(Color.WHITE);
            } else if (style.equals("box")) {
                decorPaint.setColor(accentColor);
                decorPaint.setStyle(Paint.Style.STROKE);
                decorPaint.setStrokeWidth(2f * density);
                RectF boxRect = new RectF(left, boxTop, right, boxBottom);
                canvas.drawRoundRect(boxRect, 4f * density, 4f * density, decorPaint);
            } else if (style.equals("underline")) {
                decorPaint.setColor(accentColor);
                decorPaint.setStrokeWidth(3f * density);
                canvas.drawLine(left, boxBottom, right, boxBottom, decorPaint);
            } else if (style.equals("markers")) {
                decorPaint.setColor(accentColor);
                decorPaint.setStrokeWidth(2f * density);
                float mH = 8 * density;
                canvas.drawLine(center - 5 * density, boxTop - mH, center, boxTop - 2 * density, decorPaint);
                canvas.drawLine(center + 5 * density, boxTop - mH, center, boxTop - 2 * density, decorPaint);
                canvas.drawLine(center - 5 * density, boxBottom + mH, center, boxBottom + 2 * density, decorPaint);
                canvas.drawLine(center + 5 * density, boxBottom + mH, center, boxBottom + 2 * density, decorPaint);
            }

            // Draw the focus letter
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
