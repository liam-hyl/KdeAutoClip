package com.kdeautoclip.module;

import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Logging helper.
 *
 * Everything is written both to the LSPosed log (XposedBridge) and to logcat with the tag
 * {@code KdeAutoClip}. Hot hook paths use rate limited logging so a chatty system call can never
 * flood the log (system_server log spam would be a real performance problem).
 *
 * Every logging call is wrapped in try/catch: a module must never take down system_server.
 */
public final class ModuleLog {

    public static final String TAG = "KdeAutoClip";

    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<String, Integer>();

    private ModuleLog() {
    }

    public static void i(String message) {
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    public static void error(String message, Throwable throwable) {
        i("ERROR " + message + (throwable == null ? "" : " (" + throwable + ")"));
        if (throwable != null) {
            try {
                XposedBridge.log(throwable);
            } catch (Throwable ignored) {
            }
        }
    }

    /** First {@code max} messages for a key, then silence. */
    public static void first(String key, int max, String message) {
        if (bump(key) <= max) {
            i(message);
        }
    }

    /** Logs the first 5 occurrences of a key and then every 200th. */
    public static void limited(String key, String message) {
        int count = bump(key);
        if (count <= 5 || count % 200 == 0) {
            i(message + " [#" + count + "]");
        }
    }

    public static void once(String key, String message) {
        if (bump(key) == 1) {
            i(message);
        }
    }

    public static void errorLimited(String key, String message, Throwable throwable) {
        int count = bump(key);
        if (count <= 3) {
            error(message + " [#" + count + "]", throwable);
        }
    }

    private static int bump(String key) {
        Integer previous = COUNTS.get(key);
        int next = previous == null ? 1 : previous.intValue() + 1;
        COUNTS.put(key, Integer.valueOf(next));
        return next;
    }
}
