package com.kdeautoclip.module;

import android.app.AppOpsManager;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks inside system_server that unblock clipboard access for KDE Connect.
 *
 * <p>Android 10+ only lets the focused app, the default IME (or a privileged system/shell
 * component that holds {@code READ_CLIPBOARD_IN_BACKGROUND}) read the clipboard. KDE Connect's
 * clipboard plugin already registers an {@code OnPrimaryClipChangedListener} and sends a
 * {@code kdeconnect.clipboard} packet on every change; it merely fails to read in the background.
 * Granting the read here is enough for the app's own automatic sync to work - the KDE app itself is
 * not touched.</p>
 *
 * <p>All hooks are filtered by package name ({@link Target}) and installed by method name so that
 * the different AOSP signatures (Android 12/13 vs 14/15/16) and vendor variants are all covered.
 * A failure to hook anything is logged and must never crash system_server.</p>
 */
public final class SystemHooks {

    private static final String CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService";

    private static int sAccessHooks;
    private static int sImeHooks;
    private static int sNotificationHooks;
    private static int sOpRead = -1;
    private static int sOpWrite = -1;

    private SystemHooks() {
    }

    public static void install(ClassLoader classLoader) {
        Class<?> service;
        try {
            service = XposedHelpers.findClassIfExists(CLIPBOARD_SERVICE, classLoader);
        } catch (Throwable throwable) {
            ModuleLog.error("cannot resolve " + CLIPBOARD_SERVICE, throwable);
            return;
        }
        if (service == null) {
            ModuleLog.error("ClipboardService not found: " + CLIPBOARD_SERVICE
                    + " (this ROM may use a different clipboard service)", null);
            return;
        }
        ModuleLog.i("ClipboardService found: " + service.getName());
        resolveAppOps();
        dumpRelevantMethods(service);

        sAccessHooks = hookAccessAllowed(service);
        sImeHooks = hookDefaultIme(service);
        sNotificationHooks = hookAccessNotification(service);

        ModuleLog.i("system hooks: clipboardAccessAllowed=" + sAccessHooks
                + " isDefaultIme=" + sImeHooks
                + " showAccessNotificationLocked=" + sNotificationHooks
                + " opRead=" + sOpRead + " opWrite=" + sOpWrite);

        if (sAccessHooks == 0 && sImeHooks == 0) {
            ModuleLog.error("no usable clipboard hook installed: this ROM's clipboard service differs"
                    + " from AOSP; please report the log", null);
        }
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The central gate. Returning true skips the focus check, the read AppOps check and any vendor
     * additions inside the method (HyperOS checks its per-app clipboard AppOp from here).
     */
    private static int hookAccessAllowed(Class<?> service) {
        try {
            Set<XC_MethodHook.Unhook> hooked = XposedBridge.hookAllMethods(
                    service,
                    "clipboardAccessAllowed",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Target.matchesArgs(param.args)) {
                                    return;
                                }
                                if (!returnsBoolean(param)) {
                                    return;
                                }
                                // No op filter: this method only ever governs clipboard read/write,
                                // and vendor variants may not put the op in argument 0. Allow every
                                // matching call, which also covers unknown vendor signatures.
                                param.setResult(Boolean.TRUE);
                                ModuleLog.limited("access.allow",
                                        "allow clipboardAccessAllowed op=" + firstInt(param.args)
                                                + " " + Target.describe(param.args));
                            } catch (Throwable throwable) {
                                ModuleLog.errorLimited("access.hook.err",
                                        "clipboardAccessAllowed hook error", throwable);
                            }
                        }
                    });
            logMethodSignatures(service, "clipboardAccessAllowed");
            return hooked == null ? 0 : hooked.size();
        } catch (Throwable throwable) {
            ModuleLog.error("cannot hook clipboardAccessAllowed", throwable);
            return 0;
        }
    }

    /**
     * Second, independent grant: the default IME is always allowed to read the clipboard. Claiming
     * that role for KDE Connect also suppresses AOSP's "pasted from your clipboard" toast, which is
     * skipped for the default IME (otherwise every background read would show it).
     */
    private static int hookDefaultIme(Class<?> service) {
        try {
            Set<XC_MethodHook.Unhook> hooked = XposedBridge.hookAllMethods(
                    service,
                    "isDefaultIme",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!returnsBoolean(param)) {
                                    return;
                                }
                                if (Target.matchesArgs(param.args)) {
                                    param.setResult(Boolean.TRUE);
                                    ModuleLog.limited("ime.allow",
                                            "allow isDefaultIme " + Target.describe(param.args));
                                }
                            } catch (Throwable throwable) {
                                ModuleLog.errorLimited("ime.hook.err",
                                        "isDefaultIme hook error", throwable);
                            }
                        }
                    });
            logMethodSignatures(service, "isDefaultIme");
            return hooked == null ? 0 : hooked.size();
        } catch (Throwable throwable) {
            ModuleLog.error("cannot hook isDefaultIme", throwable);
            return 0;
        }
    }

    /**
     * Fallback toast suppression in case the isDefaultIme signature differs on this ROM.
     * Argument 0 is the calling package on every AOSP version (12-16).
     */
    private static int hookAccessNotification(Class<?> service) {
        try {
            Set<XC_MethodHook.Unhook> hooked = XposedBridge.hookAllMethods(
                    service,
                    "showAccessNotificationLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object[] args = param.args;
                                if (args == null || args.length == 0 || !Target.isTargetPackage(args[0])) {
                                    return;
                                }
                                Method method = (Method) param.method;
                                if (method != null && method.getReturnType() != void.class) {
                                    ModuleLog.once("toast.type", "showAccessNotificationLocked return type "
                                            + method.getReturnType() + " - not suppressing");
                                    return;
                                }
                                param.setResult(null);
                                ModuleLog.limited("toast.suppress",
                                        "suppressed clipboard access toast for " + Target.PACKAGE);
                            } catch (Throwable throwable) {
                                ModuleLog.errorLimited("toast.hook.err",
                                        "showAccessNotificationLocked hook error", throwable);
                            }
                        }
                    });
            logMethodSignatures(service, "showAccessNotificationLocked");
            return hooked == null ? 0 : hooked.size();
        } catch (Throwable throwable) {
            ModuleLog.error("cannot hook showAccessNotificationLocked", throwable);
            return 0;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void resolveAppOps() {
        try {
            sOpRead = XposedHelpers.getStaticIntField(AppOpsManager.class, "OP_READ_CLIPBOARD");
        } catch (Throwable ignored) {
        }
        try {
            sOpWrite = XposedHelpers.getStaticIntField(AppOpsManager.class, "OP_WRITE_CLIPBOARD");
        } catch (Throwable ignored) {
        }
    }

    private static boolean returnsBoolean(XC_MethodHook.MethodHookParam param) {
        try {
            Method method = (Method) param.method;
            return method != null && method.getReturnType() == boolean.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Integer firstInt(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return null;
    }

    private static void dumpRelevantMethods(Class<?> service) {
        try {
            StringBuilder builder = new StringBuilder();
            int count = 0;
            for (Method method : service.getDeclaredMethods()) {
                String name = method.getName();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("clipboard") || lower.contains("defaultime")
                        || lower.contains("notification") || lower.contains("intendingdevice")) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(name);
                    count++;
                    if (count >= 40) {
                        builder.append(", ...");
                        break;
                    }
                }
            }
            ModuleLog.i("ClipboardService methods: " + builder);
        } catch (Throwable throwable) {
            ModuleLog.errorLimited("dump.err", "cannot dump ClipboardService methods", throwable);
        }
    }

    private static void logMethodSignatures(Class<?> service, String name) {
        try {
            StringBuilder builder = new StringBuilder();
            for (Method method : service.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    if (builder.length() > 0) {
                        builder.append(" | ");
                    }
                    builder.append(method.toString());
                }
            }
            ModuleLog.once("sig." + name, "signature " + name + ": " + builder);
        } catch (Throwable ignored) {
        }
    }
}
