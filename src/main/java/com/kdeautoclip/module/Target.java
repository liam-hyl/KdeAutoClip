package com.kdeautoclip.module;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import de.robv.android.xposed.XposedHelpers;

/**
 * The single package this module grants clipboard access to.
 *
 * The framework identifies the clipboard caller by (uid, packageName) and some code paths pass the
 * uid as an app id (uid % 100000). We therefore accept both. The package name is the primary
 * filter; the uid is only used for code paths that pass no package name at all.
 */
public final class Target {

    public static final String PACKAGE = "org.kde.kdeconnect_tp";

    private static final Object LOCK = new Object();

    private static volatile boolean sInitialised;
    private static volatile int sUid = -1;
    private static volatile int sAppId = -1;

    private Target() {
    }

    /**
     * Returns true when one of the arguments identifies KDE Connect.
     * Must stay cheap: it runs on every clipboard access of every app.
     */
    public static boolean matchesArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        for (Object arg : args) {
            if (arg instanceof String && PACKAGE.equals(arg)) {
                return true;
            }
        }
        ensureInitialised();
        int uid = sUid;
        int appId = sAppId;
        if (uid > 0) {
            for (Object arg : args) {
                if (arg instanceof Integer) {
                    int value = ((Integer) arg).intValue();
                    if (value == uid || value == appId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isTargetPackage(Object arg) {
        return arg instanceof String && PACKAGE.equals(arg);
    }

    public static void ensureInitialised() {
        if (sInitialised) {
            return;
        }
        synchronized (LOCK) {
            if (sInitialised) {
                return;
            }
            try {
                Context context = systemContext();
                if (context != null) {
                    PackageManager packageManager = context.getPackageManager();
                    try {
                        PackageInfo info = packageManager.getPackageInfo(PACKAGE, 0);
                        if (info != null) {
                            if (info.applicationInfo != null) {
                                sUid = info.applicationInfo.uid;
                            }
                            if (sUid > 0) {
                                // UserHandle.getAppId() is hidden API; per-user uid range is a stable constant.
                                sAppId = sUid % 100000;
                            }
                            String version = info.versionName;
                            ModuleLog.i("target: " + PACKAGE
                                    + " uid=" + sUid
                                    + " appId=" + sAppId
                                    + " version=" + (version == null ? "?" : version));
                        }
                    } catch (Throwable throwable) {
                        ModuleLog.error("target: KDE Connect does not look installed", throwable);
                    }
                } else {
                    ModuleLog.i("target: system context unavailable, matching by package name only");
                }
            } catch (Throwable throwable) {
                ModuleLog.error("target: initialisation failed", throwable);
            } finally {
                sInitialised = true;
            }
        }
    }

    /** Compact, bounded rendering of hook arguments for diagnostics. */
    public static String describe(Object[] args) {
        if (args == null) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object arg = args[i];
            builder.append('#').append(i).append('=');
            if (arg == null) {
                builder.append("null");
            } else if (arg instanceof String) {
                builder.append("str(").append(arg).append(')');
            } else if (arg instanceof Integer) {
                builder.append("int(").append(arg).append(')');
            } else if (arg instanceof Boolean || arg instanceof Long) {
                builder.append(arg.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT))
                        .append('(').append(arg).append(')');
            } else {
                builder.append(arg.getClass().getSimpleName());
            }
            if (builder.length() > 320) {
                builder.append(", ...");
                break;
            }
        }
        return builder.append(']').toString();
    }

    private static Context systemContext() {
        Context application = processApplication();
        if (application != null) {
            return application;
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (activityThread != null) {
                Object context = XposedHelpers.callMethod(activityThread, "getSystemContext");
                if (context instanceof Context) {
                    return (Context) context;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** ActivityThread.currentActivityThread().getApplication() via reflection (no AndroidAppHelper dependency). */
    private static Context processApplication() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (activityThread != null) {
                Object application = XposedHelpers.callMethod(activityThread, "getApplication");
                if (application instanceof Context) {
                    return (Context) application;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
