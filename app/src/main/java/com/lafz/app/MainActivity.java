package com.lafz.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ValueAnimator;
import android.provider.Settings;
import android.view.VelocityTracker;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.PathMeasure;
import android.widget.TimePicker;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {
    private static final String OPEN_TODAY = "com.lafz.app.OPEN_TODAY";
    private static final int REQUEST_NOTIFICATIONS = 702;
    private static final int FEED_BATCH_SIZE = 7;
    private static final int MAX_FEED_PAGES = 12;
    private static final int KEEP_PREVIOUS_PAGES = 2;

    private enum Tab { FEED, LIBRARY, REVIEW, SETTINGS }
    private enum QuizSet { KEPT, GENERAL }

    private Tab currentTab = Tab.FEED;
    private boolean exploreMode = true;
    private boolean launching;
    private View launchView;
    private boolean focusMode;
    private float focusAmount;
    private LinearLayout exploreTop;
    private LinearLayout exploreBottom;
    private ValueAnimator focusAnimator;
    private boolean reviewRevealed;
    private ReviewSession reviewSession;
    private int feedGeneration;
    private int exploreCursor;
    private boolean loadingMore;
    private boolean loadingInitialWords;
    private List<Word> feedWords = new ArrayList<>();
    private LinearLayout appRoot;
    private ScrollView pageScroll;
    private VerticalSnapPager snapPager;
    private TextView sourceLoadStatus;
    private int searchRequest;
    private StyledSheet activeSheet;
    private FrameLayout exploreActionDock;
    private LinearLayout exploreActions;
    private ActionIconView exploreSaveIcon;
    private TextView exploreSaveLabel;
    private View exploreNormalAction;
    private Boolean exploreActionRailMode;
    private Word activeExploreWord;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private Palette palette;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String sourceError;
    private String boostedFeedCategory;
    private LafzRepository.Listener sourceListener;
    private QuizSet quizSet;
    private boolean quizUsesChoices = true;
    private boolean quizAnswered;
    private boolean quizAnswerCorrect;
    private String quizSelectedAnswer = "";
    private List<Word> quizWords = new ArrayList<>();
    private List<List<Word>> quizOptions = new ArrayList<>();
    private int quizIndex;
    private int quizCorrect;
    private EditText quizInput;
    private String quizDraft = "";
    private Word undoRemovedWord;
    private int quizRoundSize;
    private int quizFirstCorrect;
    private int displayedDay = LafzPrefs.today();
    private final Runnable dayBoundary = this::refreshDay;
    private final BroadcastReceiver clockChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshDay(); }
    };

    static Intent todayIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OPEN_TODAY, true);
    }

    private void openToday() {
        currentTab = Tab.FEED;
        exploreMode = false;
        if (focusAnimator != null) focusAnimator.cancel();
        focusMode = false;
        focusAmount = 0;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(OPEN_TODAY, false)) {
            while (activeSheet != null) {
                activeSheet.surface.animate().cancel();
                activeSheet.closing = true;
                activeSheet.finishDismiss();
            }
            openToday();
            render();
        }
    }

    private void refreshDay() {
        int today = LafzPrefs.today();
        if (displayedDay != today) {
            displayedDay = today;
            if (currentTab == Tab.FEED && !exploreMode) render();
        }
        WordWidgetProvider.refresh(this);
        DailyWordRefreshReceiver.schedule(this);
        mainHandler.removeCallbacks(dayBoundary);
        long now = System.currentTimeMillis();
        mainHandler.postDelayed(dayBoundary,
                Math.max(1, DailyWordRefreshReceiver.nextMidnight(now, java.time.ZoneId.systemDefault()) - now));
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockChanges, filter);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDay();
        DailyWordReceiver.schedule(this);
    }

    @Override protected void onStop() {
        mainHandler.removeCallbacks(dayBoundary);
        unregisterReceiver(clockChanges);
        super.onStop();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 31) getSplashScreen().setOnExitAnimationListener(splash -> splash.remove());
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        createNotificationChannel();
        textToSpeech = new TextToSpeech(this, status -> {
            if (textToSpeech == null || isFinishing() || isDestroyed()) return;
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                textToSpeech.setLanguage(Locale.US);
            }
        });
        palette = palette();
        if (savedInstanceState != null) restorePractice(savedInstanceState);
        else if (getIntent().getBooleanExtra(OPEN_TODAY, false)) openToday();
        launching = savedInstanceState == null;
        if (launching) showLaunchScreen(); else render();
        sourceListener = new LafzRepository.Listener() {
            @Override public void onProgress(String message) {
                if (!isFinishing() && !isDestroyed() && sourceLoadStatus != null) sourceLoadStatus.setText(message);
            }
            @Override public void onReady(int wordCount) {
                if (isFinishing() || isDestroyed()) return;
                sourceError = null;
                if (launching && feedWords.isEmpty()) prepareInitialExploreWords();
                WordWidgetProvider.refresh(MainActivity.this);
                if (currentTab == Tab.FEED || currentTab == Tab.LIBRARY || currentTab == Tab.REVIEW) render();
            }
            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                sourceError = message;
                if (currentTab == Tab.FEED || currentTab == Tab.LIBRARY || currentTab == Tab.REVIEW) render();
            }
        };
        LafzRepository.get(this).ensureLoaded(sourceListener);

    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (focusAnimator != null) focusAnimator.cancel();
        if (textToSpeech != null) textToSpeech.shutdown();
        textToSpeech = null;
        LafzRepository.get(this).removeListener(sourceListener);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == Tab.FEED && exploreMode && focusMode) { setFocusMode(false); return; }
        if (currentTab == Tab.REVIEW && reviewSession != null) { reviewSession = null; reviewRevealed = false; render(); return; }
        if (currentTab == Tab.REVIEW && quizSet != null) { resetQuiz(); render(); return; }
        if (currentTab != Tab.FEED) {
            currentTab = Tab.FEED;
            render();
        } else if (exploreMode) {
            exploreMode = false;
            render();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                LafzPrefs.get(this).edit().putBoolean("daily_enabled", true).apply();
                DailyWordReceiver.schedule(this);
                Toast.makeText(this, "Daily word is on.", Toast.LENGTH_SHORT).show();
            } else {
                LafzPrefs.get(this).edit().putBoolean("daily_enabled", false).apply();
                Toast.makeText(this, "Allow notifications to receive your daily word.", Toast.LENGTH_LONG).show();
            }
            render();
        }
    }

    private void showLaunchScreen() {
        getWindow().setStatusBarColor(0xFF302340);
        getWindow().setNavigationBarColor(0xFF302340);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        LaunchAnimationView screen = new LaunchAnimationView(this);
        launchView = screen; setContentView(screen);
        screen.post(screen::start);
    }

    private void finishLaunch() {
        if (isFinishing() || isDestroyed()) return;
        launching = false; launchView = null; render();
        if (motionEnabled() && snapPager != null) {
            snapPager.setTranslationY(dp(12));
            snapPager.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private final class LaunchAnimationView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ribbon = new Path();
        private final Path segment = new Path();
        private final Path bubble = new Path();
        private final PathMeasure measure;
        private ValueAnimator animator;
        private float progress;
        LaunchAnimationView(Context context) {
            super(context);
            ribbon.moveTo(-15, -24); ribbon.lineTo(-15, 9); ribbon.cubicTo(-15, 20, -12, 24, -1, 24); ribbon.lineTo(24, 24);
            measure = new PathMeasure(ribbon, false);
            bubble.moveTo(12, -26); bubble.cubicTo(2, -26, 0, -20, 0, -13); bubble.lineTo(0, -3);
            bubble.lineTo(9, -7); bubble.cubicTo(21, -7, 24, -14, 24, -18); bubble.cubicTo(24, -23, 18, -26, 12, -26); bubble.close();
            setContentDescription("Lafz is opening");
        }
        void start() {
            if (!motionEnabled()) { progress = .8f; invalidate(); mainHandler.postDelayed(MainActivity.this::finishLaunch, 150); return; }
            animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(1450);
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (launching) finishLaunch(); }
            }); animator.start();
        }
        private float phase(float start, float end) { return Math.max(0f, Math.min(1f, (progress - start) / (end - start))); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF302340);
            float cx = getWidth() / 2f, cy = getHeight() * .44f;
            float trace = phase(.04f, .55f), settle = phase(.3f, .69f), reveal = phase(.82f, 1f);
            float eased = 1f - (float) Math.pow(1f - settle, 3);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xFFC9B4EF);
            paint.setAlpha((int) (25 * Math.sin(Math.PI * phase(0, .85f))));
            canvas.drawCircle(cx, cy, dp(58 + 70 * progress), paint);
            canvas.drawCircle(cx, cy, dp(42 + 40 * progress), paint);
            canvas.save(); canvas.translate(cx, cy); canvas.scale(dp(1.65f), dp(1.65f));
            paint.setColor(0xFFC9B4EF); paint.setAlpha(255); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(12); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            segment.reset(); measure.getSegment(0, measure.getLength() * trace, segment, true); canvas.drawPath(segment, paint);
            canvas.save(); canvas.translate((1 - eased) * 34, -(1 - eased) * 38);
            canvas.rotate((1 - eased) * -32, 12, -15); canvas.scale(.45f + .55f * eased, .45f + .55f * eased, 12, -15);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFCFAFF); paint.setAlpha((int) (255 * settle)); canvas.drawPath(bubble, paint);
            canvas.restore(); canvas.restore();
            paint.setTypeface(Typeface.create("serif", Typeface.NORMAL)); paint.setTextSize(dp(40));
            String name = "lafz"; float width = paint.measureText(name), x = cx - width / 2;
            for (int i = 0; i < name.length(); i++) {
                float letter = phase(.48f + i * .04f, .7f + i * .04f);
                paint.setColor(0xFFFCFAFF); paint.setAlpha((int) (255 * letter));
                String character = name.substring(i, i + 1);
                canvas.drawText(character, x, cy + dp(108 + 12 * (1 - letter)), paint); x += paint.measureText(character);
            }
            paint.setTextSize(dp(13)); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setColor(0xFFC9B4EF); paint.setAlpha((int) (255 * phase(.65f, .8f)));
            String tagline = "A little, every day."; canvas.drawText(tagline, cx - paint.measureText(tagline) / 2, cy + dp(143), paint);
            if (reveal > 0) {
                paint.setAlpha(255); paint.setColor(palette.background);
                float radius = (float) Math.hypot(getWidth(), getHeight()) * reveal * reveal;
                canvas.drawCircle(cx, cy, radius, paint);
            }
        }
        @Override protected void onDetachedFromWindow() {
            if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
            super.onDetachedFromWindow();
        }
    }

    private void render() {
        if (launching) return;
        captureQuizDraft();
        sourceLoadStatus = null;
        palette = palette();
        setTheme(palette.dark ? android.R.style.Theme_Material_NoActionBar : R.style.AppTheme);
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!palette.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);

        if (currentTab == Tab.FEED && exploreMode) {
            appRoot = new LinearLayout(this);
            appRoot.setOrientation(LinearLayout.VERTICAL);
            appRoot.setBackgroundColor(palette.background);
            appRoot.setPadding(0, 0, 0, 0);
            appRoot.addView(buildSnapExplorePage(), new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(appRoot);
            return;
        }

        appRoot = new LinearLayout(this);
        appRoot.setOrientation(LinearLayout.VERTICAL);
        appRoot.setBackgroundColor(palette.background);
        appRoot.setPadding(0, 0, 0, 0);

        appRoot.addView(makeHeader(), new LinearLayout.LayoutParams(-1, dp(76)));
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setPadding(0, 0, 0, dp(12));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), 0, dp(24), dp(14));
        pageScroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        appRoot.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (currentTab == Tab.FEED) buildFeedPage(page);
        else if (currentTab == Tab.LIBRARY) buildLibraryPage(page);
        else if (currentTab == Tab.REVIEW) buildReviewPage(page);
        else buildSettingsPage(page);

        appRoot.addView(makeBottomNav(), new LinearLayout.LayoutParams(-1, dp(76)));
        setContentView(appRoot);
    }

    private View makeHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(24), 0, dp(24), 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("lafz", 35, palette.ink, Typeface.create("serif", Typeface.BOLD_ITALIC));
        brand.setLetterSpacing(-.055f);
        row.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView note = eyebrow("YOUR POCKET LEXICON");
        row.addView(note, wrapMargin(0, 0, 8, 0));
        row.addView(searchButton());
        return row;
    }

    private TextView searchButton() {
        TextView search = label("⌕", 27, palette.selected, Typeface.create("sans-serif", Typeface.NORMAL));
        search.setGravity(Gravity.CENTER);
        search.setBackground(shape(palette.card, 22, palette.line, 1));
        search.setContentDescription("Search the full offline dictionary");
        search.setOnClickListener(v -> showDictionarySearch());
        search.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        return search;
    }

    private void showDictionarySearch() {
        StyledSheet sheet = new StyledSheet("Search words", null);
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(shape(palette.soft, 16, palette.line, 1));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(palette.ink);
        input.setHintTextColor(palette.muted);
        input.setHint("Search a word or meaning");
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        input.setMinHeight(dp(54));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setContentDescription("Search the full Lafz dictionary by spelling or definition");
        searchBar.addView(input, new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView clear = label("×", 23, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL));
        clear.setGravity(Gravity.CENTER); clear.setContentDescription("Clear search");
        clear.setVisibility(View.GONE); clear.setOnClickListener(v -> { input.setText(""); input.requestFocus(); });
        searchBar.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(54)));
        sheet.surface.addView(searchBar, sheet.surface.indexOfChild(sheet.scroll), margin(0, 12, 0, 8));
        TextView status = label("Preparing the full offline dictionary…", 13, palette.muted,
                Typeface.create("sans-serif", Typeface.NORMAL));
        sheet.surface.addView(status, sheet.surface.indexOfChild(sheet.scroll), margin(0, 0, 0, 4));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        sheet.body.addView(results);

        final int[] request = {0};
        final Runnable[] search = new Runnable[1];
        search[0] = () -> {
            String value = input.getText().toString().trim();
            clear.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
            int serial = ++request[0];
            results.removeAllViews();
            if (value.length() < 2) {
                status.setText("Type at least 2 letters.");
                return;
            }
            LafzRepository repository = LafzRepository.get(getApplicationContext());
            if (!repository.isFullReady()) {
                status.setText("Preparing the full offline dictionary…");
                return;
            }
            status.setText("Searching offline…");
            mainHandler.postDelayed(() -> {
                if (!sheet.isShowing() || request[0] != serial) return;
                LafzRepository.executeBackground(() -> {
                    List<Word> found;
                    boolean failed = false;
                    try { found = repository.search(value, 24); }
                    catch (RuntimeException error) { found = new ArrayList<>(); failed = true; }
                    List<Word> matches = found;
                    boolean searchFailed = failed;
                    mainHandler.post(() -> {
                        if (!sheet.isShowing() || request[0] != serial) return;
                        results.removeAllViews();
                        sheet.scroll.scrollTo(0, 0);
                        status.setText(searchFailed ? "Couldn’t search. Try again." :
                                matches.isEmpty() ? "No matches. Try another word or meaning." :
                                matches.size() == 24 ? "Top 24 matches" :
                                matches.size() + (matches.size() == 1 ? " match" : " matches"));
                        for (Word word : matches) {
                            LinearLayout row = new LinearLayout(MainActivity.this);
                            row.setOrientation(LinearLayout.VERTICAL);
                            row.setPadding(dp(2), dp(12), dp(2), dp(12));
                            row.setMinimumHeight(dp(72));
                            LinearLayout heading = new LinearLayout(MainActivity.this);
                            heading.setGravity(Gravity.CENTER_VERTICAL);
                            TextView spelling = label(word.spelling, 19, palette.ink, Typeface.create("serif", Typeface.NORMAL));
                            spelling.setMaxLines(1); spelling.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            heading.addView(spelling, new LinearLayout.LayoutParams(0, -2, 1));
                            TextView part = label(word.partOfSpeech + "  ›", 12, palette.muted,
                                    Typeface.create("sans-serif", Typeface.NORMAL));
                            LinearLayout.LayoutParams partParams = new LinearLayout.LayoutParams(-2, -2);
                            partParams.leftMargin = dp(10); heading.addView(part, partParams);
                            row.addView(heading);
                            TextView meaning = label(word.meaning, 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL));
                            meaning.setMaxLines(2);
                            meaning.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            row.addView(meaning, margin(0, 4, 0, 0));
                            row.setContentDescription(word.spelling + ", " + word.partOfSpeech + ", " + word.meaning);
                            row.setOnClickListener(v -> {
                                android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
                                if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
                                input.clearFocus();
                                showWordDetails(word);
                            });
                            results.addView(row);
                            View divider = new View(MainActivity.this); divider.setBackgroundColor(palette.line);
                            results.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
                        }
                    });
                });
            }, 180);
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { search[0].run(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        LafzRepository.get(this).ensureFull(new LafzRepository.Listener() {
            @Override public void onProgress(String message) {
                if (sheet.isShowing() && input.getText().length() >= 2) status.setText(message);
            }
            @Override public void onReady(int wordCount) {
                if (sheet.isShowing()) {
                    status.setText("Type at least 2 letters.");
                    if (input.getText().length() >= 2) search[0].run();
                }
            }
            @Override public void onError(String message) {
                if (sheet.isShowing()) status.setText("Full dictionary unavailable: " + message);
            }
        });
        sheet.show();
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus(); return true;
        });
        Window window = sheet.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.requestFocus();
        input.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null && sheet.isShowing()) keyboard.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private View buildSnapExplorePage() {
        FrameLayout viewport = new FrameLayout(this);
        viewport.setBackgroundColor(palette.background);
        snapPager = new VerticalSnapPager(this);
        snapPager.setActionDock(makeExploreActionDock());
        snapPager.setOnSwipe(direction -> {
            if (direction > 0) {
                if (snapPager.getDisplayedChild() >= snapPager.getPageCount() - 3) loadMoreExploreWords();
                snapPager.showNextPage();
            } else snapPager.showPreviousPage();
        });
        viewport.addView(snapPager, new FrameLayout.LayoutParams(-1, -1));

        exploreTop = new LinearLayout(this);
        exploreTop.setOrientation(LinearLayout.VERTICAL);
        exploreTop.setBackgroundColor(palette.background);
        exploreTop.setPadding(dp(22), dp(12), dp(22), dp(10));
        LinearLayout masthead = new LinearLayout(this);
        masthead.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("lafz", 38, palette.ink, Typeface.create("serif", Typeface.BOLD_ITALIC));
        brand.setLetterSpacing(-.055f);
        masthead.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        searchParams.rightMargin = dp(8);
        masthead.addView(searchButton(), searchParams);
        TextView filter = label(categoryFilterLabel(), 12, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        filter.setGravity(Gravity.CENTER);
        filter.setPadding(dp(16), 0, dp(16), 0);
        filter.setBackground(shape(palette.card, 22, palette.line, 1));
        filter.setContentDescription("Choose word topics");
        filter.setOnClickListener(v -> showCategoryDialog());
        masthead.addView(filter, new LinearLayout.LayoutParams(-2, dp(44)));
        exploreTop.addView(masthead, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        TextView explore = label("Discover", 13, palette.selectedText, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        explore.setGravity(Gravity.CENTER);
        explore.setBackground(shape(palette.selected, 12, palette.selected, 0));
        modes.addView(explore, new LinearLayout.LayoutParams(dp(116), dp(38)));
        TextView today = label("Today", 14, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        today.setGravity(Gravity.CENTER);
        today.setOnClickListener(v -> { exploreMode = false; render(); });
        modes.addView(today, new LinearLayout.LayoutParams(dp(84), dp(44)));
        TextView offline = eyebrow(shortLevel());
        offline.setContentDescription("Word level: " + LafzPrefs.level(this) + ". Tap to change.");
        offline.setOnClickListener(v -> showCategoryDialog());
        offline.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        modes.addView(offline, new LinearLayout.LayoutParams(0, dp(44), 1));
        exploreTop.addView(modes, margin(0, 10, 0, 0));

        if (LafzRepository.get(this).isReady()) {
            feedGeneration++; loadingMore = false;
            if (feedWords.isEmpty()) {
                snapPager.addPage(makeSourceLoadingPage());
                loadMoreExploreWords();
            }
            else {
                for (Word word : feedWords) snapPager.addPage(makeSnapWordPage(word));
                snapPager.setDisplayedChild(exploreCursor);
                if (exploreCursor >= feedWords.size() - 3) loadMoreExploreWords();
            }
        } else snapPager.addPage(makeSourceLoadingPage());
        exploreBottom = new LinearLayout(this);
        exploreBottom.setOrientation(LinearLayout.VERTICAL);
        TextView hint = label("↑  TURN THE PAGE  ·  FIND SOMETHING NEW", 10, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setLetterSpacing(.11f);
        hint.setGravity(Gravity.CENTER);
        exploreBottom.addView(hint, new LinearLayout.LayoutParams(-1, dp(34)));
        exploreBottom.addView(makeBottomNav(), new LinearLayout.LayoutParams(-1, dp(76)));
        viewport.addView(exploreTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        viewport.addView(exploreBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        focusAmount = focusMode ? 1f : 0f; applyFocusAmount();
        return viewport;
    }

    private String shortLevel() {
        String level = LafzPrefs.level(this);
        return LearningContent.ALL.equals(level) ? "FULL DICTIONARY" : LearningContent.STRETCH.equals(level) ? "STRETCH" : "EVERYDAY";
    }

    private String categoryFilterLabel() {
        Set<String> categories = LafzPrefs.categories(this);
        if (categories.isEmpty()) return "Topics  ⌄";
        if (categories.size() == 1) return categories.iterator().next() + "  ⌄";
        return categories.size() + " topics  ⌄";
    }

    private View makeSourceLoadingPage() {
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(34), dp(72), dp(34), dp(54));
        TextView mark = label("ل", 54, palette.gold, Typeface.create("serif", Typeface.NORMAL));
        mark.setGravity(Gravity.CENTER);
        page.addView(mark, margin(0, 0, 0, 13));
        page.addView(label("Opening the word library", 23, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 8));
        sourceLoadStatus = label(sourceError == null ? "Your offline word library is getting ready on this device." : sourceError,
                14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL));
        sourceLoadStatus.setGravity(Gravity.CENTER);
        sourceLoadStatus.setLineSpacing(dp(3), 1f);
        page.addView(sourceLoadStatus, margin(0, 0, 0, 17));
        page.addView(label("Bundled dictionary · available offline", 11, palette.muted,
                Typeface.create("sans-serif-medium", Typeface.NORMAL)), margin(0, 0, 0, 18));
        if (sourceError != null) page.addView(button("Try again", true, this::retryWordSource));
        return page;
    }

    private void retryWordSource() {
        sourceError = null;
        render();
        LafzRepository.get(this).ensureLoaded(sourceListener);
    }

    private void prepareInitialExploreWords() {
        if (loadingInitialWords) return;
        loadingInitialWords = true;
        Set<String> categories = new HashSet<>(LafzPrefs.categories(this));
        String level = LafzPrefs.level(this);
        LafzRepository.executeBackground(() -> {
            List<Word> words;
            try {
                words = LafzRepository.get(getApplicationContext()).nextWords(categories,
                        FEED_BATCH_SIZE, Collections.emptySet(), level);
            } catch (RuntimeException error) {
                mainHandler.post(() -> {
                    loadingInitialWords = false;
                    if (isFinishing() || isDestroyed()) return;
                    sourceError = "The next words could not be loaded. Try again.";
                    render();
                });
                return;
            }
            mainHandler.post(() -> {
                loadingInitialWords = false;
                if (isFinishing() || isDestroyed()) return;
                if (feedWords.isEmpty() && categories.equals(LafzPrefs.categories(this)) &&
                        level.equals(LafzPrefs.level(this))) feedWords.addAll(words);
                if (currentTab == Tab.FEED && exploreMode) render();
            });
        });
    }

    private void loadMoreExploreWords() {
        if (loadingInitialWords || loadingMore || snapPager == null || !LafzRepository.get(this).isReady()) return;
        loadingMore = true;
        final VerticalSnapPager requestedPager = snapPager;
        final int requestedGeneration = feedGeneration;
        final Set<String> categories = new HashSet<>(LafzPrefs.categories(this));
        final String level = LafzPrefs.level(this);
        final String boosted = boostedFeedCategory;
        boostedFeedCategory = null;
        final Set<String> alreadyAdded = new HashSet<>();
        for (Word word : feedWords) alreadyAdded.add(word.id);

        LafzRepository.executeBackground(() -> {
            List<Word> batch = new ArrayList<>();
            try {
                LafzRepository repository = LafzRepository.get(getApplicationContext());
                if (boosted != null && (categories.isEmpty() || categories.contains(boosted))) {
                    batch.addAll(repository.nextWords(Collections.singleton(boosted), 3, alreadyAdded, level));
                }
                if (batch.size() < FEED_BATCH_SIZE) {
                    Set<String> pending = new HashSet<>(alreadyAdded);
                    for (Word word : batch) pending.add(word.id);
                    batch.addAll(repository.nextWords(categories, FEED_BATCH_SIZE - batch.size(), pending, level));
                }
            } catch (RuntimeException exception) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed() && currentTab == Tab.FEED && exploreMode &&
                            requestedPager == snapPager && requestedGeneration == feedGeneration) {
                        loadingMore = false;
                        Toast.makeText(MainActivity.this, "The next words could not be loaded. Try again.", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            List<Word> loaded = batch;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || currentTab != Tab.FEED || !exploreMode ||
                        requestedPager != snapPager || requestedGeneration != feedGeneration) return;
                if (feedWords.isEmpty() && !loaded.isEmpty()) requestedPager.clearPages();
                for (Word word : loaded) {
                    if (alreadyAdded.add(word.id)) {
                        feedWords.add(word);
                        requestedPager.addPage(makeSnapWordPage(word));
                    }
                }
                loadingMore = false;
                if (loaded.isEmpty() && requestedPager.getDisplayedChild() > 0 &&
                        requestedPager.getDisplayedChild() == requestedPager.getPageCount() - 1) {
                    // A small topic can finish before the normal page-pruning threshold.
                    // Release its old pages so the next round can reuse them.
                    int previous = requestedPager.getDisplayedChild();
                    for (int i = 0; i < previous; i++) {
                        requestedPager.removePageAt(0); feedWords.remove(0);
                    }
                    requestedPager.setDisplayedChild(0);
                    loadMoreExploreWords();
                    return;
                }
                if (feedWords.isEmpty()) {
                    requestedPager.clearPages();
                    requestedPager.addPage(emptyCard("No words in this mix yet.",
                            "Try another topic or choose a broader word level.", "Change word mix", this::showCategoryDialog));
                }
            });
        });
    }

    private void pruneExplorePages(VerticalSnapPager pager) {
        if (pager != snapPager || pager.getPageCount() <= MAX_FEED_PAGES) return;
        int displayed = pager.getDisplayedChild();
        int removeCount = Math.max(0, displayed - KEEP_PREVIOUS_PAGES);
        if (removeCount == 0) return;

        for (int i = 0; i < removeCount && pager.getPageCount() > 0; i++) pager.removePageAt(0);
        pager.setDisplayedChild(Math.max(0, displayed - removeCount));
        for (int i = 0; i < removeCount && !feedWords.isEmpty(); i++) feedWords.remove(0);
    }

    private View makeSnapWordPage(Word word) {
        ReadingPage page = new ReadingPage(this);
        page.setTag(word);
        FrameLayout paper = new FrameLayout(this); page.paper = paper;
        page.addView(paper, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = new LinearLayout(this); page.copy = copy;
        copy.setTag("word-copy");
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(28), dp(120), dp(78), dp(116));

        LinearLayout topicRow = new LinearLayout(this);
        page.topic = topicRow;
        topicRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(shape(categoryColor(word.category), 4, 0, 0));
        topicRow.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        TextView topic = eyebrow(word.category.toUpperCase(Locale.getDefault()));
        topicRow.addView(topic, wrapMargin(8, 0, 0, 0));
        copy.addView(topicRow, margin(0, 0, 0, 30));
        TextView spelling = label(word.spelling, 62, palette.ink, Typeface.create("serif", Typeface.BOLD));
        spelling.setTag("word-title");
        spelling.setLetterSpacing(-.05f);
        spelling.setMaxLines(word.spelling.contains(" ") ? 2 : 1);
        spelling.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        spelling.setAutoSizeTextTypeUniformWithConfiguration(22, 62, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        spelling.setOnClickListener(v -> showWordDetails(word));
        copy.addView(spelling, margin(0, 0, 0, 14));

        TextView part = label(word.partOfSpeech + (word.pronunciationDisplay().isEmpty() ? "" : "  " + word.pronunciationDisplay()),
                13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL));
        copy.addView(part, margin(0, 0, 0, 32));
        TextView meaning = label(word.meaning, 21, palette.ink, Typeface.create("sans-serif-light", Typeface.NORMAL));
        meaning.setTag("word-meaning");
        meaning.setLineSpacing(dp(4), 1.05f);
        meaning.setMaxLines(5);
        meaning.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(meaning, margin(0, 0, 0, 22));
        TextView example = null;
        if (!word.example.isEmpty()) {
            TextView exampleHeading = eyebrow("EXAMPLE"); exampleHeading.setTag("example-heading");
            copy.addView(exampleHeading, margin(0, 0, 0, 6));
            example = label("“" + word.example + "”", 15, palette.example, Typeface.create("serif", Typeface.ITALIC));
            example.setTag("word-example");
            example.setLineSpacing(dp(3), 1f);
            example.setMaxLines(3);
            example.setEllipsize(android.text.TextUtils.TruncateAt.END);
            example.setPadding(dp(16), dp(14), dp(16), dp(14));
            example.setBackground(shape(palette.soft, 8, 0, 0));
            copy.addView(example, margin(0, 0, 0, 12));
        }
        TextView details = label("EXPLORE THIS WORD  ↗", 12, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        details.setLetterSpacing(.08f);
        page.details = details;
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setOnClickListener(v -> showWordDetails(word));
        copy.addView(details, new LinearLayout.LayoutParams(-1, dp(48)));
        paper.addView(copy, new FrameLayout.LayoutParams(-1, -1));
        final TextView exampleView = example;
        // Keep one page per viewport, even for long dictionary entries and large font settings.
        paper.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            float fontScale = getResources().getConfiguration().fontScale;
            boolean compact = (b - t - dp(305)) / fontScale < dp(390);
            copy.setPadding(dp(28), dp(compact ? 110 : 140), dp(78), dp(compact ? 105 : 135));
            topicRow.setVisibility(compact && fontScale > 1.3f ? View.GONE : View.VISIBLE);
            meaning.setMaxLines(compact ? (fontScale > 1.3f ? 2 : 3) : 5);
            details.setText("Read full meaning  ↗");
            meaning.setTextSize(compact ? 17 : 21);
            if (exampleView != null) {
                exampleView.setMaxLines(compact ? 2 : 3);
                boolean showExample = !compact || fontScale <= 1.3f;
                exampleView.setVisibility(showExample ? View.VISIBLE : View.GONE);
                copy.findViewWithTag("example-heading").setVisibility(showExample ? View.VISIBLE : View.GONE);
            }
        });
        page.applyFocus(focusAmount);
        return page;
    }

    private View makeExploreActionDock() {
        FrameLayout dock = new FrameLayout(this);
        dock.setClipChildren(false);
        dock.setVisibility(View.GONE);
        exploreActionDock = dock;
        exploreActionRailMode = null;

        exploreActions = new LinearLayout(this);
        exploreActions.setGravity(Gravity.CENTER);
        exploreSaveIcon = snapActionIcon("☆", palette.selected);
        exploreSaveLabel = snapActionLabel("Save");
        exploreActions.addView(snapAction(exploreSaveIcon, exploreSaveLabel, () -> {
            if (activeExploreWord != null) toggleSnapSaved(activeExploreWord, exploreSaveIcon, exploreSaveLabel);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        exploreActions.addView(snapAction(snapActionIcon("♪", palette.selected), snapActionLabel("Listen"), () -> {
            if (activeExploreWord != null) speak(activeExploreWord);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        exploreActions.addView(snapAction(snapActionIcon("↗", palette.selected), snapActionLabel("Share"), () -> {
            if (activeExploreWord != null) shareWordCard(activeExploreWord);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        exploreActions.addView(snapAction(snapActionIcon("⋯", palette.selected), snapActionLabel("More"), () -> {
            if (activeExploreWord != null) showFeedOptions(activeExploreWord);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        exploreNormalAction = snapAction(snapActionIcon("normal", palette.selected), snapActionLabel("Show controls"), () -> setFocusMode(false));
        exploreNormalAction.setContentDescription("Show controls");
        exploreActions.addView(exploreNormalAction, new LinearLayout.LayoutParams(0, -2, 1));
        dock.addView(exploreActions);
        applyExploreActionPlacement();
        return dock;
    }

    private void bindExploreActionsToCurrentPage() {
        if (snapPager == null || exploreActionDock == null) return;
        View page = snapPager.getPageAt(snapPager.getDisplayedChild());
        activeExploreWord = page != null && page.getTag() instanceof Word ? (Word) page.getTag() : null;
        exploreActionDock.setVisibility(activeExploreWord == null ? View.GONE : View.VISIBLE);
        if (activeExploreWord != null) {
            boolean saved = LafzPrefs.saved(this).contains(activeExploreWord.id);
            setActionSymbol(exploreSaveIcon, saved ? "★" : "☆", palette.selected);
            exploreSaveLabel.setText(saved ? "Saved" : "Save");
            String displayedId = activeExploreWord.id;
            mainHandler.post(() -> {
                if (currentTab == Tab.FEED && exploreMode && !launching && activeExploreWord != null &&
                        displayedId.equals(activeExploreWord.id)) {
                    LafzRepository.executeBackground(() -> LafzRepository.get(getApplicationContext()).markSeen(displayedId));
                }
            });
        }
    }

    private void applyExploreActionPlacement() {
        if (exploreActions == null) return;
        if (exploreActionRailMode != null && exploreActionRailMode == focusMode) return;
        exploreActionRailMode = focusMode;
        exploreActions.setOrientation(LinearLayout.VERTICAL);
        exploreNormalAction.setVisibility(focusMode ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(dp(64), -2, Gravity.RIGHT | Gravity.BOTTOM);
        placement.setMargins(0, 0, dp(8), dp(focusMode ? 70 : 158));
        exploreActions.setLayoutParams(placement);
        for (int i = 0; i < exploreActions.getChildCount(); i++) {
            exploreActions.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(-1, dp(74)));
        }
    }

    private void setFocusMode(boolean enabled) {
        if (enabled && LafzPrefs.get(this).getBoolean("keep_controls_visible", false)) return;
        if (focusMode == enabled || snapPager == null) return;
        if (enabled && !LafzPrefs.get(this).getBoolean("reading_hint_seen", false)) {
            LafzPrefs.get(this).edit().putBoolean("reading_hint_seen", true).apply();
            Toast.makeText(this, "Reading mode hides controls. Tap Show controls or press Back to restore them. Change this in Settings.", Toast.LENGTH_LONG).show();
        }
        focusMode = enabled;
        if (focusAnimator != null) focusAnimator.cancel();
        if (!motionEnabled()) { focusAmount = enabled ? 1 : 0; applyFocusAmount(); return; }
        focusAnimator = ValueAnimator.ofFloat(focusAmount, enabled ? 1f : 0f);
        focusAnimator.setDuration(260); focusAnimator.setInterpolator(new DecelerateInterpolator(2));
        focusAnimator.addUpdateListener(value -> { focusAmount = (float) value.getAnimatedValue(); applyFocusAmount(); });
        focusAnimator.start();
    }

    private void applyFocusAmount() {
        if (exploreTop == null || exploreBottom == null || snapPager == null) return;
        exploreTop.setAlpha(1 - focusAmount); exploreTop.setTranslationY(-dp(24) * focusAmount);
        exploreBottom.setAlpha(1 - focusAmount); exploreBottom.setTranslationY(dp(24) * focusAmount);
        int visibility = focusAmount >= .99f ? View.INVISIBLE : View.VISIBLE;
        exploreTop.setVisibility(visibility); exploreBottom.setVisibility(visibility);
        exploreTop.setImportantForAccessibility(focusMode ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        exploreBottom.setImportantForAccessibility(focusMode ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        for (int i = 0; i < snapPager.getPageCount(); i++) {
            View page = snapPager.getPageAt(i); if (page instanceof ReadingPage) ((ReadingPage) page).applyFocus(focusAmount);
        }
        applyExploreActionPlacement();
    }

    private final class ReadingPage extends FrameLayout {
        FrameLayout paper;
        LinearLayout copy;
        View topic, details;
        ReadingPage(Context context) { super(context); }
        void applyFocus(float amount) {
            // Fade supporting controls without reflowing text or resizing the paging viewport.
            topic.setAlpha(1 - amount);
            details.setAlpha(1);
            details.setVisibility(VISIBLE);
            topic.setImportantForAccessibility(focusMode ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            details.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
    }

    private void showFeedOptions(Word word) {
        StyledSheet sheet = new StyledSheet(word.spelling, word.category + " · " + word.partOfSpeech);
        sheet.addAction("normal", "Word details", "Meaning, examples and related words", () -> { sheet.dismiss(); showWordDetails(word); });
        sheet.addAction("plus", "More like this", "Explore more " + word.category.toLowerCase(Locale.getDefault()) + " words", () -> {
            boostedFeedCategory = word.category; sheet.dismiss();
            Toast.makeText(this, "Your upcoming words will include more from this topic.", Toast.LENGTH_SHORT).show();
        });
        sheet.addAction("minus", "Not for me", "Hide this word from your feed", () -> {
            sheet.dismiss(); LafzRepository.get(this).hide(word.id);
            loadMoreExploreWords(); snapPager.showNextPage();
        });
        sheet.show();
    }

    /** Shared, fully styled surface for every in-app information and choice popup. */
    private final class StyledSheet extends Dialog {
        final LinearLayout surface = new LinearLayout(MainActivity.this);
        final LinearLayout body = new LinearLayout(MainActivity.this);
        final LinearLayout footer = new LinearLayout(MainActivity.this);
        final ScrollView scroll;
        private boolean closing;
        private StyledSheet previousSheet;
        StyledSheet(String title, String subtitle) {
            super(MainActivity.this);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            surface.setOrientation(LinearLayout.VERTICAL); surface.setPadding(dp(22), dp(12), dp(22), dp(22));
            surface.setBackground(shape(palette.card, 30, palette.line, 1)); surface.setClipToOutline(true);
            View handle = new View(MainActivity.this); handle.setBackground(shape(palette.line, 2, palette.line, 0));
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(34), dp(4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL; handleParams.bottomMargin = dp(20); surface.addView(handle, handleParams);
            LinearLayout heading = new LinearLayout(MainActivity.this); heading.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            TextView name = label(title, 27, palette.ink, Typeface.create("serif", Typeface.NORMAL));
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (title == null || title.trim().isEmpty()) name.setVisibility(View.GONE);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView close = label("×", 26, palette.muted, Typeface.create("serif", Typeface.NORMAL));
            close.setGravity(Gravity.CENTER); close.setBackground(shape(palette.soft, 24, palette.soft, 0));
            close.setContentDescription("Close"); close.setOnClickListener(v -> dismiss());
            heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); surface.addView(heading);
            if (subtitle != null && !subtitle.isEmpty()) {
                TextView sub = label(subtitle, 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL));
                sub.setLineSpacing(dp(3), 1); surface.addView(sub, margin(0, 7, 0, 18));
            }
            body.setOrientation(LinearLayout.VERTICAL);
            scroll = new ScrollView(MainActivity.this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int max = Math.round(getResources().getDisplayMetrics().heightPixels * .53f);
                    if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
                        max = Math.min(max, View.MeasureSpec.getSize(heightSpec));
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
                }
            };
            scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); surface.addView(scroll, margin(0, 4, 0, 12));
            footer.setOrientation(LinearLayout.VERTICAL); surface.addView(footer);
            setContentView(surface); setCanceledOnTouchOutside(true);
        }
        void addAction(String icon, String title, String description, Runnable action) {
            LinearLayout row = new LinearLayout(MainActivity.this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(12), dp(10), dp(12)); row.setBackground(shape(palette.soft, 20, palette.soft, 0));
            ActionIconView mark = snapActionIcon(icon, palette.selected);
            row.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout copy = new LinearLayout(MainActivity.this); copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(label(title, 15, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
            copy.addView(label(description, 12, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 4, 0, 0));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = dp(12); row.addView(copy, cp);
            row.setOnClickListener(v -> action.run()); body.addView(row, margin(0, 0, 0, 8));
        }
        @Override public void show() {
            previousSheet = activeSheet;
            if (previousSheet != null && previousSheet.isShowing()) previousSheet.surface.setVisibility(View.INVISIBLE);
            activeSheet = this;
            super.show(); Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.gravity = Gravity.BOTTOM; attributes.y = dp(12); attributes.dimAmount = .32f; attributes.windowAnimations = 0;
                window.setAttributes(attributes);
                window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(520)), -2);
            }
            if (motionEnabled()) {
                surface.setAlpha(0); surface.setTranslationY(dp(32));
                surface.animate().alpha(1).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2)).start();
            }
        }
        @Override public void dismiss() {
            if (closing) return; closing = true;
            if (motionEnabled() && isShowing() && !isFinishing()) {
                surface.animate().alpha(0).translationY(dp(24)).setDuration(140).withEndAction(this::finishDismiss).start();
            } else finishDismiss();
        }
        private void finishDismiss() {
            super.dismiss();
            if (activeSheet != this) return;
            StyledSheet restore = previousSheet;
            while (restore != null && (!restore.isShowing() || restore.closing)) restore = restore.previousSheet;
            activeSheet = restore;
            if (restore != null) restore.surface.setVisibility(View.VISIBLE);
        }
    }

    private LinearLayout snapAction(ActionIconView icon, TextView title, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(dp(47), dp(47)));
        item.addView(title, margin(0, 2, 0, 0));
        item.setPadding(0, dp(2), 0, dp(4));
        title.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) { item.setContentDescription(value); }
            public void afterTextChanged(android.text.Editable value) { }
        });
        item.setContentDescription(title.getText());
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.setOnClickListener(v -> {
            if (motionEnabled()) icon.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).withEndAction(() ->
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(180).start()).start();
            action.run();
        });
        return item;
    }

    private ActionIconView snapActionIcon(String value, int color) { return new ActionIconView(this, value, color); }

    private void setActionSymbol(ActionIconView view, String symbol, int color) { view.setSymbol(symbol, color); }

    private final class ActionIconView extends View {
        private Drawable symbol;
        ActionIconView(Context context, String value, int color) {
            super(context); setBackground(shape(palette.card, 24, palette.line, 1)); setSymbol(value, color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setSymbol(String value, int color) { symbol = new ActionDrawable(value, color); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            int size = dp(23), left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
            symbol.setBounds(left, top, left + size, top + size); symbol.draw(canvas);
        }
    }

    private final class ActionDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String symbol;
        ActionDrawable(String symbol, int color) {
            this.symbol = symbol; paint.setColor(color); paint.setStrokeWidth(1.65f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            path.reset();
            if (symbol.equals("☆") || symbol.equals("★")) {
                path.moveTo(6, 3); path.lineTo(18, 3); path.lineTo(18, 21); path.lineTo(12, 17); path.lineTo(6, 21); path.close();
                paint.setStyle(symbol.equals("★") ? Paint.Style.FILL : Paint.Style.STROKE); canvas.drawPath(path, paint);
            } else if (symbol.equals("♪")) {
                canvas.translate(-1.5f, 0);
                path.moveTo(4, 9); path.lineTo(8, 9); path.lineTo(13, 5); path.lineTo(13, 19); path.lineTo(8, 15); path.lineTo(4, 15); path.close(); canvas.drawPath(path, paint);
                canvas.drawArc(10, 7, 20, 17, -55, 110, false, paint); canvas.drawArc(10, 3, 24, 21, -55, 110, false, paint);
            } else if (symbol.equals("↗")) {
                canvas.drawLine(12, 3, 12, 15, paint); canvas.drawLine(8, 7, 12, 3, paint); canvas.drawLine(12, 3, 16, 7, paint);
                path.moveTo(5, 12); path.lineTo(5, 21); path.lineTo(19, 21); path.lineTo(19, 12); canvas.drawPath(path, paint);
            } else if (symbol.equals("plus") || symbol.equals("minus")) {
                canvas.drawLine(5, 12, 19, 12, paint);
                if (symbol.equals("plus")) canvas.drawLine(12, 5, 12, 19, paint);
            } else if (symbol.equals("source")) {
                canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint);
                canvas.drawLine(9, 3, 9, 21, paint); canvas.drawLine(12, 8, 16, 8, paint); canvas.drawLine(12, 12, 16, 12, paint);
            } else if (symbol.equals("normal")) {
                canvas.drawLine(4, 9, 4, 4, paint); canvas.drawLine(4, 4, 9, 4, paint);
                canvas.drawLine(15, 4, 20, 4, paint); canvas.drawLine(20, 4, 20, 9, paint);
                canvas.drawLine(4, 15, 4, 20, paint); canvas.drawLine(4, 20, 9, 20, paint);
                canvas.drawLine(15, 20, 20, 20, paint); canvas.drawLine(20, 20, 20, 15, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(5, 12, 1.7f, paint); canvas.drawCircle(12, 12, 1.7f, paint); canvas.drawCircle(19, 12, 1.7f, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private TextView snapActionLabel(String value) {
        TextView text = label(value, 9, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private void toggleSnapSaved(Word word, ActionIconView icon, TextView title) {
        Set<String> saved = LafzPrefs.saved(this);
        if (saved.contains(word.id)) {
            saveCollectionWord(word, false);
            setActionSymbol(icon, "☆", palette.selected); title.setText("Save");
            Toast.makeText(this, "Removed from your library.", Toast.LENGTH_SHORT).show();
        } else {
            LafzPrefs.saveWord(this, word, true);
            setActionSymbol(icon, "★", palette.selected); title.setText("Saved");
            Toast.makeText(this, "Saved to your library.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCategoryDialog() {
        Set<String> draft = new HashSet<>(LafzPrefs.categories(this));
        final String[] draftLevel = {LafzPrefs.level(this)};
        StyledSheet sheet = new StyledSheet("Your word mix", "Choose a word level, then mix your topics.");
        sheet.body.addView(eyebrow("WORD LEVEL"), margin(0, 0, 0, 10));
        List<TextView> levelChoices = new ArrayList<>();
        for (String level : LearningContent.LEVELS) {
            TextView choice = button(level, level.equals(draftLevel[0]), () -> { });
            choice.setOnClickListener(v -> {
                draftLevel[0] = level;
                for (int index = 0; index < levelChoices.size(); index++) {
                    TextView option = levelChoices.get(index);
                    boolean selected = LearningContent.LEVELS[index].equals(level);
                    option.setTextColor(selected ? palette.selectedText : palette.ink);
                    option.setBackground(shape(selected ? palette.selected : palette.soft, 18, palette.line, 0));
                    option.setSelected(selected);
                    option.setContentDescription(LearningContent.LEVELS[index] + (selected ? ", selected" : ""));
                }
            });
            choice.setSelected(level.equals(draftLevel[0]));
            choice.setContentDescription(level + (level.equals(draftLevel[0]) ? ", selected" : ""));
            levelChoices.add(choice); sheet.body.addView(choice, margin(0, 0, 0, 8));
        }
        sheet.body.addView(label("Everyday and Stretch use NGSL-ranked learner vocabulary. Full dictionary opens all 115,130 entries.",
                12, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 4, 0, 18));
        sheet.body.addView(eyebrow("TOPICS · " + LafzWords.CATEGORIES.size()), margin(0, 0, 0, 10));
        List<TextView> marks = new ArrayList<>(); List<LinearLayout> rows = new ArrayList<>();
        List<String> topics = new ArrayList<>(); topics.add("Blend"); topics.addAll(LafzWords.CATEGORIES);
        TextView summary = label("", 12, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL));
        summary.setGravity(Gravity.CENTER); sheet.footer.addView(summary, margin(0, 2, 0, 12));
        Runnable refresh = () -> {
            for (int i = 0; i < topics.size(); i++) {
                boolean selected = i == 0 ? draft.isEmpty() : draft.contains(topics.get(i));
                marks.get(i).setText(selected ? "✓" : "");
                marks.get(i).setBackground(shape(selected ? palette.selected : palette.card, 12, selected ? palette.selected : palette.line, selected ? 0 : 1));
                marks.get(i).setTextColor(palette.selectedText);
                rows.get(i).setBackground(shape(selected ? palette.soft : palette.card, 18, selected ? palette.soft : palette.line, selected ? 0 : 1));
                rows.get(i).setSelected(selected); rows.get(i).setContentDescription(topics.get(i) + (selected ? ", selected" : ", not selected"));
            }
            summary.setText(draft.isEmpty() ? "All " + LafzWords.CATEGORIES.size() + " topics, gently mixed" : draft.size() + (draft.size() == 1 ? " topic selected" : " topics selected"));
        };
        for (int i = 0; i < topics.size(); i++) {
            String topic = topics.get(i); LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
            copy.addView(label(topic, 15, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
            if (i == 0) copy.addView(label("A little of every topic", 12, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 4, 0, 0));
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mark = label("", 15, palette.selectedText, Typeface.create("sans-serif-medium", Typeface.NORMAL)); mark.setGravity(Gravity.CENTER);
            row.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26))); marks.add(mark); rows.add(row);
            row.setMinimumHeight(dp(56)); row.setOnClickListener(v -> {
                if (topic.equals("Blend")) draft.clear(); else if (!draft.add(topic)) draft.remove(topic);
                refresh.run();
            }); sheet.body.addView(row, margin(0, 0, 0, 8));
        }
        refresh.run();
        sheet.footer.addView(button("Apply word mix", true, () -> {
            LafzPrefs.get(this).edit().putStringSet(LafzPrefs.CATEGORIES, new HashSet<>(draft)).putString("word_level", draftLevel[0]).apply();
            feedWords.clear(); exploreCursor = 0; boostedFeedCategory = null;
            if (LearningContent.ALL.equals(draftLevel[0])) LafzRepository.get(this).ensureFull(sourceListener);
            sheet.dismiss(); WordWidgetProvider.refresh(this); render();
        })); sheet.show();
    }

    private void showInfoSheet(String title, String subtitle, View content) {
        StyledSheet sheet = new StyledSheet(title, subtitle);
        sheet.body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        sheet.footer.addView(button("Done", true, sheet::dismiss)); sheet.show();
    }

    private void showTimeSheet(int hour, int minute) {
        StyledSheet sheet = new StyledSheet("Your daily moment", "Choose when your word arrives.");
        TimePicker picker = new TimePicker(this); picker.setIs24HourView(false); picker.setHour(hour); picker.setMinute(minute);
        sheet.body.addView(picker);
        sheet.footer.addView(button("Save time", true, () -> {
            LafzPrefs.get(this).edit().putInt("daily_time", picker.getHour() * 60 + picker.getMinute()).apply();
            DailyWordReceiver.schedule(this); sheet.dismiss(); render();
        })); sheet.show();
    }

    private void showSourceDetails() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), dp(4), dp(8), dp(4));
        body.addView(label("Lafz Lexicon", 19, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 8));
        body.addView(label("Source definitions, pronunciation, frequency, and word-level sentences are credited separately. A word-level example has not been matched to a particular meaning.",
                13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 12));
        for (LexiconSource source : LafzRepository.get(this).sources()) {
            LinearLayout card = settingCard();
            card.addView(label(source.name, 15, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
            card.addView(label(source.publisher + " · " + source.version, 11, palette.muted,
                    Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 4, 0, 8));
            if (!source.attribution.isEmpty()) card.addView(label(source.attribution, 11, palette.muted,
                    Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 7));
            card.addView(label("License: " + source.license + "\n" + source.redistribution, 11, palette.muted,
                    Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 7));
            TextView link = label("Open license", 12, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            link.setOnClickListener(v -> openWeb(source.licenseUrl));
            card.addView(link);
            body.addView(card, margin(0, 0, 0, 9));
        }
        showInfoSheet("Credits & licenses", "Attribution and reuse terms for included datasets", body);
    }

    private void openWeb(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { Toast.makeText(this, "A browser is needed to open this source.", Toast.LENGTH_SHORT).show(); }
    }

    private void buildFeedPage(LinearLayout page) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()));
        page.addView(eyebrow(date.toUpperCase(Locale.getDefault())), margin(0, 8, 0, 12));
        page.addView(label("A moment,\na new word.", 34, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 12));
        page.addView(label("Take a moment with today's discovery.", 14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 24));
        if (!LafzRepository.get(this).isReady()) { page.addView(makeSourceLoadingPage()); return; }
        Word word = LafzRepository.get(this).daily(LafzPrefs.categories(this), LafzPrefs.today());
        if (word == null) {
            page.addView(emptyCard("No words in this mix yet.", "Choose Blend or add another category to see more words.", "Choose categories", this::showCategoryDialog)); return;
        }
        page.addView(makeWordCard(word, false, true), margin(0, 0, 0, 20));
        TextView exploreButton = button("Keep exploring  ↑", true, () -> { exploreMode = true; render(); });
        LinearLayout.LayoutParams exploreButtonParams = (LinearLayout.LayoutParams) exploreButton.getLayoutParams();
        exploreButtonParams.bottomMargin = dp(18);
        page.addView(exploreButton, exploreButtonParams);
        TextView mix = label("Your topics · " + categoryFilterLabel(), 13, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mix.setGravity(Gravity.CENTER); mix.setOnClickListener(v -> showCategoryDialog());
        page.addView(mix, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private View makeWordCard(Word word, boolean discovery, boolean daily) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(discovery || daily ? 24 : 14), dp(24), dp(discovery || daily ? 24 : 14), dp(24));
        card.setBackground(shape(palette.card, 28, palette.line, 0));
        card.setElevation(0);

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView category = eyebrow(word.category.toUpperCase(Locale.getDefault()) + "  ·  " + word.partOfSpeech.toUpperCase(Locale.getDefault()));
        category.setTextColor(categoryColor(word.category));
        meta.addView(category, new LinearLayout.LayoutParams(0, -2, 1));
        if (daily) meta.addView(label("✦  DAILY", 10, palette.gold, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        card.addView(meta);

        LinearLayout wordRow = new LinearLayout(this);
        wordRow.setOrientation(LinearLayout.VERTICAL);
        wordRow.setGravity(Gravity.START);
        wordRow.setPadding(0, dp(7), 0, dp(4));
        TextView spelling = label(word.spelling, 39, palette.ink, Typeface.create("serif", Typeface.NORMAL));
        spelling.setLetterSpacing(-0.025f);
        spelling.setGravity(Gravity.CENTER_VERTICAL);
        spelling.setSingleLine(true);
        spelling.setAutoSizeTextTypeUniformWithConfiguration(22, 39, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        wordRow.addView(spelling, new LinearLayout.LayoutParams(-1, -2));
        TextView listen = label("♪  Listen", 12, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        listen.setGravity(Gravity.CENTER);
        listen.setPadding(dp(11), 0, dp(11), 0);
        listen.setBackground(shape(palette.soft, 18, palette.soft, 0));
        listen.setContentDescription("Hear pronunciation of " + word.spelling);
        listen.setOnClickListener(v -> speak(word));
        LinearLayout.LayoutParams listenParams = new LinearLayout.LayoutParams(-2, dp(36));
        listenParams.gravity = Gravity.END;
        listenParams.topMargin = dp(8);
        wordRow.addView(listen, listenParams);
        card.addView(wordRow);
        if (!word.pronunciationDisplay().isEmpty()) card.addView(label(word.pronunciationDisplay(), 14, palette.muted, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 13));

        TextView meaning = label(word.meaning, 16, palette.ink, Typeface.create("sans-serif", Typeface.NORMAL));
        meaning.setLineSpacing(dp(3), 1f);
        card.addView(meaning, margin(0, 0, 0, 14));

        if (!word.example.isEmpty()) {
            LinearLayout exampleBox = new LinearLayout(this);
            exampleBox.setOrientation(LinearLayout.VERTICAL);
            exampleBox.setPadding(dp(13), dp(11), dp(12), dp(11));
            exampleBox.setBackground(shape(palette.soft, 14, palette.soft, 0));
            exampleBox.addView(eyebrow("EXAMPLE"), margin(0, 0, 0, 6));
            View rule = new View(this);
            rule.setBackgroundColor(categoryColor(word.category));
            LinearLayout exampleRow = new LinearLayout(this);
            exampleRow.setGravity(Gravity.CENTER_VERTICAL);
            exampleRow.addView(rule, new LinearLayout.LayoutParams(dp(2), -1));
            TextView example = label("“" + word.example + "”", 13, palette.example, Typeface.create("serif", Typeface.ITALIC));
            example.setLineSpacing(dp(2), 1f);
            exampleRow.addView(example, new LinearLayout.LayoutParams(0, -2, 1));
            exampleBox.addView(exampleRow, new LinearLayout.LayoutParams(-1, -2));
            card.addView(exampleBox, margin(0, 0, 0, 13));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        SharedPreferences prefs = LafzPrefs.get(this);
        Set<String> saved = LafzPrefs.saved(this);
        boolean isSaved = saved.contains(word.id);
        TextView save = actionText(isSaved ? "✓  Saved" : "☆  Save", isSaved ? palette.gold : palette.ink);
        save.setOnClickListener(v -> toggleSaved(word, save));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(39), 1.15f));
        TextView share = actionText("↗  Share", palette.ink);
        share.setOnClickListener(v -> shareWordCard(word));
        actions.addView(share, new LinearLayout.LayoutParams(0, dp(39), 1));
        TextView details = actionText("Details  ›", palette.ink);
        details.setOnClickListener(v -> showWordDetails(word));
        actions.addView(details, new LinearLayout.LayoutParams(0, dp(39), 1));
        card.addView(actions);

        if (discovery) {
            View separator = new View(this);
            separator.setBackgroundColor(palette.line);
            LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(-1, dp(1));
            separatorParams.setMargins(0, dp(8), 0, dp(7));
            card.addView(separator, separatorParams);
            LinearLayout feedback = new LinearLayout(this);
            feedback.setGravity(Gravity.CENTER_VERTICAL);
            TextView more = label("More like this", 12, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            more.setGravity(Gravity.CENTER);
            more.setOnClickListener(v -> boostCategory(word.category));
            TextView less = label("Not for me", 12, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            less.setGravity(Gravity.CENTER);
            less.setOnClickListener(v -> hideWord(word));
            feedback.addView(more, new LinearLayout.LayoutParams(0, dp(34), 1));
            feedback.addView(less, new LinearLayout.LayoutParams(0, dp(34), 1));
            card.addView(feedback);
        }
        return card;
    }

    private void buildLibraryPage(LinearLayout page) {
        List<Word> saved = wordsFor(LafzPrefs.saved(this));
        if (saved.isEmpty()) {
            page.addView(eyebrow("SAVED BY YOU"), margin(0, 8, 0, 10));
            page.addView(label("Your collection", 36, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 20));
            page.addView(emptyCard("Keep what speaks to you.", "Tap Save on any word. Your own little dictionary starts here.",
                    "Discover a word", () -> { currentTab = Tab.FEED; exploreMode = true; render(); }));
            return;
        }
        page.addView(new SavedWordsScreen(this, saved, palette.ink, palette.muted, palette.selected,
                palette.card, palette.line, palette.selectedText, this::showSavedWord));
    }

    private LinearLayout makeQuizLaunchCard(String title, String description, QuizSet set, boolean enabled) {
        LinearLayout card = settingCard();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(label(title, 18, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        copy.addView(label(description, 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 4, 0, 0));
        top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mark = label(set == QuizSet.KEPT ? "☆" : "↗", 20, palette.selected, Typeface.create("sans-serif", Typeface.NORMAL));
        mark.setGravity(Gravity.CENTER);
        top.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));
        card.addView(top, margin(0, 0, 0, 13));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(button("Type the word", false, () -> startQuiz(set, false)), new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView choose = button("Choose a word", true, () -> startQuiz(set, true));
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        chooseParams.leftMargin = dp(8);
        actions.addView(choose, chooseParams);
        if (!enabled) {
            actions.getChildAt(0).setEnabled(false);
            choose.setEnabled(false);
            choose.setAlpha(.55f);
            actions.getChildAt(0).setAlpha(.55f);
            card.setContentDescription(description);
        }
        card.addView(actions);
        return card;
    }

    private void startQuiz(QuizSet set, boolean useChoices) {
        List<Word> pool;
        if (set == QuizSet.KEPT) {
            pool = wordsFor(LafzPrefs.saved(this));
            if (pool.isEmpty()) {
                Toast.makeText(this, "Save a word before starting this quiz.", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (!LafzRepository.get(this).isReady()) {
                Toast.makeText(this, "The dictionary is still getting ready.", Toast.LENGTH_SHORT).show();
                return;
            }
            pool = uniqueSpellings(LafzRepository.get(this).randomWords(300));
            if (pool.isEmpty()) {
                Toast.makeText(this, "No quiz words are available yet.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Collections.shuffle(pool);
        int roundSize = Math.min(10, pool.size());
        quizWords = new ArrayList<>(pool.subList(0, roundSize));
        quizRoundSize = roundSize; quizFirstCorrect = 0;
        quizOptions = new ArrayList<>();
        List<Word> choicePool = new ArrayList<>(pool);
        LafzRepository repository = LafzRepository.get(this);
        choicePool.addAll(repository.randomWords(300));
        for (Word answer : quizWords) {
            choicePool.addAll(repository.quizDistractors(answer.id, 12));
            quizOptions.add(makeQuizOptions(answer, choicePool));
        }
        quizDraft = "";
        quizSet = set;
        quizUsesChoices = useChoices;
        if (quizUsesChoices && !quizOptions.isEmpty() && quizOptions.get(0).size() < 2) {
            quizUsesChoices = false;
            Toast.makeText(this, "Type mode is ready; multiple choice will be available when the dictionary loads.", Toast.LENGTH_LONG).show();
        }
        quizIndex = 0;
        quizCorrect = 0;
        quizAnswered = false;
        quizAnswerCorrect = false;
        quizSelectedAnswer = "";
        quizInput = null;
        render();
    }

    private List<Word> uniqueSpellings(List<Word> words) { return QuizEngine.uniqueSpellings(words); }

    private List<Word> makeQuizOptions(Word answer, List<Word> pool) { return QuizEngine.options(answer, pool); }

    private void buildQuizPage(LinearLayout page) {
        String setTitle = quizSet == QuizSet.KEPT ? "SAVED WORDS" : "GENERAL VOCABULARY";
        page.addView(eyebrow("MEANING → WORD  ·  " + setTitle), margin(0, 2, 0, 8));
        if (quizIndex >= quizWords.size()) {
            LinearLayout result = settingCard();
            result.setGravity(Gravity.CENTER_HORIZONTAL);
            result.addView(label("Round complete", 25, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 8));
            result.addView(label(quizFirstCorrect + " of " + quizRoundSize + " correct on the first try", 16, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL)), margin(0, 0, 0, 7));
            result.addView(label(quizFirstCorrect == quizRoundSize ? "Every word found its way back." : "Keep these meanings close; they’ll come around again." , 14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 18));
            if (quizWords.size() > quizRoundSize) result.addView(label((quizWords.size() - quizRoundSize) +
                    (quizWords.size() - quizRoundSize == 1 ? " missed word revisited · " : " missed words revisited · ") +
                    (quizCorrect - quizFirstCorrect) + " recalled on retry", 14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 14));
            result.addView(button("Play another round", true, () -> startQuiz(quizSet, quizUsesChoices)));
            page.addView(result);
            TextView done = label("Back to practice", 13, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            done.setGravity(Gravity.CENTER);
            done.setOnClickListener(v -> { resetQuiz(); render(); });
            page.addView(done, margin(0, 10, 0, 0));
            return;
        }

        Word word = quizWords.get(quizIndex);
        boolean choicesAvailable = quizOptions.get(quizIndex).size() >= 2;
        if (quizIndex >= quizRoundSize) page.addView(eyebrow("ANOTHER LOOK · MISSED WORDS"), margin(0, 0, 0, 8));
        page.addView(label("Question " + (quizIndex + 1) + " of " + quizWords.size() + "     ·     " + quizCorrect + " right", 13, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL)), margin(0, 0, 0, 13));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        modeRow.setBackground(shape(palette.soft, 17, palette.soft, 0));
        TextView typeMode = quizModeButton("Type the word", !quizUsesChoices || !choicesAvailable, () -> { quizUsesChoices = false; render(); });
        TextView choiceMode = quizModeButton("Choose a word", quizUsesChoices && choicesAvailable, () -> { quizUsesChoices = true; render(); });
        choiceMode.setEnabled(choicesAvailable); choiceMode.setAlpha(choicesAvailable ? 1f : .45f);
        modeRow.addView(typeMode, new LinearLayout.LayoutParams(0, dp(38), 1));
        modeRow.addView(choiceMode, new LinearLayout.LayoutParams(0, dp(38), 1));
        page.addView(modeRow, margin(0, 0, 0, 13));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(21), dp(20), dp(20));
        card.setBackground(shape(palette.card, 25, palette.line, 1));
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView category = eyebrow(word.category.toUpperCase(Locale.getDefault()));
        category.setTextColor(categoryColor(word.category));
        meta.addView(category, new LinearLayout.LayoutParams(0, -2, 1));
        meta.addView(label(word.partOfSpeech, 12, palette.muted, Typeface.create("serif", Typeface.NORMAL)));
        card.addView(meta, margin(0, 0, 0, 16));
        TextView meaning = label(word.meaning, 20, palette.ink, Typeface.create("sans-serif", Typeface.NORMAL));
        meaning.setLineSpacing(dp(4), 1f);
        card.addView(meaning, margin(0, 0, 0, 18));

        if (quizUsesChoices && choicesAvailable) {
            List<Word> options = quizOptions.get(quizIndex);
            for (Word option : options) {
                String spelling = option.spelling;
                boolean selectedAnswer = quizAnswered && spelling.equalsIgnoreCase(quizSelectedAnswer);
                TextView choice = label(spelling, 16, selectedAnswer ? palette.selectedText : palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL));
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setPadding(dp(15), dp(14), dp(15), dp(14));
                choice.setMinHeight(dp(54));
                choice.setBackground(shape(selectedAnswer ? palette.selected : palette.soft, 14, selectedAnswer ? palette.selected : palette.soft, 0));
                choice.setEnabled(!quizAnswered);
                choice.setContentDescription("Answer " + spelling);
                choice.setOnClickListener(v -> submitQuizAnswer(spelling));
                card.addView(choice, margin(0, 0, 0, 8));
            }
        } else if (!quizAnswered) {
            quizInput = new EditText(this);
            quizInput.setSingleLine(true);
            quizInput.setTextSize(16);
            quizInput.setTextColor(palette.ink);
            quizInput.setHintTextColor(palette.muted);
            quizInput.setHint("Type the word");
            quizInput.setText(quizDraft);
            quizInput.setSelection(quizInput.length());
            quizInput.setPadding(dp(14), 0, dp(14), 0);
            quizInput.setBackground(shape(palette.soft, 14, palette.line, 1));
            quizInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            quizInput.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    submitQuizAnswer(view.getText().toString());
                    return true;
                }
                return false;
            });
            card.addView(quizInput, new LinearLayout.LayoutParams(-1, dp(48)));
            card.addView(button("Check answer", true, () -> submitQuizAnswer(quizInput.getText().toString())), margin(0, 10, 0, 0));
        } else {
            card.addView(label(quizSelectedAnswer, 18, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)), margin(0, 0, 0, 10));
        }

        if (quizAnswered) {
            String feedback = quizAnswerCorrect ? "That’s it — " + word.spelling + "." : "The word was " + word.spelling + ".";
            card.addView(label(feedback, 15, quizAnswerCorrect ? palette.selected : palette.copper, Typeface.create("sans-serif-medium", Typeface.NORMAL)), margin(0, 10, 0, 12));
            if (!quizAnswerCorrect) {
                card.addView(label(word.spelling + " means " + word.meaning + ".", 14, palette.ink,
                        Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 10));
                for (Word option : quizOptions.get(quizIndex)) {
                    if (!option.id.equals(word.id) && QuizEngine.normalize(option.spelling).equals(QuizEngine.normalize(quizSelectedAnswer))) {
                        card.addView(label(option.spelling + " means " + option.meaning + ".", 13, palette.muted,
                                Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 10));
                        break;
                    }
                }
                if (quizIndex < quizRoundSize) card.addView(label("You'll try this word again after this round.", 13, palette.selected,
                        Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 10));
            }
            if (!word.example.isEmpty()) {
                TextView example = label("“" + word.example + "”", 14, palette.example, Typeface.create("serif", Typeface.ITALIC));
                example.setLineSpacing(dp(2), 1f);
                card.addView(example, margin(0, 0, 0, 14));
            }
            card.addView(button(quizIndex + 1 == quizWords.size() ? "See your score" : "Next meaning", true, () -> {
                quizDraft = "";
                quizIndex++;
                quizAnswered = false;
                quizAnswerCorrect = false;
                quizSelectedAnswer = "";
                quizInput = null;
                render();
            }));
        }
        page.addView(card);
    }

    private TextView quizModeButton(String text, boolean selected, Runnable action) {
        TextView mode = label(text, 12, selected ? palette.selectedText : palette.muted,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mode.setGravity(Gravity.CENTER);
        mode.setBackground(shape(selected ? palette.selected : palette.soft, 13, selected ? palette.selected : palette.soft, 0));
        mode.setOnClickListener(v -> action.run());
        return mode;
    }

    private void submitQuizAnswer(String answer) {
        if (quizAnswered || quizSet == null || quizIndex >= quizWords.size()) return;
        String submitted = answer == null ? "" : answer.trim();
        if (submitted.isEmpty()) {
            Toast.makeText(this, "Enter a word to check it.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (quizInput != null) {
            android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.hideSoftInputFromWindow(quizInput.getWindowToken(), 0);
            quizInput.clearFocus();
        }
        quizSelectedAnswer = submitted;
        Word answerWord = quizWords.get(quizIndex);
        quizAnswerCorrect = QuizEngine.normalize(submitted).equals(QuizEngine.normalize(answerWord.spelling));
        if (quizAnswerCorrect) {
            quizCorrect++;
            if (quizIndex < quizRoundSize) quizFirstCorrect++;
        } else if (quizIndex < quizRoundSize) {
            quizWords.add(answerWord);
            quizOptions.add(new ArrayList<>(quizOptions.get(quizIndex)));
        }
        quizAnswered = true;
        render();
    }

    private void resetQuiz() {
        quizDraft = "";
        quizSet = null;
        quizWords.clear();
        quizOptions.clear();
        quizIndex = 0;
        quizCorrect = 0;
        quizAnswered = false;
        quizInput = null;
    }

    private void showSavedWord(Word word) {
        Set<String> before = LafzPrefs.saved(this);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(10), dp(12), dp(10), dp(18)); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(makeWordCard(word, false, false));
        StyledSheet sheet = new StyledSheet("", "Saved in your library");
        sheet.body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        sheet.footer.addView(button("Done", true, sheet::dismiss));
        sheet.setOnDismissListener(dialog -> {
            if (currentTab == Tab.LIBRARY && !before.equals(LafzPrefs.saved(this))) render();
        });
        sheet.show();
    }

    private void buildReviewPage(LinearLayout page) {
        if (quizSet != null) {
            page.addView(eyebrow("PRACTICE / QUIZ"), margin(0, 8, 0, 10));
            LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(label(quizIndex >= quizWords.size() ? "Your score" : "Find the word", 32, palette.ink,
                    Typeface.create("serif", Typeface.NORMAL)), new LinearLayout.LayoutParams(0, -2, 1));
            TextView exit = button("Exit", false, () -> { resetQuiz(); render(); });
            heading.addView(exit, new LinearLayout.LayoutParams(dp(64), dp(48)));
            page.addView(heading, margin(0, 0, 0, 20));
            buildQuizPage(page); return;
        }
        if (reviewSession != null) { buildFocusedReview(page); return; }
        page.addView(eyebrow("A LITTLE, EVERY DAY"), margin(0, 8, 0, 10));
        page.addView(label("Make it stick.", 36, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 10));
        page.addView(label("Start with the words ready to return.", 14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 20));
        List<Word> saved = wordsFor(LafzPrefs.saved(this));
        List<Word> due = ReviewScheduler.due(this, saved);
        if (!due.isEmpty()) {
            LinearLayout ready = settingCard();
            ready.addView(label("Review " + due.size() + (due.size() == 1 ? " word" : " words"), 28, palette.ink,
                    Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 8));
            ready.addView(label("Recall the meaning, then choose when it returns.", 14, palette.muted,
                    Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 16));
            ready.addView(button("Start review", true, () -> startReview(due)));
            if (due.size() > 5) ready.addView(button("Review 5 words", false,
                    () -> startReview(new ArrayList<>(due.subList(0, 5)))), margin(0, 8, 0, 0));
            page.addView(ready, margin(0, 0, 0, 24));
        } else if (saved.isEmpty()) {
            page.addView(emptyCard("Your first word is waiting.", "Save a word in Explore to start your collection and review it here.",
                    "Find a word", () -> { currentTab = Tab.FEED; exploreMode = true; render(); }), margin(0, 0, 0, 24));
        } else {
            SharedPreferences prefs = LafzPrefs.get(this);
            Word next = Collections.min(saved, Comparator.comparingInt(word -> prefs.getInt("due_" + word.id, LafzPrefs.today() + 1)));
            LocalDate nextDate = LocalDate.ofEpochDay(prefs.getInt("due_" + next.id, LafzPrefs.today() + 1));
            page.addView(emptyCard("You're all caught up.", "Next review: " + nextDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())) + ".",
                    "Review one now", () -> startReview(Collections.singletonList(next))), margin(0, 0, 0, 24));
        }
        addProgress(page);
        page.addView(eyebrow("OR TRY A QUIZ"), margin(0, 0, 0, 12));
        page.addView(makeQuizLaunchCard("Your saved words", "Practice the words you've collected.", QuizSet.KEPT,
                !saved.isEmpty()), margin(0, 0, 0, 12));
        page.addView(makeQuizLaunchCard("Something new", "Try " + LafzPrefs.level(this).toLowerCase(Locale.ROOT) + ".", QuizSet.GENERAL,
                LafzRepository.get(this).isReady()), margin(0, 0, 0, 20));
    }

    private void startReview(List<Word> words) {
        reviewSession = new ReviewSession(words); reviewRevealed = false; render();
    }

    private void buildFocusedReview(LinearLayout page) {
        page.addView(eyebrow("PRACTICE / REVIEW"), margin(0, 8, 0, 10));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label(reviewSession.complete() ? "Review complete" : "A second look.", 30, palette.ink,
                Typeface.create("serif", Typeface.NORMAL)), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("Exit", false, () -> { reviewSession = null; reviewRevealed = false; render(); }), new LinearLayout.LayoutParams(dp(64), dp(48)));
        page.addView(heading, margin(0, 0, 0, 18));
        if (reviewSession.complete()) {
            LinearLayout result = settingCard();
            result.addView(label(reviewSession.size() + (reviewSession.size() == 1 ? " word reviewed" : " words reviewed"), 25, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 10));
            result.addView(label(reviewSession.remembered() + " remembered · " + (reviewSession.size() - reviewSession.remembered()) + " returning tomorrow",
                    14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 18));
            result.addView(button("Back to practice", true, () -> { reviewSession = null; render(); })); page.addView(result); return;
        }
        page.addView(label("Word " + (reviewSession.position() + 1) + " of " + reviewSession.size(), 13, palette.selected,
                Typeface.create("sans-serif-medium", Typeface.NORMAL)), margin(0, 0, 0, 14));
        page.addView(makeRecallCard(reviewSession.current()));
    }

    private View makeRecallCard(Word word) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(shape(palette.card, 28, palette.line, 0));
        TextView cat = eyebrow("RECALL  ·  " + word.category.toUpperCase(Locale.getDefault()));
        cat.setTextColor(categoryColor(word.category));
        card.addView(cat);
        card.addView(label(word.spelling, 39, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 8, 0, 3));
        card.addView(label((word.pronunciationDisplay().isEmpty() ? "" : word.pronunciationDisplay() + "  ·  ") + word.partOfSpeech, 13, palette.muted, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 15));
        if (reviewRevealed) {
            card.addView(label(word.meaning, 17, palette.ink, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 12));
            if (!word.example.isEmpty()) card.addView(label("“" + word.example + "”", 14, palette.example, Typeface.create("serif", Typeface.ITALIC)), margin(0, 0, 0, 18));
            LinearLayout buttons = new LinearLayout(this);
            buttons.addView(button("Again tomorrow", false, () -> reviewAgain(word)), new LinearLayout.LayoutParams(0, dp(44), 1));
            int interval = ReviewScheduler.nextInterval(this, word);
            buttons.addView(button("Got it · back in " + interval + (interval == 1 ? " day" : " days"), true, () -> reviewGotIt(word)), marginWeight(9, 1));
            for (int i = 0; i < buttons.getChildCount(); i++) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) buttons.getChildAt(i).getLayoutParams();
                params.height = -2;
                buttons.getChildAt(i).setLayoutParams(params);
            }
            card.addView(buttons);
        } else {
            card.addView(label("Can you remember what it means?", 15, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 18));
            card.addView(button("Reveal meaning", true, () -> { reviewRevealed = true; render(); }));
        }
        return card;
    }


    private void captureQuizDraft() {
        if (quizInput != null && !quizAnswered) quizDraft = quizInput.getText().toString();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        captureQuizDraft();
        state.putString("tab", currentTab.name());
        state.putBoolean("exploreMode", exploreMode);
        state.putSerializable("review", reviewSession);
        state.putBoolean("revealed", reviewRevealed);
        state.putString("quizSet", quizSet == null ? "" : quizSet.name());
        state.putSerializable("quizWords", new ArrayList<>(quizWords));
        state.putSerializable("quizOptions", new ArrayList<>(quizOptions));
        state.putBoolean("choices", quizUsesChoices); state.putBoolean("answered", quizAnswered);
        state.putBoolean("correctAnswer", quizAnswerCorrect); state.putString("selectedAnswer", quizSelectedAnswer);
        state.putString("draft", quizDraft); state.putInt("index", quizIndex);
        state.putInt("correct", quizCorrect); state.putInt("roundSize", quizRoundSize);
        state.putInt("firstCorrect", quizFirstCorrect); state.putSerializable("undoRemoved", undoRemovedWord);
        super.onSaveInstanceState(state);
    }

    @SuppressWarnings("unchecked")
    private void restorePractice(Bundle state) {
        currentTab = Tab.valueOf(state.getString("tab", "FEED"));
        exploreMode = state.getBoolean("exploreMode", true);
        reviewSession = (ReviewSession) state.getSerializable("review");
        reviewRevealed = state.getBoolean("revealed");
        String set = state.getString("quizSet", ""); quizSet = set.isEmpty() ? null : QuizSet.valueOf(set);
        if (state.getSerializable("quizWords") != null) quizWords = (List<Word>) state.getSerializable("quizWords");
        if (state.getSerializable("quizOptions") != null) quizOptions = (List<List<Word>>) state.getSerializable("quizOptions");
        quizUsesChoices = state.getBoolean("choices", true); quizAnswered = state.getBoolean("answered");
        quizAnswerCorrect = state.getBoolean("correctAnswer"); quizSelectedAnswer = state.getString("selectedAnswer", "");
        quizDraft = state.getString("draft", ""); quizIndex = state.getInt("index");
        quizCorrect = state.getInt("correct"); quizRoundSize = state.getInt("roundSize");
        quizFirstCorrect = state.getInt("firstCorrect"); undoRemovedWord = (Word) state.getSerializable("undoRemoved");
    }

    private void addProgress(LinearLayout page) {
        SharedPreferences prefs = LafzPrefs.get(this);
        Set<String> week = new HashSet<>(); int remembered = 0;
        for (int day = LafzPrefs.today() - 6; day <= LafzPrefs.today(); day++) {
            week.addAll(prefs.getStringSet("reviewed_day_" + day, new HashSet<>()));
            remembered += prefs.getInt("remembered_day_" + day, 0);
        }
        int today = prefs.getStringSet("reviewed_day_" + LafzPrefs.today(), new HashSet<>()).size();
        LinearLayout progress = settingCard();
        progress.addView(label(week.size() + " words reviewed · last 7 days", 16, palette.ink, Typeface.create("serif", Typeface.NORMAL)));
        progress.addView(label(remembered + " successful recalls · " + today + " / 5 words today", 13, palette.muted,
                Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 6, 0, 0));
        page.addView(progress, margin(0, 0, 0, 20));
    }

    private void saveCollectionWord(Word word, boolean keep) {
        LafzPrefs.saveWord(this, word, keep);
        if (!keep) {
            undoRemovedWord = word;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || undoRemovedWord != word) return;
                StyledSheet undo = new StyledSheet("Removed from saved", word.spelling);
                undo.body.addView(button("Undo removal", true, () -> {
                    LafzPrefs.saveWord(this, word, true); undoRemovedWord = null; undo.dismiss(); render();
                }));
                undo.show();
            });
        }
    }

    private void showHiddenWords() {
        StyledSheet sheet = new StyledSheet("Hidden words", "Restore words marked Not for me.");
        LafzRepository repository = LafzRepository.get(this);
        List<Word> hidden = wordsFor(repository.hiddenIds());
        if (hidden.isEmpty()) addReadingText(sheet.body, "No hidden words.", 16);
        for (Word word : hidden) {
            TextView restore = button("Restore " + word.spelling, false, () -> {});
            restore.setOnClickListener(v -> {
                repository.restoreHidden(word.id); restore.setText("Restored · " + word.spelling); restore.setEnabled(false);
            });
            sheet.body.addView(restore, margin(0, 0, 0, 8));
        }
        sheet.show();
    }

    private void openBackupDocument(boolean export) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "lafz-collection-" + LocalDate.now() + ".json");
        try { startActivityForResult(intent, export ? 710 : 711); }
        catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "No document picker is available on this device.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != 710 && request != 711) || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        LafzRepository.executeBackground(() -> {
            try {
                if (request == 710) {
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new java.io.IOException();
                        output.write(CollectionBackup.exportJson(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } else {
                    String text;
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new java.io.IOException();
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[8192]; int count;
                        while ((count = input.read(chunk)) != -1) {
                            buffer.write(chunk, 0, count);
                            if (buffer.size() > 8 * 1024 * 1024) throw new java.io.IOException("Backup too large");
                        }
                        byte[] bytes = buffer.toByteArray();
                        if (bytes.length > 8 * 1024 * 1024) throw new java.io.IOException("Backup too large");
                        text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    CollectionBackup.importJson(this, text);
                }
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, request == 710 ? "Backup exported." : "Backup merged. Existing review schedules kept.", Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Exception error) {
                mainHandler.post(() -> Toast.makeText(this, request == 710 ? "Couldn't export backup. Try another location." :
                        "Couldn't import. Choose a valid Lafz collection backup.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void buildSettingsPage(LinearLayout page) {
        page.addView(eyebrow("MAKE IT YOURS"), margin(0, 6, 0, 7));
        page.addView(label("Your space.", 36, palette.ink, Typeface.create("serif", Typeface.NORMAL)), margin(0, 0, 0, 17));

        page.addView(label("Small rituals. A reading space that feels like you.", 14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 0, 0, 26));
        page.addView(eyebrow("YOUR DAILY RITUAL"), margin(0, 0, 0, 12));
        LinearLayout notification = settingCard();
        LinearLayout noticeTop = new LinearLayout(this);
        noticeTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout noticeCopy = new LinearLayout(this);
        noticeCopy.setOrientation(LinearLayout.VERTICAL);
        noticeCopy.addView(label("Daily word", 16, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        noticeCopy.addView(label("A gentle nudge, once a day.", 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 3, 0, 0));
        noticeTop.addView(noticeCopy, new LinearLayout.LayoutParams(0, -2, 1));
        Switch dailySwitch = new Switch(this);
        dailySwitch.setContentDescription("Daily word notifications");
        dailySwitch.setChecked(LafzPrefs.get(this).getBoolean("daily_enabled", false));
        noticeTop.addView(dailySwitch);
        notification.addView(noticeTop);
        View line = new View(this); line.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1));
        lineParams.setMargins(0, dp(15), 0, dp(12));
        notification.addView(line, lineParams);
        int minutes = LafzPrefs.get(this).getInt("daily_time", 9 * 60);
        int hour = minutes / 60; int minute = minutes % 60;
        TextView timeRow = label("Notification time\n" + formatTime(hour, minute), 14, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        timeRow.setLineSpacing(dp(3), 1f);
        timeRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        timeRow.setBackground(shape(palette.soft, 13, palette.soft, 0));
        timeRow.setOnClickListener(v -> showTimeSheet(hour, minute));
        notification.addView(timeRow);
        dailySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setDailyEnabled(isChecked));
        page.addView(notification, margin(0, 0, 0, 12));

        page.addView(eyebrow("READING & APPEARANCE"), margin(0, 16, 0, 12));
        LinearLayout appearance = settingCard();
        LinearLayout appearanceRow = new LinearLayout(this);
        appearanceRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout appearanceCopy = new LinearLayout(this);
        appearanceCopy.setOrientation(LinearLayout.VERTICAL);
        appearanceCopy.addView(label("Night reading", 16, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        appearanceCopy.addView(label("A softer canvas for evening reading.", 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 3, 0, 0));
        appearanceRow.addView(appearanceCopy, new LinearLayout.LayoutParams(0, -2, 1));
        Switch themeSwitch = new Switch(this);
        themeSwitch.setContentDescription("Night reading theme");
        themeSwitch.setChecked(palette.dark);
        appearanceRow.addView(themeSwitch);
        themeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            LafzPrefs.get(this).edit().putBoolean("dark_theme", checked).apply();
            render();
        });
        appearance.addView(appearanceRow);
        Switch controls = new Switch(this);
        controls.setText("Keep navigation visible"); controls.setTextColor(palette.ink);
        controls.setPadding(0, dp(16), 0, 0);
        controls.setChecked(LafzPrefs.get(this).getBoolean("keep_controls_visible", false));
        controls.setOnCheckedChangeListener((view, checked) -> {
            LafzPrefs.get(this).edit().putBoolean("keep_controls_visible", checked).apply();
            if (checked) { focusMode = false; focusAmount = 0; }
        });
        appearance.addView(controls);
        page.addView(appearance, margin(0, 0, 0, 12));

        LinearLayout widget = settingCard();
        widget.addView(label("Keep a word on your home screen", 16, palette.ink, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        widget.addView(label("Add the Lafz widget for a small daily reminder right where you start.", 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 5, 0, 13));
        widget.addView(button("Add Lafz widget", false, this::requestWidgetPin));
        page.addView(widget, margin(0, 0, 0, 12));

        LinearLayout collection = settingCard();
        collection.addView(label("Your collection", 20, palette.ink, Typeface.create("serif", Typeface.NORMAL)));
        collection.addView(button("Export saved words & history", false, () -> openBackupDocument(true)), margin(0, 10, 0, 0));
        collection.addView(button("Import backup", false, () -> openBackupDocument(false)), margin(0, 8, 0, 0));
        collection.addView(button("Restore hidden words", false, this::showHiddenWords), margin(0, 8, 0, 0));
        if (undoRemovedWord != null) collection.addView(button("Undo removal of " + undoRemovedWord.spelling, false, () -> {
            LafzPrefs.saveWord(this, undoRemovedWord, true); undoRemovedWord = null; render();
        }), margin(0, 8, 0, 0));
        page.addView(collection, margin(0, 12, 0, 12));
        page.addView(eyebrow("BEHIND THE WORDS"), margin(0, 16, 0, 12));
        LinearLayout about = settingCard();
        about.addView(label("Credits & licenses", 16, palette.ink, Typeface.create("serif", Typeface.NORMAL)));
        about.addView(label("Open dictionary sources, learner vocabulary, and pronunciation data with their licenses retained.", 13, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 6, 0, 11));
        about.addView(button("Full source & license", false, this::showSourceDetails));
        page.addView(about);
    }

    private View makeBottomNav() {
        FrameLayout container = new FrameLayout(this);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackground(shape(palette.ink, 18, palette.ink, 0));
        nav.setPadding(dp(12), dp(6), dp(12), dp(8));
        nav.addView(navChoice("explore", "Discover", Tab.FEED), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navChoice("library", "Saved", Tab.LIBRARY), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navChoice("review", "Practice", Tab.REVIEW), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navChoice("settings", "Settings", Tab.SETTINGS), new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-1, -1);
        placement.setMargins(dp(16), 0, dp(16), dp(8));
        container.addView(nav, placement);
        return container;
    }

    private View navChoice(String icon, String title, Tab tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        boolean selected = currentTab == tab;
        FrameLayout capsule = new FrameLayout(this);
        if (selected) capsule.setBackground(shape(palette.selected, 10, palette.selected, 0));
        IconView glyph = new IconView(this, icon, selected ? 0xFFFFFFFF : 0xFFB9B3A9);
        capsule.addView(glyph, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        item.addView(capsule, new LinearLayout.LayoutParams(dp(56), dp(32)));
        TextView name = label(title, 10, selected ? 0xFFFFFFFF : 0xFFB9B3A9, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        item.addView(name, wrapMargin(0, 5, 0, 0));
        item.setSelected(selected);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.setContentDescription(title);
        item.setOnClickListener(v -> {
            captureQuizDraft();
            currentTab = tab; render();
        });
        return item;
    }

    private void setDailyEnabled(boolean enabled) {
        if (!enabled) {
            LafzPrefs.get(this).edit().putBoolean("daily_enabled", false).apply();
            DailyWordReceiver.schedule(this);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        LafzPrefs.get(this).edit().putBoolean("daily_enabled", true).apply();
        DailyWordReceiver.schedule(this);
    }

    private void toggleSaved(Word word, TextView button) {
        boolean keep = !LafzPrefs.saved(this).contains(word.id);
        saveCollectionWord(word, keep);
        button.setText(keep ? "✓  Saved" : "☆  Save");
        button.setTextColor(keep ? palette.gold : palette.ink);
        Toast.makeText(this, keep ? "Saved to your library." : "Removed from your library.", Toast.LENGTH_SHORT).show();
    }

    private void boostCategory(String category) {
        SharedPreferences prefs = LafzPrefs.get(this);
        prefs.edit().putInt("boost_" + category, prefs.getInt("boost_" + category, 0) + 1).apply();
        Toast.makeText(this, "More " + category.toLowerCase(Locale.getDefault()) + " words in your blend.", Toast.LENGTH_SHORT).show();
        render();
    }

    private void hideWord(Word word) {
        LafzRepository.get(this).hide(word.id);
        Toast.makeText(this, "We'll leave this word out of your feed.", Toast.LENGTH_SHORT).show();
        render();
    }

    private void speak(Word word) {
        if (textToSpeech == null || !ttsReady) {
            Toast.makeText(this, "Voice is still getting ready. Try again in a moment.", Toast.LENGTH_SHORT).show();
            return;
        }
        textToSpeech.speak(word.spelling, TextToSpeech.QUEUE_FLUSH, null, "lafz-" + word.id);
    }

    private void showWordDetails(Word word) {
        String subtitle = word.partOfSpeech;
        if (!"Unclassified".equals(word.category)) subtitle += " · " + word.category;
        String pronunciation = word.pronunciationDisplay();
        if (!pronunciation.isEmpty()) {
            if ("IPA".equalsIgnoreCase(word.pronunciationAlphabet))
                pronunciation = "/" + pronunciation.replaceAll("^/+|/+$", "") + "/";
            subtitle += "  ·  " + pronunciation;
        }
        StyledSheet sheet = new StyledSheet(word.spelling, subtitle);
        List<LexiconSense> senses = LafzRepository.get(this).senses(word.id);
        LexiconSense primary = null;
        List<LexiconSense> otherSenses = new ArrayList<>();
        for (LexiconSense sense : senses) {
            boolean matches = false;
            for (LexiconDefinition definition : sense.definitions)
                if (definition.text.equals(word.meaning) && sense.partOfSpeech.equals(word.partOfSpeech)) matches = true;
            if (primary == null && matches) primary = sense;
            else otherSenses.add(sense);
        }
        addReadingText(sheet.body, word.meaning, 18);
        if (primary != null && !primary.labels.isEmpty())
            addReadingText(sheet.body, String.join(" · ", primary.labels), 12);
        if (!word.example.isEmpty()) addExampleQuote(sheet.body, word.example);
        if (primary != null) {
            LinearLayout alternatives = null;
            Set<String> displayedDefinitions = new HashSet<>();
            displayedDefinitions.add(word.meaning);
            for (LexiconDefinition definition : primary.definitions) {
                if (!displayedDefinitions.add(definition.text)) continue;
                if (alternatives == null) alternatives = detailDisclosure(sheet.body, "Other source definitions");
                addReadingText(alternatives, definition.text, 15);
            }
        }

        List<LexiconExample> extraExamples = new ArrayList<>();
        if (primary != null) for (LexiconExample example : primary.examples)
            if (example.source.equals(word.source) && !example.text.equals(word.example)) extraExamples.add(example);
        List<LexiconExample> wordExamples = LafzRepository.get(this).wordExamples(word.id, 3);
        if (!extraExamples.isEmpty() || !wordExamples.isEmpty()) {
            addDetailLink(sheet.body, "More examples", () -> {
                StyledSheet examples = new StyledSheet(word.spelling, "Examples");
                for (LexiconExample example : extraExamples) addExampleQuote(examples.body, example.text);
                if (!wordExamples.isEmpty()) {
                    addReadingText(examples.body, "Other uses · meanings may differ", 12);
                    for (LexiconExample example : wordExamples) addExampleQuote(examples.body, example.text);
                }
                addSourceDisclosure(examples.body, word, senses, wordExamples);
                examples.show();
            });
        }
        if (!otherSenses.isEmpty()) addDetailLink(sheet.body, "More meanings · " + otherSenses.size(),
                () -> showOtherMeanings(word, otherSenses));
        List<Word> related = LafzRepository.get(this).relatedWords(word.id, 8);
        if (!related.isEmpty()) addDetailLink(sheet.body, "Related words · " + related.size(), () -> {
            StyledSheet relatedSheet = new StyledSheet("Related words", word.spelling);
            for (Word entry : related) addDetailLink(relatedSheet.body, entry.spelling + " · " + entry.partOfSpeech, () -> {
                relatedSheet.dismiss(); sheet.dismiss();
                mainHandler.postDelayed(() -> showWordDetails(entry), motionEnabled() ? 160 : 0);
            });
            relatedSheet.show();
        });
        addSourceDisclosure(sheet.body, word, senses, wordExamples);

        boolean initiallySaved = LafzPrefs.saved(this).contains(word.id);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView save = button(initiallySaved ? "Saved" : "Save word", true, () -> { });
        save.setSelected(initiallySaved);
        save.setContentDescription(initiallySaved ? "Saved, remove from library" : "Save word to library");
        save.setOnClickListener(v -> {
            boolean keep = !LafzPrefs.saved(this).contains(word.id);
            saveCollectionWord(word, keep);
            save.setText(keep ? "Saved" : "Save word");
            save.setSelected(keep);
            save.setContentDescription(keep ? "Saved, remove from library" : "Save word to library");
            Toast.makeText(this, keep ? "Saved to your library." : "Removed from your library.", Toast.LENGTH_SHORT).show();
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        TextView listen = button("Listen", false, () -> speak(word));
        LinearLayout.LayoutParams listenParams = new LinearLayout.LayoutParams(0, -2, 1);
        listenParams.leftMargin = dp(10); actions.addView(listen, listenParams);
        sheet.footer.addView(actions);
        sheet.setOnDismissListener(dialog -> {
            if (initiallySaved != LafzPrefs.saved(this).contains(word.id) &&
                    (currentTab == Tab.LIBRARY || currentTab == Tab.REVIEW)) render();
        });
        sheet.show();
    }

    /** One meaning at a time keeps large dictionary entries readable without an endless scroll. */
    private void showOtherMeanings(Word word, List<LexiconSense> senses) {
        StyledSheet sheet = new StyledSheet(word.spelling, "More meanings");
        int[] index = {0};
        Runnable[] update = new Runnable[1];
        update[0] = () -> {
            sheet.body.removeAllViews(); sheet.footer.removeAllViews();
            LexiconSense sense = senses.get(index[0]);
            addReadingText(sheet.body, sense.partOfSpeech + " · " + (index[0] + 1) + " of " + senses.size(), 12);
            Set<String> definitions = new HashSet<>();
            LinearLayout alternatives = null;
            for (LexiconDefinition definition : sense.definitions) {
                if (!definitions.add(definition.text)) continue;
                if (definitions.size() == 1) addReadingText(sheet.body, definition.text, 17);
                else {
                    if (alternatives == null) alternatives = detailDisclosure(sheet.body, "Alternative definitions");
                    addReadingText(alternatives, definition.text, 15);
                }
            }
            if (!sense.labels.isEmpty()) addReadingText(sheet.body, String.join(" · ", sense.labels), 12);
            List<LexiconExample> matchingExamples = new ArrayList<>();
            if (!sense.definitions.isEmpty()) for (LexiconExample example : sense.examples)
                if (example.source.equals(sense.definitions.get(0).source)) matchingExamples.add(example);
            if (!matchingExamples.isEmpty()) addExampleQuote(sheet.body, matchingExamples.get(0).text);
            if (matchingExamples.size() > 1) {
                LinearLayout examples = detailDisclosure(sheet.body, "More examples");
                for (int i = 1; i < matchingExamples.size(); i++) addExampleQuote(examples, matchingExamples.get(i).text);
            }
            addSourceDisclosure(sheet.body, word, Collections.singletonList(sense), Collections.emptyList());
            LinearLayout navigation = new LinearLayout(this);
            TextView previous = button("Previous", false, () -> { index[0]--; update[0].run(); });
            previous.setEnabled(index[0] > 0); previous.setAlpha(index[0] > 0 ? 1f : .4f);
            navigation.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
            TextView next = button("Next meaning", true, () -> { index[0]++; update[0].run(); });
            next.setEnabled(index[0] < senses.size() - 1); next.setAlpha(index[0] < senses.size() - 1 ? 1f : .4f);
            LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1);
            nextParams.leftMargin = dp(10); navigation.addView(next, nextParams);
            sheet.footer.addView(navigation);
            sheet.scroll.post(() -> sheet.scroll.scrollTo(0, 0));
        };
        update[0].run(); sheet.show();
    }

    private void addReadingText(LinearLayout host, String value, int size) {
        TextView text = label(value, size, size <= 12 ? palette.muted : palette.ink,
                Typeface.create("sans-serif", Typeface.NORMAL));
        text.setLineSpacing(dp(4), 1f); text.setTextIsSelectable(true);
        host.addView(text, margin(0, 0, 0, 14));
    }

    private void addExampleQuote(LinearLayout host, String value) {
        TextView quote = label("“" + value + "”", 16, palette.example, Typeface.create("serif", Typeface.ITALIC));
        quote.setLineSpacing(dp(3), 1f); quote.setTextIsSelectable(true);
        quote.setPadding(dp(14), dp(12), dp(14), dp(12));
        quote.setBackground(shape(palette.soft, 16, palette.line, 0));
        host.addView(quote, margin(0, 0, 0, 16));
    }

    private TextView addDetailLink(LinearLayout host, String title, Runnable action) {
        TextView row = label(title + "  ›", 14, palette.selected, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.setGravity(Gravity.CENTER_VERTICAL); row.setMinHeight(dp(48));
        row.setPadding(0, dp(10), 0, dp(10)); row.setOnClickListener(v -> action.run());
        host.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private LinearLayout detailDisclosure(LinearLayout host, String title) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        TextView row = addDetailLink(host, title, () -> { });
        row.setContentDescription(title + ", collapsed");
        row.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            row.setText(title + (expanded ? "  ⌃" : "  ›")); row.setSelected(expanded);
            row.setContentDescription(title + (expanded ? ", expanded" : ", collapsed"));
        });
        host.addView(content, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    private void addSourceDisclosure(LinearLayout host, Word word, List<LexiconSense> senses,
                                     List<LexiconExample> wordExamples) {
        LinearLayout info = detailDisclosure(host, "Word info & sources");
        addReadingText(info, LearningContent.displayDifficulty(word) + " · estimated difficulty", 13);
        addReadingText(info, "Estimate, not a CEFR level · " + Math.round(word.difficultyConfidence * 100) +
                "% confidence\n" + word.difficultyEvidence, 12);
        Set<String> credits = new HashSet<>();
        for (LexiconSense sense : senses) {
            for (LexiconDefinition definition : sense.definitions)
                addSourceCredit(info, credits, definition.source, definition.license, definition.attribution, definition.licenseUrl);
            for (LexiconExample example : sense.examples)
                addSourceCredit(info, credits, example.source, example.license, "", example.licenseUrl);
        }
        for (LexiconExample example : wordExamples)
            addSourceCredit(info, credits, example.source, example.license, "", example.licenseUrl);
        if (senses.isEmpty()) addSourceCredit(info, credits, word.source, "", "", word.licenseUrl);
    }

    private void addSourceCredit(LinearLayout host, Set<String> credits, String source, String license,
                                 String attribution, String url) {
        // One credit per source/license/attribution; no repeated legal paragraphs between meanings.
        String value = source + (license.isEmpty() ? "" : " · " + license);
        String key = value + "\n" + url;
        if (!credits.add(key)) return;
        addReadingText(host, value, 12);
        if (!attribution.isEmpty() && !attribution.equals(source)) addReadingText(host, attribution, 12);
        if (!url.isEmpty()) addDetailLink(host, "View license", () -> openWeb(url));
    }

    private void reviewAgain(Word word) { finishReviewWord(word, false); }

    private void reviewGotIt(Word word) { finishReviewWord(word, true); }

    private void finishReviewWord(Word word, boolean remembered) {
        if (reviewSession == null || reviewSession.complete()) return;
        ReviewScheduler.answer(this, word, remembered);
        reviewSession.advance(remembered); reviewRevealed = false; render();
    }

    private void requestWidgetPin() {
        if (Build.VERSION.SDK_INT >= 26) {
            AppWidgetManager manager = getSystemService(AppWidgetManager.class);
            ComponentName provider = new ComponentName(this, WordWidgetProvider.class);
            if (manager != null && manager.isRequestPinAppWidgetSupported()) {
                manager.requestPinAppWidget(provider, null, null);
            } else {
                Toast.makeText(this, "Open your home screen's widget picker and choose Lafz.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void shareWordCard(Word word) {
        final int categoryTint = categoryColor(word.category);
        final File cacheDir = getCacheDir();
        final String packageName = getPackageName();
        LafzRepository.executeBackground(() -> {
            File file;
            try {
                file = createShareCardFile(word, cacheDir, categoryTint);
            } catch (Exception | OutOfMemoryError exception) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(MainActivity.this, "The word card could not be prepared.", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            Uri uri = Uri.parse("content://" + packageName + ".share/card/" + word.id);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(ClipData.newUri(getContentResolver(), "Lafz word card", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share this word"));
                } catch (Exception exception) {
                    Toast.makeText(MainActivity.this, "The word card could not be shared.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private File createShareCardFile(Word word, File cacheDir, int categoryTint) throws Exception {
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            canvas.drawColor(0xFFF0EBF8);
            paint.setColor(0xFF302340);
            canvas.drawRoundRect(new RectF(54, 54, 1026, 1296), 44, 44, paint);
            paint.setColor(categoryTint);
            canvas.drawRoundRect(new RectF(96, 116, 104, 1234), 4, 4, paint);
            drawShareText(canvas, "LAFZ   /   A WORD TO KEEP", 16, 148, 122, 25, 0xFFC9B4EF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            drawShareText(canvas, word.category.toUpperCase(Locale.getDefault()) + "  ·  " + word.partOfSpeech.toUpperCase(Locale.getDefault()), 19, 148, 210, 24, 0xFFB9ACC9, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            drawShareText(canvas, word.spelling, 76, 142, 390, 790, 0xFFFCFAFF, Typeface.create("serif", Typeface.NORMAL));
            drawShareText(canvas, word.pronunciationDisplay(), 24, 148, 448, 35, 0xFFC9B4EF, Typeface.create("serif", Typeface.NORMAL));
            paint.setColor(0xFF695675);
            canvas.drawRect(148, 520, 932, 523, paint);
            drawWrappedShareText(canvas, word.meaning, 31, 148, 584, 780, 220, 0xFFFCFAFF, Typeface.create("sans-serif", Typeface.NORMAL));
            drawWrappedShareText(canvas, "“" + word.example + "”", 24, 148, 850, 780, 190, 0xFFD1C3E2, Typeface.create("serif", Typeface.ITALIC));
            drawShareText(canvas, "SOURCE: " + word.source.toUpperCase(Locale.ROOT), 14, 148, 1156, 780, 0xFFC9B4EF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            drawWrappedShareText(canvas, "License: " + word.licenseUrl, 13, 148, 1172, 780, 44, 0xFFD1C3E2, Typeface.create("sans-serif", Typeface.NORMAL));
            drawShareText(canvas, "ONE WORD, A LITTLE MORE ROOM", 14, 148, 1256, 780, 0xFFC9B4EF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            File dir = new File(cacheDir, "shared-words");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not prepare the share card.");
            File file = new File(dir, word.id + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Could not encode the word card.");
            }
            return file;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static void drawShareText(Canvas canvas, String text, float size, float x, float baseline,
                               float maxWidth, int color, Typeface typeface) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color); paint.setTextSize(size); paint.setTypeface(typeface);
        String value = text;
        while (paint.measureText(value) > maxWidth && paint.getTextSize() > 12) paint.setTextSize(paint.getTextSize() - 1);
        canvas.drawText(value, x, baseline, paint);
    }

    private static void drawWrappedShareText(Canvas canvas, String text, float size, float x, float top,
                                      int width, int maxHeight, int color, Typeface typeface) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color); paint.setTextSize(size); paint.setTypeface(typeface);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(8, 1.08f).setIncludePad(false).build();
        canvas.save(); canvas.translate(x, top); canvas.clipRect(0, 0, width, maxHeight); layout.draw(canvas); canvas.restore();
    }

    private List<Word> wordsFor(Set<String> ids) {
        return LafzRepository.get(this).byIds(ids);
    }

    private LinearLayout emptyCard(String title, String description, String action, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(21), dp(20), dp(20));
        card.setBackground(shape(palette.card, 12, palette.line, 1));
        card.addView(label(title, 21, palette.ink, Typeface.create("serif", Typeface.NORMAL)));
        card.addView(label(description, 14, palette.muted, Typeface.create("sans-serif", Typeface.NORMAL)), margin(0, 7, 0, 16));
        card.addView(button(action, true, onClick));
        return card;
    }

    private LinearLayout settingCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(shape(palette.card, 14, palette.line, 1));
        return card;
    }

    private TextView button(String title, boolean primary, Runnable action) {
        TextView button = label(title, 13, primary ? palette.selectedText : palette.ink,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        button.setMinHeight(dp(48));
        button.setBackground(shape(primary ? palette.selected : palette.soft, 12,
                primary ? palette.selected : palette.soft, 0));
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        return button;
    }

    private TextView actionText(String title, int color) {
        TextView text = label(title, 12, color, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private TextView eyebrow(String title) {
        TextView text = label(title, 10, palette.muted, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setLetterSpacing(0.10f);
        return text;
    }

    private TextView label(String value, float size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(typeface);
        text.setIncludeFontPadding(false);
        return text;
    }

    private GradientDrawable shape(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams wrapMargin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams marginWeight(int left, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), weight);
        params.leftMargin = dp(left);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatTime(int hour, int minute) {
        return String.format(Locale.getDefault(), "%d:%02d %s", (hour % 12 == 0 ? 12 : hour % 12), minute, hour < 12 ? "AM" : "PM");
    }

    private int categoryColor(String category) {
        switch (category) {
            case "Nature": return palette.green;
            case "Science": return palette.blue;
            case "Food": return palette.copper;
            case "Emotions": return palette.pink;
            case "Unclassified": return palette.muted;
            default: return palette.gold;
        }
    }

    private Palette palette() {
        boolean dark = LafzPrefs.get(this).getBoolean("dark_theme", false);
        return dark ? new Palette(true,
                0xFF171B19, 0xFF252B27, 0xFF333B35, 0xFF404941, 0xFFF5F1E7,
                0xFFB3BAAF, 0xFFF0A86B, 0xFFD4D2C4, 0xFFFF754D, 0xFFFFFFFF,
                0xFFFF754D, 0xFFFFFFFF, 0xFF303A33, 0xFFF0A86B, 0xFF8BC8A6,
                0xFF91B8DC, 0xFFF0A86B, 0xFFDF9FAD)
                : new Palette(false,
                0xFFF4F1E9, 0xFFFFFDF7, 0xFFEAE5D9, 0xFFE0DCD1, 0xFF1B2924,
                0xFF6E756D, 0xFFAF6841, 0xFF66766B, 0xFFF06442, 0xFFFFFFFF,
                0xFFF06442, 0xFFFFFFFF, 0xFFEDE8DE, 0xFFF06442, 0xFF5C916D,
                0xFF4E7895, 0xFFB77F43, 0xFFAF6674);
    }

    private boolean motionEnabled() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    private final class AtmosphereView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int tint;
        AtmosphereView(Context context, int tint) { super(context); this.tint = tint; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() * .88f, y = getHeight() * .23f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(tint); paint.setAlpha(palette.dark ? 12 : 9);
            canvas.drawCircle(x, y, getWidth() * .68f, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setAlpha(palette.dark ? 22 : 16);
            canvas.drawCircle(x, y, getWidth() * .48f, paint);
            canvas.drawCircle(x, y, getWidth() * .57f, paint);
        }
    }

    private final class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String icon;
        IconView(Context context, String icon, int color) {
            super(context); this.icon = icon; paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.scale(getWidth() / 24f, getHeight() / 24f);
            if (icon.equals("explore")) {
                canvas.drawCircle(12, 12, 9, paint);
                path.reset(); path.moveTo(16, 8); path.lineTo(14, 14); path.lineTo(8, 16); path.lineTo(10, 10); path.close(); canvas.drawPath(path, paint);
            } else if (icon.equals("library")) {
                canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint);
                canvas.drawLine(9, 3, 9, 21, paint); canvas.drawLine(12, 8, 16, 8, paint); canvas.drawLine(12, 12, 16, 12, paint);
            } else if (icon.equals("review")) {
                canvas.drawArc(4, 4, 20, 20, -60, 300, false, paint);
                canvas.drawLine(16, 3, 17, 8, paint); canvas.drawLine(17, 8, 12, 8, paint);
                canvas.drawLine(12, 9, 12, 13, paint); canvas.drawLine(12, 13, 15, 15, paint);
            } else {
                canvas.drawCircle(12, 12, 4, paint);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i * 45, 12, 12); canvas.drawLine(12, 2, 12, 5, paint); canvas.restore();
                }
            }
            canvas.restore();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(DailyWordReceiver.CHANNEL_ID,
                    "Daily word", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("A word from Lafz, once a day.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private interface SwipeListener { void onSwipe(int direction); }

    /** Finger-tracked, one-word paging. Only the current and adjacent pages are drawn. */
    private final class VerticalSnapPager extends FrameLayout {
        private final boolean animateMotion = motionEnabled();
        private final int touchSlop;
        private final int minFling;
        private float downX, downY, offset;
        private int displayed;
        private boolean dragging;
        private boolean ignoreGesture;
        private VelocityTracker velocity;
        private ValueAnimator settle;
        private SwipeListener swipeListener;
        private View actionDock;

        VerticalSnapPager(Context context) {
            super(context);
            ViewConfiguration configuration = ViewConfiguration.get(context);
            touchSlop = configuration.getScaledTouchSlop();
            minFling = configuration.getScaledMinimumFlingVelocity();
            setClipChildren(true);
            setFocusable(true);
            setContentDescription("Word discovery feed. Swipe up for the next word, down for the previous word.");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        void setOnSwipe(SwipeListener listener) { swipeListener = listener; }
        int getDisplayedChild() { return displayed; }
        int getPageCount() { return getChildCount() - (actionDock == null ? 0 : 1); }
        View getPageAt(int index) { return index >= 0 && index < getPageCount() ? getChildAt(index) : null; }
        void setActionDock(View dock) {
            if (actionDock != null) removeView(actionDock);
            actionDock = dock;
            if (dock != null) addView(dock, new FrameLayout.LayoutParams(-1, -1));
            positionPages();
            bindExploreActionsToCurrentPage();
        }
        void addPage(View page) {
            if (actionDock == null) addView(page, new FrameLayout.LayoutParams(-1, -1));
            else addView(page, getPageCount(), new FrameLayout.LayoutParams(-1, -1));
            if (getPageCount() == 1 && displayed == 0) bindExploreActionsToCurrentPage();
        }
        void removePageAt(int index) {
            if (index < 0 || index >= getPageCount()) return;
            removeViewAt(index);
            if (displayed >= getPageCount()) displayed = Math.max(0, getPageCount() - 1);
            positionPages();
            bindExploreActionsToCurrentPage();
        }
        void clearPages() {
            while (getPageCount() > 0) removeViewAt(0);
            displayed = 0;
            exploreCursor = 0;
            offset = 0;
            positionPages();
            bindExploreActionsToCurrentPage();
        }
        void setDisplayedChild(int index) {
            displayed = Math.max(0, Math.min(index, getPageCount() - 1));
            if (getPageCount() == 0) displayed = 0;
            exploreCursor = displayed; offset = 0; positionPages(); bindExploreActionsToCurrentPage();
        }
        @Override public void onViewAdded(View child) { super.onViewAdded(child); positionPages(); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (settle != null) settle.cancel(); offset = 0; positionPages();
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                ignoreGesture = settle != null && settle.isRunning();
                if (ignoreGesture) return true;
                downX = event.getX(); downY = event.getY(); dragging = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
            }
            if (ignoreGesture) return true;
            if (velocity != null) velocity.addMovement(event);
            boolean handled = super.dispatchTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (velocity != null) { velocity.recycle(); velocity = null; }
            }
            return handled;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (settle != null && settle.isRunning()) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
                if (dy > touchSlop && dy > dx * 1.15f) {
                    setFocusMode(true);
                    dragging = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
                }
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (settle != null && settle.isRunning()) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                float delta = event.getY() - downY;
                if (Math.abs(delta) > touchSlop && Math.abs(delta) > Math.abs(event.getX() - downX) * 1.15f) { dragging = true; setFocusMode(true); }
                if (!dragging) return true;
                offset = Math.max(-getHeight(), Math.min(getHeight(), delta));
                if ((displayed == 0 && offset > 0) || (displayed == getPageCount() - 1 && offset < 0)) offset *= .22f;
                positionPages(); return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!dragging) { if (action == MotionEvent.ACTION_UP) performClick(); return true; }
                float speed = 0;
                if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getYVelocity(); }
                boolean fling = Math.abs(speed) > Math.max(minFling, dp(420)) && Math.abs(offset) > touchSlop * 2;
                if (action != MotionEvent.ACTION_CANCEL && (Math.abs(offset) > getHeight() * .18f || fling) && swipeListener != null) {
                    swipeListener.onSwipe(offset < 0 ? 1 : -1);
                } else animateTo(displayed);
                dragging = false; return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        void showNextPage() { animateTo(Math.min(getPageCount() - 1, displayed + 1)); }
        void showPreviousPage() { animateTo(Math.max(0, displayed - 1)); }
        private void animateTo(int target) {
            if (target < 0 || (settle != null && settle.isRunning())) return;
            final float destination = -(target - displayed) * getHeight();
            if (!animateMotion) { displayed = target; exploreCursor = displayed; offset = 0; positionPages(); bindExploreActionsToCurrentPage(); pruneExplorePages(this); return; }
            settle = ValueAnimator.ofFloat(offset, destination);
            settle.setDuration(target == displayed ? 230 : 360);
            settle.setInterpolator(new DecelerateInterpolator(2f));
            settle.addUpdateListener(animation -> { offset = (float) animation.getAnimatedValue(); positionPages(); });
            settle.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (cancelled) return;
                    displayed = target; exploreCursor = displayed; offset = 0; positionPages(); bindExploreActionsToCurrentPage();
                    if (displayed >= getPageCount() - 3) loadMoreExploreWords();
                    pruneExplorePages(VerticalSnapPager.this);
                    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
                }
            });
            settle.start();
        }
        private void positionPages() {
            float height = Math.max(1, getHeight());
            for (int i = 0; i < getPageCount(); i++) {
                View child = getChildAt(i);
                float position = (i - displayed) * height + offset;
                boolean visible = Math.abs(position) < height + 1;
                child.setVisibility(visible ? VISIBLE : INVISIBLE);
                child.setTranslationY(position);
                float progress = Math.min(1, Math.abs(position) / height);
                child.setAlpha(animateMotion ? 1f - .38f * progress : 1f);
                child.setScaleX(1f);
                child.setScaleY(1f);
                child.setImportantForAccessibility(i == displayed ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                View copy = child.findViewWithTag("word-copy");
                if (copy != null) copy.setTranslationY(animateMotion ? -position * .045f : 0);
                View title = child.findViewWithTag("word-title");
                View meaning = child.findViewWithTag("word-meaning");
                View example = child.findViewWithTag("word-example");
                if (title != null) {
                    title.setTranslationY(animateMotion ? -position * .075f : 0);
                    title.setAlpha(animateMotion ? 1f - .25f * progress : 1f);
                }
                if (meaning != null) meaning.setTranslationY(animateMotion ? -position * .025f : 0);
                if (example != null) example.setTranslationY(animateMotion ? position * .015f : 0);
            }
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(true);
            if (displayed < getPageCount() - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (displayed > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && swipeListener != null) { swipeListener.onSwipe(1); return true; }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && swipeListener != null) { swipeListener.onSwipe(-1); return true; }
            return super.performAccessibilityAction(action, arguments);
        }
        @Override protected void onDetachedFromWindow() {
            if (settle != null) settle.cancel();
            if (velocity != null) { velocity.recycle(); velocity = null; }
            super.onDetachedFromWindow();
        }
    }

    private static final class Palette {
        final boolean dark;
        final int background, card, rail, line, ink, muted, gold, example, selected, selectedText;
        final int chip, chipInk, soft, lilac, green, blue, copper, pink;

        Palette(boolean dark, int background, int card, int rail, int line, int ink, int muted,
                int gold, int example, int selected, int selectedText, int chip, int chipInk,
                int soft, int lilac, int green, int blue, int copper, int pink) {
            this.dark = dark; this.background = background; this.card = card; this.rail = rail;
            this.line = line; this.ink = ink; this.muted = muted; this.gold = gold;
            this.example = example; this.selected = selected; this.selectedText = selectedText;
            this.chip = chip; this.chipInk = chipInk; this.soft = soft; this.lilac = lilac;
            this.green = green; this.blue = blue; this.copper = copper; this.pink = pink;
        }
    }
}
