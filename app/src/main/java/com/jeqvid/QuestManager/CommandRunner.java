package com.jeqvid.QuestManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Runs a shell command with shell-level rights (root via su, or wireless ADB). */
public interface CommandRunner {

    /** True when commands can be sent right now. */
    boolean isReady();

    /** Runs the command and waits for it to finish. Never returns null. */
    Result run(String command);

    /** Short name shown in the UI. */
    String name();

    final class Result {
        public final boolean ok;
        public final String output;

        public Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output == null ? "" : output.trim();
        }
    }

    final class Streams {
        private Streams() {}

        static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), "UTF-8");
        }
    }
}
