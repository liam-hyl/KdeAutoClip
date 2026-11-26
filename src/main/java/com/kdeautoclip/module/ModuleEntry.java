package com.kdeautoclip.module;

import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * KdeAutoClip entry point.
 *
 * <p>Only system_server matters: lpparam.packageName is {@code "android"} there. The module grants
 * background clipboard access to KDE Connect so that the app's own clipboard observer can send
 * clipboard changes to the desktop without the user opening the app.</p>
 *
 * <p>Design constraints: never crash system_server, never change behaviour for other packages,
 * keep the hot path allocation free.</p>
 */
public class ModuleEntry implements IXposedHookLoadPackage {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(LoadPackageParam loadPackageParam) {
        try {
            if (loadPackageParam == null || !"android".equals(loadPackageParam.packageName)) {
                return;
            }
            if (!INSTALLED.compareAndSet(false, true)) {
                return;
            }
            ModuleLog.i("init: system_server (android) sdk=" + Build.VERSION.SDK_INT
                    + " release=" + Build.VERSION.RELEASE
                    + " target=" + Target.PACKAGE);
            try {
                SystemHooks.install(loadPackageParam.classLoader);
            } catch (Throwable throwable) {
                ModuleLog.error("SystemHooks.install failed", throwable);
            }
            try {
                MiuiHooks.install(loadPackageParam.classLoader);
            } catch (Throwable throwable) {
                ModuleLog.error("MiuiHooks.install failed", throwable);
            }
            ModuleLog.i("init: done");
        } catch (Throwable throwable) {
            // Last line of defence: a vendor Xposed bridge detail must not kill system_server.
            try {
                ModuleLog.error("handleLoadPackage failed", throwable);
            } catch (Throwable ignored) {
            }
        }
    }
}
