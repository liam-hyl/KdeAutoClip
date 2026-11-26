package com.kdeautoclip.module;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Optional HyperOS / MIUI compatibility layer.
 *
 * <p>HyperOS keeps the AOSP {@code ClipboardService} but adds a vendor stub layer on top of it
 * (confirmed class name on retail HyperOS 2: {@code com.android.server.clipboard.ClipboardServiceStubImpl}).
 * Its gates are called for individual clipboard accesses and can deny a read even when the AOSP
 * check was bypassed. The hooks below only answer "allow" when the arguments actually identify KDE
 * Connect, so no other application gets access.</p>
 *
 * <p>Everything here is best effort: missing classes are fine (pure AOSP ROMs), unknown argument
 * shapes are logged once and never overridden.</p>
 */
public final class MiuiHooks {

    private static final String[] CANDIDATE_CLASSES = {
            "com.android.server.clipboard.ClipboardServiceStubImpl",
            "com.android.server.clipboard.ClipboardServiceInjector",
            "com.miui.server.clipboard.ClipboardServiceStubImpl",
            "com.miui.server.clipboard.MiuiClipboardService"
    };

    /** Vendor gate methods; values follow FuckClip's MIUI interceptor mapping (0/false = deny). */
    private static final String[] GATE_METHODS = {
            "clipboardAccessResult",
            "waitUserChoice",
            "checkProviderWakePathForClipboard",
            "clipboardAccessAllowed"
    };

    private static int sClassesFound;
    private static int sHooksInstalled;

    private MiuiHooks() {
    }

    public static void install(ClassLoader classLoader) {
        for (String className : CANDIDATE_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz == null) {
                    continue;
                }
                sClassesFound++;
                ModuleLog.i("vendor class found: " + className);
                dumpGateMethods(className, clazz);
                for (String methodName : GATE_METHODS) {
                    hookGate(clazz, className, methodName);
                }
            } catch (Throwable throwable) {
                ModuleLog.errorLimited("vendor.install." + className,
                        "vendor hook install failed for " + className, throwable);
            }
        }
        ModuleLog.i("vendor hooks: classesFound=" + sClassesFound
                + " methodsHooked=" + sHooksInstalled);
        if (sClassesFound == 0) {
            ModuleLog.i("vendor hooks: no known MIUI/HyperOS stub classes present (AOSP-style build)");
        }
    }

    private static void hookGate(Class<?> clazz, String className, final String methodName) {
        try {
            Set<XC_MethodHook.Unhook> hooked = XposedBridge.hookAllMethods(
                    clazz,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Target.matchesArgs(param.args)) {
                                    ModuleLog.first("vendor.unmatched." + methodName, 3,
                                            "vendor " + methodName + " (not KDE) "
                                                    + Target.describe(param.args));
                                    return;
                                }
                                Method method = (Method) param.method;
                                Class<?> returnType = method == null ? null : method.getReturnType();
                                if (returnType == boolean.class) {
                                    param.setResult(Boolean.TRUE);
                                } else if (returnType == int.class) {
                                    param.setResult(Integer.valueOf(0));
                                } else {
                                    ModuleLog.once("vendor.type." + methodName,
                                            "vendor " + methodName + " return type not supported: "
                                                    + returnType);
                                    return;
                                }
                                ModuleLog.limited("vendor.allow." + methodName,
                                        "vendor allow " + className + "#" + methodName
                                                + " " + Target.describe(param.args));
                            } catch (Throwable throwable) {
                                ModuleLog.errorLimited("vendor.hook." + methodName,
                                        "vendor hook error " + methodName, throwable);
                            }
                        }
                    });
            int count = hooked == null ? 0 : hooked.size();
            if (count > 0) {
                sHooksInstalled += count;
                ModuleLog.i("vendor hook installed: " + className + "#" + methodName + " x" + count);
            }
        } catch (Throwable throwable) {
            ModuleLog.errorLimited("vendor.gate." + className + "." + methodName,
                    "cannot hook " + className + "#" + methodName, throwable);
        }
    }

    private static void dumpGateMethods(String className, Class<?> clazz) {
        try {
            StringBuilder builder = new StringBuilder();
            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("clipboard") || name.contains("access") || name.contains("choice")
                        || name.contains("wake") || name.contains("provider")
                        || name.contains("result") || name.contains("modify")) {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(method.getName());
                    count++;
                    if (count >= 40) {
                        builder.append(", ...");
                        break;
                    }
                }
            }
            ModuleLog.i("vendor " + className + " gate-like methods: " + builder);
        } catch (Throwable throwable) {
            ModuleLog.errorLimited("vendor.dump." + className,
                    "cannot dump methods of " + className, throwable);
        }
    }
}
