package com.fastread.nativeapp;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class ReaderActivity extends Activity {
    private static final int OPEN_BOOK = 10;
    private static final String DEMO = "Привет! Это FastRead — скорочтение для электронных книг Onyx BOOX.\n\n" +
        "Смотри на выделенную букву и не двигай взглядом. Каждое слово выравнивается по центру, поэтому глазу не нужно прыгать по строке.\n\n" +
        "Нажми на центр экрана или Пробел для старта и паузы. Тап у левого края перематывает назад на 10 слов, тап у правого края — вперёд.\n\n" +
        "Кнопки громкости и листания на читалке также управляют чтением. Встретил интересное место — жми закладку слева, чтобы сохранить его.";

    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScreenController screen = new ScreenController();
    private SharedPreferences preferences;
    private BookStore store;
    private BookStore.Record record;
    private BookData book;
    private PlaybackClock clock;

    private float density;
    private WordView wordView;
    private TextView bannerView, positionView, remainingView, speedView, contextView;
    private Runnable contextJustifyRunnable;
    private VectorButton playButton;
    private BookmarkButton markButton;
    private ChapterSeekBar progress;
    private ScrollView contextScroll;
    private FrameLayout contextCard;
    private boolean updatingProgress, busy, destroyed;
    private int loadGeneration;
    private long lastUi, lastSave;
    private volatile String lastSavedKey = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        preferences = getSharedPreferences("reader", MODE_PRIVATE);
        store = new BookStore(this);

        fullscreen();
        buildUi();

        clock = new PlaybackClock(new PlaybackClock.Scheduler() {
            public long now() { return SystemClock.uptimeMillis(); }
            public void post(Runnable task, long delay) { handler.postDelayed(task, delay); }
            public void cancel(Runnable task) { handler.removeCallbacks(task); }
        }, new PlaybackClock.Display() {
            public void show(int index, long frameId) {
                if (book == null || book.words.length == 0) return;
                wordView.showWord(book.words[index], frameId);
                if (!clock.playing() || SystemClock.uptimeMillis() - lastUi >= 1000) refreshUi();
                if (clock.playing() && SystemClock.uptimeMillis() - lastSave >= 5000) saveProgress();
            }
            public void stopped(boolean end, boolean timeout) {
                screen.stop(wordView);
                wordView.setKeepScreenOn(false);
                if (preferences.getBoolean("clearOnPause", false) && screen.canRefresh()) {
                    screen.fullRefresh(wordView);
                }
                refreshUi();
                showContext();
                saveProgress();
                if (end) toast("Конец текста");
                if (timeout) toast("Нет отрисовки: чтение остановлено без пропуска слова");
            }
        });

        wordView.setListeners(new WordView.DrawListener() {
            public void onDrawn(long id) { if (clock != null) clock.onDrawn(id); }
        }, new WordView.TapListener() {
            public void onTap(int zone) {
                if (zone == 0) toggle();
                else seek(clock.index() + zone * 10);
            }
        });

        screen.configure(wordView);
        applySettings();
        loadDemo();

        if (getIntent().getData() != null) {
            importUri(getIntent().getData());
        } else {
            final String last = preferences.getString("last", "");
            if (last.length() > 0) {
                worker.execute(new Runnable() {
                    public void run() {
                        final ArrayList<BookStore.Record> records = store.list();
                        onUi(new Runnable() {
                            public void run() {
                                for (BookStore.Record r : records) {
                                    if (r.id.equals(last)) {
                                        openRecord(r);
                                        break;
                                    }
                                }
                            }
                        });
                    }
                });
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

        public static final class BookmarkButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean bookmarked;
        private final float density;

        public BookmarkButton(Context context, float density) {
            super(context);
            this.density = density;
            setClickable(true);
            setFocusable(true);

            GradientDrawable normal = new GradientDrawable();
            normal.setColor(Color.WHITE);
            normal.setStroke(Math.round(1.5f * density), Color.BLACK);
            normal.setCornerRadius(8f * density);

            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(Color.parseColor("#d8d8d8"));
            pressed.setStroke(Math.round(1.5f * density), Color.BLACK);
            pressed.setCornerRadius(8f * density);

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[]{}, normal);
            setBackgroundDrawable(states);
        }

        public void setBookmarked(boolean bm) {
            if (this.bookmarked != bm) {
                this.bookmarked = bm;
                invalidate();
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Ribbon SVG viewBox 0 0 24 24: "M6 4h12v17l-6-4-6 4z" from webview
            float iconSize = 22f * density;
            float scale = iconSize / 24f;
            float ox = (w - iconSize) / 2f;
            float oy = (h - iconSize) / 2f;

            path.reset();
            path.moveTo(ox + 6f * scale, oy + 4f * scale);
            path.lineTo(ox + 18f * scale, oy + 4f * scale);
            path.lineTo(ox + 18f * scale, oy + 21f * scale);
            path.lineTo(ox + 12f * scale, oy + 17f * scale);
            path.lineTo(ox + 6f * scale, oy + 21f * scale);
            path.close();

            if (bookmarked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.BLACK);
                canvas.drawPath(path, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * density);
                paint.setColor(Color.BLACK);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(path, paint);
            }
        }
    }

    public static class VectorButton extends View {
        public static final int TYPE_MINUS = 1;
        public static final int TYPE_PLUS = 2;
        public static final int TYPE_PREV = 3;
        public static final int TYPE_NEXT = 4;
        public static final int TYPE_PLAY_PAUSE = 5;
        public static final int TYPE_BACK10 = 6;
        public static final int TYPE_FWD10 = 7;

        private final int type;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean playing = false;

        public VectorButton(Context context, float density, int type) {
            super(context);
            this.density = density;
            this.type = type;
            setClickable(true);
            setFocusable(true);

            GradientDrawable normal = new GradientDrawable();
            normal.setColor(Color.TRANSPARENT);
            normal.setCornerRadius(6f * density);

            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(Color.parseColor("#d8d8d8"));
            pressed.setCornerRadius(6f * density);

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[]{}, normal);
            setBackgroundDrawable(states);
        }

        public void setPlaying(boolean playing) {
            if (this.playing != playing) {
                this.playing = playing;
                invalidate();
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f;
            float cy = h / 2f;

            paint.setColor(Color.BLACK);

            if (type == TYPE_MINUS) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.6f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float half = 8.5f * density;
                canvas.drawLine(cx - half, cy, cx + half, cy, paint);
            } else if (type == TYPE_PLUS) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.6f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float half = 8.5f * density;
                canvas.drawLine(cx - half, cy, cx + half, cy, paint);
                canvas.drawLine(cx, cy - half, cx, cy + half, paint);
            } else if (type == TYPE_PREV) {
                // Large crisp chevron left ‹
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(cx + 5.5f * density, cy - 9.5f * density);
                path.lineTo(cx - 5.5f * density, cy);
                path.lineTo(cx + 5.5f * density, cy + 9.5f * density);
                canvas.drawPath(path, paint);
            } else if (type == TYPE_NEXT) {
                // Large crisp chevron right ›
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                path.moveTo(cx - 5.5f * density, cy - 9.5f * density);
                path.lineTo(cx + 5.5f * density, cy);
                path.lineTo(cx - 5.5f * density, cy + 9.5f * density);
                canvas.drawPath(path, paint);
            } else if (type == TYPE_PLAY_PAUSE) {
                if (playing) {
                    // Pause bars: two thick rounded bars
                    paint.setStyle(Paint.Style.FILL);
                    float barW = 4.5f * density;
                    float barH = 19f * density;
                    float gap = 5.5f * density;
                    canvas.drawRoundRect(new RectF(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f), 1.5f * density, 1.5f * density, paint);
                    canvas.drawRoundRect(new RectF(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f), 1.5f * density, 1.5f * density, paint);
                } else {
                    // Play triangle: perfectly centered equilateral triangle
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    float size = 19f * density;
                    float left = cx - 7.5f * density;
                    float right = cx + 9.5f * density;
                    float top = cy - size / 2f;
                    float bottom = cy + size / 2f;
                    path.moveTo(left, top);
                    path.lineTo(right, cy);
                    path.lineTo(left, bottom);
                    path.close();
                    canvas.drawPath(path, paint);
                }
            } else if (type == TYPE_BACK10) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                paint.setTextSize(15f * density);
                paint.setTextAlign(Paint.Align.CENTER);
                float y = cy - (paint.ascent() + paint.descent()) / 2f;
                canvas.drawText("−10", cx, y, paint);
            } else if (type == TYPE_FWD10) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                paint.setTextSize(15f * density);
                paint.setTextAlign(Paint.Align.CENTER);
                float y = cy - (paint.ascent() + paint.descent()) / 2f;
                canvas.drawText("+10", cx, y, paint);
            }
        }
    }

    private StateListDrawable makeFlatButtonDrawable(boolean isRoundPrimary) {
        if (isRoundPrimary) {
            GradientDrawable normal = new GradientDrawable();
            normal.setShape(GradientDrawable.OVAL);
            normal.setColor(Color.BLACK);

            GradientDrawable pressed = new GradientDrawable();
            pressed.setShape(GradientDrawable.OVAL);
            pressed.setColor(Color.parseColor("#444444"));

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[]{}, normal);
            return states;
        } else {
            GradientDrawable normal = new GradientDrawable();
            normal.setColor(Color.TRANSPARENT);
            normal.setCornerRadius(dp(8f));

            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(Color.parseColor("#e0e0e0"));
            pressed.setCornerRadius(dp(8f));

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[]{}, normal);
            return states;
        }
    }

    private ColorStateList makeButtonTextColor(boolean isRoundPrimary) {
        int normal = isRoundPrimary ? Color.WHITE : Color.BLACK;
        int pressed = isRoundPrimary ? Color.WHITE : Color.BLACK;
        return new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
            new int[]{pressed, normal}
        );
    }

    private Button makeFlatButton(String text, int minWidthDp, int heightDp, boolean isRoundPrimary, float textSizeSp, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(textSizeSp);
        btn.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        btn.setTextColor(makeButtonTextColor(isRoundPrimary));
        btn.setBackgroundDrawable(makeFlatButtonDrawable(isRoundPrimary));
        btn.setMinWidth(dp(minWidthDp));
        btn.setMinimumWidth(dp(minWidthDp));
        btn.setPadding(0, 0, 0, 0);
        btn.setSingleLine(true);
        btn.setEllipsize(null);
        btn.setIncludeFontPadding(false);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private Button makeFlatButton(String text, int minWidthDp, int heightDp, boolean isRoundPrimary, View.OnClickListener onClick) {
        return makeFlatButton(text, minWidthDp, heightDp, isRoundPrimary, 14f, onClick);
    }

    private Button makeHeaderButton(String text, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(13.5f);
        btn.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        btn.setTextColor(Color.BLACK);
        btn.setBackgroundDrawable(makeFlatButtonDrawable(false));
        btn.setMinWidth(dp(54));
        btn.setMinimumWidth(dp(54));
        btn.setPadding(dp(8), dp(4), dp(8), dp(4));
        btn.setSingleLine(true);
        btn.setIncludeFontPadding(false);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 1. Top Header: [■ FastRead] ... [ Книги ] [ + Книга ] [ Настройки ]
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(5), dp(10), dp(5));

        LinearLayout logoLayout = new LinearLayout(this);
        logoLayout.setOrientation(LinearLayout.HORIZONTAL);
        logoLayout.setGravity(Gravity.CENTER_VERTICAL);

        View square = new View(this);
        GradientDrawable sqDrawable = new GradientDrawable();
        sqDrawable.setColor(Color.BLACK);
        sqDrawable.setSize(dp(9), dp(9));
        square.setBackgroundDrawable(sqDrawable);
        LinearLayout.LayoutParams sqParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        sqParams.rightMargin = dp(7);
        logoLayout.addView(square, sqParams);

        TextView logoText = new TextView(this);
        logoText.setText("FastRead");
        logoText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        logoText.setTextSize(16.5f);
        logoText.setTextColor(Color.BLACK);
        logoLayout.addView(logoText);

        header.addView(logoLayout, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnLib = makeHeaderButton("Книги", new View.OnClickListener() {
            public void onClick(View v) { showLibrary(); }
        });
        Button btnAdd = makeHeaderButton("+ Книга", new View.OnClickListener() {
            public void onClick(View v) { chooseBook(); }
        });
        Button btnSet = makeHeaderButton("Настройки", new View.OnClickListener() {
            public void onClick(View v) { showSettings(); }
        });

        LinearLayout.LayoutParams hBtnParams = new LinearLayout.LayoutParams(-2, dp(38));
        hBtnParams.leftMargin = dp(4);
        header.addView(btnLib, hBtnParams);
        header.addView(btnAdd, hBtnParams);
        header.addView(btnSet, hBtnParams);

        root.addView(header);

        // Header divider (1dp light gray)
        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(Color.parseColor("#dcdcdc"));
        root.addView(headerDivider, new LinearLayout.LayoutParams(-1, dp(1f)));

        // 2. Book & Chapter Banner: 📖 <Book Title> • <Chapter>
        bannerView = new TextView(this);
        bannerView.setPadding(dp(12), dp(6), dp(12), dp(6));
        bannerView.setSingleLine(true);
        bannerView.setEllipsize(TextUtils.TruncateAt.END);
        bannerView.setBackgroundColor(Color.WHITE);
        bannerView.setTextColor(Color.BLACK);
        bannerView.setTextSize(13.5f);
        bannerView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        bannerView.setText("📖 Инструкция");
        bannerView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showChapters(); }
        });
        root.addView(bannerView);

        View bannerDivider = new View(this);
        bannerDivider.setBackgroundColor(Color.parseColor("#dcdcdc"));
        root.addView(bannerDivider, new LinearLayout.LayoutParams(-1, dp(1f)));

        // 3. Middle Reading Stage
        wordView = new WordView(this);
        root.addView(wordView, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 4. Paused Context Card (visible on pause)
        contextCard = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setStroke(dp(1.8f), Color.BLACK);
        cardBg.setCornerRadius(dp(8f));
        contextCard.setBackgroundDrawable(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardParams.setMargins(dp(8), dp(4), dp(8), dp(4));
        contextCard.setLayoutParams(cardParams);

        contextScroll = new ScrollView(this);
        contextScroll.setFillViewport(true);
        contextView = new TextView(this);
        contextView.setTextSize(preferences != null ? preferences.getInt("contextSize", 19) : 19f);
        contextView.setTypeface(Typeface.SERIF);
        contextView.setTextColor(Color.BLACK);
        contextView.setLineSpacing(dp(3f), 1.25f);
        contextView.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (Build.VERSION.SDK_INT >= 26) {
            contextView.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
        contextView.setMovementMethod(LinkMovementMethod.getInstance());
        contextScroll.addView(contextView);
        contextCard.addView(contextScroll);
        root.addView(contextCard);

        // 5. Footer divider (1dp light gray)
        View footerDivider = new View(this);
        footerDivider.setBackgroundColor(Color.parseColor("#dcdcdc"));
        root.addView(footerDivider, new LinearLayout.LayoutParams(-1, dp(1f)));

        // 6. Progress Row: [11 / 71] [━━━━━━●━━━━] [≈ 14 с]
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(dp(10), dp(2), dp(10), dp(2));

        positionView = new TextView(this);
        positionView.setText("0 / 0");
        positionView.setTextSize(12.5f);
        positionView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        positionView.setTextColor(Color.BLACK);
        progressRow.addView(positionView, new LinearLayout.LayoutParams(-2, -2));

        progress = new ChapterSeekBar(this);
        progress.setPadding(dp(8), 0, dp(8), 0);
        progressRow.addView(progress, new LinearLayout.LayoutParams(0, dp(26), 1f));

        remainingView = new TextView(this);
        remainingView.setText("—");
        remainingView.setTextSize(12.5f);
        remainingView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        remainingView.setTextColor(Color.BLACK);
        progressRow.addView(remainingView, new LinearLayout.LayoutParams(-2, -2));

        root.addView(progressRow);

        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean isTracking = false;
            public void onStartTrackingTouch(SeekBar bar) {
                isTracking = true;
                if (clock != null) clock.pause();
            }
            public void onStopTrackingTouch(SeekBar bar) {
                isTracking = false;
                if (clock != null) seek(bar.getProgress());
            }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && !updatingProgress && clock != null) {
                    if (isTracking) {
                        clock.seek(value);
                        refreshUi();
                    } else {
                        seek(value);
                    }
                }
            }
        });

        // 7. Grouped Controls Bar:
        // [ 🔖 ]      [ −  300 сл/м  + ]          [ −10  ‹  ▶  ›  +10 ]
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), dp(4), dp(8), dp(6));

        // Group 1: Bookmark Button (framed pod)
        markButton = new BookmarkButton(this, density);
        markButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleBookmark(); }
        });
        markButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showBookmarks(); return true; }
        });

        // Group 2: Speed Controls Pod (snug pod with crisp black border)
        LinearLayout speedPod = new LinearLayout(this);
        speedPod.setOrientation(LinearLayout.HORIZONTAL);
        speedPod.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable speedBg = new GradientDrawable();
        speedBg.setColor(Color.WHITE);
        speedBg.setCornerRadius(dp(8f));
        speedBg.setStroke(dp(1.5f), Color.BLACK);
        speedPod.setBackgroundDrawable(speedBg);
        speedPod.setPadding(dp(2), 0, dp(2), 0);

        VectorButton slower = new VectorButton(this, density, VectorButton.TYPE_MINUS);
        slower.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSpeed(-25); }
        });

        speedView = new TextView(this);
        speedView.setText("300\nсл/м");
        speedView.setTextSize(12.5f);
        speedView.setGravity(Gravity.CENTER);
        speedView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        speedView.setTextColor(Color.BLACK);
        speedView.setPadding(dp(4), 0, dp(4), 0);

        VectorButton faster = new VectorButton(this, density, VectorButton.TYPE_PLUS);
        faster.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSpeed(25); }
        });

        speedPod.addView(slower, new LinearLayout.LayoutParams(dp(36), dp(42)));
        speedPod.addView(speedView, new LinearLayout.LayoutParams(dp(54), dp(42)));
        speedPod.addView(faster, new LinearLayout.LayoutParams(dp(36), dp(42)));

        // Group 3: Transport Controls Pod (snug pod with crisp black border)
        LinearLayout transportPod = new LinearLayout(this);
        transportPod.setOrientation(LinearLayout.HORIZONTAL);
        transportPod.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable transportBg = new GradientDrawable();
        transportBg.setColor(Color.WHITE);
        transportBg.setCornerRadius(dp(8f));
        transportBg.setStroke(dp(1.5f), Color.BLACK);
        transportPod.setBackgroundDrawable(transportBg);
        transportPod.setPadding(dp(2), 0, dp(2), 0);

        VectorButton back10 = new VectorButton(this, density, VectorButton.TYPE_BACK10);
        back10.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() - 10); }
        });

        VectorButton back1 = new VectorButton(this, density, VectorButton.TYPE_PREV);
        back1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() - 1); }
        });

        playButton = new VectorButton(this, density, VectorButton.TYPE_PLAY_PAUSE);
        playButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });

        VectorButton fwd1 = new VectorButton(this, density, VectorButton.TYPE_NEXT);
        fwd1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() + 1); }
        });

        VectorButton fwd10 = new VectorButton(this, density, VectorButton.TYPE_FWD10);
        fwd10.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() + 10); }
        });

        transportPod.addView(back10, new LinearLayout.LayoutParams(dp(40), dp(42)));
        transportPod.addView(back1, new LinearLayout.LayoutParams(dp(36), dp(42)));
        transportPod.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(42)));
        transportPod.addView(fwd1, new LinearLayout.LayoutParams(dp(36), dp(42)));
        transportPod.addView(fwd10, new LinearLayout.LayoutParams(dp(40), dp(42)));

        // Add to controls row: bookmark, snug gap, speed pod, flexible gap, transport pod, right margin spacer
        controls.addView(markButton, new LinearLayout.LayoutParams(dp(42), dp(42)));

        View gap1 = new View(this);
        controls.addView(gap1, new LinearLayout.LayoutParams(dp(10), -1));

        controls.addView(speedPod, new LinearLayout.LayoutParams(-2, -2));

        View spacerMid = new View(this);
        controls.addView(spacerMid, new LinearLayout.LayoutParams(0, -1, 1f));

        controls.addView(transportPod, new LinearLayout.LayoutParams(-2, -2));

        View spacerRight = new View(this);
        controls.addView(spacerRight, new LinearLayout.LayoutParams(0, -1, 1.2f));

        root.addView(controls);

        setContentView(root);
    }

    private void fullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void applySettings() {
        wordView.setMonochrome(ScreenController.BOOX && preferences.getBoolean("monoInk", true));
        wordView.configure(
            preferences.getInt("size", 46),
            preferences.getString("font", "serif"),
            preferences.getString("focusStyle", "box"),
            preferences.getBoolean("orp", false),
            preferences.getBoolean("dark", false)
        );
        int wpm = preferences.getInt("wpm", 300);
        if (clock != null) {
            clock.configure(wpm, preferences.getBoolean("smart", true), preferences.getInt("minimum", 0));
        }
        speedView.setText(wpm + "\nсл/м");
    }

    private void loadDemo() {
        install(new BookStore.Record("demo", "Инструкция"), BookData.tokenize(DEMO, null));
    }

    private void install(BookStore.Record next, BookData data) {
        clock.pause();
        saveProgress();
        record = next;
        book = data;
        record.total = data.words.length;
        lastSavedKey = "";
        clock.setBook(data, record.pos);
        progress.setMax(Math.max(0, data.words.length - 1));
        progress.setBookData(data, record.bookmarks);
        if (!record.id.equals("demo")) {
            preferences.edit().putString("last", record.id).apply();
        }
        clock.seek(record.pos);
        refreshUi();
        showContext();
    }

    private void refreshUi() {
        if (clock == null || book == null) return;
        lastUi = SystemClock.uptimeMillis();
        int index = clock.index();
        int chapter = book.chapterAt(index);

        String chTitle = (book.chapters != null && chapter >= 0 && chapter < book.chapters.length) ? book.chapters[chapter].title : "";
        bannerView.setText("📖 " + (record != null ? record.title : "") + (chTitle.length() > 0 ? "  •  " + chTitle : ""));
        positionView.setText((book.words.length > 0 ? index + 1 : 0) + " / " + book.words.length);

        int wordsLeft = Math.max(0, book.words.length - index);
        int wpm = preferences.getInt("wpm", 300);
        long estMs = (long) (wordsLeft * (60000.0 / Math.max(50, wpm)) * (preferences.getBoolean("smart", true) ? 1.15 : 1.0));
        long sec = estMs / 1000;
        if (sec < 60) {
            remainingView.setText("≈ " + sec + " с");
        } else {
            long min = Math.round(sec / 60.0);
            remainingView.setText("≈ " + min + " мин");
        }

        updatingProgress = true;
        progress.setProgress(index);
        updatingProgress = false;

        playButton.setPlaying(clock.playing());

        boolean isMarked = record != null && record.bookmarks != null && record.bookmarks.contains(index);
        markButton.setBookmarked(isMarked);

        speedView.setText(wpm + "\nсл/м");
    }

    public static class BoxWordSpan extends ReplacementSpan {
        private final float density;
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BoxWordSpan(float density) {
            this.density = density;
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f * density);
            borderPaint.setColor(Color.BLACK);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            return (int) (paint.measureText(text, start, end) + 12 * density);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            float textWidth = paint.measureText(text, start, end);
            RectF rect = new RectF(x + 2 * density, top + 2 * density, x + textWidth + 10 * density, bottom - 2 * density);
            canvas.drawRoundRect(rect, 3 * density, 3 * density, borderPaint);
            canvas.drawText(text, start, end, x + 6 * density, y, paint);
        }
    }

    public static final class EpigraphSpan implements LeadingMarginSpan {
        private final int indent;
        private final int stripeWidth;
        private final int gap;

        public EpigraphSpan(int indent, int stripeWidth, int gap) {
            this.indent = indent;
            this.stripeWidth = stripeWidth;
            this.gap = gap;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return indent + stripeWidth + gap;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.BLACK);
            int left = x + dir * indent;
            int right = left + dir * stripeWidth;
            c.drawRect(Math.min(left, right), top, Math.max(left, right), bottom, p);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }

    private void showContext() {
        if (book == null || clock.playing() || !preferences.getBoolean("context", true)) {
            contextCard.setVisibility(View.GONE);
            wordView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            return;
        }
        wordView.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(96)));
        contextCard.setVisibility(View.VISIBLE);

        final int current = clock.index();
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        int startIdx = Math.max(0, current - 120);
        int endIdx = Math.min(book.words.length, current + 160);

        int currentWordStartInSpan = -1;
        int pStartSpan = 0;
        boolean isParagraphStart = true;
        byte currentParagraphType = BookData.TYPE_NORMAL;

        for (int i = startIdx; i < endIdx; i++) {
            final int position = i;
            byte type = (book.types != null && i < book.types.length) ? book.types[i] : BookData.TYPE_NORMAL;

            if (isParagraphStart) {
                pStartSpan = builder.length();
                currentParagraphType = type;
                if (type == BookData.TYPE_EPIGRAPH) {
                    if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                        builder.append("\n");
                    }
                } else if (type == BookData.TYPE_AUTHOR) {
                    if (!book.words[i].matches("^[—–-].*")) {
                        builder.append("— ");
                    }
                } else if (type == BookData.TYPE_CHAPTER) {
                    if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                        builder.append("\n");
                    }
                } else {
                    // Book paragraph first line indent (красная строка)
                    builder.append("\u00A0\u00A0\u00A0\u00A0");
                }
                isParagraphStart = false;
            }

            int s = builder.length();
            builder.append(book.words[i]);
            int e = builder.length();

            if (i == current) {
                currentWordStartInSpan = s;
                builder.setSpan(new BoxWordSpan(density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    seek(position);
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(Color.BLACK);
                    ds.setUnderlineText(false);
                }
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            if (book.paragraphEnds[i] || i == endIdx - 1) {
                int pEndSpan = builder.length();
                if (currentParagraphType == BookData.TYPE_CHAPTER) {
                    builder.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new StyleSpan(Typeface.BOLD), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new RelativeSizeSpan(1.22f), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.append("\n\n");
                } else if (currentParagraphType == BookData.TYPE_EPIGRAPH) {
                    builder.setSpan(new EpigraphSpan(dp(26), dp(2.8f), dp(10)), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new StyleSpan(Typeface.ITALIC), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    boolean nextIsAuthor = (i + 1 < endIdx && book.types != null && i + 1 < book.types.length && book.types[i + 1] == BookData.TYPE_AUTHOR);
                    builder.append(nextIsAuthor ? "\n" : "\n\n");
                } else if (currentParagraphType == BookData.TYPE_AUTHOR) {
                    builder.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new StyleSpan(Typeface.BOLD), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.append("\n\n");
                } else {
                    builder.append("\n");
                }
                isParagraphStart = true;
            } else {
                builder.append(" ");
            }
        }
        contextView.setText(builder);

        if (contextJustifyRunnable != null) {
            contextView.removeCallbacks(contextJustifyRunnable);
        }
        final int targetOffset = currentWordStartInSpan;
        contextJustifyRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    Layout layout = contextView.getLayout();
                    if (layout != null && layout.getText() != null) {
                        if (!layout.getText().toString().equals(builder.toString())) {
                            return;
                        }
                        int availableWidth = contextView.getWidth() - contextView.getPaddingLeft() - contextView.getPaddingRight();
                        if (availableWidth > 0) {
                            CharSequence text = builder;
                            int textLen = text.length();
                            int lineCount = layout.getLineCount();
                            boolean modified = false;

                            for (int l = 0; l < lineCount; l++) {
                                int lineStart = layout.getLineStart(l);
                                int lineEnd = layout.getLineEnd(l);
                                if (lineEnd <= lineStart || lineEnd > textLen) continue;

                                char lastChar = text.charAt(lineEnd - 1);
                                if (lastChar == '\n' || lastChar == '\r') continue;
                                if (lineEnd < textLen && (text.charAt(lineEnd) == '\n' || text.charAt(lineEnd) == '\r')) continue;

                                Object[] alignSpans = builder.getSpans(lineStart, lineEnd, AlignmentSpan.class);
                                if (alignSpans != null && alignSpans.length > 0) continue;
                                Object[] epigraphSpans = builder.getSpans(lineStart, lineEnd, EpigraphSpan.class);
                                if (epigraphSpans != null && epigraphSpans.length > 0) continue;

                                float lineWidth = layout.getLineWidth(l);
                                float emptySpace = availableWidth - lineWidth;

                                if (emptySpace > dp(6) && emptySpace < availableWidth * 0.40f) {
                                    int spaceCount = 0;
                                    for (int ci = lineStart; ci < lineEnd && ci < textLen; ci++) {
                                        if (text.charAt(ci) == ' ') spaceCount++;
                                    }
                                    if (spaceCount >= 1) {
                                        float spaceWidth = contextView.getPaint().measureText(" ");
                                        float extraPerSpace = (emptySpace - dp(2f)) / spaceCount;
                                        float scaleX = (spaceWidth + extraPerSpace) / spaceWidth;
                                        if (scaleX > 1.05f && scaleX <= 2.8f) {
                                            for (int ci = lineStart; ci < lineEnd && ci < textLen; ci++) {
                                                if (text.charAt(ci) == ' ') {
                                                    builder.setSpan(new ScaleXSpan(scaleX), ci, ci + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                                    modified = true;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (modified) {
                                contextView.setText(builder);
                            }
                        }

                        if (targetOffset >= 0) {
                            Layout lyt = contextView.getLayout();
                            if (lyt != null && targetOffset < lyt.getText().length()) {
                                int line = lyt.getLineForOffset(targetOffset);
                                int lineTop = lyt.getLineTop(line);
                                int targetY = Math.max(0, lineTop - contextScroll.getHeight() / 2 + dp(20));
                                contextScroll.smoothScrollTo(0, targetY);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Safety guard against layout race conditions during rapid dragging
                }
            }
        };
        contextView.post(contextJustifyRunnable);
    }

    private void toggle() {
        if (busy || book == null) return;
        if (clock.playing()) {
            clock.pause();
        } else {
            contextCard.setVisibility(View.GONE);
            wordView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            screen.start(wordView, preferences.getBoolean("autoA2", true));
            wordView.setKeepScreenOn(true);
            clock.play();
            refreshUi();
        }
    }

    private void seek(int index) {
        if (book == null || busy) return;
        clock.seek(index);
        refreshUi();
        if (!clock.playing()) showContext();
    }

    private void changeSpeed(int delta) {
        int wpm = Math.max(50, Math.min(1500, preferences.getInt("wpm", 300) + delta));
        preferences.edit().putInt("wpm", wpm).apply();
        clock.configure(wpm, preferences.getBoolean("smart", true), preferences.getInt("minimum", 0));
        refreshUi();
    }

    private void saveProgress() {
        if (record == null || book == null || record.id.equals("demo")) return;
        lastSave = SystemClock.uptimeMillis();
        record.pos = clock.index();
        String key = record.id + ":" + record.pos + ":" + record.bookmarks.toString();
        if (key.equals(lastSavedKey)) return;
        lastSavedKey = key;

        final BookStore.Record snapshot = new BookStore.Record(record.id, record.title);
        snapshot.pos = record.pos;
        snapshot.total = record.total;
        snapshot.bookmarks.addAll(record.bookmarks);

        if (!worker.isShutdown()) {
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        store.save(snapshot);
                    } catch (Exception e) {
                        lastSavedKey = "";
                    }
                }
            });
        }
    }

    private void chooseBook() {
        if (busy) return;
        clock.pause();
        new AlertDialog.Builder(this)
            .setTitle("Открыть книгу")
            .setItems(new String[]{"📂 Выбрать через систему", "📁 Память читалки (Onyx BOOX / SD)"}, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) systemPicker();
                    else storagePicker();
                }
            }).show();
    }

    private void systemPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, OPEN_BOOK);
        } catch (ActivityNotFoundException e) {
            storagePicker();
        }
    }

    private void storagePicker() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 11);
            return;
        }
        browse(Environment.getExternalStorageDirectory());
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 11 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            browse(Environment.getExternalStorageDirectory());
        }
    }

    private void browse(final File directory) {
        worker.execute(new Runnable() {
            public void run() {
                File[] found = directory.listFiles(new FileFilter() {
                    public boolean accept(File file) {
                        return !file.getName().startsWith(".") &&
                            (file.isDirectory() || file.getName().toLowerCase(Locale.ROOT).matches(".*\\.(epub|fb2|txt|md|html?|json)"));
                    }
                });
                final File[] entries = found == null ? new File[0] : found;
                Arrays.sort(entries, new Comparator<File>() {
                    public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                onUi(new Runnable() {
                    public void run() {
                        String[] names = new String[entries.length];
                        for (int i = 0; i < names.length; i++) {
                            names[i] = (entries[i].isDirectory() ? "📁 " : "📄 ") + entries[i].getName();
                        }
                        new AlertDialog.Builder(ReaderActivity.this)
                            .setTitle(directory.getAbsolutePath())
                            .setItems(names, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) {
                                    if (entries[which].isDirectory()) browse(entries[which]);
                                    else importUri(Uri.fromFile(entries[which]));
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .setNeutralButton("Наверх", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) {
                                    File parent = directory.getParentFile();
                                    if (parent != null) browse(parent);
                                }
                            }).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN_BOOK && result == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getData() != null) importUri(intent.getData());
    }

    private void importUri(final Uri uri) {
        if (busy) return;
        clock.pause();
        busy = true;
        bannerView.setText("📖 Импортирую книгу…");
        final int generation = ++loadGeneration;
        worker.execute(new Runnable() {
            public void run() {
                File temporary = null;
                try {
                    String name = uri.getLastPathSegment();
                    if (name == null) name = "Книга.txt";
                    if (!"file".equalsIgnoreCase(uri.getScheme())) {
                        Cursor cursor = null;
                        try {
                            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                        } catch (Exception ignored) {}
                        finally { if (cursor != null) cursor.close(); }
                    }
                    temporary = File.createTempFile("import-", ".book", getCacheDir());
                    InputStream input = getContentResolver().openInputStream(uri);
                    if (input == null) throw new IOException("Файл недоступен");
                    OutputStream output = new FileOutputStream(temporary);
                    try {
                        byte[] buffer = new byte[16384];
                        int n;
                        long total = 0;
                        while ((n = input.read(buffer)) != -1) {
                            total += n;
                            if (total > 40L * 1024 * 1024) throw new IOException("Файл больше 40 МБ");
                            output.write(buffer, 0, n);
                        }
                    } finally {
                        try { input.close(); } finally { output.close(); }
                    }
                    BookParser.Parsed parsed = BookParser.parse(temporary, name);
                    final BookData data = BookData.tokenize(parsed.text, parsed.chapters);
                    if (data.words.length == 0) throw new IOException("Не найдено слов");
                    final BookStore.Record imported = store.add(parsed, data);
                    onUi(new Runnable() {
                        public void run() {
                            if (generation != loadGeneration) return;
                            busy = false;
                            install(imported, data);
                            toast("Книга загружена: " + data.words.length + " слов");
                        }
                    });
                } catch (final Exception e) {
                    onUi(new Runnable() {
                        public void run() {
                            if (generation != loadGeneration) return;
                            busy = false;
                            refreshUi();
                            toast("Не удалось открыть: " + e.getMessage());
                        }
                    });
                } finally {
                    if (temporary != null) temporary.delete();
                }
            }
        });
    }

    private void openRecord(final BookStore.Record next) {
        if (busy) return;
        clock.pause();
        saveProgress();
        busy = true;
        bannerView.setText("📖 Открываю «" + next.title + "»…");
        final int generation = ++loadGeneration;
        worker.execute(new Runnable() {
            public void run() {
                try {
                    final BookData loaded = store.load(next);
                    onUi(new Runnable() {
                        public void run() {
                            if (generation != loadGeneration) return;
                            busy = false;
                            install(next, loaded);
                        }
                    });
                } catch (final Exception e) {
                    onUi(new Runnable() {
                        public void run() {
                            if (generation != loadGeneration) return;
                            busy = false;
                            refreshUi();
                            toast(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void showLibrary() {
        if (busy) return;
        clock.pause();
        saveProgress();
        worker.execute(new Runnable() {
            public void run() {
                final ArrayList<BookStore.Record> records = store.list();
                onUi(new Runnable() {
                    public void run() {
                        String[] names = new String[records.size()];
                        for (int i = 0; i < names.length; i++) {
                            BookStore.Record r = records.get(i);
                            int pct = r.total > 0 ? (int) Math.round(100.0 * r.pos / r.total) : 0;
                            names[i] = r.title + "\n" + (r.pos + 1) + " / " + r.total + " · " + pct + "%";
                        }
                        ListView list = new ListView(ReaderActivity.this);
                        list.setAdapter(new ArrayAdapter<String>(ReaderActivity.this, android.R.layout.simple_list_item_1, names));
                        final AlertDialog dialog = new AlertDialog.Builder(ReaderActivity.this)
                            .setTitle(names.length == 0 ? "Библиотека пуста" : "📚 Мои книги (долгое нажатие — удалить)")
                            .setView(list)
                            .setNegativeButton("Закрыть", null)
                            .setNeutralButton("Инструкция", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) { loadDemo(); }
                            }).create();

                        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                            public void onItemClick(AdapterView<?> p, View v, int index, long id) {
                                dialog.dismiss();
                                openRecord(records.get(index));
                            }
                        });
                        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                            public boolean onItemLongClick(AdapterView<?> p, View v, final int index, long id) {
                                new AlertDialog.Builder(ReaderActivity.this)
                                    .setMessage("Удалить «" + records.get(index).title + "»?")
                                    .setNegativeButton("Отмена", null)
                                    .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int which) {
                                            final BookStore.Record removed = records.get(index);
                                            dialog.dismiss();
                                            if (record.id.equals(removed.id)) {
                                                clock.pause();
                                                record = null;
                                                book = null;
                                                loadDemo();
                                                preferences.edit().remove("last").apply();
                                            }
                                            worker.execute(new Runnable() {
                                                public void run() { store.delete(removed); }
                                            });
                                        }
                                    }).show();
                                return true;
                            }
                        });
                        dialog.show();
                    }
                });
            }
        });
    }

    private void toggleBookmark() {
        if (record == null || book == null) return;
        Integer index = clock.index();
        if (record.bookmarks.contains(index)) {
            record.bookmarks.remove(index);
            toast("Закладка удалена");
        } else {
            record.bookmarks.add(index);
            Collections.sort(record.bookmarks);
            toast("Закладка добавлена (слово " + (index + 1) + ")");
        }
        lastSavedKey = "";
        saveProgress();
        progress.setBookData(book, record.bookmarks);
        refreshUi();
    }

    private void showBookmarks() {
        if (record == null || record.bookmarks.isEmpty()) {
            toast("Закладок пока нет");
            return;
        }
        clock.pause();
        final ArrayList<Integer> marks = new ArrayList<Integer>(record.bookmarks);
        String[] names = new String[marks.size()];
        for (int i = 0; i < names.length; i++) {
            int index = marks.get(i);
            names[i] = "Слово " + (index + 1) + ": " + (index < book.words.length ? book.words[index] : "");
        }
        new AlertDialog.Builder(this)
            .setTitle("★ Закладки в этой книге")
            .setItems(names, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    seek(marks.get(which));
                }
            }).show();
    }

    private void showChapters() {
        if (book == null || book.chapters.length == 0) {
            toast("В этом тексте нет глав");
            return;
        }
        clock.pause();
        String[] names = new String[book.chapters.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i + 1) + ". " + book.chapters[i].title;
        }
        new AlertDialog.Builder(this)
            .setTitle("📖 Оглавление")
            .setItems(names, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    seek(book.chapters[which].start);
                }
            }).show();
    }

    private CheckBox check(LinearLayout box, String label, String key, boolean defaultValue) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setTextSize(14f);
        check.setTextColor(Color.BLACK);
        check.setChecked(preferences.getBoolean(key, defaultValue));
        box.addView(check);
        return check;
    }

    private SeekBar settingBar(LinearLayout box, final String label, int max, int progressVal, final int step, final int offset, final String suffix) {
        final TextView value = new TextView(this);
        value.setText(label + (offset + progressVal * step) + suffix);
        value.setTextSize(14.5f);
        value.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        value.setTextColor(Color.BLACK);
        value.setPadding(0, dp(4), 0, dp(2));
        box.addView(value);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progressVal);
        box.addView(bar);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                value.setText(label + (offset + p * step) + suffix);
            }
        });
        return bar;
    }

    private Spinner spinner(LinearLayout box, String title, String[] values, int selected) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14.5f);
        tv.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        tv.setTextColor(Color.BLACK);
        tv.setPadding(0, dp(6), 0, dp(2));
        box.addView(tv);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        box.addView(spinner);
        return spinner;
    }

    private void showSettings() {
        clock.pause();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        final SeekBar speed = settingBar(box, "Скорость: ", 58, (preferences.getInt("wpm", 300) - 50) / 25, 25, 50, " сл/мин");
        final SeekBar size = settingBar(box, "Размер шрифта слова: ", 24, (preferences.getInt("size", 46) - 24) / 2, 2, 24, " sp");
        final SeekBar ctxSize = settingBar(box, "Размер текста контекста: ", 14, preferences.getInt("contextSize", 19) - 14, 1, 14, " sp");
        final SeekBar minimum = settingBar(box, "Минимум на слово: ", 20, preferences.getInt("minimum", 0) / 25, 25, 0, " мс (0 — выкл.)");

        final CheckBox smart = check(box, "Умные паузы (пунктуация и длина)", "smart", true);
        final CheckBox orp = check(box, "Опорная буква чуть левее центра (ORP)", "orp", false);
        final CheckBox context = check(box, "Окно контекста книги на паузе", "context", true);
        final CheckBox dark = check(box, "Инверсия (тёмная тема)", "dark", false);

        final String[] fonts = {"serif", "sans", "mono"};
        final String[] fontNames = {"С засечками (Georgia / Serif)", "Без засечек (Roboto / Sans)", "Моноширинный"};
        final Spinner font = spinner(box, "Шрифт слова", fontNames, Math.max(0, Arrays.asList(fonts).indexOf(preferences.getString("font", "serif"))));

        final String[] styles = {"box", "invert", "underline", "markers"};
        final String[] styleNames = {"Прямоугольная рамка", "Инвертированный блок", "Подчёркивание", "Стрелки сверку/снизу"};
        final Spinner style = spinner(box, "Выделение опорной буквы", styleNames, Math.max(0, Arrays.asList(styles).indexOf(preferences.getString("focusStyle", "box"))));

        final CheckBox keys = check(box, "Кнопки листания меняют скорость (иначе ±10 слов)", "keysSpeed", false);

        // BOOX acceleration options
        final CheckBox a2 = check(box, "A2 режим E-Ink во время чтения", "autoA2", true);
        final CheckBox clear = check(box, "Полная очистка экрана (GC) при паузе", "clearOnPause", false);
        final CheckBox monoInk = check(box, "Чёрно-белый режим глифов без полутонов", "monoInk", true);

        a2.setVisibility(ScreenController.BOOX ? View.VISIBLE : View.GONE);
        clear.setVisibility(ScreenController.BOOX ? View.VISIBLE : View.GONE);
        monoInk.setVisibility(ScreenController.BOOX ? View.VISIBLE : View.GONE);

        if (ScreenController.BOOX) {
            Button refresh = makeFlatButton("Очистить экран от артефактов сейчас", 0, 42, false, new View.OnClickListener() {
                public void onClick(View v) {
                    if (!screen.fullRefresh(wordView)) toast("Команда очистки отклонена прошивкой");
                }
            });
            box.addView(refresh);
        }

        TextView info = new TextView(this);
        info.setText("\n" + screen.description() + "\nУстройство: " + Build.MODEL + " (" + Build.DISPLAY + ")");
        info.setTextSize(11.5f);
        info.setTextColor(Color.DKGRAY);
        box.addView(info);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
            .setTitle("⚙ Настройки FastRead")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    preferences.edit()
                        .putInt("wpm", 50 + speed.getProgress() * 25)
                        .putInt("size", 24 + size.getProgress() * 2)
                        .putInt("contextSize", 14 + ctxSize.getProgress())
                        .putInt("minimum", minimum.getProgress() * 25)
                        .putBoolean("smart", smart.isChecked())
                        .putBoolean("orp", orp.isChecked())
                        .putBoolean("context", context.isChecked())
                        .putBoolean("dark", dark.isChecked())
                        .putString("font", fonts[Math.max(0, font.getSelectedItemPosition())])
                        .putString("focusStyle", styles[Math.max(0, style.getSelectedItemPosition())])
                        .putBoolean("keysSpeed", keys.isChecked())
                        .putBoolean("autoA2", a2.isChecked())
                        .putBoolean("clearOnPause", clear.isChecked())
                        .putBoolean("monoInk", monoInk.isChecked())
                        .apply();
                    applySettings();
                    clock.seek(clock.index());
                    refreshUi();
                    showContext();
                }
            }).show();
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        if (clock == null) return super.onKeyDown(key, event);
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_PAGE_UP) {
            if (preferences.getBoolean("keysSpeed", false)) changeSpeed(25);
            else seek(clock.index() - 10);
            return true;
        }
        if (key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (preferences.getBoolean("keysSpeed", false)) changeSpeed(-25);
            else seek(clock.index() + 10);
            return true;
        }
        if (key == KeyEvent.KEYCODE_SPACE || key == KeyEvent.KEYCODE_DPAD_CENTER) {
            toggle();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            seek(clock.index() - 1);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            seek(clock.index() + 1);
            return true;
        }
        return super.onKeyDown(key, event);
    }

    @Override
    protected void onPause() {
        if (clock != null) clock.pause();
        if (wordView != null) {
            screen.stop(wordView);
            wordView.setKeepScreenOn(false);
        }
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadGeneration++;
        if (clock != null) clock.pause();
        if (wordView != null) screen.stop(wordView);
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) fullscreen();
    }

    @Override
    public void onBackPressed() {
        if (clock != null && clock.playing()) clock.pause();
        else super.onBackPressed();
    }

    private void toast(String message) {
        Toast.makeText(this, message == null ? "Ошибка" : message, Toast.LENGTH_SHORT).show();
    }

    private void onUi(final Runnable task) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (!destroyed && !isFinishing()) task.run();
            }
        });
    }
}
