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
    private static final String DEMO = "Привет! Это FastRead — скорочтение для Android.\n\n" +
        "Смотри на выделенную букву и не двигай взглядом. Каждое слово выравнивается по центру, поэтому глазу не нужно прыгать по строке.\n\n" +
        "Нажми на центр экрана или Пробел для старта и паузы. Тап у левого края перематывает назад на 10 слов, тап у правого края — вперёд.\n\n" +
        "Кнопки громкости также управляют чтением. Встретил интересное место — жми закладку, чтобы сохранить его.";

    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScreenController screen = new ScreenController();
    private SharedPreferences preferences;
    private BookStore store;
    private BookStore.Record record;
    private BookData book;
    private PlaybackClock clock;

    private float density;
    private LinearLayout root, headerView, progressRow, controlsView, speedRow, transportRow;
    private View dividerView;
    private WordView wordView;
    private TextView bannerView, positionView, remainingView, speedView, contextView, logoText;
    private Runnable contextJustifyRunnable;
    private View logoDot;
    private TextView booksBtn, addBtn, settingsBtn;
    private VectorButton playButton, markButton, slowerBtn, fasterBtn, back10Btn, back1Btn, fwd1Btn, fwd10Btn;
    private LinearLayout speedPod;
    private ChapterSeekBar progress;
    private ScrollView contextScroll;
    private FrameLayout contextCard;
    private boolean updatingProgress, busy, destroyed;
    private int loadGeneration;
    private long lastUi, lastSave;
    private volatile String lastSavedKey = "";

    // Herman's color themes
    private int themeBg, themePanel, themeSoft, themeLine, themeText, themeMuted, themeAccent, themeAccentSoft;
    private String currentTheme = "dark";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        preferences = getSharedPreferences("reader", MODE_PRIVATE);
        store = new BookStore(this);

        fullscreen();
        loadThemeColors();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themeBg);

        // 1. Header Bar (Brand Logo + Subtitle + Action Pills)
        headerView = new LinearLayout(this);
        headerView.setOrientation(LinearLayout.HORIZONTAL);
        headerView.setGravity(Gravity.CENTER_VERTICAL);
        headerView.setPadding(dp(16), dp(10), dp(16), dp(6));

        // Brand: Red Dot + "FastRead"
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        logoDot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(themeAccent);
        logoDot.setBackgroundDrawable(dotBg);
        brand.addView(logoDot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        View dotGap = new View(this);
        brand.addView(dotGap, new LinearLayout.LayoutParams(dp(8), -1));

        logoText = new TextView(this);
        logoText.setText("FastRead");
        logoText.setTextSize(16f);
        logoText.setTextColor(themeText);
        logoText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        brand.addView(logoText);

        headerView.addView(brand);

        View headerSpacer = new View(this);
        headerView.addView(headerSpacer, new LinearLayout.LayoutParams(0, -1, 1f));

        booksBtn = makeHeaderButton("Книги", new View.OnClickListener() {
            public void onClick(View v) { showBookList(); }
        });
        addBtn = makeHeaderButton("+ Книга", new View.OnClickListener() {
            public void onClick(View v) { pickBook(); }
        });
        settingsBtn = makeHeaderButton("⚙", new View.OnClickListener() {
            public void onClick(View v) { showSettings(); }
        });

        headerView.addView(booksBtn);
        headerView.addView(new View(this), new LinearLayout.LayoutParams(dp(6), -1));
        headerView.addView(addBtn);
        headerView.addView(new View(this), new LinearLayout.LayoutParams(dp(6), -1));
        headerView.addView(settingsBtn);

        root.addView(headerView);

        // 2. Book & Chapter Banner
        bannerView = new TextView(this);
        bannerView.setText("Инструкция");
        bannerView.setTextSize(12.5f);
        bannerView.setTextColor(themeMuted);
        bannerView.setSingleLine(true);
        bannerView.setEllipsize(TextUtils.TruncateAt.END);
        bannerView.setPadding(dp(16), 0, dp(16), dp(6));
        bannerView.setClickable(true);
        bannerView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showChapterList(); }
        });
        root.addView(bannerView);

        // 3. Stage (WordView)
        wordView = new WordView(this);
        wordView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(wordView);

        // 4. Context Card (Expanded on pause with book typography)
        contextCard = new FrameLayout(this);
        updateCardBackground();
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardParams.setMargins(dp(16), dp(4), dp(16), dp(8));
        contextCard.setLayoutParams(cardParams);
        contextCard.setVisibility(View.GONE);

        contextScroll = new ScrollView(this);
        contextScroll.setFillViewport(true);
        contextView = new TextView(this);
        contextView.setTextSize(preferences.getInt("contextSize", 19));
        contextView.setTypeface(Typeface.SERIF);
        contextView.setTextColor(themeText);
        contextView.setLineSpacing(dp(3f), 1.25f);
        contextView.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (Build.VERSION.SDK_INT >= 26) {
            contextView.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
        contextView.setMovementMethod(LinkMovementMethod.getInstance());
        contextScroll.addView(contextView);
        contextCard.addView(contextScroll);
        root.addView(contextCard);

        // 5. Divider Line
        dividerView = new View(this);
        dividerView.setBackgroundColor(themeLine);
        root.addView(dividerView, new LinearLayout.LayoutParams(-1, dp(1f)));

        // 6. Progress Row: [ 20 / 46275 ] [━━━━━━●━━━━━] [ ≈ 15 мин ]
        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(dp(16), dp(6), dp(16), dp(4));

        positionView = new TextView(this);
        positionView.setText("0 / 0");
        positionView.setTextSize(12f);
        positionView.setTextColor(themeMuted);
        positionView.setTypeface(Typeface.MONOSPACE);
        progressRow.addView(positionView);

        progress = new ChapterSeekBar(this);
        LinearLayout.LayoutParams progParams = new LinearLayout.LayoutParams(0, dp(32), 1f);
        progParams.setMargins(dp(10), 0, dp(10), 0);
        progress.setLayoutParams(progParams);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean isTracking = false;

            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && clock != null) {
                    if (isTracking) {
                        clock.seek(value);
                        refreshUi();
                    } else {
                        seek(value);
                    }
                }
            }
            public void onStartTrackingTouch(SeekBar bar) {
                isTracking = true;
                if (clock != null && clock.playing()) clock.pause();
            }
            public void onStopTrackingTouch(SeekBar bar) {
                isTracking = false;
                if (clock != null) seek(bar.getProgress());
            }
        });
        progressRow.addView(progress);

        remainingView = new TextView(this);
        remainingView.setText("≈ 0 с");
        remainingView.setTextSize(12f);
        remainingView.setTextColor(themeMuted);
        remainingView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        progressRow.addView(remainingView);

        root.addView(progressRow);

        // 7. Controls Container (TWO ROWS FOR SMARTPHONES)
        controlsView = new LinearLayout(this);
        controlsView.setOrientation(LinearLayout.VERTICAL);
        controlsView.setPadding(dp(16), dp(4), dp(16), dp(14));

        // ROW 1: Main Playback Transport [ −10  ‹   ▶   ›  +10 ]
        transportRow = new LinearLayout(this);
        transportRow.setOrientation(LinearLayout.HORIZONTAL);
        transportRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);

        back10Btn = new VectorButton(this, density, VectorButton.TYPE_BACK10);
        back10Btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() - 10); }
        });

        back1Btn = new VectorButton(this, density, VectorButton.TYPE_PREV);
        back1Btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() - 1); }
        });

        // 54dp Circular Hero Play Button
        playButton = new VectorButton(this, density, VectorButton.TYPE_PLAY_PAUSE);
        playButton.setCircular(true);
        playButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });

        fwd1Btn = new VectorButton(this, density, VectorButton.TYPE_NEXT);
        fwd1Btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() + 1); }
        });

        fwd10Btn = new VectorButton(this, density, VectorButton.TYPE_FWD10);
        fwd10Btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { seek(clock.index() + 10); }
        });

        transportRow.addView(back10Btn, new LinearLayout.LayoutParams(dp(46), dp(42)));
        transportRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), -1));
        transportRow.addView(back1Btn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        transportRow.addView(new View(this), new LinearLayout.LayoutParams(dp(12), -1));
        transportRow.addView(playButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
        transportRow.addView(new View(this), new LinearLayout.LayoutParams(dp(12), -1));
        transportRow.addView(fwd1Btn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        transportRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), -1));
        transportRow.addView(fwd10Btn, new LinearLayout.LayoutParams(dp(46), dp(42)));

        controlsView.addView(transportRow);

        View rowGap = new View(this);
        controlsView.addView(rowGap, new LinearLayout.LayoutParams(-1, dp(10)));

        // ROW 2: Bookmark, Speed Pod, Symmetry
        speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);

        markButton = new VectorButton(this, density, VectorButton.TYPE_BOOKMARK);
        markButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleBookmark(); }
        });
        markButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showBookmarks(); return true; }
        });
        speedRow.addView(markButton, new LinearLayout.LayoutParams(dp(42), dp(38)));

        View speedSpacer1 = new View(this);
        speedRow.addView(speedSpacer1, new LinearLayout.LayoutParams(0, -1, 1f));

        // Speed Pod [ − 350 сл/м + ]
        speedPod = new LinearLayout(this);
        speedPod.setOrientation(LinearLayout.HORIZONTAL);
        speedPod.setGravity(Gravity.CENTER_VERTICAL);
        updateSpeedPodBackground();

        slowerBtn = new VectorButton(this, density, VectorButton.TYPE_MINUS);
        slowerBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSpeed(-25); }
        });

        speedView = new TextView(this);
        speedView.setText("300\nсл/м");
        speedView.setTextSize(12f);
        speedView.setGravity(Gravity.CENTER);
        speedView.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        speedView.setTextColor(themeText);
        speedView.setPadding(dp(4), 0, dp(4), 0);
        speedView.setMinWidth(dp(62));
        speedView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int target = preferences.getInt("wpm", 300);
                int act = clock != null ? clock.actualWpm() : target;
                int lat = clock != null ? clock.averageDrawLatencyMs() : 0;
                boolean cal = preferences.getBoolean("calibrate", true);
                toast("Задано: " + target + " сл/м\n" +
                      "Факт: " + act + " сл/м\n" +
                      "Задержка экрана: ~" + lat + " мс\n" +
                      "Калибровка: " + (cal ? "Вкл" : "Выкл"));
            }
        });

        fasterBtn = new VectorButton(this, density, VectorButton.TYPE_PLUS);
        fasterBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeSpeed(25); }
        });

        speedPod.addView(slowerBtn, new LinearLayout.LayoutParams(dp(36), dp(38)));
        speedPod.addView(speedView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)));
        speedPod.addView(fasterBtn, new LinearLayout.LayoutParams(dp(36), dp(38)));
        speedRow.addView(speedPod, new LinearLayout.LayoutParams(-2, -2));

        View speedSpacer2 = new View(this);
        speedRow.addView(speedSpacer2, new LinearLayout.LayoutParams(0, -1, 1f));

        // Right placeholder matching bookmark width for perfect center symmetry
        View rightSymmetry = new View(this);
        speedRow.addView(rightSymmetry, new LinearLayout.LayoutParams(dp(42), dp(38)));

        controlsView.addView(speedRow);

        root.addView(controlsView);

        setContentView(root);

        // 8. Initialize Playback Engine
        clock = new PlaybackClock(new PlaybackClock.Scheduler() {
            public long now() { return SystemClock.uptimeMillis(); }
            public void post(Runnable task, long delay) { handler.postDelayed(task, delay); }
            public void cancel(Runnable task) { handler.removeCallbacks(task); }
        }, new PlaybackClock.Display() {
            public void show(int index, long id) {
                wordView.showWord(book.words[index], id);
                refreshUi();
            }
            public void stopped(boolean end, boolean timeout) {
                wordView.setKeepScreenOn(false);
                refreshUi();
                showContext();
                saveProgress();
                setPlaybackDimming(false);
                if (end) toast("Конец текста");
                if (timeout) toast("Таймаут отрисовки");
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

    private void loadThemeColors() {
        currentTheme = preferences.getString("theme", "dark");
        if ("light".equals(currentTheme)) {
            themeBg = Color.parseColor("#f5f2ec");      // Warm Paper
            themePanel = Color.parseColor("#ffffff");   // Pure White
            themeSoft = Color.parseColor("#ebe7df");    // Button background
            themeLine = Color.parseColor("#ddd8cd");    // Subtle border
            themeText = Color.parseColor("#1c1c1e");    // Near black
            themeMuted = Color.parseColor("#77736b");   // Muted secondary
            themeAccent = Color.parseColor("#de2328");  // Rich Book Red
            themeAccentSoft = Color.parseColor("#fceced");
        } else if ("amoled".equals(currentTheme)) {
            themeBg = Color.parseColor("#000000");      // AMOLED Black
            themePanel = Color.parseColor("#121214");   // Deep surface
            themeSoft = Color.parseColor("#1e1e22");    // Soft surface
            themeLine = Color.parseColor("#28282c");    // Border
            themeText = Color.parseColor("#ffffff");    // Crisp White
            themeMuted = Color.parseColor("#9e9ea4");   // Gray
            themeAccent = Color.parseColor("#ff4b4e");  // Neon Red
            themeAccentSoft = Color.parseColor("#2a181a");
        } else { // default dark
            themeBg = Color.parseColor("#121315");      // Herman Dark
            themePanel = Color.parseColor("#1a1b1e");   // Surface
            themeSoft = Color.parseColor("#222327");    // Hover/Button
            themeLine = Color.parseColor("#2c2e33");    // Border
            themeText = Color.parseColor("#e9e6df");    // Warm White
            themeMuted = Color.parseColor("#8a8c92");   // Gray
            themeAccent = Color.parseColor("#ff4b4e");  // Vibrant Red
            themeAccentSoft = Color.parseColor("#2a181a");
        }
    }

    private void updateCardBackground() {
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(themePanel);
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(1.2f), themeLine);
        contextCard.setBackgroundDrawable(cardBg);
    }

    private void updateSpeedPodBackground() {
        GradientDrawable speedBg = new GradientDrawable();
        speedBg.setColor(themePanel);
        speedBg.setCornerRadius(dp(10));
        speedBg.setStroke(dp(1.2f), themeLine);
        speedPod.setBackgroundDrawable(speedBg);
        speedPod.setPadding(dp(2), 0, dp(2), 0);
    }

    private TextView makeHeaderButton(String text, View.OnClickListener onClick) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12.5f);
        btn.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(11), dp(5), dp(11), dp(5));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(onClick);
        applyHeaderButtonTheme(btn);
        return btn;
    }

    private void applyHeaderButtonTheme(TextView btn) {
        if (btn == null) return;
        btn.setTextColor(themeMuted);

        GradientDrawable norm = new GradientDrawable();
        norm.setColor(Color.TRANSPARENT);
        norm.setCornerRadius(dp(6));
        norm.setStroke(dp(0.9f), themeLine);

        GradientDrawable press = new GradientDrawable();
        press.setColor(Color.argb(30, 128, 128, 128));
        press.setCornerRadius(dp(6));
        press.setStroke(dp(0.9f), themeLine);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, press);
        sld.addState(new int[]{}, norm);
        btn.setBackgroundDrawable(sld);
    }

    private void applySettings() {
        loadThemeColors();
        root.setBackgroundColor(themeBg);
        wordView.configureTheme(currentTheme);
        wordView.configure(
            preferences.getInt("size", 48),
            preferences.getString("font", "serif"),
            preferences.getString("focusStyle", "box"),
            preferences.getBoolean("orp", false),
            "dark".equals(currentTheme) || "amoled".equals(currentTheme)
        );

        int wpm = preferences.getInt("wpm", 300);
        if (clock != null) {
            clock.configure(wpm, preferences.getBoolean("smart", true), preferences.getInt("minimum", 0), preferences.getBoolean("calibrate", true));
        }
        speedView.setText(wpm + "\nсл/м");
        speedView.setTextColor(themeText);

        progress.setThemeColors(themeLine, themeAccent, themeText, themeMuted);

        // Update button colors
        int buttonBg = themeSoft;
        int buttonLine = themeLine;
        markButton.setButtonColors(themeText, buttonBg, buttonLine, false);
        slowerBtn.setButtonColors(themeText, Color.TRANSPARENT, Color.TRANSPARENT, false);
        fasterBtn.setButtonColors(themeText, Color.TRANSPARENT, Color.TRANSPARENT, false);

        back10Btn.setButtonColors(themeText, buttonBg, buttonLine, false);
        back1Btn.setButtonColors(themeText, buttonBg, buttonLine, false);
        fwd1Btn.setButtonColors(themeText, buttonBg, buttonLine, false);
        fwd10Btn.setButtonColors(themeText, buttonBg, buttonLine, false);

        applyHeaderButtonTheme(booksBtn);
        applyHeaderButtonTheme(addBtn);
        applyHeaderButtonTheme(settingsBtn);

        // Large 54dp Hero Play Button: Solid vibrant accent red circle with crisp white icon
        playButton.setButtonColors(Color.WHITE, themeAccent, Color.TRANSPARENT, true);

        logoDot.setBackgroundColor(themeAccent);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(themeAccent);
        logoDot.setBackgroundDrawable(dotBg);
        logoText.setTextColor(themeText);
        bannerView.setTextColor(themeMuted);
        dividerView.setBackgroundColor(themeLine);
        positionView.setTextColor(themeMuted);
        remainingView.setTextColor(themeMuted);

        updateCardBackground();
        updateSpeedPodBackground();
        contextView.setTextColor(themeText);
        contextView.setTextSize(preferences.getInt("contextSize", 19));
    }

    private void setPlaybackDimming(boolean playing) {
        float alpha = playing ? 0.16f : 1.0f;
        headerView.animate().alpha(alpha).setDuration(250).start();
        dividerView.animate().alpha(alpha).setDuration(250).start();
        progressRow.animate().alpha(alpha).setDuration(250).start();
        controlsView.animate().alpha(alpha).setDuration(250).start();
    }

    private void fullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
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
        String chTitle = (chapter >= 0 && book.chapters != null && chapter < book.chapters.length && book.chapters[chapter] != null) ? book.chapters[chapter].title : "";
        bannerView.setText("📖 " + record.title + (chTitle.length() > 0 ? "  •  " + chTitle : ""));
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

        boolean isMarked = record.bookmarks.contains(index);
        markButton.setBookmarkState(isMarked, themeAccent, themeText);

        if (clock != null && clock.playing() && clock.completed >= 3) {
            speedView.setText(wpm + "\nфакт: " + clock.actualWpm());
        } else {
            speedView.setText(wpm + "\nсл/м");
        }
    }

    public static class BoxWordSpan extends ReplacementSpan {
        private final float density;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int textColor;

        public BoxWordSpan(float density, int fillColor, int borderColor, int textColor) {
            this.density = density;
            this.textColor = textColor;
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(fillColor);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1.8f * density);
            borderPaint.setColor(borderColor);
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
            canvas.drawRoundRect(rect, 4 * density, 4 * density, fillPaint);
            canvas.drawRoundRect(rect, 4 * density, 4 * density, borderPaint);
            int oldColor = paint.getColor();
            paint.setColor(textColor);
            canvas.drawText(text, start, end, x + 6 * density, y, paint);
            paint.setColor(oldColor);
        }
    }

    public static final class EpigraphSpan implements LeadingMarginSpan {
        private final int indent;
        private final int stripeWidth;
        private final int gap;
        private final int color;

        public EpigraphSpan(int indent, int stripeWidth, int gap, int color) {
            this.indent = indent;
            this.stripeWidth = stripeWidth;
            this.gap = gap;
            this.color = color;
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
            p.setColor(color);
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
        wordView.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(110)));
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
                    // Book paragraph first line indent
                    builder.append("\u00A0\u00A0\u00A0\u00A0");
                }
                isParagraphStart = false;
            }

            int s = builder.length();
            builder.append(book.words[i]);
            int e = builder.length();

            if (i == current) {
                currentWordStartInSpan = s;
                builder.setSpan(new BoxWordSpan(density, themeAccentSoft, themeAccent, themeText), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    seek(position);
                }
                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(themeText);
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
                    builder.setSpan(new EpigraphSpan(dp(24), dp(2.5f), dp(10), themeAccent), pStartSpan, pEndSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

        final int targetOffset = currentWordStartInSpan;
        if (contextJustifyRunnable != null) {
            contextView.removeCallbacks(contextJustifyRunnable);
        }
        contextJustifyRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (contextView == null || book == null) return;
                    Layout layout = contextView.getLayout();
                    if (layout != null) {
                        CharSequence layoutText = layout.getText();
                        if (layoutText == null || !layoutText.toString().equals(builder.toString())) return;

                        int availableWidth = contextView.getWidth() - contextView.getPaddingLeft() - contextView.getPaddingRight();
                        if (availableWidth > 0) {
                            CharSequence text = builder;
                            int textLen = text.length();
                            int lineCount = layout.getLineCount();
                            boolean modified = false;

                            for (int l = 0; l < lineCount; l++) {
                                int lineStart = layout.getLineStart(l);
                                int lineEnd = layout.getLineEnd(l);
                                if (lineStart < 0 || lineStart >= textLen || lineEnd <= lineStart || lineEnd > textLen) continue;

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
                                    for (int ci = lineStart; ci < lineEnd; ci++) {
                                        if (text.charAt(ci) == ' ') spaceCount++;
                                    }
                                    if (spaceCount >= 1) {
                                        float spaceWidth = contextView.getPaint().measureText(" ");
                                        float extraPerSpace = (emptySpace - dp(2f)) / spaceCount;
                                        float scaleX = (spaceWidth + extraPerSpace) / spaceWidth;
                                        if (scaleX > 1.05f && scaleX <= 2.8f) {
                                            for (int ci = lineStart; ci < lineEnd; ci++) {
                                                if (ci < textLen && text.charAt(ci) == ' ') {
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
                    }

                    if (targetOffset >= 0 && targetOffset < contextView.getText().length()) {
                        Layout lyt = contextView.getLayout();
                        if (lyt != null && targetOffset < lyt.getText().length()) {
                            int line = lyt.getLineForOffset(targetOffset);
                            int lineTop = lyt.getLineTop(line);
                            int targetY = Math.max(0, lineTop - contextScroll.getHeight() / 2 + dp(20));
                            contextScroll.smoothScrollTo(0, targetY);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        };
        contextView.post(contextJustifyRunnable);
    }

    private void toggle() {
        if (busy || book == null) return;
        if (clock.playing()) {
            clock.pause();
            setPlaybackDimming(false);
        } else {
            contextCard.setVisibility(View.GONE);
            wordView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            wordView.setKeepScreenOn(true);
            setPlaybackDimming(true);
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
        clock.configure(wpm, preferences.getBoolean("smart", true), preferences.getInt("minimum", 0), preferences.getBoolean("calibrate", true));
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
                    try { store.save(snapshot); } catch (Exception ignored) {}
                }
            });
        }
    }

    private void toggleBookmark() {
        if (record == null) return;
        int index = clock.index();
        if (record.bookmarks.contains(index)) {
            record.bookmarks.remove(Integer.valueOf(index));
            toast("Закладка удалена");
        } else {
            record.bookmarks.add(index);
            Collections.sort(record.bookmarks);
            toast("Закладка сохранена");
        }
        progress.setBookData(book, record.bookmarks);
        refreshUi();
        saveProgress();
    }

    private void showBookmarks() {
        if (record == null || record.bookmarks.isEmpty()) {
            toast("Нет сохранённых закладок");
            return;
        }
        String[] items = new String[record.bookmarks.size()];
        for (int i = 0; i < items.length; i++) {
            int pos = record.bookmarks.get(i);
            String snippet = "";
            if (book != null && pos < book.words.length) {
                StringBuilder sb = new StringBuilder();
                int end = Math.min(book.words.length, pos + 5);
                for (int k = pos; k < end; k++) sb.append(book.words[k]).append(" ");
                snippet = " — \"" + sb.toString().trim() + "...\"";
            }
            items[i] = (pos + 1) + " / " + record.total + snippet;
        }

        new AlertDialog.Builder(this)
            .setTitle("Закладки")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    seek(record.bookmarks.get(which));
                }
            })
            .setPositiveButton("Закрыть", null)
            .show();
    }

    private void showChapterList() {
        if (book == null || book.chapters == null || book.chapters.length == 0) {
            toast("Оглавление не найдено");
            return;
        }
        String[] items = new String[book.chapters.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = book.chapters[i].title;
        }
        new AlertDialog.Builder(this)
            .setTitle("Оглавление")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    seek(book.chapters[which].start);
                }
            })
            .setPositiveButton("Закрыть", null)
            .show();
    }

    private void showBookList() {
        worker.execute(new Runnable() {
            public void run() {
                final ArrayList<BookStore.Record> records = store.list();
                onUi(new Runnable() {
                    public void run() {
                        if (records.isEmpty()) {
                            toast("Библиотека пуста. Добавьте книгу.");
                            return;
                        }
                        String[] names = new String[records.size()];
                        for (int i = 0; i < names.length; i++) {
                            BookStore.Record r = records.get(i);
                            int pct = r.total > 0 ? (int) Math.round(100.0 * r.pos / r.total) : 0;
                            names[i] = r.title + " (" + pct + "%)";
                        }
                        new AlertDialog.Builder(ReaderActivity.this)
                            .setTitle("Мои книги")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    openRecord(records.get(which));
                                }
                            })
                            .setNegativeButton("Закрыть", null)
                            .show();
                    }
                });
            }
        });
    }

    private void openRecord(final BookStore.Record r) {
        worker.execute(new Runnable() {
            public void run() {
                try {
                    final BookData data = store.load(r);
                    onUi(new Runnable() {
                        public void run() {
                            install(r, data);
                        }
                    });
                } catch (final Exception e) {
                    onUi(new Runnable() {
                        public void run() { toast("Ошибка открытия: " + e.getMessage()); }
                    });
                }
            }
        });
    }

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Выберите книгу"), OPEN_BOOK);
        } catch (Exception e) {
            toast("Файловый менеджер не найден");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == OPEN_BOOK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        }
    }

    private void importUri(final Uri uri) {
        worker.execute(new Runnable() {
            public void run() {
                try {
                    String name = "Книга";
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                                if (col >= 0) name = cursor.getString(col);
                            }
                        } finally { cursor.close(); }
                    }

                    File temp = new File(getCacheDir(), "import.tmp");
                    InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream out = new FileOutputStream(temp);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    in.close();
                    out.close();

                    BookParser.Parsed p = BookParser.parse(temp, name);
                    if (p.title.isEmpty()) p.title = name;
                    final BookData data = BookData.tokenize(p.text, p.chapters);
                    final BookStore.Record r = store.add(p, data);
                    temp.delete();

                    onUi(new Runnable() {
                        public void run() {
                            install(r, data);
                            toast("Книга добавлена!");
                        }
                    });
                } catch (final Exception e) {
                    onUi(new Runnable() {
                        public void run() { toast("Ошибка импорта: " + e.getMessage()); }
                    });
                }
            }
        });
    }

    private void showSettings() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));

        // 1. Theme Selection
        TextView themeLabel = new TextView(this);
        themeLabel.setText("Тема оформления:");
        themeLabel.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        layout.addView(themeLabel);

        final RadioGroup themeGroup = new RadioGroup(this);
        themeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbDark = new RadioButton(this); rbDark.setText("Тёмная  "); rbDark.setId(1);
        RadioButton rbLight = new RadioButton(this); rbLight.setText("Тёплая бумага  "); rbLight.setId(2);
        RadioButton rbAmoled = new RadioButton(this); rbAmoled.setText("AMOLED"); rbAmoled.setId(3);
        themeGroup.addView(rbDark);
        themeGroup.addView(rbLight);
        themeGroup.addView(rbAmoled);

        String curT = preferences.getString("theme", "dark");
        if ("light".equals(curT)) rbLight.setChecked(true);
        else if ("amoled".equals(curT)) rbAmoled.setChecked(true);
        else rbDark.setChecked(true);

        layout.addView(themeGroup);

        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor("#888888"));
        layout.addView(div1, new LinearLayout.LayoutParams(-1, dp(1)));

        // 2. Reading Word Font Size
        final TextView sizeVal = new TextView(this);
        int curSize = preferences.getInt("size", 48);
        sizeVal.setText("Размер слова: " + curSize + " sp");
        sizeVal.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        sizeVal.setPadding(0, dp(8), 0, 0);
        layout.addView(sizeVal);

        final SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(40);
        sizeBar.setProgress(curSize - 30);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { sizeVal.setText("Размер слова: " + (v + 30) + " sp"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(sizeBar);

        // 3. Context Font Size
        final TextView ctxVal = new TextView(this);
        int curCtx = preferences.getInt("contextSize", 19);
        ctxVal.setText("Шрифт текста на паузе: " + curCtx + " sp");
        ctxVal.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        ctxVal.setPadding(0, dp(8), 0, 0);
        layout.addView(ctxVal);

        final SeekBar ctxBar = new SeekBar(this);
        ctxBar.setMax(14); // 14 to 28 sp
        ctxBar.setProgress(curCtx - 14);
        ctxBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { ctxVal.setText("Шрифт текста на паузе: " + (v + 14) + " sp"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(ctxBar);

        // 4. Smart Punctuation Pauses
        final CheckBox smartCheck = new CheckBox(this);
        smartCheck.setText("Умные паузы на знаках препинания (. , ! ? —)");
        smartCheck.setChecked(preferences.getBoolean("smart", true));
        smartCheck.setPadding(0, dp(8), 0, 0);
        layout.addView(smartCheck);

        final CheckBox calCheck = new CheckBox(this);
        calCheck.setText("Автокалибровка темпа (компенсация задержки экрана)");
        calCheck.setChecked(preferences.getBoolean("calibrate", true));
        calCheck.setPadding(0, dp(8), 0, 0);
        layout.addView(calCheck);

        // 5. Context Card on Pause
        final CheckBox ctxCheck = new CheckBox(this);
        ctxCheck.setText("Показывать абзац текста на паузе");
        ctxCheck.setChecked(preferences.getBoolean("context", true));
        layout.addView(ctxCheck);

        new AlertDialog.Builder(this)
            .setTitle("Настройки FastRead")
            .setView(layout)
            .setPositiveButton("Применить", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    int selected = themeGroup.getCheckedRadioButtonId();
                    String tName = selected == 2 ? "light" : selected == 3 ? "amoled" : "dark";

                    preferences.edit()
                        .putString("theme", tName)
                        .putInt("size", sizeBar.getProgress() + 30)
                        .putInt("contextSize", ctxBar.getProgress() + 14)
                        .putBoolean("smart", smartCheck.isChecked())
                        .putBoolean("calibrate", calCheck.isChecked())
                        .putBoolean("context", ctxCheck.isChecked())
                        .apply();

                    applySettings();
                    clock.seek(clock.index());
                    refreshUi();
                    showContext();
                }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void onUi(Runnable r) {
        if (!destroyed) handler.post(r);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent event) {
        if (clock == null) return super.onKeyDown(key, event);
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_PAGE_UP) {
            seek(clock.index() - 10);
            return true;
        }
        if (key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN) {
            seek(clock.index() + 10);
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
        if (wordView != null) wordView.setKeepScreenOn(false);
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (clock != null) clock.pause();
        worker.shutdown();
        super.onDestroy();
    }

    // Canvas Vector Button for modern resolution-independent UI
    public static class VectorButton extends View {
        public static final int TYPE_MINUS = 1;
        public static final int TYPE_PLUS = 2;
        public static final int TYPE_PREV = 3;
        public static final int TYPE_NEXT = 4;
        public static final int TYPE_PLAY_PAUSE = 5;
        public static final int TYPE_BACK10 = 6;
        public static final int TYPE_FWD10 = 7;
        public static final int TYPE_BOOKMARK = 8;

        private final int type;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean playing = false;
        private boolean isMarked = false;
        private boolean circular = false;
        private int iconColor = Color.WHITE;
        private int activeColor = Color.parseColor("#ff4b4e");

        public VectorButton(Context context, float density, int type) {
            super(context);
            this.density = density;
            this.type = type;
            setClickable(true);
            setFocusable(true);
            setButtonColors(Color.WHITE, Color.parseColor("#222327"), Color.parseColor("#2c2e33"), false);
        }

        public void setCircular(boolean circular) {
            this.circular = circular;
            invalidate();
        }

        public void setPlaying(boolean playing) {
            if (this.playing != playing) {
                this.playing = playing;
                invalidate();
            }
        }

        public void setBookmarkState(boolean marked, int activeColor, int defaultColor) {
            this.isMarked = marked;
            this.activeColor = activeColor;
            this.iconColor = marked ? activeColor : defaultColor;
            invalidate();
        }

        public void setButtonColors(int iconColor, int bgColor, int borderColor, boolean circular) {
            this.iconColor = iconColor;
            this.circular = circular;

            GradientDrawable norm = new GradientDrawable();
            if (circular) {
                norm.setShape(GradientDrawable.OVAL);
            } else {
                norm.setCornerRadius(10f * density);
            }
            norm.setColor(bgColor);
            if (borderColor != Color.TRANSPARENT) {
                norm.setStroke(Math.round(1.2f * density), borderColor);
            }

            GradientDrawable press = new GradientDrawable();
            if (circular) {
                press.setShape(GradientDrawable.OVAL);
            } else {
                press.setCornerRadius(10f * density);
            }
            press.setColor(Color.argb(40, 255, 255, 255));
            if (borderColor != Color.TRANSPARENT) {
                press.setStroke(Math.round(1.2f * density), borderColor);
            }

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, press);
            states.addState(new int[]{}, norm);
            setBackgroundDrawable(states);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            paint.setColor(iconColor);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeCap(Paint.Cap.ROUND);

            switch (type) {
                case TYPE_MINUS: {
                    paint.setStrokeWidth(2.6f * density);
                    float hw = 6f * density;
                    canvas.drawLine(cx - hw, cy, cx + hw, cy, paint);
                    break;
                }
                case TYPE_PLUS: {
                    paint.setStrokeWidth(2.6f * density);
                    float hw = 6f * density;
                    canvas.drawLine(cx - hw, cy, cx + hw, cy, paint);
                    canvas.drawLine(cx, cy - hw, cx, cy + hw, paint);
                    break;
                }
                case TYPE_PREV: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2.8f * density);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    path.reset();
                    float w = 6f * density, h = 9f * density;
                    path.moveTo(cx + w * 0.5f, cy - h);
                    path.lineTo(cx - w * 0.7f, cy);
                    path.lineTo(cx + w * 0.5f, cy + h);
                    canvas.drawPath(path, paint);
                    break;
                }
                case TYPE_NEXT: {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2.8f * density);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    path.reset();
                    float w = 6f * density, h = 9f * density;
                    path.moveTo(cx - w * 0.5f, cy - h);
                    path.lineTo(cx + w * 0.7f, cy);
                    path.lineTo(cx - w * 0.5f, cy + h);
                    canvas.drawPath(path, paint);
                    break;
                }
                case TYPE_PLAY_PAUSE: {
                    if (playing) {
                        float bw = 4f * density, bh = 9f * density, gap = 4.5f * density;
                        RectF bar1 = new RectF(cx - gap - bw, cy - bh, cx - gap, cy + bh);
                        RectF bar2 = new RectF(cx + gap, cy - bh, cx + gap + bw, cy + bh);
                        canvas.drawRoundRect(bar1, 2f * density, 2f * density, paint);
                        canvas.drawRoundRect(bar2, 2f * density, 2f * density, paint);
                    } else {
                        path.reset();
                        float ph = 11f * density, pw = 9f * density;
                        path.moveTo(cx - pw * 0.65f, cy - ph);
                        path.lineTo(cx + pw * 1.05f, cy);
                        path.lineTo(cx - pw * 0.65f, cy + ph);
                        path.close();
                        canvas.drawPath(path, paint);
                    }
                    break;
                }
                case TYPE_BACK10: {
                    paint.setTextSize(13f * density);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    float y = cy - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText("−10", cx, y, paint);
                    break;
                }
                case TYPE_FWD10: {
                    paint.setTextSize(13f * density);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    float y = cy - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText("+10", cx, y, paint);
                    break;
                }
                case TYPE_BOOKMARK: {
                    path.reset();
                    float bw = 6.5f * density, bh = 8.5f * density;
                    path.moveTo(cx - bw, cy - bh);
                    path.lineTo(cx + bw, cy - bh);
                    path.lineTo(cx + bw, cy + bh);
                    path.lineTo(cx, cy + bh * 0.45f);
                    path.lineTo(cx - bw, cy + bh);
                    path.close();
                    if (isMarked) {
                        paint.setColor(activeColor);
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawPath(path, paint);
                    } else {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2f * density);
                        canvas.drawPath(path, paint);
                    }
                    break;
                }
            }
        }
    }
}
