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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
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
    private static final int HZ = 0, ONE = 1, BOTH = 2, SIZE = 3;

    private static final class Setting {
        final String title, hint, tag;
        final int kind, lo, hi;
        final String[] props;
        EditText input;
        TextView status, current;

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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<TextView> tabs = new ArrayList<>();
    private final List<View> pages = new ArrayList<>();
    private final List<Setting> allSettings = new ArrayList<>();
    private float density;
    private ScrollView scroll;
    private int setupTab = -1;
    private SharedPreferences prefs;
    private ValueAnimator rainbowAnim;

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
            adb = new AdbRunner(this);
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
                new Setting("REFRESH RATE", "Hz, e.g. 72", null, HZ, 0, 0, P + "refreshRate"),
                new Setting("SWAP INTERVAL", "e.g. 1", null, ONE, 1, 4, P + "swapInterval")));
        addTab(side, "RESOLUTION", pageOf(
                new Setting("RESOLUTION", "Width & height, e.g. 1440", null, BOTH, 512, 4096,
                        P + "textureWidth", P + "textureHeight")));
        addTab(side, "RECORDING", recordingPage());
        addTab(side, "EXPERIMENTAL", pageOf(
                new Setting("EXTRA KICKOFF HEADROOM", "0 or 1", "V80 AND ABOVE", ONE, 0, 1, P + "extraKickoffHeadroom"),
                new Setting("PHASE SYNC", "0 - 4 (sets sync, padding & delay)", "V67 MAX", BOTH, 0, 4,
                        P + "phaseSync", P + "PhaseSyncAdditionalPadding", P + "PhaseSyncDelayOverride"),
                new Setting("VSYNC EMULATION", "0 or 1", null, ONE, 0, 1, P + "vsyncEmu")));

        side.addView(new View(this), lp(-1, 0, 1f, 0)); // pushes the rest to the bottom
        if (!quest1) setupTab = addTab(side, "WIRELESS ADB", setupPage());
        int infoTab = addTab(side, "INFO", infoPage());
        addTab(side, "SETTINGS", settingsPage());

        root.addView(side, lp(220, -1, 0, 0));
        View divider = new View(this);
        divider.setBackgroundColor(0xff1c1c1c);
        root.addView(divider, lp(1, -1, 0, 0));
        root.addView(scroll, lp(0, -1, 1f, 0));
        setContentView(root);

        selectTab(infoTab);
        if (adb != null) startupConnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rainbowAnim != null) rainbowAnim.cancel();
        worker.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrent();
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
                + "2. Tap <b>OPEN ANDROID SETTINGS</b>, go to <b>System &gt; Developer options &gt; Wireless debugging</b> "
                + "and turn it on. Keep that window open next to this one.<br>"
                + "3. Tap <b>Pair device with pairing code</b>. Type the 6-digit code and the port it shows, then tap <b>PAIR</b>.<br>"
                + "4. On the Wireless debugging screen, type the port shown under <b>IP address &amp; Port</b> "
                + "(the number after the colon) and tap <b>CONNECT</b>. Leave it empty to find it automatically.",
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
        if (adb != null) {
            connStatus = text("Wireless ADB: not connected", 16, 0xffdddddd);
            page.addView(connStatus);
            LinearLayout r = row();
            r.addView(button("WIRELESS ADB SETUP", v -> selectTab(setupTab)), lp(-2, -2, 0, 0));
            page.addView(r);
        }
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
        return page;
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
        LinearLayout r = row();
        s.input = input(s.hint);
        r.addView(s.input, lp(0, -2, 1f, 0));
        r.addView(button("APPLY", v -> apply(s)), lp(-2, -2, 0, 14));
        r.addView(button("RESET", v -> reset(s)), lp(-2, -2, 0, 14));
        page.addView(r);
        s.status = text("", 14, STATUS);
        page.addView(s.status);
    }

    // ---------------------------------------------------------------- actions
    private void apply(Setting s) {
        String text = s.input.getText().toString().trim();
        String cmd;
        if (s.kind == SIZE) {
            int[] wh = parseSize(text, s.lo, s.hi);
            if (wh == null) {
                s.status.setText("Not applied: type a size like 1024x1024 (each side " + s.lo + " to " + s.hi + ").");
                return;
            }
            cmd = "setprop " + s.props[0] + " " + wh[0] + " && setprop " + s.props[1] + " " + wh[1];
        } else {
            int v = parseInt(text);
            if (s.kind == HZ) {
                if (v < 0) {
                    s.status.setText("Type a whole number first.");
                    return;
                }
                int[] allowed = allowedRates();
                if (!contains(allowed, v)) {
                    s.status.setText("Not applied: " + v + " Hz isn't supported on " + modelName() + ". Allowed: " + join(allowed));
                    return;
                }
            } else if (v < s.lo || v > s.hi) {
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
                status.setText(r.ok ? "OK  " + r.output : "Failed: " + r.output);
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
        worker.execute(() -> {
            boolean ok = false;
            try {
                if (saved > 0) ok = adb.connect(saved);
            } catch (Exception ignored) {
            }
            if (!ok) {
                try {
                    ok = adb.autoConnect(4000);
                } catch (Exception ignored) {
                }
            }
            final boolean connected = ok;
            runOnUiThread(() -> {
                updateConnStatus();
                if (connected) {
                    setupStatus.setText("Connected to wireless ADB.");
                    refreshCurrent();
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
        worker.execute(() -> {
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
        worker.execute(() -> {
            String msg;
            try {
                boolean ok = port > 0 ? adb.connect(port) : adb.autoConnect(6000);
                if (ok) {
                    if (port > 0) prefs.edit().putInt("port", port).apply();
                    msg = "Connected to wireless ADB. You're good to go!";
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
            });
        });
    }

    private void updateConnStatus() {
        if (connStatus != null && adb != null) {
            connStatus.setText(adb.isReady() ? "Wireless ADB: connected" : "Wireless ADB: not connected");
        }
    }

    private void openAndroidSettings() {
        Intent[] tries = {
                new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings")),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        setupStatus.setText("Couldn't open Android settings on this headset.");
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

    private static int[] allowedRates() {
        String m = Build.MODEL;
        if ("Quest".equals(m)) return new int[]{60, 72};
        if ("Quest Pro".equals(m)) return new int[]{72, 90};
        if ("Quest 3".equals(m) || "Quest 3S".equals(m)) return new int[]{72, 80, 90, 120};
        return new int[]{60, 72, 80, 90, 120}; // Quest 2 and anything else
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

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static String join(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int x : a) {
            if (b.length() > 0) b.append(", ");
            b.append(x);
        }
        return b.toString();
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
