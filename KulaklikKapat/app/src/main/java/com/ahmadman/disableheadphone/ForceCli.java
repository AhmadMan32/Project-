package com.ahmadman.disableheadphone;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * Tiny command-line helper. It is NOT run inside the app: {@link AdbForce} starts it over the
 * built-in wireless debugging connection with
 * {@code CLASSPATH=<this apk> app_process /system/bin com.ahmadman.disableheadphone.ForceCli},
 * so it runs as the ADB shell user (uid 2000).
 *
 * Since Android 9 audioserver only accepts {@code AudioSystem.setForceUse} from callers holding
 * MODIFY_AUDIO_ROUTING. Normal apps cannot get it, but the shell user has it, so from here media
 * audio can be forced to the speaker on its normal (non-call) audio path.
 *
 * Protocol (one line each way): "get" -> "OK <force use for media>",
 * "set <config>" -> "OK <status>", "quit" exits. Errors are answered with "ERR <reason>".
 * It prints "READY <uid>" once at start. It exits when its input closes, i.e. when the app or the
 * ADB connection goes away.
 *
 * Keep this class self-contained: no Context, resources or other app classes.
 */
public final class ForceCli {

    private static final int FOR_MEDIA = 1;

    private ForceCli() {
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        Method getForceUse;
        Method setForceUse;
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            getForceUse = audioSystem.getMethod("getForceUse", int.class);
            setForceUse = audioSystem.getMethod("setForceUse", int.class, int.class);
        } catch (Throwable t) {
            out.println("ERR reflection " + t);
            out.flush();
            return;
        }
        out.println("READY " + android.os.Process.myUid());
        out.flush();

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    if (line.equals("get")) {
                        out.println("OK " + getForceUse.invoke(null, FOR_MEDIA));
                    } else if (line.startsWith("set ")) {
                        int config = Integer.parseInt(line.substring(4).trim());
                        out.println("OK " + setForceUse.invoke(null, FOR_MEDIA, config));
                    } else if (line.equals("quit")) {
                        break;
                    } else {
                        out.println("ERR unknown command");
                    }
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    out.println("ERR " + cause.toString().replace('\n', ' '));
                }
                out.flush();
            }
        } catch (Throwable ignored) {
            // input closed
        }
        System.exit(0);
    }
}
