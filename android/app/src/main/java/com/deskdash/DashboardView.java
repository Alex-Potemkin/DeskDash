package com.deskdash;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The whole dashboard: clock + music + PC macros on the left, calendar (day or month) on the right.
 * Vertical swipes along the right edge change volume, along the left edge brightness.
 * Hosted by MainActivity and by the DashDream screensaver.
 */
public class DashboardView extends FrameLayout {
    public interface Host {
        void openSettings();
    }

    private static final String SPOTIFY = "com.spotify.music";
    private static final long CAL_IDLE_MS = 3 * 60_000L;

    private final Context ctx;
    private final Prefs prefs;
    private final Window window;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Random rnd = new Random();
    private final float dp;
    private final MusicWatcher music;
    private final AudioManager audio;

    private DashTheme th;
    private boolean nightActive, running;
    private int cutL, cutT, cutR, cutB;
    private int lastMinute = -1;
    private long lastCalTouch;

    // views (recreated by build())
    private LinearLayout content, musicRow, macroRow, allDayRow;
    private FrameLayout musicCard;
    private TextView clock, dateText, hint, calTitle, calCount, calMode, emptyText, detail, mTitle, mArtist, mTime, pcStatus;
    private FancyClock fancy;
    private ImageView art, artBg, playBtn, deviceBtn;
    private Bar mBar;
    private HorizontalScrollView allDayScroll;
    private ScrollView calScroll;
    private TimelineView timeline;
    private MonthView monthView;
    private LevelView level;
    private FrameLayout picker;
    private LinearLayout pickerList;
    private TextView pickerStatus;

    // calendar state (survives rebuilds)
    private LocalDate todayRef = LocalDate.now();
    private LocalDate shownDay = LocalDate.now();
    private YearMonth shownMonth = YearMonth.now();
    private boolean monthMode;
    private LocalDate renderedDay;
    private List<CalEvent> todayEvents = new ArrayList<>();
    private List<CalEvent> viewEvents = new ArrayList<>();

    // music state
    private MediaController media;
    private boolean musicNoAccess;
    private Bitmap lastArt;
    private Integer artColor;
    private final Map<String, Bitmap> artCache = new HashMap<>();
    private final Set<String> artLoading = new HashSet<>();
    private volatile PcClient.SpState spState;
    private volatile boolean pcSpotify;
    private int pollCount;

    // PC state
    private volatile PcClient pc;
    private List<PcClient.Macro> macros = new ArrayList<>();
    private String macrosJson = "";
    private JSONObject pcStats;
    private volatile boolean pcOnline;
    private int pcFails;
    private volatile long pcInfoAt;
    private String pcName = "";

    // edge swipe
    private int edge; // 0 none, 1 volume (right edge), 2 brightness (left edge)
    private boolean edgeActive;
    private float edgeY0, edgeV0, edgeValue;
    private int lastVolStep = -1;

    private final ContentObserver calObserver = new ContentObserver(ui) {
        @Override
        public void onChange(boolean selfChange) {
            ui.removeCallbacks(reloadCal);
            ui.postDelayed(reloadCal, 1500);
        }
    };
    private final Runnable reloadCal = this::reloadCalendar;

    public DashboardView(Context c, Window window, Host host) {
        super(c);
        this.ctx = c;
        this.window = window;
        this.host = host;
        prefs = new Prefs(c);
        dp = getResources().getDisplayMetrics().density;
        audio = c.getSystemService(AudioManager.class);
        try {
            JSONArray cached = new JSONArray(prefs.pcMacros());
            macros = PcClient.parseMacros(cached);
            macrosJson = cached.toString();
        } catch (Exception ignored) {
            // no cache yet
        }
        music = new MusicWatcher(c, new MusicWatcher.Listener() {
            @Override
            public void onController(MediaController m) {
                musicNoAccess = false;
                media = m;
                renderMusic();
            }

            @Override
            public void onNoAccess() {
                musicNoAccess = true;
                media = null;
                renderMusic();
            }
        });
        setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = insets.getDisplayCutout();
                if (dc != null) {
                    cutL = dc.getSafeInsetLeft();
                    cutT = dc.getSafeInsetTop();
                    cutR = dc.getSafeInsetRight();
                    cutB = dc.getSafeInsetBottom();
                    applyPadding();
                }
            }
            return insets;
        });
        build();
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        if (running) return;
        running = true;
        pc = prefs.pcHost().isEmpty() ? null : new PcClient(prefs.pcHost(), prefs.pcPort(), prefs.pcToken());
        pcOnline = false;
        music.start();
        lastMinute = -1;
        ui.post(secondTick);
        ui.post(pcPoll);
        reloadCalendar();
        if (CalendarRepo.hasPermission(ctx)) {
            try {
                ctx.getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, calObserver);
            } catch (RuntimeException ignored) {
                // provider unavailable
            }
        }
        renderPc();
    }

    public void stop() {
        if (!running) return;
        running = false;
        ui.removeCallbacks(secondTick);
        ui.removeCallbacks(pcPoll);
        ui.removeCallbacks(reloadCal);
        music.stop();
        try {
            ctx.getContentResolver().unregisterContentObserver(calObserver);
        } catch (RuntimeException ignored) {
            // not registered
        }
    }

    public void shutdown() {
        stop();
        io.shutdownNow();
    }

    /** Re-reads settings and rebuilds the UI (theme, fonts, layout). */
    public void refresh() {
        build();
    }

    // ---------------------------------------------------------------- build

    private void build() {
        removeAllViews();
        picker = null;
        nightActive = isNight();
        th = nightActive ? DashTheme.byId("night") : DashTheme.byId(prefs.theme());
        setBackgroundColor(th.bg);
        renderedDay = null;

        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        content = new LinearLayout(ctx);
        content.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        View left = buildLeft();
        View right = buildRight();
        View div = new View(ctx);
        div.setBackgroundColor(th.line);
        if (portrait) {
            content.addView(left, new LinearLayout.LayoutParams(-1, 0, 45));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, (int) dp));
            dl.setMargins(0, px(14), 0, px(14));
            content.addView(div, dl);
            content.addView(right, new LinearLayout.LayoutParams(-1, 0, 55));
        } else {
            content.addView(left, new LinearLayout.LayoutParams(0, -1, 53));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.max(1, (int) dp), -1);
            dl.setMargins(px(22), px(8), px(22), px(8));
            content.addView(div, dl);
            content.addView(right, new LinearLayout.LayoutParams(0, -1, 47));
        }
        level = new LevelView(ctx);
        level.setAlpha(0f);
        addView(level, new LayoutParams(px(52), px(200), Gravity.CENTER_VERTICAL | Gravity.END));

        applyPadding();
        applyNightWindow();
        sizeClock();
        tickClock();
        renderCalendar();
        renderMusic();
        renderMacros();
        renderPc();
    }

    private View buildLeft() {
        LinearLayout col = vertical();

        LinearLayout clockBox = vertical();
        clockBox.setGravity(Gravity.CENTER_VERTICAL);
        String style = prefs.clockStyle();
        if ("digital".equals(style)) {
            fancy = null;
            clock = text(th.text, 96);
            clock.setTypeface(clockFace());
            clock.setIncludeFontPadding(false);
            clock.setSingleLine();
            clock.setLetterSpacing(prefs.clockFont().equals("mono") ? 0f : -0.02f);
            clockBox.addView(clock);
        } else {
            clock = null;
            fancy = new FancyClock(ctx);
            fancy.setup(style, th, clockFace(), prefs.h24(), prefs.seconds());
            clockBox.addView(fancy, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        dateText = text(th.text, 20);
        dateText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockBox.addView(dateText, margins(-1, -2, 0, 4, 0, 0));
        hint = text(th.dim, 14);
        hint.setMaxLines(2);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setLineSpacing(0, 1.15f);
        clockBox.addView(hint, margins(-1, -2, 0, 8, 0, 0));

        GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                DashTheme n = DashTheme.next(prefs.theme());
                prefs.setTheme(n.id);
                build();
                Toast.makeText(ctx, "Тема: " + n.name, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                float dx = b.getX() - a.getX();
                if (Math.abs(dx) < 50 * dp || Math.abs(dx) < Math.abs(b.getY() - a.getY())) return false;
                String n = dx < 0 ? FancyClock.next(prefs.clockStyle()) : prevStyle(prefs.clockStyle());
                prefs.setClockStyle(n);
                build();
                Toast.makeText(ctx, "Часы: " + FancyClock.name(n), Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                host.openSettings();
            }
        });
        clockBox.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        col.addView(clockBox, new LinearLayout.LayoutParams(-1, 0, 1));

        col.addView(buildMusic(), new LinearLayout.LayoutParams(-1, -2));
        col.addView(buildPc(), margins(-1, -2, 0, 12, 0, 0));
        return col;
    }

    private static String prevStyle(String id) {
        String[][] s = FancyClock.STYLES;
        for (int i = 0; i < s.length; i++) if (s[i][0].equals(id)) return s[(i - 1 + s.length) % s.length][0];
        return s[0][0];
    }

    private View buildMusic() {
        musicCard = new FrameLayout(ctx);
        musicCard.setBackground(rounded(th.surface, 18));
        musicCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 18 * dp);
            }
        });
        musicCard.setClipToOutline(true);
        musicCard.setOnClickListener(v -> openMusicApp());

        // blurred cover behind the card content
        artBg = new BackdropImage(ctx);
        artBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBg.setVisibility(GONE);
        if (Build.VERSION.SDK_INT >= 31) {
            artBg.setRenderEffect(RenderEffect.createBlurEffect(60 * dp / 3, 60 * dp / 3, Shader.TileMode.CLAMP));
        }
        musicCard.addView(artBg, new FrameLayout.LayoutParams(-1, -1));

        musicRow = new LinearLayout(ctx);
        musicRow.setOrientation(LinearLayout.HORIZONTAL);
        musicRow.setGravity(Gravity.CENTER_VERTICAL);
        musicRow.setPadding(px(10), px(10), px(10), px(10));
        musicCard.addView(musicRow, new FrameLayout.LayoutParams(-1, -2));

        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * dp);
            }
        });
        art.setClipToOutline(true);
        art.setElevation(4 * dp);
        musicRow.addView(art, new LinearLayout.LayoutParams(px(68), px(68)));

        LinearLayout info = vertical();
        mTitle = text(th.text, 15);
        mTitle.setSingleLine();
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mArtist = text(th.dim, 13);
        mArtist.setSingleLine();
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(mTitle);
        info.addView(mArtist);
        LinearLayout barRow = new LinearLayout(ctx);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        mBar = new Bar(ctx);
        barRow.addView(mBar, new LinearLayout.LayoutParams(0, px(3), 1));
        mTime = text(th.dim, 11);
        mTime.setSingleLine();
        barRow.addView(mTime, margins(-2, -2, 8, 0, 0, 0));
        info.addView(barRow, margins(-1, -2, 0, 7, 0, 0));
        musicRow.addView(info, margins(0, -2, 12, 0, 6, 0, 1));

        deviceBtn = iconButton(R.drawable.ic_devices, th.text, 38);
        deviceBtn.setOnClickListener(v -> openPicker());
        deviceBtn.setVisibility(GONE);
        musicRow.addView(deviceBtn, new LinearLayout.LayoutParams(px(38), px(38)));
        ImageView prev = iconButton(R.drawable.ic_prev, th.text, 38);
        prev.setOnClickListener(v -> transport(-1));
        playBtn = iconButton(R.drawable.ic_play, th.bg, 46);
        playBtn.setPadding(px(11), px(11), px(11), px(11));
        playBtn.setOnClickListener(v -> transport(0));
        ImageView next = iconButton(R.drawable.ic_next, th.text, 38);
        next.setOnClickListener(v -> transport(1));
        musicRow.addView(prev, new LinearLayout.LayoutParams(px(38), px(38)));
        musicRow.addView(playBtn, margins(px(46), px(46), 4, 0, 4, 0));
        musicRow.addView(next, new LinearLayout.LayoutParams(px(38), px(38)));
        return musicCard;
    }

    private View buildPc() {
        LinearLayout box = vertical();
        pcStatus = text(th.dim, 12);
        pcStatus.setSingleLine();
        pcStatus.setEllipsize(TextUtils.TruncateAt.END);
        pcStatus.setOnClickListener(v -> onPcStatusClick());
        box.addView(pcStatus, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        macroRow = new LinearLayout(ctx);
        macroRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(macroRow);
        box.addView(hs, margins(-1, -2, 0, 6, 0, 0));
        return box;
    }

    private View buildRight() {
        LinearLayout col = vertical();

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView prevBtn = iconButton(R.drawable.ic_chevron_left, th.dim, 32);
        prevBtn.setOnClickListener(v -> navigate(-1));
        header.addView(prevBtn, new LinearLayout.LayoutParams(px(32), px(32)));
        calTitle = text(th.dim, 12);
        calTitle.setLetterSpacing(0.16f);
        calTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        calTitle.setSingleLine();
        calTitle.setOnClickListener(v -> goToday());
        header.addView(calTitle, margins(-2, -2, 2, 0, 2, 0));
        ImageView nextBtn = iconButton(R.drawable.ic_chevron_right, th.dim, 32);
        nextBtn.setOnClickListener(v -> navigate(1));
        header.addView(nextBtn, new LinearLayout.LayoutParams(px(32), px(32)));
        calCount = text(th.dim, 12);
        calCount.setSingleLine();
        calCount.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(calCount, margins(0, -2, 6, 0, 6, 0, 1));
        calMode = text(th.text, 12);
        calMode.setPadding(px(12), px(5), px(12), px(5));
        calMode.setBackground(rounded(th.surface, 12));
        calMode.setOnClickListener(v -> toggleMonth());
        header.addView(calMode);
        ImageView gear = iconButton(R.drawable.ic_more, th.dim, 34);
        gear.setOnClickListener(v -> host.openSettings());
        header.addView(gear, new LinearLayout.LayoutParams(px(34), px(34)));
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));

        allDayScroll = new HorizontalScrollView(ctx);
        allDayScroll.setHorizontalScrollBarEnabled(false);
        allDayRow = new LinearLayout(ctx);
        allDayScroll.addView(allDayRow);
        allDayScroll.setVisibility(GONE);
        col.addView(allDayScroll, margins(-1, -2, 0, 4, 0, 2));

        FrameLayout frame = new FrameLayout(ctx);
        col.addView(frame, margins(-1, 0, 0, 6, 0, 0, 1));

        calScroll = new ScrollView(ctx);
        calScroll.setVerticalScrollBarEnabled(false);
        calScroll.setVerticalFadingEdgeEnabled(true);
        calScroll.setFadingEdgeLength(px(28));
        calScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        calScroll.setOnTouchListener((v, e) -> {
            lastCalTouch = SystemClock.elapsedRealtime();
            return false;
        });
        timeline = new TimelineView(ctx);
        timeline.setTheme(th, prefs.googleColors() && !nightActive, prefs.h24());
        timeline.setOnEventTap(this::showDetail);
        calScroll.addView(timeline, new ScrollView.LayoutParams(-1, -2));
        frame.addView(calScroll, new FrameLayout.LayoutParams(-1, -1));

        monthView = new MonthView(ctx);
        monthView.setTheme(th, prefs.googleColors() && !nightActive);
        monthView.setListener(new MonthView.Listener() {
            @Override
            public void onDay(LocalDate day) {
                lastCalTouch = SystemClock.elapsedRealtime();
                monthMode = false;
                shownDay = day;
                reloadCalendar();
            }

            @Override
            public void onSwipe(int dir) {
                navigate(dir);
            }
        });
        monthView.setVisibility(GONE);
        frame.addView(monthView, new FrameLayout.LayoutParams(-1, -1));

        emptyText = text(th.dim, 14);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(px(24), px(10), px(24), px(10));
        emptyText.setBackground(rounded(th.bg, 14));
        emptyText.setVisibility(GONE);
        frame.addView(emptyText, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        detail = text(th.text, 14);
        detail.setPadding(px(14), px(12), px(14), px(12));
        GradientDrawable d = rounded(th.surface, 14);
        d.setStroke(Math.max(1, (int) dp), th.line);
        detail.setBackground(d);
        detail.setVisibility(GONE);
        detail.setOnClickListener(v -> v.setVisibility(GONE));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        dlp.setMargins(px(8), 0, px(8), px(8));
        frame.addView(detail, dlp);
        return col;
    }

    // ---------------------------------------------------------------- clock

    private final Runnable secondTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tickClock();
            updateProgress();
            LocalTime t = LocalTime.now();
            int m = t.getHour() * 60 + t.getMinute();
            if (m != lastMinute) {
                boolean first = lastMinute < 0;
                lastMinute = m;
                if (!first) onMinute();
            }
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private void onMinute() {
        if (isNight() != nightActive) build();
        if (timeline != null) timeline.invalidate();
        LocalDate now = LocalDate.now();
        boolean idle = SystemClock.elapsedRealtime() - lastCalTouch > CAL_IDLE_MS;
        if (!now.equals(todayRef)) {
            if (shownDay.equals(todayRef)) shownDay = now;
            todayRef = now;
            reloadCalendar();
        } else if (idle && (monthMode || !shownDay.equals(now))) {
            goToday();
        } else if (lastMinute % 5 == 0) {
            reloadCalendar();
        }
        if (prefs.burnIn() && content != null) {
            content.animate().translationX((rnd.nextFloat() * 10 - 5) * dp).translationY((rnd.nextFloat() * 8 - 4) * dp).setDuration(2000).start();
        }
        if (idle && !monthMode && shownDay.equals(now)) scrollToNow(true);
    }

    private void tickClock() {
        if (dateText == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (clock != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder(now.format(DateTimeFormatter.ofPattern(prefs.h24() ? "HH:mm" : "h:mm")));
            if (prefs.seconds()) {
                int st = sb.length();
                sb.append(now.format(DateTimeFormatter.ofPattern(":ss")));
                sb.setSpan(new RelativeSizeSpan(0.32f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(th.dim), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!prefs.h24()) {
                int st = sb.length();
                sb.append(" ").append(now.format(DateTimeFormatter.ofPattern("a", Locale.ENGLISH)));
                sb.setSpan(new RelativeSizeSpan(0.22f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(th.dim), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            clock.setText(sb);
        } else if (fancy != null) {
            fancy.setTime(now.toLocalTime());
        }
        String date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"));
        dateText.setText(date.isEmpty() ? date : Character.toUpperCase(date.charAt(0)) + date.substring(1));
        hint.setText(nextHint());
    }

    private void sizeClock() {
        int w = getWidth(), h = getHeight();
        if (clock == null || w == 0) return;
        boolean portrait = h > w;
        float colW = portrait ? w - px(40) : (w - px(90)) * 0.53f;
        float ems = prefs.seconds() ? 3.15f : 2.55f;
        if (!prefs.h24()) ems += 0.2f;
        if (prefs.clockFont().equals("mono")) ems *= 1.2f;
        float size = Math.min(colW * 0.9f / ems, (portrait ? h * 0.45f : h) * 0.25f);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        post(this::sizeClock);
    }

    private Typeface clockFace() {
        switch (prefs.clockFont()) {
            case "light": return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case "condensed": return Typeface.create("sans-serif-condensed-light", Typeface.NORMAL);
            case "serif": return Typeface.create("serif", Typeface.NORMAL);
            case "mono": return Typeface.create("monospace", Typeface.NORMAL);
            case "bold": return Typeface.create("sans-serif-black", Typeface.NORMAL);
            default: return Typeface.create("sans-serif-thin", Typeface.NORMAL);
        }
    }

    private String nextHint() {
        long now = System.currentTimeMillis();
        CalEvent cur = null, next = null;
        for (CalEvent e : todayEvents) {
            if (e.allDay) continue;
            if (e.start <= now && now < e.end) {
                if (cur == null) cur = e;
            } else if (e.start > now && next == null) {
                next = e;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (cur != null) sb.append("Сейчас · ").append(cur.title).append(" · до ").append(fmt(cur.end));
        if (next != null) {
            if (sb.length() > 0) sb.append('\n');
            long min = (next.start - now + 59_999) / 60_000;
            sb.append("Далее · ").append(next.title).append(" · ")
                    .append(min < 60 ? "через " + min + " мин" : "в " + fmt(next.start));
        }
        if (sb.length() == 0 && !todayEvents.isEmpty()) sb.append("На сегодня всё");
        return sb.toString();
    }

    private String fmt(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(prefs.h24() ? "H:mm" : "h:mm a", Locale.ENGLISH));
    }

    // ---------------------------------------------------------------- night & brightness

    private boolean isNight() {
        if (!prefs.nightMode()) return false;
        int h = LocalTime.now().getHour(), from = prefs.nightFrom(), to = prefs.nightTo();
        if (from == to) return false;
        return from > to ? h >= from || h < to : h >= from && h < to;
    }

    private void applyNightWindow() {
        float b = prefs.brightness();
        setWindowBrightness(nightActive ? 0.01f : b >= 0 ? b : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    private void setWindowBrightness(float v) {
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = v;
        window.setAttributes(lp);
    }

    private float currentBrightness() {
        if (window != null && window.getAttributes().screenBrightness >= 0) return window.getAttributes().screenBrightness;
        try {
            return Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    // ---------------------------------------------------------------- edge swipes

    private int edgeAt(float x) {
        float zone = 34 * dp;
        if (x >= getWidth() - cutR - zone) return 1;
        if (x <= cutL + zone) return 2;
        return 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (picker != null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edge = edgeAt(e.getX());
                edgeActive = false;
                edgeY0 = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (edge != 0 && !edgeActive && Math.abs(e.getY() - edgeY0) > 10 * dp) {
                    startEdge(e.getY());
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edge = edgeAt(e.getX());
                edgeActive = false;
                edgeY0 = e.getY();
                return edge != 0;
            case MotionEvent.ACTION_MOVE:
                if (edge == 0) return false;
                if (!edgeActive) {
                    if (Math.abs(e.getY() - edgeY0) <= 10 * dp) return true;
                    startEdge(e.getY());
                }
                updateEdge(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (edgeActive) endEdge();
                edge = 0;
                return true;
            default:
                return edge != 0;
        }
    }

    private void startEdge(float y) {
        edgeActive = true;
        edgeY0 = y;
        if (edge == 1) {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            edgeV0 = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, max);
            lastVolStep = -1;
        } else {
            edgeV0 = currentBrightness();
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) level.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (edge == 1 ? Gravity.END : Gravity.START);
        lp.setMargins(px(26) + cutL, 0, px(26) + cutR, 0);
        level.setLayoutParams(lp);
        level.set(edge == 1 ? R.drawable.ic_vol_up : R.drawable.ic_brightness, edgeV0);
        level.animate().cancel();
        level.animate().alpha(1f).setDuration(120).start();
        updateEdge(y);
    }

    private void updateEdge(float y) {
        float f = Math.max(0f, Math.min(1f, edgeV0 + (edgeY0 - y) / (getHeight() * 0.6f)));
        if (edge == 1) {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = Math.round(f * max);
            if (v != lastVolStep) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                if (lastVolStep >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                lastVolStep = v;
            }
            level.set(v == 0 ? R.drawable.ic_mute : R.drawable.ic_vol_up, v / (float) Math.max(1, max));
        } else {
            f = Math.max(0.05f, f);
            setWindowBrightness(f);
            level.set(R.drawable.ic_brightness, f);
        }
        edgeValue = f;
    }

    private void endEdge() {
        edgeActive = false;
        if (edge == 2 && !nightActive) prefs.setBrightness(edgeValue);
        level.animate().alpha(0f).setStartDelay(700).setDuration(400).start();
    }

    // ---------------------------------------------------------------- calendar

    public void reloadCalendar() {
        LocalDate today = LocalDate.now();
        boolean month = monthMode;
        LocalDate day = shownDay;
        YearMonth ym = shownMonth;
        io.execute(() -> {
            List<CalEvent> todayList = CalendarRepo.load(ctx, prefs, today);
            List<CalEvent> view;
            if (month) {
                LocalDate gs = MonthView.gridStart(ym);
                view = CalendarRepo.load(ctx, prefs, gs, gs.plusDays(42));
            } else {
                view = day.equals(today) ? todayList : CalendarRepo.load(ctx, prefs, day);
            }
            ui.post(() -> {
                todayEvents = todayList;
                if (month == monthMode && day.equals(shownDay) && ym.equals(shownMonth)) {
                    viewEvents = view;
                    renderCalendar();
                } else {
                    if (hint != null) hint.setText(nextHint());
                }
            });
        });
    }

    private void navigate(int dir) {
        lastCalTouch = SystemClock.elapsedRealtime();
        if (monthMode) shownMonth = shownMonth.plusMonths(dir);
        else shownDay = shownDay.plusDays(dir);
        renderHeader();
        reloadCalendar();
    }

    private void goToday() {
        lastCalTouch = SystemClock.elapsedRealtime();
        monthMode = false;
        shownDay = LocalDate.now();
        shownMonth = YearMonth.now();
        reloadCalendar();
        renderHeader();
    }

    private void toggleMonth() {
        lastCalTouch = SystemClock.elapsedRealtime();
        monthMode = !monthMode;
        if (monthMode) shownMonth = YearMonth.from(shownDay);
        renderHeader();
        reloadCalendar();
    }

    private void renderHeader() {
        if (calTitle == null) return;
        LocalDate today = LocalDate.now();
        String title;
        if (monthMode) {
            title = shownMonth.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
                    + (shownMonth.getYear() != today.getYear() ? " " + shownMonth.getYear() : "");
        } else if (shownDay.equals(today)) {
            title = "Сегодня";
        } else if (shownDay.equals(today.plusDays(1))) {
            title = "Завтра";
        } else if (shownDay.equals(today.minusDays(1))) {
            title = "Вчера";
        } else {
            title = shownDay.format(DateTimeFormatter.ofPattern("EE, d MMMM"));
        }
        calTitle.setText(title.toUpperCase(Locale.getDefault()));
        calTitle.setTextColor(monthMode || shownDay.equals(today) ? th.dim : th.accent);
        calMode.setText(monthMode ? "День" : "Месяц");
    }

    private void renderCalendar() {
        if (timeline == null) return;
        renderHeader();
        calScroll.setVisibility(monthMode ? GONE : VISIBLE);
        monthView.setVisibility(monthMode ? VISIBLE : GONE);
        detail.setVisibility(GONE);

        int timed = 0;
        allDayRow.removeAllViews();
        if (monthMode) {
            monthView.setMonth(shownMonth, shownDay, viewEvents);
            allDayScroll.setVisibility(GONE);
            int n = 0;
            for (CalEvent e : viewEvents) {
                LocalDate d = Instant.ofEpochMilli(e.start).atZone(ZoneId.systemDefault()).toLocalDate();
                if (YearMonth.from(d).equals(shownMonth)) n++;
            }
            calCount.setText(n == 0 ? "" : "· " + plural(n));
        } else {
            long ds = shownDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            timeline.setEvents(viewEvents, ds);
            for (CalEvent e : viewEvents) {
                if (!e.allDay) {
                    timed++;
                    continue;
                }
                int color = th.eventColor(e.color, e.title, prefs.googleColors() && !nightActive);
                TextView chip = text(DashTheme.onColor(color), 12);
                chip.setText(e.title);
                chip.setSingleLine();
                chip.setPadding(px(10), px(4), px(10), px(4));
                chip.setBackground(rounded(color, 10));
                chip.setOnClickListener(v -> showDetail(e));
                allDayRow.addView(chip, margins(-2, -2, 0, 0, 6, 0));
            }
            allDayScroll.setVisibility(allDayRow.getChildCount() > 0 ? VISIBLE : GONE);
            calCount.setText(viewEvents.isEmpty() ? "" : "· " + plural(viewEvents.size()));
        }

        String src = prefs.calSource();
        emptyText.setOnClickListener(null);
        emptyText.setClickable(false);
        if (!"ics".equals(src) && !CalendarRepo.hasPermission(ctx)) {
            emptyText.setText("Нет доступа к календарю\nНажми, чтобы открыть настройки");
            emptyText.setOnClickListener(v -> host.openSettings());
            emptyText.setVisibility(VISIBLE);
        } else if ("ics".equals(src) && prefs.icsUrls().trim().isEmpty()) {
            emptyText.setText("Добавь ICS-ссылку календаря\nв настройках");
            emptyText.setOnClickListener(v -> host.openSettings());
            emptyText.setVisibility(VISIBLE);
        } else if (!monthMode && timed == 0) {
            emptyText.setText(shownDay.equals(LocalDate.now()) ? "На сегодня событий нет" : "В этот день событий нет");
            emptyText.setVisibility(VISIBLE);
        } else {
            emptyText.setVisibility(GONE);
        }
        if (hint != null) hint.setText(nextHint());
        if (!monthMode && !shownDay.equals(renderedDay)) {
            renderedDay = shownDay;
            calScroll.post(() -> scrollToNow(false));
        }
    }

    /** Today: scroll to the "now" line; other days: to the first event (or 8:00). */
    private void scrollToNow(boolean smooth) {
        if (calScroll == null || timeline == null) return;
        long target;
        if (shownDay.equals(LocalDate.now())) {
            target = System.currentTimeMillis();
        } else {
            target = shownDay.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            for (CalEvent e : viewEvents) {
                if (!e.allDay) {
                    target = e.start + 30 * 60_000L;
                    break;
                }
            }
        }
        int y = Math.max(0, (int) (timeline.yForTime(target) - calScroll.getHeight() * 0.3f));
        if (smooth) calScroll.smoothScrollTo(0, y);
        else calScroll.scrollTo(0, y);
    }

    private void showDetail(CalEvent e) {
        StringBuilder sb = new StringBuilder(e.title);
        sb.append('\n').append(e.allDay ? "Весь день" : fmt(e.start) + " – " + fmt(e.end));
        if (!e.location.isEmpty()) sb.append('\n').append(e.location);
        if (!e.calendar.isEmpty()) sb.append('\n').append(e.calendar);
        SpannableStringBuilder s = new SpannableStringBuilder(sb);
        s.setSpan(new RelativeSizeSpan(1.15f), 0, e.title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(th.dim), e.title.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        detail.setText(s);
        detail.setVisibility(VISIBLE);
        ui.removeCallbacks(hideDetail);
        ui.postDelayed(hideDetail, 6000);
    }

    private final Runnable hideDetail = () -> {
        if (detail != null) detail.setVisibility(GONE);
    };

    private static String plural(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? "событие"
                : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "события" : "событий";
        return n + " " + w;
    }

    // ---------------------------------------------------------------- music

    /** What the card shows: the phone's media session, or Spotify playing elsewhere (via the PC agent). */
    private void renderMusic() {
        if (musicCard == null) return;
        MediaMetadata md = media == null ? null : media.getMetadata();
        PcClient.SpState sp = spState;
        boolean remote = md == null && sp != null && sp.hasTrack;
        renderDeviceButton();

        if (musicNoAccess && !remote || md == null && !remote) {
            showArt(null);
            mTitle.setText(musicNoAccess ? "Музыка" : "Тишина");
            mArtist.setText(musicNoAccess ? "Нажми, чтобы дать доступ к уведомлениям" : "Нажми ▶ или открой Spotify");
            mTime.setText(deviceSuffix(false));
            mBar.set(0, th.line, th.accent);
            musicCard.setBackground(rounded(th.surface, 18));
            stylePlay(false, th.accent);
            return;
        }

        String title, artist;
        Bitmap bmp = null;
        boolean playing;
        if (remote) {
            title = sp.title;
            artist = sp.artist;
            if (!sp.image.isEmpty()) bmp = artFromUri(sp.image);
            playing = sp.playing;
        } else {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (bmp == null) {
                // newer Spotify versions only publish a URI for the cover
                for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                    String uri = md.getString(key);
                    if (uri != null && !uri.isEmpty()) {
                        bmp = artFromUri(uri);
                        if (bmp != null) break;
                    }
                }
            }
            if (bmp == null && sp != null && sp.hasTrack && !sp.image.isEmpty() && title != null
                    && title.equalsIgnoreCase(sp.title)) {
                bmp = artFromUri(sp.image);
            }
            PlaybackState st = media.getPlaybackState();
            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        }
        mTitle.setText(title == null ? "" : title);
        mArtist.setText(artist == null ? "" : artist);
        showArt(bmp);

        int acc = artColor != null && !nightActive ? artColor : th.accent;
        musicCard.setBackground(rounded(artColor != null && !nightActive ? DashTheme.blend(th.surface, acc, 0.14f) : th.surface, 18));
        stylePlay(playing, acc);
        mBar.set(mBar.frac, th.line, acc);
        if (remote) {
            mBar.set(0, th.line, acc);
            mTime.setText(deviceSuffix(true));
        } else {
            updateProgress();
        }
    }

    private void showArt(Bitmap bmp) {
        if (bmp != null) {
            art.setImageTintList(null);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(null);
            art.setImageBitmap(bmp);
            if (Build.VERSION.SDK_INT >= 31 && !nightActive) {
                artBg.setImageBitmap(bmp);
                artBg.setAlpha(th.light ? 0.28f : 0.42f);
                artBg.setVisibility(VISIBLE);
            }
            if (bmp != lastArt) {
                lastArt = bmp;
                artColor = prefs.artAccent() ? dominant(bmp, th.light) : null;
            }
        } else {
            lastArt = null;
            artColor = null;
            art.setImageResource(R.drawable.ic_music);
            art.setImageTintList(ColorStateList.valueOf(th.dim));
            art.setScaleType(ImageView.ScaleType.CENTER);
            art.setBackground(rounded(th.line, 12));
            artBg.setVisibility(GONE);
        }
    }

    /** "  ·  zenbook" when Spotify plays on another device. */
    private String deviceSuffix(boolean alone) {
        PcClient.SpState sp = spState;
        if (sp == null || sp.device.isEmpty()) return "";
        String d = sp.device.length() > 16 ? sp.device.substring(0, 15) + "…" : sp.device;
        return alone ? "▶ " + d : "  ·  " + d;
    }

    private Bitmap artFromUri(String uri) {
        Bitmap b = artCache.get(uri);
        if (b != null || artLoading.contains(uri)) return b;
        artLoading.add(uri);
        io.execute(() -> {
            Bitmap loaded = null;
            try {
                byte[] data;
                if (uri.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        data = readAll(in);
                    } finally {
                        c.disconnect();
                    }
                } else {
                    try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
                        data = in == null ? null : readAll(in);
                    }
                }
                if (data != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= 320) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    loaded = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                }
            } catch (Exception ignored) {
                // no permission for the provider, or offline: keep the placeholder
            }
            Bitmap result = loaded;
            ui.post(() -> {
                if (result != null) {
                    if (artCache.size() > 12) artCache.clear();
                    artCache.put(uri, result);
                    renderMusic();
                }
            });
        });
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private void stylePlay(boolean playing, int acc) {
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(acc);
        playBtn.setBackground(g);
        playBtn.setImageTintList(ColorStateList.valueOf(DashTheme.onColor(acc)));
    }

    private void updateProgress() {
        if (mBar == null || media == null) return;
        MediaMetadata md = media.getMetadata();
        if (md == null) return;
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState st = media.getPlaybackState();
        long pos = st == null ? 0 : st.getPosition();
        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        if (dur > 0) {
            pos = Math.max(0, Math.min(pos, dur));
            mBar.set(pos / (float) dur, mBar.track, mBar.fg);
            mTime.setText(mmss(pos) + " / " + mmss(dur) + deviceSuffix(false));
        } else {
            mBar.set(0, mBar.track, mBar.fg);
            mTime.setText(deviceSuffix(true));
        }
    }

    private void transport(int dir) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        PcClient.SpState sp = spState;
        PcClient c = pc;
        if (media == null && sp != null && sp.hasTrack && c != null) {
            // Spotify plays on another device: steer it through the PC agent
            String cmd = dir < 0 ? "prev" : dir > 0 ? "next" : sp.playing ? "pause" : "play";
            if (dir == 0) sp.playing = !sp.playing;
            renderMusic();
            io.execute(() -> {
                String err = c.spotifyCommand(cmd);
                if (err != null) ui.post(() -> Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show());
                refreshSpotifySoon();
            });
            return;
        }
        if (media == null) {
            if (dir == 0) {
                // No active session: a media key resumes the last player (usually Spotify).
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            }
            return;
        }
        MediaController.TransportControls tc = media.getTransportControls();
        if (dir < 0) tc.skipToPrevious();
        else if (dir > 0) tc.skipToNext();
        else {
            PlaybackState st = media.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
            else tc.play();
        }
    }

    private void openMusicApp() {
        Intent i;
        if (musicNoAccess) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        } else {
            String pkg = media != null ? media.getPackageName() : SPOTIFY;
            i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(ctx, "Spotify не установлен", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
            // nothing to open
        }
    }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** Most saturated colour of the cover, nudged so it works as an accent on the theme background. */
    private static int dominant(Bitmap b, boolean lightTheme) {
        try {
            if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
            float[] hsv = new float[3];
            float best = -1;
            int bestC = 0;
            long r = 0, g = 0, bl = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = s.getPixel(x, y);
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * (1f - Math.abs(hsv[2] - 0.7f));
                    if (score > best) {
                        best = score;
                        bestC = p;
                    }
                }
            }
            int c = best > 0.2f ? bestC : Color.rgb((int) (r / 576), (int) (g / 576), (int) (bl / 576));
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(hsv[1], 0.7f);
            hsv[2] = lightTheme ? Math.min(hsv[2], 0.55f) : Math.max(hsv[2], 0.7f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFFC8A27C;
        }
    }

    // ---------------------------------------------------------------- Spotify device picker

    private static int deviceIcon(String type) {
        switch (type == null ? "" : type) {
            case "Computer": return R.drawable.ic_desktop;
            case "Smartphone":
            case "Tablet": return R.drawable.ic_phone;
            case "TV":
            case "CastVideo":
            case "STB": return R.drawable.ic_tv;
            case "Speaker":
            case "CastAudio":
            case "AVR": return R.drawable.ic_speaker;
            default: return R.drawable.ic_devices;
        }
    }

    private void renderDeviceButton() {
        if (deviceBtn == null) return;
        boolean show = pcSpotify && pcOnline;
        deviceBtn.setVisibility(show ? VISIBLE : GONE);
        PcClient.SpState sp = spState;
        String active = null;
        if (sp != null) {
            for (PcClient.SpDevice d : sp.devices) if (d.active) active = d.type;
            if (active == null && !sp.deviceType.isEmpty()) active = sp.deviceType;
        }
        deviceBtn.setImageResource(active == null ? R.drawable.ic_devices : deviceIcon(active));
        deviceBtn.setImageTintList(ColorStateList.valueOf(active == null ? th.text : th.accent));
    }

    private void openPicker() {
        if (picker != null) return;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        picker = new FrameLayout(ctx);
        picker.setBackgroundColor(0x99000000);
        picker.setOnClickListener(v -> closePicker());

        LinearLayout card = vertical();
        card.setClickable(true);
        card.setPadding(px(18), px(16), px(18), px(14));
        GradientDrawable bg = rounded(th.surface, 22);
        bg.setStroke(Math.max(1, (int) dp), th.line);
        card.setBackground(bg);
        card.setElevation(12 * dp);

        TextView title = text(th.text, 18);
        title.setText("Где играть");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        TextView sub = text(th.dim, 13);
        sub.setText("Spotify Connect · выбери устройство");
        card.addView(sub, margins(-1, -2, 0, 2, 0, 10));
        pickerList = vertical();
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(pickerList);
        card.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        pickerStatus = text(th.dim, 13);
        card.addView(pickerStatus, margins(-1, -2, 0, 8, 0, 0));

        int w = Math.min(px(380), (int) (getWidth() * 0.46f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2, Gravity.CENTER_VERTICAL | Gravity.START);
        lp.leftMargin = px(28) + cutL;
        picker.addView(card, lp);
        picker.setAlpha(0f);
        addView(picker, new LayoutParams(-1, -1));
        picker.animate().alpha(1f).setDuration(160).start();
        renderPicker();
        refreshSpotifySoon();
        ui.postDelayed(autoClosePicker, 30_000);
    }

    private final Runnable autoClosePicker = this::closePicker;

    private void closePicker() {
        ui.removeCallbacks(autoClosePicker);
        if (picker == null) return;
        View p = picker;
        picker = null;
        p.animate().alpha(0f).setDuration(140).withEndAction(() -> removeView(p)).start();
    }

    private void renderPicker() {
        if (picker == null) return;
        pickerList.removeAllViews();
        PcClient.SpState sp = spState;
        if (sp == null) {
            pickerStatus.setText("Загружаю устройства…");
            return;
        }
        if (sp.error != null) {
            pickerStatus.setText(sp.error);
            return;
        }
        if (sp.devices.isEmpty()) {
            pickerStatus.setText("Устройств нет. Открой Spotify на телефоне, ноутбуке или колонке.");
            return;
        }
        if (pickerStatus.getTag() == null) pickerStatus.setText("");
        for (PcClient.SpDevice d : sp.devices) {
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(12), px(10), px(12), px(10));
            row.setBackground(rounded(d.active ? DashTheme.blend(th.surface, th.accent, 0.22f) : th.bg, 14));
            ImageView ic = new ImageView(ctx);
            ic.setImageResource(deviceIcon(d.type));
            ic.setImageTintList(ColorStateList.valueOf(d.active ? th.accent : th.text));
            row.addView(ic, new LinearLayout.LayoutParams(px(24), px(24)));
            LinearLayout col = vertical();
            TextView name = text(th.text, 15);
            name.setText(d.name);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            TextView info = text(d.active ? th.accent : th.dim, 12);
            info.setText(d.active ? "играет сейчас" + (d.volume >= 0 ? " · громкость " + d.volume + "%" : "") : typeName(d.type));
            col.addView(info);
            row.addView(col, margins(0, -2, 12, 0, 0, 0, 1));
            row.setOnClickListener(v -> transferTo(d));
            pickerList.addView(row, margins(-1, -2, 0, 0, 0, 6));
        }
    }

    private static String typeName(String t) {
        switch (t == null ? "" : t) {
            case "Computer": return "Компьютер";
            case "Smartphone": return "Телефон";
            case "Tablet": return "Планшет";
            case "Speaker": return "Колонка";
            case "TV": return "Телевизор";
            case "CastAudio":
            case "CastVideo": return "Chromecast";
            case "GameConsole": return "Консоль";
            case "Automobile": return "Автомобиль";
            default: return t == null ? "" : t;
        }
    }

    private void transferTo(PcClient.SpDevice d) {
        PcClient c = pc;
        if (c == null || d.active) {
            closePicker();
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        pickerStatus.setTag("busy");
        pickerStatus.setText("Переключаю на «" + d.name + "»…");
        ui.removeCallbacks(autoClosePicker);
        io.execute(() -> {
            String err = c.spotifyTransfer(d.id);
            if (err == null) SystemClock.sleep(1200);
            PcClient.SpState fresh = fetchSpotify(c);
            ui.post(() -> {
                if (fresh != null) spState = fresh;
                if (pickerStatus != null) pickerStatus.setTag(null);
                renderMusic();
                renderPicker();
                if (picker == null) return;
                if (err == null) {
                    pickerStatus.setText("Играет на «" + d.name + "»");
                    ui.postDelayed(this::closePicker, 800);
                } else {
                    pickerStatus.setText(err);
                    ui.postDelayed(autoClosePicker, 30_000);
                }
            });
        });
    }

    private PcClient.SpState fetchSpotify(PcClient c) {
        try {
            return c.spotifyState();
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshSpotifySoon() {
        PcClient c = pc;
        if (c == null) return;
        io.execute(() -> {
            PcClient.SpState s = fetchSpotify(c);
            ui.post(() -> {
                if (s != null) spState = s;
                else if (spState == null) {
                    PcClient.SpState e = new PcClient.SpState();
                    e.error = "Нет связи с ПК";
                    spState = e;
                }
                renderMusic();
                renderPicker();
            });
        });
    }

    // ---------------------------------------------------------------- PC

    private final Runnable pcPoll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (pc != null && polling.compareAndSet(false, true)) io.execute(DashboardView.this::pollPc);
            ui.postDelayed(this, 3000);
        }
    };

    private void pollPc() {
        PcClient c = pc;
        try {
            if (c == null) return;
            boolean needInfo = !pcOnline || System.currentTimeMillis() - pcInfoAt > 60_000;
            PcClient.Info info = needInfo ? c.info() : null;
            JSONObject st = c.stats();
            boolean spOn = info != null ? info.spotify : pcSpotify;
            PcClient.SpState sp = spOn && pollCount++ % 3 == 0 ? fetchSpotify(c) : null;
            ui.post(() -> {
                pcFails = 0;
                boolean was = pcOnline;
                pcOnline = true;
                pcStats = st;
                pcSpotify = spOn;
                if (!spOn) spState = null;
                if (sp != null) spState = sp;
                if (info != null) {
                    pcInfoAt = System.currentTimeMillis();
                    pcName = info.name;
                    if (!info.mac.isEmpty()) prefs.setPcMac(info.mac);
                    if (!info.macrosJson.equals(macrosJson)) {
                        macrosJson = info.macrosJson;
                        macros = info.macros;
                        prefs.setPcMacros(macrosJson);
                        renderMacros();
                    }
                }
                if (!was) renderMacros();
                renderPc();
                if (sp != null || !was) renderMusic();
                renderPicker();
            });
        } catch (Exception e) {
            ui.post(() -> {
                if (++pcFails >= 2 && pcOnline) {
                    pcOnline = false;
                    spState = null;
                    renderMacros();
                    renderMusic();
                }
                renderPc();
            });
        } finally {
            polling.set(false);
        }
    }

    private void renderPc() {
        if (pcStatus == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder("●  ");
        int dot = th.dim;
        if (prefs.pcHost().isEmpty()) {
            sb.append("Компьютер не подключён · нажми, чтобы настроить");
        } else if (pcOnline && pcStats != null) {
            dot = th.accent;
            sb.append(pcName.isEmpty() ? "ПК" : pcName)
                    .append("   CPU ").append(String.valueOf(Math.round(pcStats.optDouble("cpu"))))
                    .append("%   RAM ").append(String.valueOf(pcStats.optInt("ram"))).append('%');
            if (!pcStats.isNull("battery")) {
                sb.append("   BAT ").append(String.valueOf(pcStats.optInt("battery"))).append('%');
                if (pcStats.optBoolean("charging")) sb.append("+");
            }
        } else if (pcFails >= 2 || !pcOnline && pcFails > 0) {
            sb.append(pcName.isEmpty() ? "ПК" : pcName).append(" не в сети");
            if (!prefs.pcMac().isEmpty()) sb.append(" · нажми, чтобы разбудить");
        } else {
            sb.append("Подключаюсь к ").append(prefs.pcHost()).append("…");
        }
        sb.setSpan(new ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        pcStatus.setText(sb);
    }

    private void renderMacros() {
        if (macroRow == null) return;
        macroRow.removeAllViews();
        if (prefs.pcHost().isEmpty()) return;
        for (PcClient.Macro m : macros) macroRow.addView(macroButton(m), macroParams());
    }

    private LinearLayout.LayoutParams macroParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(62), px(56));
        lp.rightMargin = px(8);
        return lp;
    }

    private View macroButton(PcClient.Macro m) {
        LinearLayout b = vertical();
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(th.surface, 14));
        b.setAlpha(pcOnline ? 1f : 0.4f);
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconFor(m.icon));
        ic.setImageTintList(ColorStateList.valueOf(th.text));
        b.addView(ic, new LinearLayout.LayoutParams(px(22), px(22)));
        TextView l = text(th.dim, 10);
        l.setSingleLine();
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setGravity(Gravity.CENTER);
        l.setText(m.label);
        b.addView(l, margins(-1, -2, 4, 4, 4, 0));
        b.setOnClickListener(v -> onMacro(m, b, l));
        return b;
    }

    private void onMacro(PcClient.Macro m, View b, TextView l) {
        b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        PcClient c = pc;
        if (!pcOnline || c == null) {
            flash(b, false);
            return;
        }
        if (m.confirm && b.getTag() == null) {
            b.setTag("armed");
            l.setText("Точно?");
            l.setTextColor(th.accent);
            b.postDelayed(() -> {
                b.setTag(null);
                l.setText(m.label);
                l.setTextColor(th.dim);
            }, 3000);
            return;
        }
        b.setTag(null);
        l.setText(m.label);
        l.setTextColor(th.dim);
        io.execute(() -> {
            boolean ok = c.run(m.id);
            ui.post(() -> flash(b, ok));
        });
    }

    private void flash(View b, boolean ok) {
        b.setBackground(rounded(DashTheme.blend(th.surface, ok ? th.accent : 0xFFE5534B, 0.45f), 14));
        b.postDelayed(() -> b.setBackground(rounded(th.surface, 14)), 350);
    }

    private void onPcStatusClick() {
        if (prefs.pcHost().isEmpty()) {
            host.openSettings();
        } else if (!pcOnline && !prefs.pcMac().isEmpty()) {
            String mac = prefs.pcMac();
            io.execute(() -> {
                try {
                    PcClient.wake(mac);
                    ui.post(() -> Toast.makeText(ctx, "Отправил сигнал пробуждения", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(ctx, "Не удалось: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private static int iconFor(String name) {
        switch (name == null ? "" : name) {
            case "lock": return R.drawable.ic_lock;
            case "mute": return R.drawable.ic_mute;
            case "vol_up": return R.drawable.ic_vol_up;
            case "vol_down": return R.drawable.ic_vol_down;
            case "play": return R.drawable.ic_play;
            case "pause": return R.drawable.ic_pause;
            case "next": return R.drawable.ic_next;
            case "prev": return R.drawable.ic_prev;
            case "desktop": return R.drawable.ic_desktop;
            case "sleep": return R.drawable.ic_sleep;
            case "screenshot": return R.drawable.ic_screenshot;
            case "open": return R.drawable.ic_open;
            case "power": return R.drawable.ic_power;
            case "music": return R.drawable.ic_music;
            case "eye_off": return R.drawable.ic_eye_off;
            case "web": return R.drawable.ic_web;
            case "settings": return R.drawable.ic_settings;
            default: return R.drawable.ic_bolt;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void applyPadding() {
        if (content == null) return;
        int base = px(22);
        content.setPadding(base + cutL, px(16) + cutT, base + cutR, px(16) + cutB);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(int color, float sp) {
        TextView t = new TextView(ctx);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private ImageView iconButton(int res, int tint, int sizeDp) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        int p = px(sizeDp * 0.2f);
        v.setPadding(p, p, p, p);
        return v;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    /** LayoutParams with margins in dp; width/height of -1/-2 mean MATCH/WRAP, other values are px. */
    private LinearLayout.LayoutParams margins(int w, int h, float l, float t, float r, float b) {
        return margins(w, h, l, t, r, b, 0);
    }

    private LinearLayout.LayoutParams margins(int w, int h, float l, float t, float r, float b, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.setMargins(px(l), px(t), px(r), px(b));
        return lp;
    }

    /** Thin progress bar. */
    private static final class Bar extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float frac;
        int track, fg;

        Bar(Context c) {
            super(c);
        }

        void set(float f, int track, int fg) {
            frac = f;
            this.track = track;
            this.fg = fg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = getHeight() / 2f;
            p.setColor(track);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
            p.setColor(fg);
            c.drawRoundRect(0, 0, getWidth() * frac, getHeight(), r, r, p);
        }
    }

    /** Fills its parent but never makes it bigger: sizes itself only when the size is exact. */
    private static final class BackdropImage extends ImageView {
        BackdropImage(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int mw = MeasureSpec.getMode(w) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(w) : 0;
            int mh = MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : 0;
            setMeasuredDimension(mw, mh);
        }
    }

    /** Volume / brightness pill shown while swiping along a screen edge. */
    private final class LevelView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float value;
        Drawable icon;

        LevelView(Context c) {
            super(c);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(12 * dp);
        }

        void set(int iconRes, float v) {
            value = v;
            icon = getContext().getDrawable(iconRes);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = w / 2, top = 22 * dp;
            p.setColor(th.surface);
            c.drawRoundRect(0, top, w, h, r, r, p);
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(0, top, w, h, r, r, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            p.setColor(th.accent);
            c.drawRect(0, h - (h - top) * value, w, h, p);
            c.restore();
            p.setColor(th.text);
            c.drawText(Math.round(value * 100) + "%", w / 2, 14 * dp, p);
            if (icon != null) {
                int s = (int) (22 * dp), x = (int) (w / 2 - s / 2f), y = (int) (h - s - 12 * dp);
                icon.setBounds(x, y, x + s, y + s);
                icon.setTint(value > 0.12f ? DashTheme.onColor(th.accent) : th.text);
                icon.draw(c);
            }
        }
    }
}
