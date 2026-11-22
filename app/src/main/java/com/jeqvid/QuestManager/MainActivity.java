package com.jeqvid.QuestManager;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.method.ScrollingMovementMethod;
import android.widget.CheckBox;
import android.content.pm.PackageManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.tukaani.xz.XZInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements View.OnHoverListener {

    static final String URL = "https://github.com/jeqvid/questmanager";

    private static final int WHITE = Color.WHITE;
    private static final int BLACK = Color.BLACK;
    private static final int GREY = 0xff8a8a8a;
    private static final int STATUS = 0xffbdbdbd;

    // ---------------------------------------------------------------- settings
    private static final int HZ = 0, ONE = 1, BOTH = 2, SIZE = 3, SLIDER = 4;
    private static final int NEON = 0xffc05cff, NEON_LIGHT = 0xffe2a8ff, NEON_TRACK = 0xff2a1a3d;

    private static final class Setting {
        final String title, hint, tag;
        final int kind, lo, hi;
        final String[] props;
        EditText input;
        TextView status, current;
        SeekBar slider;

        Setting(String title, String hint, String tag, int kind, int lo, int hi, String... props) {
            this.title = title;
            this.hint = hint;
            this.tag = tag;
            this.kind = kind;
            this.lo = lo;
            this.hi = hi;
            this.props = props;
        }
    }

    private static final String P = "debug.oculus.";

    // ---------------------------------------------------------------- state
    // Static: survives the window being recreated, so a running command or reconnect isn't killed.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    // PAIR / CONNECT run here so they never wait behind a background reconnect or a command.
    private static final ExecutorService setupWorker = Executors.newCachedThreadPool();
    private final List<TextView> tabs = new ArrayList<>();
    private final List<View> pages = new ArrayList<>();
    private final List<Setting> allSettings = new ArrayList<>();
    private float density;
    private ScrollView scroll;
    private int setupTab = -1;
    private SharedPreferences prefs;
    private ValueAnimator rainbowAnim;
    private SnowView snow;

    private boolean quest1;
    private CommandRunner runner;
    private AdbRunner adb; // null on Quest 1

    private TextView setupStatus, connStatus, proxStatus, proxCurrent;
    private EditText pairCode, pairPort, connectPort;

    // ---------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("qm", MODE_PRIVATE);
        quest1 = "Quest".equals(Build.MODEL);
        if (quest1) {
            runner = new RootRunner();
        } else {
            adb = AdbRunner.get(this);
            runner = adb;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BLACK);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        pad(side, 20, 28);
        TextView brand = header("QUEST\nMANAGER");
        brand.setTextSize(22);
        brand.setLetterSpacing(0.2f);
        rainbow(brand);
        side.addView(brand);
        side.addView(new View(this), lp(-1, 28, 0, 0));

        scroll = new ScrollView(this);

        addTab(side, "REFRESH RATE", pageOf(
                new Setting("REFRESH RATE", "Hz, 1 - 1000", null, HZ, 1, 1000, P + "refreshRate"),
                new Setting("SWAP INTERVAL", "e.g. 1", null, ONE, 1, 4, P + "swapInterval")));
        addTab(side, "RESOLUTION", pageOf(
                new Setting("RESOLUTION", "Width & height, e.g. 1440", null, BOTH, 512, 4096,
                        P + "textureWidth", P + "textureHeight")));
        addTab(side, "RECORDING", recordingPage());
        int maxLevel = Build.MODEL.startsWith("Quest 3") ? 5 : 4; // Quest 3 / 3S have an extra level
        addTab(side, "CPU & GPU", pageOf(
                new Setting("CPU LEVEL", "", "HIGHER = FASTER, HOTTER", SLIDER, 0, maxLevel, P + "cpuLevel"),
                new Setting("GPU LEVEL", "", "HIGHER = FASTER, HOTTER", SLIDER, 0, maxLevel, P + "gpuLevel")));
        LinearLayout experimental = pageOf(
                new Setting("EXTRA KICKOFF HEADROOM", "", "V80 AND ABOVE", SLIDER, 0, 1, P + "extraKickoffHeadroom"),
                new Setting("PHASE SYNC", "", "V67 MAX  -  SETS SYNC, PADDING & DELAY", SLIDER, 0, 4,
                        P + "phaseSync", P + "PhaseSyncAdditionalPadding", P + "PhaseSyncDelayOverride"),
                new Setting("VSYNC EMULATION", "", null, SLIDER, 0, 1, P + "vsyncEmu"));
        addProximitySection(experimental);
        addAdbSection(experimental);
        addSingularitySection(experimental);
        addFridaSection(experimental);
        addTab(side, "EXPERIMENTAL", experimental);

        side.addView(new View(this), lp(-1, 0, 1f, 0)); // pushes the rest to the bottom
        if (!quest1) setupTab = addTab(side, "WIRELESS ADB", setupPage());
        int infoTab = addTab(side, "INFO", infoPage());
        addTab(side, "SETTINGS", settingsPage());

        root.addView(side, lp(220, -1, 0, 0));
        View divider = new View(this);
        divider.setBackgroundColor(0xff1c1c1c);
        root.addView(divider, lp(1, -1, 0, 0));
        root.addView(scroll, lp(0, -1, 1f, 0));

        // Snow falls over everything; it never takes clicks or hover.
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        snow = new SnowView(this);
        snow.setVisibility(prefs.getBoolean("snow", true) ? View.VISIBLE : View.GONE);
        frame.addView(snow, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        selectTab(infoTab);
        if (adb != null) startupConnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rainbowAnim != null) rainbowAnim.cancel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrent();
        refreshTools();
    }

    // ---------------------------------------------------------------- current values
    /** Reads every property from the headset and fills in the "Current:" lines. */
    private void refreshCurrent() {
        updateProx();
        if (!runner.isReady()) {
            for (Setting s : allSettings) s.current.setText("Current: connect wireless ADB to see");
            return;
        }
        final StringBuilder cmd = new StringBuilder();
        for (Setting s : allSettings) {
            for (String p : s.props) cmd.append("echo \"").append(p).append("=$(getprop ").append(p).append(")\"; ");
        }
        cmd.append("true");
        worker.execute(() -> {
            final CommandRunner.Result r = runner.run(cmd.toString());
            final Map<String, String> values = new HashMap<>();
            for (String line : r.output.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            runOnUiThread(() -> {
                for (Setting s : allSettings) {
                    s.current.setText(r.ok ? "Current: " + describe(s, values) : "Current: couldn't read");
                    if (r.ok && s.slider != null && !s.input.hasFocus()) {
                        int v = parseInt(values.containsKey(s.props[0]) ? values.get(s.props[0]) : "");
                        if (v >= 0) s.slider.setProgress(Math.max(s.lo, Math.min(s.hi, v)) - s.lo);
                    }
                }
            });
        });
    }

    private static String describe(Setting s, Map<String, String> values) {
        List<String> v = new ArrayList<>();
        for (String p : s.props) {
            String x = values.get(p);
            v.add(x == null || x.isEmpty() ? "default" : x);
        }
        if (v.size() == 1) {
            String one = v.get(0);
            return s.kind == HZ && !"default".equals(one) ? one + " Hz" : one;
        }
        if (v.size() == 2) return v.get(0) + " x " + v.get(1);
        boolean same = true;
        for (String x : v) same &= x.equals(v.get(0));
        if (same) return v.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) b.append(" / ");
            b.append(v.get(i));
        }
        return b.toString();
    }

    /** The proximity sensor has no readable setting, so remember what was last set (reset by a reboot). */
    private void saveProx(boolean disabled) {
        prefs.edit().putBoolean("prox_off", disabled).putInt("prox_boot", bootCount()).apply();
        updateProx();
    }

    private void updateProx() {
        if (proxCurrent == null) return;
        boolean off = prefs.getBoolean("prox_off", false) && prefs.getInt("prox_boot", -2) == bootCount();
        proxCurrent.setText(off ? "Current: Disabled" : "Current: Enabled (default)");
    }

    private int bootCount() {
        if (Build.VERSION.SDK_INT < 24) return -1;
        return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /** Rainbow gradient that slides across the text forever (shimmer). */
    private void rainbow(final TextView t) {
        final int[] colors = {0xffff3b5c, 0xffff9f1c, 0xffffee32, 0xff3dff8b, 0xff2ec8ff, 0xff8a5cff, 0xffff4fd8, 0xffff3b5c};
        final Matrix matrix = new Matrix();
        t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || t.getPaint().getShader() == null) {
                t.getPaint().setShader(new LinearGradient(0, 0, Math.max(1, r - l), 0, colors, null, Shader.TileMode.REPEAT));
            }
        });
        rainbowAnim = ValueAnimator.ofFloat(0f, 1f);
        rainbowAnim.setDuration(2500);
        rainbowAnim.setRepeatCount(ValueAnimator.INFINITE);
        rainbowAnim.setInterpolator(new LinearInterpolator());
        rainbowAnim.addUpdateListener(a -> {
            Shader shader = t.getPaint().getShader();
            if (shader == null) return;
            matrix.setTranslate(t.getWidth() * (Float) a.getAnimatedValue(), 0);
            shader.setLocalMatrix(matrix);
            t.invalidate();
        });
        rainbowAnim.start();
    }

    // ---------------------------------------------------------------- pages
    private int addTab(LinearLayout side, String name, View page) {
        final int index = tabs.size();
        TextView tab = text(name, 14, WHITE);
        tab.setTypeface(null, Typeface.BOLD);
        tab.setLetterSpacing(0.12f);
        pad(tab, 16, 14);
        tab.setOnClickListener(v -> selectTab(index));
        side.addView(tab, lp(-1, -2, 0, 0));
        tabs.add(tab);
        pages.add(page);
        return index;
    }

    private void selectTab(int index) {
        scroll.removeAllViews();
        scroll.addView(pages.get(index));
        scroll.scrollTo(0, 0);
        for (int i = 0; i < tabs.size(); i++) {
            TextView t = tabs.get(i);
            if (i == index) {
                t.setBackground(rounded(0xff161616, 24, 0, 0));
                t.setTextColor(WHITE);
            } else {
                t.setBackground(null);
                t.setTextColor(0xff6e6e6e);
            }
        }
    }

    private LinearLayout newPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        pad(page, 36, 24);
        return page;
    }

    private LinearLayout pageOf(Setting... settings) {
        LinearLayout page = newPage();
        for (Setting s : settings) addSection(page, s);
        return page;
    }

    private View recordingPage() {
        LinearLayout page = newPage();
        final Setting size = new Setting("VIDEO CAPTURE SIZE", "e.g. 1024x1024", null, SIZE, 128, 4096,
                P + "capture.width", P + "capture.height");
        addSection(page, size);
        size.input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout presets = row();
        for (final String preset : new String[]{"1024x1024", "1280x960"}) {
            TextView b = button(preset, v -> {
                size.input.setText(preset);
                apply(size);
            });
            presets.addView(b, lp(-2, -2, 0, presets.getChildCount() == 0 ? 0 : 14));
        }
        page.addView(presets);
        addSection(page, new Setting("VIDEO CAPTURE FPS", "e.g. 30", null, ONE, 1, 120, P + "capture.fps"));
        return page;
    }

    private View setupPage() {
        LinearLayout page = newPage();
        spacerHeader(page, "WIRELESS ADB SETUP");
        page.addView(html("Quest 2 and newer don't need root. Quest Manager talks to the headset's own "
                + "<b>wireless debugging</b> instead. You only have to pair once.<br><br>"
                + "1. Make sure the headset is connected to <b>Wi-Fi</b>.<br>"
                + "2. Tap <b>OPEN ANDROID SETTINGS</b>. An <b>App info</b> window opens: tap <b>OPEN</b> (top left) and the "
                + "real Android Settings appears. Go to <b>System &gt; Developer options &gt; Wireless debugging</b> and turn it on "
                + "(no Developer options? tap <b>About &gt; Build number</b> 7 times). Keep that window open next to this one.<br>"
                + "3. Tap <b>Pair device with pairing code</b>. Type the 6-digit code and the port it shows, then tap <b>PAIR</b>.<br>"
                + "4. On the Wireless debugging screen, type the port shown under <b>IP address &amp; Port</b> "
                + "(the number after the colon) and tap <b>CONNECT</b>. Leave it empty to find it automatically.<br><br>"
                + "After that, the pairing never expires and Quest Manager reconnects by itself, switching Wireless "
                + "debugging back on when it turns off (also after a restart). If an <b>Allow wireless debugging on this "
                + "network?</b> popup appears, tick <b>Always allow</b>.",
                15, 0xffdddddd));

        LinearLayout open = row();
        open.addView(button("OPEN ANDROID SETTINGS", v -> openAndroidSettings()), lp(-2, -2, 0, 0));
        page.addView(open);

        spacerHeader(page, "PAIR");
        LinearLayout pairRow = row();
        pairCode = input("Pairing code");
        pairPort = input("Pairing port");
        pairRow.addView(pairCode, lp(0, -2, 1f, 0));
        pairRow.addView(pairPort, lp(0, -2, 1f, 14));
        pairRow.addView(button("PAIR", v -> doPair()), lp(-2, -2, 0, 14));
        page.addView(pairRow);

        spacerHeader(page, "CONNECT");
        LinearLayout connRow = row();
        connectPort = input("Port (optional)");
        int saved = prefs.getInt("port", 0);
        if (saved > 0) connectPort.setText(String.valueOf(saved));
        connRow.addView(connectPort, lp(0, -2, 1f, 0));
        connRow.addView(button("CONNECT", v -> doConnect()), lp(-2, -2, 0, 14));
        page.addView(connRow);

        setupStatus = text("", 14, STATUS);
        page.addView(setupStatus);
        return page;
    }

    private View infoPage() {
        LinearLayout page = newPage();
        spacerHeader(page, "WELCOME TO QUEST MANAGER");
        page.addView(html("Quest Manager works for Quest 1 and above.<br>You can always find new releases here:", 16, 0xffdddddd));
        TextView link = html("<u>" + URL + "</u>", 16, WHITE);
        link.setOnClickListener(v -> openUrl());
        page.addView(link);
        page.addView(new View(this), lp(-1, 18, 0, 0));
        page.addView(html("<b>IMPORTANT:</b> On Quest 1 you have to be rooted with Magisk/Singularity. "
                + "On Quest 2 and above, use the wireless ADB option.", 16, 0xffdddddd));
        page.addView(text("Detected headset: " + modelName() + "  (" + runner.name() + ")", 14, GREY));
        return page;
    }

    private View settingsPage() {
        LinearLayout page = newPage();
        spacerHeader(page, "SETTINGS");
        page.addView(text("Device: " + modelName(), 20, WHITE));
        page.addView(text("Mode: " + runner.name(), 16, 0xffdddddd));
        page.addView(text("Root status: " + isRooted(), 16, 0xffdddddd));
        page.addView(new View(this), lp(-1, 16, 0, 0));
        CheckBox snowBox = new CheckBox(this);
        snowBox.setText("Snow");
        snowBox.setTextColor(WHITE);
        snowBox.setTextSize(18);
        snowBox.setButtonTintList(ColorStateList.valueOf(NEON));
        snowBox.setChecked(prefs.getBoolean("snow", true));
        snowBox.setOnHoverListener(this);
        snowBox.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("snow", on).apply();
            if (snow != null) snow.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        page.addView(snowBox);
        if (adb != null) {
            connStatus = text("Wireless ADB: not connected", 16, 0xffdddddd);
            page.addView(connStatus);
            LinearLayout r = row();
            r.addView(button("WIRELESS ADB SETUP", v -> selectTab(setupTab)), lp(-2, -2, 0, 0));
            page.addView(r);
        }
        return page;
    }

    private void addProximitySection(LinearLayout page) {
        spacerHeader(page, "PROXIMITY SENSOR");
        proxCurrent = text("Current: ...", 16, 0xffcfcfcf);
        page.addView(proxCurrent);
        LinearLayout r = row();
        r.addView(button("DISABLE", v -> runCommand(
                "am broadcast -a com.oculus.vrpowermanager.prox_close >/dev/null && echo 'Proximity sensor disabled (until reboot or Enable).'",
                proxStatus, "Sending...", () -> saveProx(true))), lp(-2, -2, 0, 0));
        r.addView(button("ENABLE", v -> runCommand(
                "am broadcast -a com.oculus.vrpowermanager.automation_disable >/dev/null && echo 'Proximity sensor enabled.'",
                proxStatus, "Sending...", () -> saveProx(false))), lp(-2, -2, 0, 14));
        page.addView(r);
        proxStatus = text("", 14, STATUS);
        page.addView(proxStatus);
    }

    private void addSection(LinearLayout page, final Setting s) {
        page.addView(new View(this), lp(-1, 40, 0, 0));
        if (s.tag != null) {
            TextView tag = text(s.tag, 12, 0xff9e9e9e);
            tag.setTypeface(null, Typeface.BOLD);
            tag.setLetterSpacing(0.2f);
            page.addView(tag);
        }
        page.addView(header(s.title));
        s.current = text("Current: ...", 16, 0xffcfcfcf);
        page.addView(s.current);
        allSettings.add(s);
        if (s.kind == SLIDER) {
            addSliderControls(page, s);
            return;
        }
        LinearLayout r = row();
        s.input = input(s.hint);
        r.addView(s.input, lp(0, -2, 1f, 0));
        r.addView(button("APPLY", v -> apply(s)), lp(-2, -2, 0, 14));
        r.addView(button("RESET", v -> reset(s)), lp(-2, -2, 0, 14));
        page.addView(r);
        s.status = text("", 14, STATUS);
        page.addView(s.status);
    }

    /** Neon slider with a number box you can also type in, then APPLY / RESET below. Whole numbers only. */
    private void addSliderControls(LinearLayout page, final Setting s) {
        page.addView(text("Value", 14, GREY));
        LinearLayout r = row();
        r.setPadding(0, px(4), 0, px(4));
        final SeekBar bar = new SeekBar(this);
        bar.setMax(s.hi - s.lo);
        neon(bar);
        s.slider = bar;
        r.addView(bar, lp(0, 40, 1f, 0));
        final EditText box = input(String.valueOf(s.lo));
        box.setGravity(Gravity.CENTER);
        box.setTextColor(NEON_LIGHT);
        box.setBackground(rounded(0xff120a1c, 36, px(2), NEON));
        s.input = box;
        r.addView(box, lp(90, -2, 0, 16));
        page.addView(r);

        final boolean[] syncing = {false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (syncing[0]) return;
                syncing[0] = true;
                box.setText(String.valueOf(s.lo + progress));
                syncing[0] = false;
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (syncing[0]) return;
                int v = parseInt(e.toString());
                if (v < 0) return;
                syncing[0] = true;
                bar.setProgress(Math.max(s.lo, Math.min(s.hi, v)) - s.lo);
                syncing[0] = false;
            }
        });
        box.setText(String.valueOf(s.lo));

        LinearLayout buttons = row();
        buttons.addView(button("APPLY", v -> apply(s)), lp(-2, -2, 0, 0));
        buttons.addView(button("RESET", v -> reset(s)), lp(-2, -2, 0, 14));
        page.addView(buttons);
        s.status = text("", 14, STATUS);
        page.addView(s.status);
    }

    /** Thick glowing neon-purple track and a glowing thumb. */
    private void neon(SeekBar bar) {
        GradientDrawable track = rounded(NEON_TRACK, 100, 0, 0);
        GradientDrawable fill = rounded(NEON, 100, 0, 0);
        LayerDrawable progress = new LayerDrawable(new Drawable[]{track, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            progress.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
            progress.setLayerHeight(i, px(8));
        }
        bar.setProgressDrawable(progress);

        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setColor(0x66c05cff);
        glow.setSize(px(30), px(30));
        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(NEON_LIGHT);
        core.setStroke(px(2), Color.WHITE);
        LayerDrawable thumb = new LayerDrawable(new Drawable[]{glow, core});
        thumb.setLayerInset(1, px(7), px(7), px(7), px(7));
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        bar.setPadding(px(16), 0, px(16), 0);
    }

    // ---------------------------------------------------------------- ADB command line
    private EditText adbInput;
    private TextView adbOutput;

    /** ADB section (Experimental tab): type any adb shell command and see its output. */
    private void addAdbSection(LinearLayout page) {
        spacerHeader(page, "ADB");
        LinearLayout r = row();
        adbInput = input("e.g. getprop ro.build.version.release");
        adbInput.setInputType(InputType.TYPE_CLASS_TEXT);
        adbInput.setTypeface(Typeface.MONOSPACE);
        adbInput.setTextSize(15);
        r.addView(adbInput, lp(0, -2, 1f, 0));
        r.addView(button("RUN", v -> runAdbLine()), lp(-2, -2, 0, 14));
        page.addView(r);
        adbOutput = text("", 13, 0xffcfcfcf);
        adbOutput.setTypeface(Typeface.MONOSPACE);
        adbOutput.setBackground(rounded(0xff0a0a0a, 24, px(1), 0xff2a2a2a));
        pad(adbOutput, 14, 10);
        adbOutput.setMaxLines(12);
        adbOutput.setVerticalScrollBarEnabled(true);
        adbOutput.setMovementMethod(new ScrollingMovementMethod());
        page.addView(adbOutput);
    }

    // ---------------------------------------------------------------- Singularity + frida-server
    static final String SINGULARITY_PKG = "com.lumi.singularity";
    static final String SINGULARITY_URL = "https://github.com/Lumince/singularity/releases";
    static final String FRIDA_PATH = "/data/local/tmp/frida-server";
    static final String FRIDA_DEFAULT_VERSION = "17.17.0"; // the version Singularity uses
    /** Kill by PID of the process named exactly frida-server. (pkill -f would also match, and kill, our own shell.) */
    static final String FRIDA_KILL = "p=$(pidof frida-server); [ -z \"$p\" ] || kill $p";
    /** Runs a command as root if possible: already root, else su (max 5 s so a prompt can't freeze it), else as is. */
    static String asRoot(String c) {
        return "if [ \"$(id -u)\" = 0 ]; then " + c + "; "
                + "elif command -v su >/dev/null 2>&1; then timeout 5 su -c '" + c + "' || " + c + "; "
                + "else " + c + "; fi";
    }

    private TextView singStatus, singMsg, fridaStatus, fridaMsg;
    private View singDownload, singLaunch, singUninstall;
    private View fridaDownload, fridaStart, fridaStop, fridaUninstall;
    private EditText fridaVersion;

    private void addSingularitySection(LinearLayout page) {
        spacerHeader(page, "SINGULARITY");
        singStatus = text("Singularity Status: ...", 16, 0xffcfcfcf);
        page.addView(singStatus);
        LinearLayout r = row();
        singDownload = button("DOWNLOAD", v -> openLink(SINGULARITY_URL));
        r.addView(singDownload, lp(-2, -2, 0, 0));
        singLaunch = button("LAUNCH", v -> launchSingularity());
        r.addView(singLaunch, lp(-2, -2, 0, 0));
        singUninstall = button("UNINSTALL", v -> uninstallSingularity());
        r.addView(singUninstall, lp(-2, -2, 0, 14));
        page.addView(r);
        singMsg = text("", 14, STATUS);
        page.addView(singMsg);
    }

    private void addFridaSection(LinearLayout page) {
        spacerHeader(page, "FRIDA-SERVER");
        fridaStatus = text("Frida Status: ...", 16, 0xffcfcfcf);
        page.addView(fridaStatus);
        LinearLayout r = row();
        fridaVersion = input("Version");
        fridaVersion.setInputType(InputType.TYPE_CLASS_TEXT);
        String savedVersion = prefs.getString("frida_version", FRIDA_DEFAULT_VERSION);
        if ("17.0.9".equals(savedVersion)) savedVersion = FRIDA_DEFAULT_VERSION; // old default didn't exist
        fridaVersion.setText(savedVersion);
        fridaVersion.setGravity(Gravity.CENTER);
        r.addView(fridaVersion, lp(130, -2, 0, 0));
        fridaDownload = button("DOWNLOAD", v -> installFrida());
        r.addView(fridaDownload, lp(-2, -2, 0, 14));
        fridaStart = button("START", v -> startFrida());
        r.addView(fridaStart, lp(-2, -2, 0, 0));
        fridaStop = button("STOP", v -> stopFrida());
        r.addView(fridaStop, lp(-2, -2, 0, 14));
        fridaUninstall = button("UNINSTALL", v -> uninstallFrida());
        r.addView(fridaUninstall, lp(-2, -2, 0, 14));
        page.addView(r);
        fridaMsg = text("Installs to " + FRIDA_PATH, 14, STATUS);
        page.addView(fridaMsg);
    }

    /** Updates both status lines and swaps DOWNLOAD for the installed-only buttons (and back). */
    private void refreshTools() {
        if (singStatus == null) return;
        boolean sing = isInstalled(SINGULARITY_PKG);
        singStatus.setText("Singularity Status: " + (sing ? "installed" : "not installed"));
        singDownload.setVisibility(sing ? View.GONE : View.VISIBLE);
        singLaunch.setVisibility(sing ? View.VISIBLE : View.GONE);
        singUninstall.setVisibility(sing ? View.VISIBLE : View.GONE);

        if (!runner.isReady()) {
            fridaStatus.setText("Frida Status: connect wireless ADB to check");
            showFridaButtons(false);
            return;
        }
        worker.execute(() -> {
            final CommandRunner.Result r = runner.run("if [ -f " + FRIDA_PATH + " ]; then echo yes; "
                    + FRIDA_PATH + " --version 2>/dev/null; "
                    + "if pidof frida-server >/dev/null 2>&1 || ps -A 2>/dev/null | grep -q '[f]rida-server'; "
                    + "then echo running; else echo stopped; fi; else echo no; fi");
            runOnUiThread(() -> {
                String[] lines = r.output.trim().split("\n");
                boolean installed = r.ok && lines.length > 0 && lines[0].trim().equals("yes");
                boolean running = installed && r.output.contains("running");
                String version = "";
                if (installed && lines.length > 2) version = lines[1].trim();
                if (!r.ok) {
                    fridaStatus.setText("Frida Status: couldn't check");
                } else if (!installed) {
                    fridaStatus.setText("Frida Status: not installed");
                } else {
                    fridaStatus.setText("Frida Status: installed" + (version.isEmpty() ? "" : " (" + version + ")")
                            + (running ? "  -  running" : "  -  not running"));
                }
                showFridaButtons(installed);
            });
        });
    }

    private void showFridaButtons(boolean installed) {
        int on = installed ? View.VISIBLE : View.GONE, off = installed ? View.GONE : View.VISIBLE;
        fridaVersion.setVisibility(off);
        fridaDownload.setVisibility(off);
        fridaStart.setVisibility(on);
        fridaStop.setVisibility(on);
        fridaUninstall.setVisibility(on);
    }

    private void launchSingularity() {
        Intent i = getPackageManager().getLaunchIntentForPackage(SINGULARITY_PKG);
        if (i == null) {
            singMsg.setText("Couldn't find Singularity's launcher.");
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            singMsg.setText("Couldn't launch Singularity: " + e.getMessage());
        }
    }

    /** Runs frida-server in the background; as root through su when the headset is rooted. */
    private void startFrida() {
        if (!runner.isReady()) {
            fridaMsg.setText("Not connected. Set up wireless ADB first.");
            return;
        }
        fridaMsg.setText("Starting frida-server...");
        String launch = FRIDA_PATH + " -D >/dev/null 2>&1";
        String cmd = "chmod 755 " + FRIDA_PATH + "; " + asRoot(launch) + "; sleep 1; "
                + "if pidof frida-server >/dev/null 2>&1 || ps -A 2>/dev/null | grep -q '[f]rida-server'; "
                + "then echo started; else echo 'did not start'; exit 1; fi";
        runTool(cmd, "frida-server is running.");
    }

    private void stopFrida() {
        if (!runner.isReady()) {
            fridaMsg.setText("Not connected. Set up wireless ADB first.");
            return;
        }
        fridaMsg.setText("Stopping frida-server...");
        String cmd = asRoot(FRIDA_KILL) + "; true";
        runTool(cmd, "frida-server stopped.");
    }

    private void runTool(final String cmd, final String okText) {
        worker.execute(() -> {
            final CommandRunner.Result r = runner.run(cmd);
            runOnUiThread(() -> {
                fridaMsg.setText(r.ok ? okText : "Failed: " + r.output);
                refreshTools();
            });
        });
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            singMsg.setText("Couldn't open the browser: " + e.getMessage());
        }
    }

    private void uninstallSingularity() {
        if (runner.isReady()) {
            singMsg.setText("Uninstalling...");
            worker.execute(() -> {
                final CommandRunner.Result r = runner.run("pm uninstall " + SINGULARITY_PKG);
                runOnUiThread(() -> {
                    singMsg.setText(r.ok ? "Singularity uninstalled." : "Failed: " + r.output);
                    refreshTools();
                });
            });
        } else {
            // no shell access yet: let Android ask the user
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + SINGULARITY_PKG))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                singMsg.setText("Couldn't start the uninstall: " + e.getMessage());
            }
        }
    }

    /** Downloads frida-server for arm64 from Frida's GitHub releases, unpacks it and installs it to /data/local/tmp. */
    private void installFrida() {
        final String version = fridaVersion.getText().toString().trim();
        if (version.isEmpty()) {
            fridaMsg.setText("Type a Frida version, e.g. " + FRIDA_DEFAULT_VERSION);
            return;
        }
        if (!runner.isReady()) {
            fridaMsg.setText("Not connected. Set up wireless ADB first (installing needs shell access).");
            if (setupTab >= 0) selectTab(setupTab);
            return;
        }
        prefs.edit().putString("frida_version", version).apply();
        final String url = "https://github.com/frida/frida/releases/download/" + version
                + "/frida-server-" + version + "-android-arm64.xz";
        fridaMsg.setText("Downloading frida-server " + version + "...");
        setupWorker.execute(() -> {
            String msg;
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null) throw new Exception("no storage");
                File out = new File(dir, "frida-server");
                download(url, out, version);
                post("Installing...");
                final CommandRunner.Result r = runner.run("cp '" + out.getAbsolutePath() + "' " + FRIDA_PATH
                        + " && chmod 755 " + FRIDA_PATH + " && " + FRIDA_PATH + " --version");
                out.delete();
                msg = r.ok ? "Installed frida-server " + r.output + " to " + FRIDA_PATH : "Install failed: " + r.output;
            } catch (Exception e) {
                msg = "Download failed: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                fridaMsg.setText(m);
                refreshTools();
            });
        });
    }

    private void post(final String m) {
        runOnUiThread(() -> fridaMsg.setText(m));
    }

    private void download(String url, File out, String version) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code == 404) throw new Exception("Frida " + version + " doesn't exist. Check the version number.");
        if (code != 200) throw new Exception("server said " + code);
        long total = c.getContentLength();
        InputStream raw = c.getInputStream();
        InputStream in = new XZInputStream(raw);
        OutputStream o = new FileOutputStream(out);
        try {
            byte[] buf = new byte[65536];
            long done = 0, lastShown = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                o.write(buf, 0, n);
                done += n;
                if (done - lastShown > 2_000_000) {
                    lastShown = done;
                    post("Downloading frida-server " + version + "... " + (done / 1_000_000) + " MB");
                }
            }
        } finally {
            o.close();
            in.close();
            c.disconnect();
        }
    }

    private void uninstallFrida() {
        if (!runner.isReady()) {
            fridaMsg.setText("Not connected. Set up wireless ADB first.");
            return;
        }
        fridaMsg.setText("Removing...");
        worker.execute(() -> {
            final CommandRunner.Result r = runner.run("(" + asRoot(FRIDA_KILL) + ") >/dev/null 2>&1; "
                    + "rm -f " + FRIDA_PATH + " || " + asRoot("rm -f " + FRIDA_PATH) + "; "
                    + "[ ! -f " + FRIDA_PATH + " ] && echo removed");
            runOnUiThread(() -> {
                fridaMsg.setText(r.ok ? "frida-server removed." : "Failed: " + r.output);
                refreshTools();
            });
        });
    }


    private void runAdbLine() {
        String cmd = adbInput.getText().toString().trim();
        if (cmd.startsWith("adb shell ")) cmd = cmd.substring("adb shell ".length()).trim();
        else if (cmd.equals("adb shell")) cmd = "";
        else if (cmd.startsWith("adb ")) {
            adbOutput.setText("Only shell commands work here. Type the part after \"adb shell\".");
            return;
        }
        if (cmd.isEmpty()) {
            adbOutput.setText("Type a command first.");
            return;
        }
        if (!runner.isReady()) {
            adbOutput.setText("Not connected. Set up wireless ADB first.");
            if (setupTab >= 0) selectTab(setupTab);
            return;
        }
        final String command = cmd;
        adbOutput.setText("$ " + command + "\nRunning...");
        worker.execute(() -> {
            final CommandRunner.Result r = runner.run(command);
            runOnUiThread(() -> {
                String out = r.output.isEmpty() ? (r.ok ? "(no output)" : "") : r.output;
                adbOutput.setText("$ " + command + "\n" + (r.ok ? out : "Failed: " + out));
                adbOutput.scrollTo(0, 0);
                refreshCurrent();
            });
        });
    }

    // ---------------------------------------------------------------- actions
    private void apply(Setting s) {
        String text = s.input.getText().toString().trim();
        String cmd;
        if (s.kind == SLIDER) {
            int v = parseInt(text);
            if (v < s.lo || v > s.hi) {
                s.status.setText("Not applied: pick a value from " + s.lo + " to " + s.hi + ".");
                return;
            }
            StringBuilder b = new StringBuilder();
            for (String p : s.props) {
                if (b.length() > 0) b.append(" && ");
                b.append("setprop ").append(p).append(' ').append(v);
            }
            runCommand(b + " && " + readBack(s), s.status, "Applying...");
            return;
        }
        if (s.kind == SIZE) {
            int[] wh = parseSize(text, s.lo, s.hi);
            if (wh == null) {
                s.status.setText("Not applied: type a size like 1024x1024 (each side " + s.lo + " to " + s.hi + ").");
                return;
            }
            cmd = "setprop " + s.props[0] + " " + wh[0] + " && setprop " + s.props[1] + " " + wh[1];
        } else {
            int v = parseInt(text);
            if (v < s.lo || v > s.hi) {
                s.status.setText("Not applied: type a whole number from " + s.lo + " to " + s.hi + ".");
                return;
            }
            StringBuilder b = new StringBuilder();
            for (String p : s.props) {
                if (b.length() > 0) b.append(" && ");
                b.append("setprop ").append(p).append(' ').append(v);
            }
            cmd = b.toString();
        }
        runCommand(cmd + " && " + readBack(s), s.status, "Applying...");
    }

    private void reset(Setting s) {
        StringBuilder b = new StringBuilder();
        for (String p : s.props) {
            if (b.length() > 0) b.append(" && ");
            b.append("setprop ").append(p).append(" ''");
        }
        runCommand(b + " && " + readBack(s), s.status, "Resetting to default...");
    }

    private static String readBack(Setting s) {
        if (s.props.length == 1) {
            return "v=$(getprop " + s.props[0] + "); echo \"current value: ${v:-default}\"";
        }
        if (s.props.length == 2) {
            return "w=$(getprop " + s.props[0] + "); h=$(getprop " + s.props[1]
                    + "); echo \"current value: ${w:-default} x ${h:-default}\"";
        }
        // 3+ properties: "current values: phaseSync 3, PhaseSyncAdditionalPadding 3, ..."
        StringBuilder vars = new StringBuilder(), echo = new StringBuilder("current values: ");
        for (int i = 0; i < s.props.length; i++) {
            String name = s.props[i].substring(s.props[i].lastIndexOf('.') + 1);
            vars.append("v").append(i).append("=$(getprop ").append(s.props[i]).append("); ");
            if (i > 0) echo.append(", ");
            echo.append(name).append(' ').append("${v").append(i).append(":-default}");
        }
        return vars + "echo \"" + echo + "\"";
    }

    private void runCommand(String cmd, TextView status, String pending) {
        runCommand(cmd, status, pending, null);
    }

    private void runCommand(final String cmd, final TextView status, String pending, final Runnable onOk) {
        if (!runner.isReady()) {
            status.setText("Not connected. Set up wireless ADB first.");
            if (setupTab >= 0) selectTab(setupTab);
            return;
        }
        status.setText(pending);
        worker.execute(() -> {
            final CommandRunner.Result r = runner.run(cmd);
            runOnUiThread(() -> {
                status.setText(r.ok ? "OK  " + r.output : "Failed: " + r.output
                        + (adb != null && !adb.isReady() ? "\nTap CONNECT in the WIRELESS ADB tab." : ""));
                if (r.ok && onOk != null) onOk.run();
                updateConnStatus();
                refreshCurrent();
            });
        });
    }

    // ---------------------------------------------------------------- wireless ADB
    private void startupConnect() {
        final int saved = prefs.getInt("port", 0);
        setupStatus.setText("Looking for wireless debugging...");
        adb.setLastWirelessPort(saved);
        worker.execute(() -> {
            // Still connected (window was just recreated)? Keep it. Otherwise reconnect by itself.
            if (!adb.isReady()) adb.enableWirelessDebugging(); // after a restart it's off: switch it on (if allowed)
            boolean ok = adb.isReady() || adb.reconnect(saved, 2); // quick: 2 rounds, cancelled by PAIR/CONNECT
            final String lock = ok ? adb.makeStable() : null;
            final boolean connected = ok;
            runOnUiThread(() -> {
                updateConnStatus();
                if (connected) {
                    setupStatus.setText("Connected to wireless ADB. " + lock);
                    refreshCurrent();
                    refreshTools();
                } else {
                    setupStatus.setText("Not connected yet. Follow the steps above.");
                    selectTab(setupTab);
                }
            });
        });
    }

    private void doPair() {
        final String code = pairCode.getText().toString().trim();
        final int port = parseInt(pairPort.getText().toString().trim());
        if (code.isEmpty() || port <= 0) {
            setupStatus.setText("Type the pairing code and the pairing port first.");
            return;
        }
        setupStatus.setText("Pairing...");
        adb.cancelReconnect();
        final long started = System.currentTimeMillis();
        setupStatus.postDelayed(() -> {
            if ("Pairing...".contentEquals(setupStatus.getText()) && System.currentTimeMillis() - started >= 19000) {
                setupStatus.setText("Still pairing... Make sure the pairing code window is still open in Android Settings "
                        + "(the code and port change every time it opens).");
            }
        }, 20000);
        setupWorker.execute(() -> {
            String msg;
            try {
                msg = adb.pair(port, code)
                        ? "Paired! Now type the port from the Wireless debugging screen and tap CONNECT."
                        : "Pairing failed. Check the code and port (they change every time the pairing dialog opens).";
            } catch (Exception e) {
                msg = "Pairing failed: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> setupStatus.setText(m));
        });
    }

    private void doConnect() {
        final int port = parseInt(connectPort.getText().toString().trim());
        setupStatus.setText(port > 0 ? "Connecting..." : "Searching for the wireless debugging port...");
        adb.cancelReconnect();
        setupWorker.execute(() -> {
            String msg;
            try {
                boolean ok = false;
                if (port > 0) {
                    try {
                        ok = adb.connect(port);
                    } catch (Exception ignored) {
                    }
                }
                if (!ok) ok = adb.autoConnect(6000);
                if (ok) {
                    if (port > 0) {
                        prefs.edit().putInt("port", port).apply();
                        adb.setLastWirelessPort(port);
                    }
                    msg = "Connected! " + adb.makeStable();
                } else {
                    msg = "Couldn't connect. Is Wireless debugging on, and is the device paired?";
                }
            } catch (Exception e) {
                msg = "Couldn't connect: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                setupStatus.setText(m);
                updateConnStatus();
                refreshCurrent();
                refreshTools();
            });
        });
    }

    private void updateConnStatus() {
        if (connStatus != null && adb != null) {
            connStatus.setText(adb.isReady() ? "Wireless ADB: connected" : "Wireless ADB: not connected");
        }
    }

    private static final String AOSP_SETTINGS = "com.android.settings";

    /**
     * v81+ blocks apps from opening Android Settings, but still allows the "App info" screen, which is part of
     * Android Settings. Its OPEN button then starts Android Settings from inside itself (the Lightning Launcher
     * workaround). With shell access it's opened directly instead.
     */
    /**
     * Exactly what Lightning Launcher does (seen in the headset's log): open Android Settings' own App info
     * screen by its component, InstalledAppDetailsTop. Meta redirects the generic "app details" intent to its
     * own permissions page, but not this component. Tapping OPEN there starts the real Android Settings.
     */
    private void openAndroidSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setComponent(new ComponentName(AOSP_SETTINGS, "com.android.settings.applications.InstalledAppDetailsTop"))
                .setData(Uri.fromParts("package", AOSP_SETTINGS, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startInWindow(i, "In the App info window, tap OPEN (top left). Android Settings opens.\n"
                + "Then go to System > Developer options > Wireless debugging.");
    }

    private void startInWindow(Intent i, String hint) {
        try {
            startActivity(i);
            setupStatus.setText(hint);
        } catch (Exception e) {
            setupStatus.setText("Couldn't open it: " + e.getMessage());
        }
    }

    private void openUrl() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- headset info
    static String modelName() {
        return "Quest".equals(Build.MODEL) ? "Quest 1" : Build.MODEL;
    }

    /**
     * True if an su binary (Magisk / Singularity / other root) is present. Only looks for the file,
     * so it never triggers a root permission popup.
     */
    static boolean isRooted() {
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(":")) if (!d.isEmpty()) dirs.add(d);
        for (String d : new String[]{"/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin",
                "/vendor/bin", "/debug_ramdisk", "/data/local/xbin", "/data/local/bin"}) {
            if (!dirs.contains(d)) dirs.add(d);
        }
        for (String d : dirs) {
            if (new File(d, "su").exists()) return true;
        }
        return false;
    }


    // ---------------------------------------------------------------- parsing
    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int[] parseSize(String s, int lo, int hi) {
        String[] parts = s.toLowerCase().split("x");
        if (parts.length != 2) return null;
        int w = parseInt(parts[0]), h = parseInt(parts[1]);
        if (w < lo || w > hi || h < lo || h > hi) return null;
        return new int[]{w, h};
    }



    // ---------------------------------------------------------------- UI helpers
    private int px(int dp) {
        return (int) (dp * density + 0.5f);
    }

    private void pad(View v, int h, int vert) {
        v.setPadding(px(h), px(vert), px(h), px(vert));
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int marginLeft) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w > 0 ? px(w) : w, h > 0 ? px(h) : h, weight);
        p.setMargins(px(marginLeft), 0, 0, 0);
        return p;
    }

    private static GradientDrawable rounded(int fill, float radius, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        pad(t, 6, 6);
        t.setOnHoverListener(this);
        return t;
    }

    @SuppressWarnings("deprecation")
    private TextView html(String h, float sp, int color) {
        TextView t = text("", sp, color);
        t.setText(Html.fromHtml(h));
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 34, WHITE);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setLetterSpacing(0.08f);
        return t;
    }

    private void spacerHeader(LinearLayout page, String title) {
        page.addView(new View(this), lp(-1, 40, 0, 0));
        page.addView(header(title));
    }

    private TextView button(String label, View.OnClickListener onClick) {
        TextView b = text(label, 15, WHITE);
        b.setTypeface(null, Typeface.BOLD);
        b.setLetterSpacing(0.15f);
        b.setGravity(Gravity.CENTER);
        pad(b, 26, 12);
        b.setBackground(rounded(BLACK, 200, px(2), WHITE));
        b.setOnClickListener(onClick);
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setTextColor(WHITE);
        e.setHintTextColor(0xff5a5a5a);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextSize(20);
        pad(e, 18, 12);
        e.setBackground(rounded(0xff0d0d0d, 36, px(1), 0xff3a3a3a));
        e.setOnHoverListener(this);
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(14), 0, px(14));
        return r;
    }

    /** White glow on any text the pointer hovers over. */
    @Override
    public boolean onHover(View v, MotionEvent event) {
        if (v instanceof TextView) {
            int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_ENTER) ((TextView) v).setShadowLayer(18, 0, 0, WHITE);
            else if (a == MotionEvent.ACTION_HOVER_EXIT) ((TextView) v).setShadowLayer(0, 0, 0, 0);
        }
        return false;
    }
}
