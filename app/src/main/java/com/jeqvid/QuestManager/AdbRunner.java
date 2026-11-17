package com.jeqvid.QuestManager;

import android.content.Context;

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
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new Exception(String.valueOf(cause));
        }
        return connected;
    }

    @Override
    public Result run(String command) {
        if (!connected) return new Result(false, "Not connected to wireless ADB.");
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
            return new Result(false, "Lost the wireless ADB connection (" + e.getMessage() + "). Reconnect in the WIRELESS ADB tab.");
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
