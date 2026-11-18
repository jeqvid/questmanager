package com.jeqvid.QuestManager;

import android.content.Context;
import android.util.Log;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.muntashirakon.adb.AdbStream;

/** Quest 2 / 3 / 3S / Pro path: runs commands through the headset's own wireless debugging. */
public class AdbRunner implements CommandRunner {

    static final String HOST = "127.0.0.1";
    private static final String RC_MARK = "__QM_RC=";

    private final Context context;
    private volatile boolean connected;
    private volatile int connectedPort;

    private static AdbRunner instance;

    /** One connection for the whole app, so it survives the window being recreated (e.g. after a Hz change). */
    public static synchronized AdbRunner get(Context context) {
        if (instance == null) instance = new AdbRunner(context);
        return instance;
    }

    private AdbRunner(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean isReady() {
        return connected;
    }

    @Override
    public String name() {
        return "Wireless ADB";
    }

    /** Pairs using the code + port from "Pair device with pairing code". */
    public boolean pair(int port, String code) throws Exception {
        return AdbManager.get(context).pair(HOST, port, code);
    }

    /** Connects to the port shown under "IP address & Port". */
    public boolean connect(int port) throws Exception {
        try {
            connected = AdbManager.get(context).connect(HOST, port);
        } catch (Exception e) {
            Log.w(TAG, "connect(" + port + ") failed", e);
            throw e;
        }
        Log.i(TAG, "connect(" + port + ") = " + connected);
        connectedPort = connected ? port : 0;
        return connected;
    }

    /**
     * Finds the wireless debugging port by itself (mDNS). Called by reflection because
     * older libadb-android versions don't have autoConnect(); then the port must be typed.
     */
    public boolean autoConnect(long timeoutMs) throws Exception {
        AdbManager manager = AdbManager.get(context);
        Method method;
        try {
            method = manager.getClass().getMethod("autoConnect", Context.class, long.class);
        } catch (NoSuchMethodException e) {
            throw new Exception("auto-detect isn't available in this build, type the port instead");
        }
        try {
            connected = Boolean.TRUE.equals(method.invoke(manager, context, timeoutMs));
            connectedPort = -1; // found by auto-detect, port unknown
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new Exception(String.valueOf(cause));
        }
        return connected;
    }

    /**
     * Makes the pairing last: it never expires, and Quest Manager gets permission to switch Wireless debugging
     * back on by itself when it turns off (after a display change or a restart). Returns a short UI message.
     */
    public String makeStable() {
        Result keep = run("settings put global adb_allowed_connection_time 0");
        Result grant = run("pm grant " + context.getPackageName() + " " + WRITE_SECURE);
        Log.i(TAG, "makeStable: keep=" + keep.ok + " '" + keep.output + "' grant=" + grant.ok + " '" + grant.output + "'");
        if (keep.ok && grant.ok) return "Pairing saved for good: Quest Manager reconnects by itself from now on.";
        if (!connected) return "Connected, but the connection dropped right after (" + grant.output + ").";
        return "Connected. (Auto-reconnect setup: " + (keep.ok ? "" : "keep-pairing failed: " + keep.output + " ")
                + (grant.ok ? "" : "permission failed: " + grant.output) + ")";
    }

    /**
     * Reconnects without the user. Keeps trying for ~15 s (the headset can take a moment after a display change):
     * the last wireless debugging port, then auto-detect (the port changes when Wireless debugging restarts). If it
     * got switched off, it switches it back on by itself (needs WRITE_SECURE_SETTINGS, which makeStable() grants).
     */
    public synchronized boolean reconnect(int lastWirelessPort) {
        disconnectQuietly();
        boolean reenabled = false;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (lastWirelessPort > 0 && tryConnect(lastWirelessPort)) return true;
            try {
                if (autoConnect(3000)) return true;
            } catch (Exception ignored) {
            }
            if (!reenabled) reenabled = enableWirelessDebugging(); // it may have switched off: turn it back on
            sleep(1500);
        }
        return false;
    }

    /** Turns Android's Wireless debugging back on, if Quest Manager has been allowed to. */
    public boolean enableWirelessDebugging() {
        if (context.checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            Settings.Global.putInt(context.getContentResolver(), "adb_enabled", 1);
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    private boolean tryConnect(int port) {
        try {
            return connect(port);
        } catch (Exception e) {
            return false;
        }
    }

    private void disconnectQuietly() {
        connected = false;
        try {
            AdbManager m = AdbManager.get(context);
            m.getClass().getMethod("disconnect").invoke(m);
        } catch (Exception ignored) {
            // older libadb without disconnect(): a new connect() replaces the connection
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private int lastWirelessPort;

    public void setLastWirelessPort(int port) {
        lastWirelessPort = port;
    }

    @Override
    public Result run(String command) {
        Result r = runOnce(command);
        if (r.output.startsWith(LOST) && reconnect(lastWirelessPort)) {
            r = runOnce(command); // the connection dropped (e.g. after a Hz change): reconnected, try again
        }
        return r;
    }

    private static final String LOST = "Lost the wireless ADB connection";

    private Result runOnce(String command) {
        if (!connected) return new Result(false, LOST + ". Reconnect in the WIRELESS ADB tab.");
        Exception last = null;
        // A quick command can finish (and its stream close) before we start reading. That's not a dropped
        // connection, so open a fresh stream and try again a couple of times before giving up.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Result r = execOnce(command);
                if (r != null) return r;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "shell attempt " + (attempt + 1) + " failed: " + command, e);
            }
            sleep(250);
        }
        connected = false;
        Log.e(TAG, "giving up, treating connection as lost", last);
        return new Result(false, LOST + " (" + (last == null ? "no reply" : last.getMessage()) + "). Reconnect in the WIRELESS ADB tab.");
    }

    /** Runs one shell stream. Returns null if the reply got cut off (caller retries). */
    private Result execOnce(String command) throws Exception {
        AdbStream stream = AdbManager.get(context).openStream("shell:(" + command + ") 2>&1; echo " + RC_MARK + "$?");
        StringBuilder out = new StringBuilder();
        try {
            InputStream in = stream.openInputStream();
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = in.read(buf)) != -1) out.append(new String(buf, 0, n, "UTF-8"));
            } catch (java.io.IOException closed) {
                // stream closed under us: keep whatever arrived, the marker decides if it's complete
                Log.w(TAG, "stream closed while reading (" + out.length() + " chars so far)", closed);
            }
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        String all = out.toString();
        int mark = all.lastIndexOf(RC_MARK);
        if (mark < 0) {
            Log.w(TAG, "no exit marker in reply: '" + all + "'");
            return null;
        }
        String body = all.substring(0, mark);
        int code;
        try {
            code = Integer.parseInt(all.substring(mark + RC_MARK.length()).trim());
        } catch (NumberFormatException e) {
            code = -1;
        }
        if (code == 0) return new Result(true, body);
        return new Result(false, "exit code " + code + (body.trim().isEmpty() ? "" : "\n" + body.trim()));
    }

    private static final String TAG = "QuestManager";
}
