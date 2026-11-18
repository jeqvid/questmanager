package com.jeqvid.QuestManager;

import android.content.Context;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.muntashirakon.adb.AdbStream;

/** Quest 2 / 3 / 3S / Pro path: runs commands through the headset's own wireless debugging. */
public class AdbRunner implements CommandRunner {

    static final String HOST = "127.0.0.1";
    /** Fixed ADB port (same as "adb tcpip 5555"): survives wireless debugging toggling off, until reboot. */
    static final int FIXED_PORT = 5555;
    private static final String RC_MARK = "__QM_RC=";

    private final Context context;
    private volatile boolean connected;
    private volatile int connectedPort;

    public AdbRunner(Context context) {
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
        connected = AdbManager.get(context).connect(HOST, port);
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
     * Makes the connection last: pairing never expires, and ADB moves to fixed port 5555 so Quest Manager can
     * reconnect by itself when wireless debugging switches off (e.g. after a refresh rate change).
     * Returns a short message for the UI.
     */
    public String makeStable() {
        // 1) Never forget the pairing (Android's default is 7 days without use).
        run("settings put global adb_allowed_connection_time 0");
        // 2) Already on the fixed port? Nothing else to do.
        if (connectedPort == FIXED_PORT) return "Connection locked in (port " + FIXED_PORT + ").";
        // 3) Same as "adb tcpip 5555": adbd restarts listening on the fixed port.
        try {
            AdbStream s = AdbManager.get(context).openStream("tcpip:" + FIXED_PORT);
            try {
                Streams.readAll(s.openInputStream());
            } catch (Exception ignored) {
                // adbd restarts and drops us: expected
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            return "Connected (couldn't switch to the fixed port: " + e.getMessage() + ").";
        }
        connected = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            sleep(700);
            if (tryConnect(FIXED_PORT)) return "Connection locked in: stays connected until you restart the headset.";
        }
        return "Connected, but the fixed port didn't come up. Reconnect with CONNECT if commands fail.";
    }

    /** Reconnects without the user: fixed port first, then the last wireless debugging port, then auto-detect. */
    public synchronized boolean reconnect(int lastWirelessPort) {
        disconnectQuietly();
        if (tryConnect(FIXED_PORT)) return true;
        if (lastWirelessPort > 0 && tryConnect(lastWirelessPort)) return true;
        try {
            return autoConnect(3000);
        } catch (Exception e) {
            return false;
        }
    }

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
        AdbStream stream = null;
        try {
            stream = AdbManager.get(context).openStream("shell:(" + command + ") 2>&1; echo " + RC_MARK + "$?");
            InputStream in = stream.openInputStream();
            String out = Streams.readAll(in);
            int mark = out.lastIndexOf(RC_MARK);
            if (mark < 0) return new Result(false, "No reply from the headset.\n" + out);
            String body = out.substring(0, mark);
            int code;
            try {
                code = Integer.parseInt(out.substring(mark + RC_MARK.length()).trim());
            } catch (NumberFormatException e) {
                code = -1;
            }
            if (code == 0) return new Result(true, body);
            return new Result(false, "exit code " + code + (body.trim().isEmpty() ? "" : "\n" + body.trim()));
        } catch (Exception e) {
            connected = false;
            return new Result(false, LOST + " (" + e.getMessage() + "). Reconnect in the WIRELESS ADB tab.");
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
