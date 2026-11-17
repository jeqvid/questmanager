package com.jeqvid.QuestManager;

/** Quest 1 path: runs commands through Magisk/Singularity su. */
public class RootRunner implements CommandRunner {

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public String name() {
        return "Root (su)";
    }

    @Override
    public Result run(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "(" + command + ") 2>&1"});
            String out = Streams.readAll(p.getInputStream());
            int code = p.waitFor();
            if (code == 0) return new Result(true, out);
            return new Result(false, "exit code " + code + ". Root (su) was denied or isn't available."
                    + (out.trim().isEmpty() ? "" : "\n" + out.trim()));
        } catch (Exception e) {
            return new Result(false, "Root (su) isn't available: " + e.getMessage());
        }
    }
}
