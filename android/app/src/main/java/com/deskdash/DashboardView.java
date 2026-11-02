package com.deskdash;

import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
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
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.EditText;
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
 * The whole dashboard. Shows the current desk of the active profile: columns of blocks (clock,
 * music, PC macros, calendar, smart home tiles, weather); horizontal swipes or the dots at the bottom switch desks.
 * The same blocks in other looks (Settings → «Вид»): cards ("bento"), a big clock with the desks on a tap
 * ("focus"), sections on a side rail ("dock") and the day as a strip ("ribbon"). At night: a dim clock only,
 * the desk in night colours or the own theme quieter; night starts by the clock, at sunset or when the room goes dark.
 * Vertical swipes along the right edge change volume, along the left edge brightness.
 * Hosted by MainActivity and by the DashDream screensaver.
 */
public class DashboardView extends FrameLayout {
    public interface Host {
        void open(Class<? extends android.app.Activity> screen);
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

    // calls, day stats, live strip
    private volatile PcClient.Calls pcCalls;
    private volatile String pcFeatures = "";
    private volatile JSONObject pcDay;
    private volatile long pcDayAt;
    private CallPanel callPanel;
    private StatsPanel statsPanel;
    private String callsSig = "";
    private LinearLayout live;
    private TextView liveText;
    private View liveDot;
    private String liveFlash;
    private long liveFlashUntil, bambuEventSeen = System.currentTimeMillis();
    private Runnable liveAction;
    private final Map<String, Bitmap> pics = new HashMap<>();
    private final Map<String, Long> picAt = new HashMap<>();
    private int musicSec, musicTracks, musicTick;
    private String musicTrack = "";

    // smart home
    private final SmartHome home;
    private final AtomicBoolean homeBusy = new AtomicBoolean();
    private final List<HomePanel> homePanels = new ArrayList<>();
    private FrameLayout sheet;

    // desks
    private int deskCount = 1, curDesk;
    private boolean hasBar;
    private String deskName = "";
    private final List<View> hOwners = new ArrayList<>(); // views with their own horizontal swipes
    private TextView barLabel;
    private boolean swipeOk, swiping;
    private float downX, downY;

    // edge swipe
    private int edge; // 0 none, 1 volume (right edge), 2 brightness (left edge)
    private boolean edgeActive;
    private float edgeY0, edgeV0, edgeValue;
    private int lastVolStep = -1;

    // weather
    private volatile Weather weather;
    private final List<WeatherPanel> weatherPanels = new ArrayList<>();
    private TextView weatherLine;
    private long weatherTryAt;
    private String rainHidden = "";

    // looks: focus, dock and ribbon; peeking at the desk from the focus or night clock
    private String look = "classic";
    private boolean peeking, deskSwipe, bigClock;
    private long peekUntil;
    private TextView dockTime;
    private RibbonView ribbon;
    private List<CalEvent> ribbonEvents = new ArrayList<>();
    private LinearLayout focusPills;
    private String nightText = "";

    // night by the room light
    private SensorManager sensors;
    private boolean luxNight, haveLux;
    private float lux = -1;
    private long darkSince, brightSince;

    // calls on this phone, links from the clipboard, smart home reactions
    private PhoneCalls.Call phoneCall;
    private Meetings.Meeting clipLink;
    private long clipStamp = -1;
    private int slowTick;
    private final Set<String> reminded = new HashSet<>();
    private boolean callWasOn, callLightOn;
    private int dawnStep = -1;

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
        home = new SmartHome(prefs);
        weather = Weather.cached(prefs);
        loadMacroCache();
        music =new MusicWatcher(c, new MusicWatcher.Listener() {
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
        Bambu.sync(prefs);
        music.start();
        lastMinute = -1;
        ui.post(secondTick);
        ui.post(pcPoll);
        ui.post(homePoll);
        ui.post(weatherPoll);
        startLightSensor();
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
        ui.removeCallbacks(homePoll);
        ui.removeCallbacks(weatherPoll);
        ui.removeCallbacks(reloadCal);
        stopLightSensor();
        flushMusic();
        Bambu.stopAll();
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
        sheet = null;
        forgetBlockViews();
        nightActive = isNight();
        DashTheme base = DashTheme.byId(prefs.theme());
        th = !nightActive ? base : "dim".equals(prefs.nightLook()) ? base.dimmed() : DashTheme.night(prefs.nightTint());
        setBackgroundColor(th.bg);
        renderedDay = null;
        hasBar = false;
        bigClock = false;

        look = prefs.uiStyle();
        boolean nightClock = nightActive && "clock".equals(prefs.nightLook());
        if (peeking) {
            if ("focus".equals(look)) look = "classic";
        } else if (nightClock) {
            look = "night";
        }
        deskSwipe = "classic".equals(look) || "bento".equals(look);

        JSONArray desks = Desks.load(prefs);
        deskCount = desks.length();
        int di = Math.max(0, Math.min(prefs.deskIndex(), deskCount - 1));
        curDesk = di;
        JSONObject desk = desks.optJSONObject(di);
        if (desk == null) desk = Desks.defaults().optJSONObject(0);
        deskName = desk.optString("name");

        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        content = new LinearLayout(ctx);
        content.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        Set<String> used = new HashSet<>();
        switch (look) {
            case "night":
                buildNight();
                break;
            case "focus":
                buildFocus();
                break;
            case "dock":
                buildDock(desk, portrait, used);
                break;
            case "ribbon":
                buildRibbon(desks);
                break;
            default:
                buildDesk(content, desk, portrait, used, "bento".equals(look));
                break;
        }
        level = new LevelView(ctx);
        level.setAlpha(0f);
        addView(level, new LayoutParams(px(52), px(200), Gravity.CENTER_VERTICAL | Gravity.END));
        if (deskSwipe && !"dock".equals(look)) buildBar(di, !used.contains("calendar") && !used.contains("clock"));
        else if (deskSwipe) buildBar(di, false);

        applyPadding();
        applyNightWindow();
        updateNightText();
        sizeClock();
        tickClock();
        renderCalendar();
        renderMusic();
        renderMacros();
        renderPc();
        renderHome();
        renderWeather();
        buildLive();
        callsSig = "";
        renderCalls();
        renderStats();
    }

    /** Columns of blocks of one desk; "bento" puts every block on its own card. */
    private void buildDesk(LinearLayout into, JSONObject desk, boolean portrait, Set<String> used, boolean bento) {
        int card = th.surface;
        if (bento) th = th.lifted();
        JSONArray cols = desk.optJSONArray("cols");
        int ncols = cols == null ? 0 : cols.length();
        for (int i = 0; i < ncols; i++) {
            JSONObject col = cols.optJSONObject(i);
            if (col == null) continue;
            if (into.getChildCount() > 0) {
                View div = new View(ctx);
                LinearLayout.LayoutParams dl;
                if (bento) {
                    dl = portrait ? new LinearLayout.LayoutParams(-1, px(12)) : new LinearLayout.LayoutParams(px(12), -1);
                } else if (portrait) {
                    div.setBackgroundColor(th.line);
                    dl = new LinearLayout.LayoutParams(-1, Math.max(1, (int) dp));
                    dl.setMargins(0, px(14), 0, px(14));
                } else {
                    div.setBackgroundColor(th.line);
                    dl = new LinearLayout.LayoutParams(Math.max(1, (int) dp), -1);
                    dl.setMargins(px(22), px(8), px(22), px(8));
                }
                into.addView(div, dl);
            }
            LinearLayout colView = vertical();
            JSONArray blocks = col.optJSONArray("blocks");
            for (int j = 0; blocks != null && j < blocks.length(); j++) {
                JSONObject b = blocks.optJSONObject(j);
                if (b == null) continue;
                String t = b.optString("t");
                if (Desks.single(t) && !used.add(t)) continue;
                View v = buildBlock(t, b);
                if (v == null) continue;
                int h = b.optInt("h", Desks.defaultHeight(t));
                if (h <= 0 && Desks.needsHeight(t)) h = 1;
                if (bento && !"music".equals(t)) v = card(v, card, h > 0);
                LinearLayout.LayoutParams lp = h > 0 ? new LinearLayout.LayoutParams(-1, 0, h) : new LinearLayout.LayoutParams(-1, -2);
                if (colView.getChildCount() > 0) lp.topMargin = px(12);
                colView.addView(v, lp);
            }
            int w = Math.max(1, col.optInt("w", 50));
            into.addView(colView, portrait ? new LinearLayout.LayoutParams(-1, 0, w) : new LinearLayout.LayoutParams(0, -1, w));
        }
    }

    private View card(View v, int color, boolean fill) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(rounded(color, 22));
        f.setPadding(px(14), px(12), px(14), px(12));
        f.addView(v, new FrameLayout.LayoutParams(-1, fill ? -1 : -2));
        return f;
    }

    /** Block views of the previous desk; every render method skips blocks that aren't on screen. */
    private void forgetBlockViews() {
        musicRow = macroRow = allDayRow = null;
        musicCard = null;
        clock = dateText = hint = calTitle = calCount = calMode = emptyText = detail = mTitle = mArtist = mTime = pcStatus = null;
        fancy = null;
        art = artBg = playBtn = deviceBtn = null;
        mBar = null;
        allDayScroll = null;
        calScroll = null;
        timeline = null;
        monthView = null;
        barLabel = null;
        callPanel = null;
        statsPanel = null;
        live = null;
        homePanels.clear();
        hOwners.clear();
        weatherPanels.clear();
        weatherLine = null;
        dockTime = null;
        ribbon = null;
        focusPills = null;
    }

    private View buildBlock(String type, JSONObject b) {
        switch (type) {
            case "clock": return buildClock();
            case "music": return buildMusic();
            case "pc": return buildPc();
            case "calendar": return buildCalendar();
            case "home": return buildHome(b);
            case "calls": return buildCalls();
            case "stats": return buildStats();
            case "weather": return buildWeather();
            default: return null;
        }
    }

    private View buildClock() {
        return buildClock(false);
    }

    /** Centered: the focus and night looks, where the clock is alone in the middle of the screen. */
    private View buildClock(boolean centered) {
        LinearLayout clockBox = vertical();
        clockBox.setGravity(centered ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
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
        if (Weather.configured(prefs) && prefs.weatherClock() && !centered) {
            weatherLine = text(th.text, 15);
            weatherLine.setSingleLine();
            weatherLine.setEllipsize(TextUtils.TruncateAt.END);
            weatherLine.setCompoundDrawablePadding(px(8));
            weatherLine.setGravity(Gravity.CENTER_VERTICAL | (centered ? Gravity.CENTER_HORIZONTAL : Gravity.START));
            weatherLine.setVisibility(GONE);
            weatherLine.setOnClickListener(v -> openWeatherSheet());
            clockBox.addView(weatherLine, margins(-1, -2, 0, 6, 0, 0));
        }
        clockBox.addView(hint, margins(-1, -2, 0, 8, 0, 0));
        if (centered) {
            if (clock != null) clock.setGravity(Gravity.CENTER_HORIZONTAL);
            dateText.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setMaxLines(3);
        }

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
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if ("focus".equals(look) || "night".equals(look)) peek();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                openSettings();
            }
        });
        clockBox.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        clockBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) post(this::sizeClock);
        });
        hOwners.add(clockBox);
        return clockBox;
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
        hOwners.add(hs);
        box.addView(hs, margins(-1, -2, 0, 6, 0, 0));
        return box;
    }

    private View buildCalendar() {
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
        gear.setOnClickListener(v -> openMenu());
        header.addView(gear, new LinearLayout.LayoutParams(px(34), px(34)));
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));

        allDayScroll = new HorizontalScrollView(ctx);
        allDayScroll.setHorizontalScrollBarEnabled(false);
        allDayRow = new LinearLayout(ctx);
        allDayScroll.addView(allDayRow);
        allDayScroll.setVisibility(GONE);
        hOwners.add(allDayScroll);
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
        hOwners.add(monthView);
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
            countMusic();
            if (callPanel != null) callPanel.tick();
            slowTick++;
            if (prefs.phoneCalls() && slowTick % 2 == 0) pollPhoneCall();
            if (callPanel != null && slowTick % 5 == 0) checkClipboard();
            if (haveLux) evalLux();
            if (peeking && SystemClock.elapsedRealtime() > peekUntil && sheet == null && picker == null) {
                peeking = false;
                build();
            }
            renderLive();
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
        boolean night = isNight();
        if (night != nightActive) {
            nightChanged(night);
            build();
        } else if (edge == 0) {
            applyNightWindow();
        }
        updateNightText();
        dawnTick();
        if (timeline != null) timeline.invalidate();
        if (ribbon != null) ribbon.invalidate();
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
            // the night clock stays on for hours at the same place: it wanders further
            float k = "night".equals(look) ? 8 : 1;
            content.animate().translationX((rnd.nextFloat() * 10 - 5) * dp * k).translationY((rnd.nextFloat() * 8 - 4) * dp * k).setDuration(2000).start();
        }
        if (idle && !monthMode && shownDay.equals(now)) scrollToNow(true);
        renderStats();
    }

    private void tickClock() {
        LocalDateTime now = LocalDateTime.now();
        if (dockTime != null) dockTime.setText(now.format(DateTimeFormatter.ofPattern(prefs.h24() ? "H:mm" : "h:mm")));
        if (dateText == null) return;
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
        hint.setText("night".equals(look) ? nightText : nextHint());
    }

    /** Digital clock as big as its block allows. */
    private void sizeClock() {
        if (clock == null || "ribbon".equals(look)) return;
        View box = (View) clock.getParent();
        int w = getWidth(), h = getHeight(), colW = box.getWidth(), boxH = box.getHeight();
        if (w == 0 || colW == 0) return;
        boolean portrait = h > w;
        float ems = prefs.seconds() ? 3.15f : 2.55f;
        if (!prefs.h24()) ems += 0.2f;
        if (prefs.clockFont().equals("mono")) ems *= 1.2f;
        float size = Math.min(colW * 0.9f / ems, (portrait ? h * 0.45f : h) * (bigClock ? 0.4f : 0.25f));
        // leave room for the date and the next-event hint under the digits
        if (boxH > 0) size = Math.min(size, (boxH - px(weatherLine != null ? 106 : 70) - box.getPaddingTop() - box.getPaddingBottom()) / 1.1f);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(px(28), size));
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
        switch (prefs.nightTrigger()) {
            case "light":
                if (haveLux) return luxNight;
                break; // no light sensor (yet): by the clock
            case "sun": {
                Weather w = weather;
                Boolean dark = w == null ? null : w.dark(System.currentTimeMillis());
                if (dark != null) return dark;
                break;
            }
            default:
                break;
        }
        return inNightHours(minuteOfDay());
    }

    private boolean inNightHours(int m) {
        int from = prefs.nightStart(), to = prefs.nightEnd();
        if (from == to) return false;
        return from > to ? m >= from || m < to : m >= from && m < to;
    }

    private static int minuteOfDay() {
        LocalTime t = LocalTime.now();
        return t.getHour() * 60 + t.getMinute();
    }

    /** Night brightness at night; over the half hour before it the screen slides there from the day brightness. */
    private void applyNightWindow() {
        float b = prefs.brightness(), night = prefs.nightBright() / 100f;
        if (nightActive) {
            setWindowBrightness(night);
            return;
        }
        float f = fadeFactor();
        if (f < 1f) {
            float day = b >= 0 ? b : systemBrightness();
            setWindowBrightness(Math.min(day, night + (day - night) * f));
        } else {
            setWindowBrightness(b >= 0 ? b : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        }
    }

    /** 1 = day, falling to 0 over the 30 minutes before the night starts. */
    private float fadeFactor() {
        if (!prefs.nightMode() || !prefs.nightFade() || "light".equals(prefs.nightTrigger())) return 1f;
        long now = System.currentTimeMillis(), start = 0;
        Weather w = weather;
        Weather.Day d = w == null ? null : w.today();
        if ("sun".equals(prefs.nightTrigger()) && d != null && d.sunset > 0) {
            start = d.sunset + 30 * 60_000L;
        } else {
            start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + prefs.nightStart() * 60_000L;
        }
        long left = start - now;
        if (left < -12 * 3_600_000L) left += 86_400_000L; // a night that starts after midnight
        if (left <= 0 || left > 30 * 60_000L) return 1f;
        return left / (30 * 60_000f);
    }

    private float systemBrightness() {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    /** Under the night clock: the next alarm and the weather for the morning. */
    private void updateNightText() {
        if (!"night".equals(look)) return;
        StringBuilder sb = new StringBuilder();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo a = am == null ? null : am.getNextAlarmClock();
        if (a != null && a.getTriggerTime() - System.currentTimeMillis() < 20 * 3_600_000L) sb.append("Будильник ").append(fmt(a.getTriggerTime()));
        Weather w = weather;
        if (w != null) {
            String s = LocalTime.now().getHour() >= 12 ? w.morning() : null;
            if (s == null && !Double.isNaN(w.temp)) s = "Сейчас " + Weather.deg(w.temp) + ", " + Weather.text(w.code).toLowerCase(Locale.getDefault());
            if (s != null) sb.append(sb.length() > 0 ? "\n" : "").append(s);
        }
        nightText = sb.toString();
        if (hint != null) hint.setText(nightText);
    }

    /** Tap on the focus or night clock: the desks for a while, then back to the clock. */
    private void peek() {
        peeking = true;
        peekUntil = SystemClock.elapsedRealtime() + ("night".equals(look) ? 30_000 : 60_000);
        build();
        flashBarLabel("night".equals(look) ? "" : deskName);
    }

    private void startLightSensor() {
        haveLux = false;
        if (!prefs.nightMode() || !"light".equals(prefs.nightTrigger())) return;
        sensors = ctx.getSystemService(SensorManager.class);
        Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (s == null) {
            sensors = null;
            return;
        }
        sensors.registerListener(luxListener, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stopLightSensor() {
        if (sensors != null) sensors.unregisterListener(luxListener);
        sensors = null;
    }

    private final SensorEventListener luxListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            boolean first = !haveLux;
            lux = e.values[0];
            haveLux = true;
            if (first) {
                // the first reading decides at once: no waiting when the dashboard starts in a dark room
                luxNight = lux <= prefs.nightLux();
                if (isNight() != nightActive) {
                    nightChanged(isNight());
                    build();
                }
            }
            evalLux();
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {}
    };

    /** Dark for 45 s turns the night on, light for 8 s turns it off: a passing shadow or a phone screen don't. */
    private void evalLux() {
        long now = SystemClock.elapsedRealtime();
        int thr = prefs.nightLux();
        if (lux <= thr) {
            brightSince = 0;
            if (darkSince == 0) darkSince = now;
        } else if (lux > thr * 3 + 3) {
            darkSince = 0;
            if (brightSince == 0) brightSince = now;
        } else {
            darkSince = brightSince = 0;
        }
        boolean want = luxNight;
        if (!luxNight && darkSince > 0 && now - darkSince > 45_000) want = true;
        if (luxNight && brightSince > 0 && now - brightSince > 8_000) want = false;
        if (want != luxNight) {
            luxNight = want;
            if (isNight() != nightActive) {
                nightChanged(isNight());
                build();
            }
        }
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

    private void onDown(MotionEvent e) {
        edge = edgeAt(e.getX());
        edgeActive = false;
        edgeY0 = e.getY();
        downX = e.getX();
        downY = e.getY();
        swiping = false;
        swipeOk = deskSwipe && deskCount > 1 && edge == 0 && !inHorizontalOwner(e.getRawX(), e.getRawY());
        if (peeking) peekUntil = Math.max(peekUntil, SystemClock.elapsedRealtime() + 30_000);
    }

    /** Clock, month grid and horizontal scrollers handle sideways swipes themselves. */
    private boolean inHorizontalOwner(float rx, float ry) {
        int[] loc = new int[2];
        for (View v : hOwners) {
            if (!v.isShown()) continue;
            v.getLocationOnScreen(loc);
            if (rx >= loc[0] && rx < loc[0] + v.getWidth() && ry >= loc[1] && ry < loc[1] + v.getHeight()) return true;
        }
        return false;
    }

    private boolean deskSwipeStarted(MotionEvent e) {
        float dx = e.getX() - downX, dy = e.getY() - downY;
        return swipeOk && Math.abs(dx) > 24 * dp && Math.abs(dx) > 1.6f * Math.abs(dy);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (picker != null || sheet != null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (edge != 0 && !edgeActive && Math.abs(e.getY() - edgeY0) > 10 * dp) {
                    startEdge(e.getY());
                    return true;
                }
                if (!swiping && deskSwipeStarted(e)) {
                    swiping = true;
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
        if (swiping || e.getActionMasked() == MotionEvent.ACTION_MOVE && edge == 0 && deskSwipeStarted(e)) {
            swiping = true;
            float dx = e.getX() - downX;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (content != null) content.setTranslationX(dx * 0.35f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    swiping = false;
                    int dir = dx < 0 ? 1 : -1;
                    int target = curDesk + dir;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(dx) > 70 * dp && target >= 0 && target < deskCount) {
                        goDesk(target, dir);
                    } else if (content != null) {
                        content.animate().translationX(0).setDuration(180).start();
                    }
                    return true;
                default:
                    return true;
            }
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(e);
                return edge != 0 || swipeOk;
            case MotionEvent.ACTION_MOVE:
                if (edge == 0) return swipeOk;
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
            f = Math.max(nightActive ? 0.01f : 0.05f, f);
            setWindowBrightness(f);
            level.set(R.drawable.ic_brightness, f);
        }
        edgeValue = f;
    }

    private void endEdge() {
        edgeActive = false;
        if (edge == 2 && nightActive) prefs.raw().edit().putInt("night_bright", Math.max(1, Math.min(40, Math.round(edgeValue * 100)))).apply();
        else if (edge == 2) prefs.setBrightness(edgeValue);
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
            List<CalEvent> strip = null;
            if ("ribbon".equals(look)) {
                strip = new ArrayList<>(todayList);
                if (LocalTime.now().getHour() >= 12) strip.addAll(CalendarRepo.load(ctx, prefs, today.plusDays(1)));
            }
            List<CalEvent> stripNow = strip;
            List<CalEvent> view;
            if (month) {
                LocalDate gs = MonthView.gridStart(ym);
                view = CalendarRepo.load(ctx, prefs, gs, gs.plusDays(42));
            } else {
                view = day.equals(today) ? todayList : CalendarRepo.load(ctx, prefs, day);
            }
            ui.post(() -> {
                todayEvents = todayList;
                if (stripNow != null) {
                    ribbonEvents = stripNow;
                    if (ribbon != null) ribbon.setData(ribbonEvents, weather);
                }
                renderCalls();
                renderStats();
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
            emptyText.setOnClickListener(v -> openSettings());
            emptyText.setVisibility(VISIBLE);
        } else if ("ics".equals(src) && prefs.icsUrls().trim().isEmpty()) {
            emptyText.setText("Добавь ICS-ссылку календаря\nв настройках");
            emptyText.setOnClickListener(v -> openSettings());
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
            String feats = info != null ? info.features : pcFeatures;
            PcClient.Calls calls = null;
            if (feats.contains("calls") && (callPanel != null || prefs.live())) {
                try {
                    calls = c.calls();
                } catch (Exception ignored) {
                    // a hiccup: keep the last state
                }
            }
            JSONObject day = null;
            if (feats.contains("day") && statsPanel != null && System.currentTimeMillis() - pcDayAt > 60_000) {
                try {
                    day = c.day();
                    pcDayAt = System.currentTimeMillis();
                } catch (Exception ignored) {
                    // try again next minute
                }
            }
            PcClient.Calls callsNow = calls;
            JSONObject dayNow = day;
            ui.post(() -> {
                pcFeatures = feats;
                if (callsNow != null) pcCalls = callsNow;
                if (dayNow != null) {
                    pcDay = dayNow;
                    renderStats();
                }
                renderCalls();
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
                    pcCalls = null;
                    renderMacros();
                    renderMusic();
                    renderCalls();
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
            openSettings();
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

    private void loadMacroCache() {
        macros = new ArrayList<>();
        macrosJson = "";
        try {
            JSONArray cached = new JSONArray(prefs.pcMacros());
            macros = PcClient.parseMacros(cached);
            macrosJson = cached.toString();
        } catch (Exception ignored) {
            // no cache yet
        }
    }

    // ---------------------------------------------------------------- smart home

    private View buildHome(JSONObject b) {
        HomePanel p = new HomePanel(ctx, th, b.optString("title"), Desks.items(b), new HomePanel.Listener() {
            @Override
            public void onTap(HomeDevice d) {
                homeTap(d);
            }

            @Override
            public void onLong(HomeDevice d) {
                openHomeSheet(d);
            }

            @Override
            public void onSetup() {
                host.open(SmartHome.configured(prefs) ? DeskEditorActivity.class : SettingsActivity.class);
            }

            @Override
            public Bitmap image(HomeDevice d) {
                return homeImage(d);
            }

            @Override
            public void onAllOff(List<HomeDevice> on) {
                for (HomeDevice d : on) homeAction(d, x -> x.on = false, () -> home.setOn(d, false));
                Toast.makeText(ctx, "Выключаю: " + on.size(), Toast.LENGTH_SHORT).show();
            }
        });
        homePanels.add(p);
        return p;
    }

    private void renderHome() {
        boolean on = SmartHome.configured(prefs);
        String err = home.error();
        for (HomePanel p : homePanels) p.render(home.devices(), on, err);
        String ev = Bambu.recentEvent(bambuEventSeen);
        if (ev != null) {
            bambuEventSeen = System.currentTimeMillis();
            flashLive(ev);
        }
    }

    /** Camera and printer pictures: Bambu frames straight from the printer, others over http every few seconds. */
    private Bitmap homeImage(HomeDevice d) {
        if (d.image.startsWith("bambu:")) return Bambu.frame(d.image.substring(6));
        String key = d.id, url = d.image;
        Long at = picAt.get(key);
        long now = System.currentTimeMillis();
        if (at == null || now - at > 4000) {
            picAt.put(key, now);
            io.execute(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(6000);
                    try (InputStream in = c.getInputStream()) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        Bitmap b = BitmapFactory.decodeStream(in, null, o);
                        if (b != null) ui.post(() -> pics.put(key, b));
                    } finally {
                        c.disconnect();
                    }
                } catch (Exception ignored) {
                    // offline camera: keep the last picture
                }
            });
        }
        return pics.get(key);
    }

    private final Runnable homePoll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pollHome(false);
            ui.postDelayed(this, 5000);
        }
    };

    private void pollHome(boolean forceYandex) {
        boolean auto = !prefs.autoCall().isEmpty() || !prefs.autoNight().isEmpty() || !prefs.autoDawn().isEmpty();
        if (homePanels.isEmpty() && !prefs.live() && !auto || !SmartHome.configured(prefs) || !homeBusy.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                home.poll(forceYandex);
            } finally {
                homeBusy.set(false);
            }
            ui.post(this::renderHome);
        });
    }

    private final Runnable homeRecheck = () -> pollHome(true);

    /** Tap on a tile: toggle, run a scene; sensors, locks and thermostats open the detail card instead. */
    private void homeTap(HomeDevice d) {
        if (!d.available) return;
        if (d.kind == HomeDevice.SCENE) {
            homeAction(d, null, () -> home.runScene(d));
            Toast.makeText(ctx, "Запускаю «" + d.name + "»", Toast.LENGTH_SHORT).show();
        } else if (d.canToggle && d.kind != HomeDevice.LOCK && d.kind != HomeDevice.CLIMATE) {
            boolean on = !d.on;
            homeAction(d, x -> x.on = on, () -> home.setOn(d, on));
        } else {
            openHomeSheet(d);
        }
    }

    private interface HomeCall {
        String run();
    }

    /** Shows the expected result at once, sends the command, then re-reads the real state. */
    private void homeAction(HomeDevice d, SmartHome.Change optimistic, HomeCall call) {
        DayStats.add(ctx, "home", 1);
        homeSend(d, optimistic, call);
    }

    /** The same without counting a tap: what automations send. */
    private void homeSend(HomeDevice d, SmartHome.Change optimistic, HomeCall call) {
        if (optimistic != null) {
            home.local(d.id, optimistic);
            renderHome();
        }
        io.execute(() -> {
            String err = call.run();
            ui.post(() -> {
                if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show();
                ui.removeCallbacks(homeRecheck);
                ui.postDelayed(homeRecheck, err == null ? 1500 : 100);
            });
        });
    }

    private void openHomeSheet(HomeDevice d) {
        View card = HomeSheet.build(ctx, th, d, new HomeSheet.Actions() {
            @Override
            public void setOn(boolean on) {
                homeAction(d, x -> x.on = on, () -> home.setOn(d, on));
                closeSheet();
            }

            @Override
            public void run() {
                homeAction(d, null, () -> home.runScene(d));
                closeSheet();
            }

            @Override
            public void brightness(int pct) {
                homeAction(d, x -> {
                    x.brightness = pct;
                    x.on = true;
                }, () -> home.brightness(d, pct));
                touched();
            }

            @Override
            public void colorTemp(int kelvin) {
                homeAction(d, x -> {
                    x.tempK = kelvin;
                    x.on = true;
                }, () -> home.colorTemp(d, kelvin));
                touched();
            }

            @Override
            public void color(int rgb) {
                homeAction(d, x -> x.on = true, () -> home.color(d, rgb));
                touched();
            }

            @Override
            public void temperature(double t) {
                homeAction(d, x -> x.target = t, () -> home.temperature(d, t));
                touched();
            }

            @Override
            public void command(String action) {
                homeAction(d, null, () -> home.command(d, action));
                closeSheet();
            }

            @Override
            public Bitmap image(HomeDevice x) {
                return homeImage(x);
            }

            @Override
            public void touched() {
                ui.removeCallbacks(autoCloseSheet);
                ui.postDelayed(autoCloseSheet, 30_000);
            }
        });
        openSheet(card);
    }

    // ---------------------------------------------------------------- calls

    private View buildCalls() {
        callPanel = new CallPanel(ctx, th, new CallPanel.Listener() {
            @Override
            public void onJoin(Meetings.Meeting m, boolean onPc) {
                joinMeeting(m, onPc);
            }

            @Override
            public void onCallAction(PcClient.Call c, String action) {
                callAction(c, action);
            }

            @Override
            public void onScreen() {
                host.open(StreamActivity.class);
            }

            @Override
            public void onSetup() {
                openSettings();
            }

            @Override
            public void onPhone(PhoneCalls.Call c, String action) {
                String err = PhoneCalls.act(ctx, c, action);
                if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show();
                ui.postDelayed(() -> {
                    phoneCall = PhoneCalls.current(ctx);
                    callsSig = "";
                    renderCalls();
                }, 600);
            }

            @Override
            public void onQuick() {
                openQuickJoin();
            }
        });
        return callPanel;
    }

    /** Re-draws the calls block only when something changed; timers tick on their own. */
    private void renderCalls() {
        if (callPanel == null) return;
        List<Meetings.Meeting> today = Meetings.all(todayEvents);
        PcClient.Calls live = pcOnline ? pcCalls : null;
        boolean stream = pcOnline && pcFeatures.contains("stream");
        StringBuilder sig = new StringBuilder().append(pcOnline).append(stream);
        long now = System.currentTimeMillis();
        for (Meetings.Meeting m : today) {
            sig.append('|').append(m.event.start).append(m.event.title).append(m.event.end <= now)
                    .append(m.event.start - now <= prefs.callRemind() * 60_000L);
        }
        if (live != null) {
            sig.append('#').append(live.micMuted);
            for (PcClient.Call c : live.calls) sig.append(c.id).append(c.camera).append(c.title).append(c.since);
        }
        PhoneCalls.Call ph = phoneCall;
        boolean mic = ph != null && PhoneCalls.micMuted(ctx), spk = ph != null && PhoneCalls.speaker(ctx);
        if (ph != null) sig.append('@').append(ph.key).append(ph.ringing).append(ph.title).append(mic).append(spk).append(ph.mute != null);
        if (clipLink != null) sig.append('^').append(clipLink.url);
        if (sig.toString().equals(callsSig)) return;
        callsSig = sig.toString();
        callPanel.render(today, live, pcOnline, stream, prefs.callRemind(), ph, mic, spk, clipLink);
    }

    private void joinMeeting(Meetings.Meeting m, boolean onPc) {
        if (m == clipLink) {
            rememberLink(m);
            clipLink = null;
            callsSig = "";
            renderCalls();
        }
        PcClient c = pc;
        if (onPc && c != null && pcOnline) {
            Toast.makeText(ctx, "Открываю «" + m.event.title + "» на ПК", Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                try {
                    c.open(m.url);
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(ctx, "ПК: " + SmartHome.msg(e), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "Нет приложения для " + m.platform.name, Toast.LENGTH_SHORT).show();
        }
    }

    private void callAction(PcClient.Call call, String action) {
        PcClient c = pc;
        if (c == null) return;
        PcClient.Calls cur = pcCalls;
        if (cur != null && (action.equals("mute") || action.equals("unmute"))) {
            cur.micMuted = action.equals("mute");
            callsSig = "";
            renderCalls();
        }
        io.execute(() -> {
            try {
                String note = c.callAction(action, call == null ? "" : call.id);
                ui.post(() -> {
                    if (!note.isEmpty()) Toast.makeText(ctx, note, Toast.LENGTH_SHORT).show();
                    ui.removeCallbacks(pcPoll);
                    ui.postDelayed(pcPoll, 700);
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(ctx, SmartHome.msg(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ---------------------------------------------------------------- day stats

    private View buildStats() {
        statsPanel = new StatsPanel(ctx, th, new StatsPanel.Listener() {
            @Override
            public void onDetails() {
                openDayDetails();
            }

            @Override
            public void onSetup() {
                openSettings();
            }
        });
        pcDayAt = 0;
        return statsPanel;
    }

    private JSONObject phoneDay() {
        JSONObject o = DayStats.today(ctx);
        try {
            o.put("music_sec", o.optInt("music_sec") + musicSec).put("tracks", o.optInt("tracks") + musicTracks);
        } catch (Exception ignored) {
            // numbers only
        }
        return o;
    }

    private void renderStats() {
        if (statsPanel == null) return;
        int n = 0;
        for (CalEvent e : todayEvents) if (!e.allDay) n++;
        statsPanel.render(pcOnline || pcDay != null ? pcDay : null, !prefs.pcHost().isEmpty(), phoneDay(),
                Meetings.all(todayEvents), n);
    }

    /** The day in words: PC time, every call with its time and service, music. */
    private void openDayDetails() {
        LinearLayout m = menu("Сегодня");
        JSONObject d = pcDay;
        if (d != null) {
            dayLine(m, "За компьютером", HomeDevice.minutes(d.optInt("active_min"))
                    + (d.optString("first").isEmpty() ? "" : " · с " + d.optString("first") + " до " + d.optString("last")));
            JSONArray calls = d.optJSONArray("calls");
            if (calls != null && calls.length() > 0) {
                dayLine(m, "Созвоны на ПК", HomeDevice.minutes(d.optInt("call_min")));
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject c = calls.optJSONObject(i);
                    String t = c.optString("title");
                    dayLine(m, "   " + c.optString("start") + "–" + c.optString("end") + "  " + c.optString("name"),
                            (t.isEmpty() ? "" : t + " · ") + HomeDevice.minutes(Math.max(1, c.optInt("min"))));
                }
            }
            JSONArray apps = d.optJSONArray("apps");
            for (int i = 0; apps != null && i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                dayLine(m, i == 0 ? "Приложения" : "", a.optString("name") + " · " + HomeDevice.minutes(a.optInt("min")));
            }
        } else {
            dayLine(m, "Компьютер", prefs.pcHost().isEmpty() ? "не подключён" : "нет данных (нужна свежая версия DeskDash.exe)");
        }
        List<Meetings.Meeting> meet = Meetings.all(todayEvents);
        if (!meet.isEmpty()) {
            dayLine(m, "Созвоны в календаре", String.valueOf(meet.size()));
            for (Meetings.Meeting x : meet) dayLine(m, "   " + fmt(x.event.start) + "  " + x.platform.name, x.event.title);
        }
        JSONObject ph = phoneDay();
        if (ph.optInt("music_sec") > 0) {
            dayLine(m, "Музыка", HomeDevice.minutes(ph.optInt("music_sec") / 60) + " · "
                    + StatsPanel.plural(ph.optInt("tracks"), "трек", "трека", "треков"));
        }
        if (ph.optInt("home") > 0) dayLine(m, "Умный дом", StatsPanel.plural(ph.optInt("home"), "нажатие", "нажатия", "нажатий"));
        openSheet(m);
    }

    private void dayLine(LinearLayout list, String label, String value) {
        LinearLayout r = new LinearLayout(ctx);
        TextView l = text(th.dim, 13);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(th.text, 13);
        v.setText(value);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1.4f));
        list.addView(r, margins(-1, -2, 0, 3, 0, 3));
    }

    private void countMusic() {
        boolean playing;
        String track;
        if (media != null) {
            PlaybackState st = media.getPlaybackState();
            MediaMetadata md = media.getMetadata();
            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            track = md == null ? "" : String.valueOf(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        } else {
            PcClient.SpState sp = spState;
            playing = sp != null && sp.hasTrack && sp.playing;
            track = sp == null ? "" : sp.title;
        }
        if (playing) {
            musicSec++;
            if (!track.equals(musicTrack)) {
                musicTrack = track;
                musicTracks++;
            }
        }
        if (++musicTick >= 60) flushMusic();
    }

    private void flushMusic() {
        musicTick = 0;
        if (musicSec > 0) DayStats.add(ctx, "music_sec", musicSec);
        if (musicTracks > 0) DayStats.add(ctx, "tracks", musicTracks);
        musicSec = musicTracks = 0;
    }

    // ---------------------------------------------------------------- live strip

    /** Pill at the top: a call on the PC, a call about to start, prints and washes running, alarms. */
    private void buildLive() {
        if (!prefs.live()) return;
        live = new LinearLayout(ctx);
        live.setGravity(Gravity.CENTER_VERTICAL);
        live.setPadding(px(12), px(5), px(14), px(5));
        live.setElevation(6 * dp);
        live.setVisibility(GONE);
        liveDot = new View(ctx);
        live.addView(liveDot, margins(px(8), px(8), 0, 0, 8, 0));
        liveText = text(th.text, 13);
        liveText.setSingleLine();
        liveText.setEllipsize(TextUtils.TruncateAt.END);
        liveText.setMaxWidth(px(460));
        live.addView(liveText);
        live.setOnClickListener(v -> {
            if (liveFlash != null) {
                liveFlashUntil = 0;
                renderLive();
            } else if (liveAction != null) {
                liveAction.run();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = cutT + px(6);
        addView(live, lp);
    }

    private void flashLive(String msg) {
        liveFlash = msg;
        liveFlashUntil = System.currentTimeMillis() + 60_000;
        renderLive();
    }

    private void renderLive() {
        if (live == null) return;
        long now = System.currentTimeMillis();
        String text = null;
        int color = th.accent;
        boolean pulse = false;
        Runnable action = null;
        if (liveFlash != null && now < liveFlashUntil) {
            text = liveFlash;
        } else {
            liveFlash = null;
        }
        List<HomeDevice> busy = new ArrayList<>();
        HomeDevice alarm = null;
        for (HomeDevice d : home.devices().values()) {
            if (d.alert && alarm == null) alarm = d;
            if (d.busy()) busy.add(d);
        }
        PcClient.Calls calls = pcOnline ? pcCalls : null;
        PhoneCalls.Call ph = phoneCall;
        if (text == null && alarm != null) {
            HomeDevice a = alarm;
            text = a.name + ": " + a.status();
            color = 0xFFE5534B;
            pulse = true;
            action = () -> openHomeSheet(a);
        }
        if (text == null && ph != null) {
            String who = ph.title.isEmpty() ? ph.app : ph.title;
            text = ph.ringing ? "Входящий · " + who + " · " + ph.app
                    : who + " · " + CallPanel.clock((now - ph.since) / 1000) + (PhoneCalls.micMuted(ctx) ? " · микрофон выкл" : "");
            color = ph.color;
            pulse = true;
            action = () -> PhoneCalls.act(ctx, ph, "open");
        }
        if (text == null && calls != null && !calls.calls.isEmpty()) {
            PcClient.Call c = calls.calls.get(0);
            text = c.name + " · " + CallPanel.clock((now - c.since) / 1000)
                    + (calls.micMuted != null && calls.micMuted ? " · микрофон выкл" : "");
            color = c.color;
            pulse = true;
            action = pcFeatures.contains("stream") ? () -> host.open(StreamActivity.class) : () -> callAction(c, "show");
        }
        if (text == null) {
            for (Meetings.Meeting m : Meetings.upcoming(todayEvents, now)) {
                long till = m.event.start - now;
                if (till > prefs.callRemind() * 60_000L || now - m.event.start > 10 * 60_000L) continue;
                text = m.event.title + " · " + CallPanel.countdown(m, now) + " · подключиться";
                color = m.platform.color;
                pulse = till <= 60_000;
                action = () -> joinMeeting(m, pcOnline && "pc".equals(prefs.callJoin()));
                if (till > 0 && reminded.add(m.event.start + m.url) && prefs.callSound() && !nightActive) chime();
                break;
            }
        }
        if (text == null && !busy.isEmpty() && !nightActive) {
            HomeDevice d = busy.get((int) (now / 6000 % busy.size()));
            text = d.name + " · " + d.status() + (d.remainingMin > 0 ? " · до " + HomeDevice.finishAt(d.remainingMin) : "");
            action = () -> openHomeSheet(d);
        }
        Weather w = weather;
        String rain = text == null && w != null && prefs.weatherRain() && !nightActive ? w.precipitationSoon(now) : null;
        if (rain != null && !rain.equals(rainHidden)) {
            text = rain;
            color = 0xFF5AA7E0;
            action = () -> {
                rainHidden = rain;
                renderLive();
            };
        }
        if (text == null) {
            if (live.getVisibility() == VISIBLE) {
                live.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    live.setVisibility(GONE);
                    applyPadding();
                }).start();
            }
            return;
        }
        liveAction = action;
        liveText.setText(text);
        live.setBackground(rounded(DashTheme.blend(th.surface, color, 0.18f), 16));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color | 0xFF000000);
        liveDot.setBackground(g);
        liveDot.setAlpha(pulse && now / 1000 % 2 == 0 ? 0.35f : 1f);
        if (live.getVisibility() != VISIBLE) {
            live.setAlpha(0f);
            live.setVisibility(VISIBLE);
            live.animate().alpha(1f).setDuration(200).start();
            applyPadding();
        }
    }

    // ---------------------------------------------------------------- sheet (detail cards and menus)

    private void openSheet(View body) {
        if (sheet != null) removeView(sheet);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        sheet = new FrameLayout(ctx);
        sheet.setBackgroundColor(0x99000000);
        sheet.setOnClickListener(v -> closeSheet());

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClickable(true);
        sv.setPadding(px(20), px(18), px(20), px(14));
        GradientDrawable bg = rounded(th.surface, 22);
        bg.setStroke(Math.max(1, (int) dp), th.line);
        sv.setBackground(bg);
        sv.setElevation(12 * dp);
        sv.addView(body);

        int w = Math.min(px(420), (int) (getWidth() * 0.6f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2, Gravity.CENTER);
        lp.setMargins(0, px(16) + cutT, 0, px(16) + cutB);
        sheet.addView(sv, lp);
        sheet.setAlpha(0f);
        addView(sheet, new LayoutParams(-1, -1));
        sheet.animate().alpha(1f).setDuration(160).start();
        ui.removeCallbacks(autoCloseSheet);
        ui.postDelayed(autoCloseSheet, 30_000);
    }

    private final Runnable autoCloseSheet = this::closeSheet;

    private void closeSheet() {
        ui.removeCallbacks(autoCloseSheet);
        if (sheet == null) return;
        View s = sheet;
        sheet = null;
        s.animate().alpha(0f).setDuration(140).withEndAction(() -> removeView(s)).start();
    }

    /** A list of tappable rows for menus. */
    private LinearLayout menu(String title) {
        LinearLayout list = vertical();
        TextView t = text(th.text, 18);
        t.setText(title);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        list.addView(t, margins(-1, -2, 0, 0, 0, 10));
        return list;
    }

    private void menuRow(LinearLayout list, String label, boolean selected, Runnable action) {
        TextView r = text(selected ? th.accent : th.text, 15);
        r.setText(label);
        r.setPadding(px(14), px(12), px(14), px(12));
        r.setBackground(rounded(selected ? DashTheme.blend(th.surface, th.accent, 0.22f) : th.bg, 14));
        r.setOnClickListener(v -> action.run());
        list.addView(r, margins(-1, -2, 0, 0, 0, 6));
    }

    private void openSettings() {
        host.open(SettingsActivity.class);
    }

    private void openMenu() {
        LinearLayout m = menu("DeskDash");
        if (pcOnline && pcFeatures.contains("stream")) menuRow(m, "Экран ПК на телефоне", false, () -> {
            closeSheet();
            host.open(StreamActivity.class);
        });
        menuRow(m, "Статистика дня", false, this::openDayDetails);
        if (Weather.configured(prefs)) menuRow(m, "Погода", false, this::openWeatherSheet);
        menuRow(m, "Вид: " + lookName(prefs.uiStyle()) + "…", false, this::openLooks);
        menuRow(m, "Столы и блоки…", false, () -> {
            closeSheet();
            host.open(DeskEditorActivity.class);
        });
        List<Profiles.Profile> ps = Profiles.list(ctx);
        if (ps.size() > 1) menuRow(m, "Профиль: " + Profiles.name(ctx, Profiles.active(ctx)) + "…", false, this::openProfiles);
        menuRow(m, "Настройки", false, () -> {
            closeSheet();
            openSettings();
        });
        openSheet(m);
    }

    private void openProfiles() {
        LinearLayout m = menu("Кто за столом?");
        String act = Profiles.active(ctx);
        for (Profiles.Profile p : Profiles.list(ctx)) {
            menuRow(m, p.name, p.id.equals(act), () -> switchProfile(p.id));
        }
        menuRow(m, "Добавить или изменить профили…", false, () -> {
            closeSheet();
            openSettings();
        });
        openSheet(m);
    }

    /** Everything belongs to the profile: rebuild with its settings, PC and smart home. */
    private void switchProfile(String id) {
        closeSheet();
        if (id.equals(Profiles.active(ctx))) return;
        boolean was = running;
        stop();
        Profiles.setActive(ctx, id);
        loadMacroCache();
        pc = null;
        pcStats = null;
        spState = null;
        pcSpotify = false;
        pcOnline = false;
        pcFails = 0;
        pcInfoAt = 0;
        pcName = "";
        home.clear();
        build();
        if (was) start();
        flashBarLabel(Profiles.name(ctx, id));
    }

    // ---------------------------------------------------------------- desk bar

    /** Pill at the bottom: profile name, one dot per desk, menu button. */
    private void buildBar(int di, boolean noOtherMenu) {
        List<Profiles.Profile> ps = Profiles.list(ctx);
        hasBar = !(deskCount < 2 && ps.size() < 2 && !noOtherMenu);
        if (!hasBar) return;
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(10), 0, px(2), 0);
        bar.setBackground(rounded(th.surface, 14));
        bar.setAlpha(0.9f);
        bar.setOnLongClickListener(v -> {
            openMenu();
            return true;
        });
        if (ps.size() > 1) {
            TextView who = text(th.dim, 12);
            who.setText(Profiles.name(ctx, Profiles.active(ctx)));
            who.setSingleLine();
            who.setPadding(px(2), px(6), px(8), px(6));
            who.setOnClickListener(v -> openProfiles());
            bar.addView(who);
        }
        if (deskCount > 1) {
            for (int i = 0; i < deskCount; i++) {
                FrameLayout hit = new FrameLayout(ctx);
                View dot = new View(ctx);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(i == di ? th.accent : DashTheme.blend(th.line, th.dim, 0.5f));
                dot.setBackground(g);
                hit.addView(dot, new FrameLayout.LayoutParams(px(7), px(7), Gravity.CENTER));
                int idx = i;
                hit.setOnClickListener(v -> goDesk(idx, idx > curDesk ? 1 : -1));
                bar.addView(hit, new LinearLayout.LayoutParams(px(18), px(26)));
            }
        }
        barLabel = text(th.text, 12);
        barLabel.setSingleLine();
        barLabel.setVisibility(GONE);
        barLabel.setPadding(px(6), 0, px(2), 0);
        bar.addView(barLabel);
        ImageView more = iconButton(R.drawable.ic_more, th.dim, 26);
        more.setOnClickListener(v -> openMenu());
        bar.addView(more, new LinearLayout.LayoutParams(px(26), px(26)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = cutB + px(4);
        addView(bar, lp);
    }

    private void goDesk(int index, int dir) {
        if (index == curDesk || index < 0 || index >= deskCount) return;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        prefs.setDeskIndex(index);
        build();
        if (content != null) {
            content.setTranslationX(dir * getWidth() * 0.25f);
            content.setAlpha(0f);
            content.animate().translationX(0).alpha(1f).setDuration(220).start();
        }
        flashBarLabel(deskName);
        ui.removeCallbacks(homeRecheck);
        ui.post(homeRecheck);
    }

    private final Runnable hideBarLabel = () -> {
        if (barLabel != null) barLabel.setVisibility(GONE);
    };

    private void flashBarLabel(String s) {
        if (s.isEmpty()) return;
        if (barLabel == null) {
            Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
            return;
        }
        barLabel.setText(s);
        barLabel.setVisibility(VISIBLE);
        ui.removeCallbacks(hideBarLabel);
        ui.postDelayed(hideBarLabel, 1800);
    }

    // ---------------------------------------------------------------- weather

    private View buildWeather() {
        WeatherPanel p = new WeatherPanel(ctx, th, nightActive, new WeatherPanel.Listener() {
            @Override
            public void onTap() {
                weatherTryAt = 0;
                fetchWeather(true);
            }

            @Override
            public void onSetup() {
                openSettings();
            }
        });
        hOwners.add(p.hourStrip());
        weatherPanels.add(p);
        return p;
    }

    private final Runnable weatherPoll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            fetchWeather(false);
            ui.postDelayed(this, 60_000);
        }
    };

    /** Every 15 minutes (a failed try waits 5); forced by a tap on the weather block. */
    private void fetchWeather(boolean force) {
        if (!Weather.configured(prefs)) return;
        Weather w = weather;
        long now = System.currentTimeMillis();
        if (!force && (w != null && w.fresh() || now - weatherTryAt < 5 * 60_000L)) return;
        weatherTryAt = now;
        if (force) Toast.makeText(ctx, "Обновляю погоду…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                Weather fresh = Weather.fetch(prefs);
                ui.post(() -> {
                    weather = fresh;
                    renderWeather();
                    updateNightText();
                    if (isNight() != nightActive) {
                        nightChanged(isNight());
                        build();
                    }
                });
            } catch (Exception e) {
                if (force) ui.post(() -> Toast.makeText(ctx, "Погода: " + SmartHome.msg(e), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderWeather() {
        Weather w = weather;
        String place = Weather.place(prefs);
        for (WeatherPanel p : weatherPanels) p.render(w, place);
        if (weatherLine != null) {
            if (w == null || Double.isNaN(w.temp)) {
                weatherLine.setVisibility(GONE);
            } else {
                StringBuilder sb = new StringBuilder(Weather.deg(w.temp)).append("  ").append(Weather.text(w.code));
                Weather.Day d = w.today();
                if (d != null) sb.append("  ·  ").append(Weather.deg(d.max)).append(" / ").append(Weather.deg(d.min));
                weatherLine.setText(sb);
                weatherLine.setCompoundDrawablesRelative(WeatherPanel.drawable(ctx, th, nightActive, w.code, w.isDay, px(22)), null, null, null);
                weatherLine.setVisibility(VISIBLE);
            }
        }
        if (ribbon != null) ribbon.setData(ribbonEvents, w);
        renderFocusPills();
    }

    private void openWeatherSheet() {
        WeatherPanel p = new WeatherPanel(ctx, th, nightActive, new WeatherPanel.Listener() {
            @Override
            public void onTap() {
                closeSheet();
            }

            @Override
            public void onSetup() {
                closeSheet();
                openSettings();
            }
        });
        p.render(weather, Weather.place(prefs));
        openSheet(p);
        fetchWeather(false);
    }

    // ---------------------------------------------------------------- looks: focus, night clock, dock, ribbon

    static final String[][] LOOKS = {
            {"classic", "Колонки"}, {"bento", "Плитки"}, {"focus", "Фокус"}, {"dock", "Док"}, {"ribbon", "Лента дня"},
    };

    static String lookName(String id) {
        for (String[] l : LOOKS) if (l[0].equals(id)) return l[1];
        return LOOKS[0][1];
    }

    private void openLooks() {
        LinearLayout m = menu("Вид экрана");
        String cur = prefs.uiStyle();
        for (String[] l : LOOKS) {
            menuRow(m, l[1], l[0].equals(cur), () -> {
                closeSheet();
                prefs.setUiStyle(l[0]);
                peeking = false;
                build();
            });
        }
        openSheet(m);
    }

    /** A big clock in the middle, the weather and the music under it; a tap shows the desks for a minute. */
    private void buildFocus() {
        bigClock = true;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.addView(buildClock(true), new LinearLayout.LayoutParams(-1, 0, 1));
        focusPills = new LinearLayout(ctx);
        focusPills.setGravity(Gravity.CENTER);
        content.addView(focusPills, margins(-1, -2, 0, 8, 0, 8));
        content.setOnClickListener(v -> peek());
    }

    private void renderFocusPills() {
        if (focusPills == null) return;
        focusPills.removeAllViews();
        Weather w = weather;
        if (w != null && !Double.isNaN(w.temp)) {
            TextView p = pill(Weather.deg(w.temp) + "  " + Weather.text(w.code), v -> openWeatherSheet());
            p.setCompoundDrawablesRelative(WeatherPanel.drawable(ctx, th, nightActive, w.code, w.isDay, px(20)), null, null, null);
            p.setCompoundDrawablePadding(px(8));
            focusPills.addView(p, margins(-2, -2, 5, 0, 5, 0));
        }
        MediaMetadata md = media == null ? null : media.getMetadata();
        PcClient.SpState sp = spState;
        String track = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : sp != null && sp.hasTrack ? sp.title : null;
        if (track != null && !track.isEmpty()) {
            PlaybackState st = media == null ? null : media.getPlaybackState();
            boolean playing = st != null ? st.getState() == PlaybackState.STATE_PLAYING : sp != null && sp.playing;
            focusPills.addView(pill((playing ? "❚❚  " : "▶  ") + track, v -> {
                transport(0);
                ui.postDelayed(this::renderFocusPills, 500);
            }), margins(-2, -2, 5, 0, 5, 0));
        }
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = text(th.text, 14);
        t.setText(s);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(px(320));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(14), px(8), px(16), px(8));
        t.setBackground(rounded(th.surface, 20));
        t.setOnClickListener(l);
        return t;
    }

    /** Night: the clock alone, dim, wandering slowly; under it the alarm and the morning weather. */
    private void buildNight() {
        bigClock = true;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.addView(buildClock(true), new LinearLayout.LayoutParams(-1, 0, 1));
        content.setOnClickListener(v -> peek());
    }

    /** id, icon, name of the dock sections. */
    private static final Object[][] DOCK = {
            {"main", R.drawable.ic_dashboard, "Стол"}, {"home", R.drawable.ic_home, "Дом"},
            {"calendar", R.drawable.ic_calendar, "Календарь"}, {"calls", R.drawable.ic_camera, "Созвоны"},
            {"weather", R.drawable.ic_weather, "Погода"}, {"music", R.drawable.ic_music, "Музыка"},
            {"stats", R.drawable.ic_chart, "День"},
    };

    /** A rail of sections on the left, one section on the rest of the screen; "Стол" is the current desk. */
    private void buildDock(JSONObject desk, boolean portrait, Set<String> used) {
        content.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout rail = vertical();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, px(12), 0, px(8));
        rail.setBackground(rounded(th.surface, 22));
        dockTime = text(th.text, 16);
        dockTime.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dockTime.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });
        rail.addView(dockTime, margins(-2, -2, 0, 0, 0, 8));
        String page = prefs.dockPage();
        List<Object[]> pages = new ArrayList<>();
        for (Object[] pg : DOCK) {
            if ("home".equals(pg[0]) && !SmartHome.configured(prefs)) continue;
            if ("weather".equals(pg[0]) && !Weather.configured(prefs)) continue;
            pages.add(pg);
        }
        boolean found = false;
        for (Object[] pg : pages) found |= pg[0].equals(page);
        if (!found) page = "main";
        ScrollView rs = new ScrollView(ctx);
        rs.setVerticalScrollBarEnabled(false);
        LinearLayout items = vertical();
        items.setGravity(Gravity.CENTER_HORIZONTAL);
        rs.addView(items);
        for (Object[] pg : pages) {
            boolean sel = pg[0].equals(page);
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView ic = iconButton((int) pg[1], sel ? DashTheme.onColor(th.accent) : th.dim, 36);
            if (sel) ic.setBackground(rounded(th.accent, 14));
            item.addView(ic, new LinearLayout.LayoutParams(px(46), px(32)));
            TextView l = text(sel ? th.text : th.dim, 10);
            l.setText((String) pg[2]);
            l.setSingleLine();
            item.addView(l, margins(-2, -2, 0, 2, 0, 0));
            String id = (String) pg[0];
            item.setOnClickListener(v -> {
                if (id.equals(prefs.dockPage())) return;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                prefs.setDockPage(id);
                build();
            });
            items.addView(item, margins(-1, -2, 0, 4, 0, 6));
        }
        rail.addView(rs, new LinearLayout.LayoutParams(-1, 0, 1));
        ImageView more = iconButton(R.drawable.ic_more, th.dim, 34);
        more.setOnClickListener(v -> openMenu());
        rail.addView(more, new LinearLayout.LayoutParams(px(34), px(34)));
        content.addView(rail, new LinearLayout.LayoutParams(px(72), -1));

        LinearLayout area = new LinearLayout(ctx);
        area.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, -1, 1);
        al.leftMargin = px(20);
        content.addView(area, al);
        deskSwipe = "main".equals(page);
        if (deskSwipe) {
            buildDesk(area, desk, portrait, used, false);
            return;
        }
        used.add("clock"); // the rail has ⋮: no bar needed
        switch (page) {
            case "home": {
                JSONObject b = new JSONObject();
                try {
                    b.put("t", "home").put("title", "").put("items", new org.json.JSONArray());
                } catch (Exception ignored) {
                    // constant keys
                }
                HomePanel hp = (HomePanel) buildHome(b);
                hp.setAuto();
                hOwners.add(hp.roomStrip());
                area.addView(hp, new LinearLayout.LayoutParams(-1, -1));
                break;
            }
            case "calendar":
                area.addView(buildCalendar(), new LinearLayout.LayoutParams(-1, -1));
                break;
            case "calls": {
                area.addView(buildCalls(), new LinearLayout.LayoutParams(0, -1, 58));
                LinearLayout side = vertical();
                side.addView(buildClock(), new LinearLayout.LayoutParams(-1, 0, 1));
                side.addView(buildPc(), new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -1, 42);
                sl.leftMargin = px(24);
                area.addView(side, sl);
                break;
            }
            case "weather":
                area.addView(buildWeather(), new LinearLayout.LayoutParams(-1, -1));
                break;
            case "music": {
                LinearLayout col = vertical();
                col.setGravity(Gravity.CENTER_VERTICAL);
                col.addView(buildClock(), new LinearLayout.LayoutParams(-1, 0, 1));
                col.addView(buildMusic(), margins(-1, -2, 0, 12, 0, 0));
                col.addView(buildPc(), margins(-1, -2, 0, 12, 0, 0));
                area.addView(col, new LinearLayout.LayoutParams(-1, -1));
                break;
            }
            default:
                area.addView(buildStats(), new LinearLayout.LayoutParams(-1, -1));
                break;
        }
    }

    /** Clock and weather on top, the next hours as a strip in the middle, music and favourite tiles at the bottom. */
    private void buildRibbon(JSONArray desks) {
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(th.text, 58);
        clock.setTypeface(clockFace());
        clock.setIncludeFontPadding(false);
        clock.setSingleLine();
        clock.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });
        top.addView(clock);
        LinearLayout words = vertical();
        dateText = text(th.text, 18);
        dateText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        words.addView(dateText);
        hint = text(th.dim, 13);
        hint.setMaxLines(2);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(hint);
        top.addView(words, margins(0, -2, 18, 0, 12, 0, 1));
        if (Weather.configured(prefs)) {
            View wp = buildWeather();
            ((WeatherPanel) wp).compact();
            top.addView(wp, new LinearLayout.LayoutParams(px(280), -2));
        }
        ImageView more = iconButton(R.drawable.ic_more, th.dim, 34);
        more.setOnClickListener(v -> openMenu());
        top.addView(more, new LinearLayout.LayoutParams(px(34), px(34)));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ribbon = new RibbonView(ctx);
        ribbon.setTheme(th, prefs.googleColors() && !nightActive, prefs.h24(), nightActive);
        ribbon.setListener(this::openEventSheet);
        ribbon.setData(ribbonEvents, weather);
        content.addView(ribbon, margins(-1, 0, 0, 14, 0, 14, 1));

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(buildMusic(), new LinearLayout.LayoutParams(0, -2, 1));
        List<String[]> fav = favourites(desks);
        View right;
        if (SmartHome.configured(prefs) && !fav.isEmpty()) {
            JSONObject b = new JSONObject();
            try {
                org.json.JSONArray items = new org.json.JSONArray();
                for (String[] f : fav) items.put(new JSONObject().put("id", f[0]).put("n", f[1]));
                b.put("t", "home").put("title", "").put("items", items);
            } catch (Exception ignored) {
                // constant keys
            }
            right = buildHome(b);
            bottom.addView(right, margins(0, px(118), 14, 0, 0, 0, 1.4f));
        } else {
            right = buildPc();
            bottom.addView(right, margins(0, -2, 14, 0, 0, 0, 1.4f));
        }
        content.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Devices of every smart home block of the profile, for the ribbon's bottom row. */
    private static List<String[]> favourites(JSONArray desks) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < desks.length(); i++) {
            JSONArray cols = desks.optJSONObject(i).optJSONArray("cols");
            for (int j = 0; cols != null && j < cols.length(); j++) {
                JSONArray bl = cols.optJSONObject(j).optJSONArray("blocks");
                for (int k = 0; bl != null && k < bl.length(); k++) {
                    JSONObject b = bl.optJSONObject(k);
                    if (b == null || !"home".equals(b.optString("t"))) continue;
                    for (String[] it : Desks.items(b)) if (seen.add(it[0]) && out.size() < 8) out.add(it);
                }
            }
        }
        return out;
    }

    /** An event from the ribbon: when, where and the join buttons when it is a call. */
    private void openEventSheet(CalEvent e) {
        LinearLayout m = menu(e.title);
        dayLine(m, "Когда", e.allDay ? "Весь день" : fmt(e.start) + " – " + fmt(e.end));
        if (!e.location.isEmpty()) dayLine(m, "Где", e.location);
        if (!e.calendar.isEmpty()) dayLine(m, "Календарь", e.calendar);
        Meetings.Meeting mt = Meetings.of(e);
        if (mt != null && e.end > System.currentTimeMillis()) {
            TextView gap = text(th.dim, 4);
            m.addView(gap);
            if (pcOnline) menuRow(m, "Подключиться на ПК · " + mt.platform.name, false, () -> {
                closeSheet();
                joinMeeting(mt, true);
            });
            menuRow(m, (pcOnline ? "С телефона · " : "Подключиться · ") + mt.platform.name, false, () -> {
                closeSheet();
                joinMeeting(mt, false);
            });
        }
        openSheet(m);
    }

    // ---------------------------------------------------------------- calls without the PC or the calendar

    /** The call notification of Telegram, WhatsApp, Zoom… on this phone; every 2 s. */
    private void pollPhoneCall() {
        PhoneCalls.Call c = PhoneCalls.current(ctx);
        PhoneCalls.Call was = phoneCall;
        phoneCall = c;
        boolean changed = (c == null) != (was == null) || c != null && (!c.key.equals(was.key) || c.ringing != was.ringing);
        if (changed) {
            callsSig = "";
            renderCalls();
        } else if (c != null) {
            renderCalls(); // the mic or speaker may have been switched in the app itself
        }
        callAutomation();
    }

    /** A meeting link copied anywhere shows up in the calls block; read only when the clipboard changed. */
    private void checkClipboard() {
        if (!hasWindowFocus()) return;
        ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
        ClipDescription d = cm == null ? null : cm.getPrimaryClipDescription();
        long stamp = d == null ? 0 : d.getTimestamp();
        if (stamp == clipStamp) return;
        clipStamp = stamp;
        // Android tells the user each time the clipboard is read: only look at something copied in the last half hour
        if (d != null && System.currentTimeMillis() - stamp > 30 * 60_000L) d = null;
        Meetings.Meeting m = null;
        if (d != null && d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d != null && d.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
            try {
                ClipData cd = cm.getPrimaryClip();
                CharSequence t = cd == null || cd.getItemCount() == 0 ? null : cd.getItemAt(0).coerceToText(ctx);
                m = t == null ? null : Meetings.find(t.toString(), null);
                if (m != null && m.platform == Meetings.WEB) m = null;
            } catch (RuntimeException ignored) {
                // no access to the clipboard right now
            }
        }
        if (m != null && clipLink != null && m.url.equals(clipLink.url)) return;
        clipLink = m;
        callsSig = "";
        renderCalls();
    }

    /** "Звонок по ссылке": a field for a link, recent links, a new meeting in a tap. */
    private void openQuickJoin() {
        LinearLayout m = menu("Звонок по ссылке");
        EditText field = new EditText(ctx);
        field.setHint("Ссылка Zoom, Meet, Телемоста…");
        field.setHintTextColor(th.dim);
        field.setTextColor(th.text);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setBackground(rounded(th.bg, 14));
        field.setPadding(px(14), px(10), px(14), px(10));
        if (clipLink != null) field.setText(clipLink.url);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                ui.removeCallbacks(autoCloseSheet);
                ui.postDelayed(autoCloseSheet, 60_000);
            }
        });
        m.addView(field, margins(-1, -2, 0, 0, 0, 8));
        LinearLayout btns = new LinearLayout(ctx);
        TextView phone = pill("С телефона", v -> joinQuick(field.getText().toString(), false));
        phone.setTextColor(DashTheme.onColor(th.accent));
        phone.setBackground(rounded(th.accent, 20));
        btns.addView(phone, margins(-2, -2, 0, 0, 8, 0));
        if (pcOnline) btns.addView(pill("На компьютере", v -> joinQuick(field.getText().toString(), true)));
        m.addView(btns, margins(-1, -2, 0, 0, 0, 12));
        try {
            JSONArray r = new JSONArray(prefs.callRecent());
            if (r.length() > 0) m.addView(caption("НЕДАВНИЕ"));
            for (int i = 0; i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                String url = o.optString("url");
                menuRow(m, o.optString("t") + " · " + url.replaceFirst("^https?://", ""), false,
                        () -> joinQuick(url, pcOnline && "pc".equals(prefs.callJoin())));
            }
        } catch (Exception ignored) {
            // nothing saved yet
        }
        m.addView(caption("НОВАЯ ВСТРЕЧА"));
        for (String[] n : Meetings.NEW) {
            menuRow(m, n[0], false, () -> {
                closeSheet();
                Meetings.Meeting x = Meetings.find(n[1], n[0]);
                if (x != null) joinMeeting(x, pcOnline && "pc".equals(prefs.callJoin()));
            });
        }
        openSheet(m);
    }

    private TextView caption(String s) {
        TextView t = text(th.dim, 11);
        t.setText(s);
        t.setLetterSpacing(0.12f);
        t.setPadding(px(4), px(8), 0, px(6));
        return t;
    }

    private void joinQuick(String text, boolean onPc) {
        Meetings.Meeting mt = Meetings.find(text, null);
        if (mt == null) {
            Toast.makeText(ctx, "Здесь нет ссылки на встречу", Toast.LENGTH_SHORT).show();
            return;
        }
        rememberLink(mt);
        closeSheet();
        joinMeeting(mt, onPc);
    }

    private void rememberLink(Meetings.Meeting mt) {
        try {
            JSONArray old = new JSONArray(prefs.callRecent()), out = new JSONArray();
            out.put(new JSONObject().put("url", mt.url).put("t", mt.platform.name).put("at", System.currentTimeMillis()));
            for (int i = 0; i < old.length() && out.length() < 6; i++) {
                JSONObject o = old.getJSONObject(i);
                if (!o.optString("url").equals(mt.url)) out.put(o);
            }
            prefs.setCallRecent(out.toString());
        } catch (Exception ignored) {
            // a broken list starts over next time
        }
    }

    /** The phone's notification sound when a meeting is about to start. */
    private void chime() {
        try {
            Ringtone r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (RuntimeException ignored) {
            // no sound available
        }
    }

    // ---------------------------------------------------------------- smart home reactions

    /** A call started on the phone or the PC: the chosen lamp or scene on; ended: the lamp off again. */
    private void callAutomation() {
        PcClient.Calls pcc = pcOnline ? pcCalls : null;
        boolean on = phoneCall != null && !phoneCall.ringing || pcc != null && !pcc.calls.isEmpty();
        if (on == callWasOn) return;
        callWasOn = on;
        HomeDevice d = home.devices().get(prefs.autoCall());
        if (d == null || !d.available) return;
        if (d.kind == HomeDevice.SCENE) {
            if (on) homeSend(d, null, () -> home.runScene(d));
        } else if (on && !d.on) {
            callLightOn = true;
            homeSend(d, x -> x.on = true, () -> home.setOn(d, true));
        } else if (!on && callLightOn) {
            callLightOn = false;
            homeSend(d, x -> x.on = false, () -> home.setOn(d, false));
        }
    }

    /** The night started: switch off (or run) what was chosen in the settings. */
    private void nightChanged(boolean night) {
        peeking = false;
        if (!night) return;
        for (String id : prefs.autoNight().split(",")) {
            HomeDevice d = home.devices().get(id.trim());
            if (d == null || !d.available) continue;
            if (d.kind == HomeDevice.SCENE) homeSend(d, null, () -> home.runScene(d));
            else if (d.canToggle && d.on) homeSend(d, x -> x.on = false, () -> home.setOn(d, false));
        }
    }

    /** Sunrise: the chosen lamp goes from 1 % to full over the last minutes before the night ends. */
    private void dawnTick() {
        HomeDevice d = home.devices().get(prefs.autoDawn());
        if (d == null || !d.available || !prefs.nightMode()) {
            dawnStep = -1;
            return;
        }
        int dur = Math.max(5, prefs.dawnMinutes()), into = Math.floorMod(minuteOfDay() - (prefs.nightEnd() - dur), 1440);
        if (into >= dur) {
            dawnStep = -1;
            return;
        }
        int pct = Math.max(1, Math.round(100f * (into + 1) / dur));
        if (pct == dawnStep) return;
        dawnStep = pct;
        if (d.dimmable) {
            homeSend(d, x -> {
                x.on = true;
                x.brightness = pct;
            }, () -> home.brightness(d, pct));
        } else if (!d.on) {
            homeSend(d, x -> x.on = true, () -> home.setOn(d, true));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void applyPadding() {
        if (content == null) return;
        int base = "bento".equals(look) || "dock".equals(look) ? px(14) : px(22);
        // the live strip takes the top edge: blocks move down instead of hiding under it
        int top = live != null && live.getVisibility() == VISIBLE ? px(40) : px(16);
        content.setPadding(base + cutL, top + cutT, base + cutR, (hasBar ? px(34) : px(16)) + cutB);
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
