# KdeAutoClip: Implementation Notes

[English](IMPLEMENTATION.md) | [简体中文](IMPLEMENTATION.zh-CN.md)

Version: 1.0.0

This document describes the framework access-control path that KdeAutoClip modifies, the KDE Connect behaviour the design relies on, the hook implementation, the build pipeline, verification results, and the items that remain unverified.

Primary sources used:

- AOSP `android16-release` `services/core/java/com/android/server/clipboard/ClipboardService.java` (cross-checked against `android12-release` … `android15-release`).
- KDE Connect Android `master` and tag `v1.37.3` (clipboard, share, device and plugin sources).
- Device test: Xiaomi HyperOS 3, Android 16 (API 36), KernelSU, LSPosed.

## 1. Scope

KdeAutoClip is an LSPosed module that runs inside `system_server` and modifies clipboard access decisions for the package `org.kde.kdeconnect_tp`. It does not modify the KDE Connect application, does not implement the KDE Connect protocol, and does not require root privileges at runtime.

The module is limited to Android framework hooks. KDE Connect already implements the client side of clipboard synchronization; the module only removes the framework restriction that prevents it from running while the application is in the background.

## 2. Android clipboard access control

### 2.1 Gate method

Clipboard reads and writes pass through `ClipboardService#clipboardAccessAllowed(...)`. The method exists from Android 10 onward and has two signatures per release (a convenience overload that delegates with `shouldNoteOp = true`).

| Android | Signature |
|---|---|
| 12, 13 | `clipboardAccessAllowed(int op, String callingPackage, int uid, int userId[, boolean shouldNoteOp])` |
| 14, 15, 16, main | `clipboardAccessAllowed(int op, String callingPackage, String attributionTag, int uid, int userId, int intendingDeviceId[, boolean shouldNoteOp])` |

The `callingPackage` parameter is always index 1 (index 1 in the four/five-argument form, index 1 in the six/seven-argument form). `uid` is index 3 in the 14+ form and index 2 in the 12/13 form.

### 2.2 Decision order (Android 16)

```java
mAppOps.checkPackage(uid, callingPackage);

if (intendingDeviceId == DEVICE_ID_INVALID) {
    return false;
}

if (mPm.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, callingPackage)
        == PackageManager.PERMISSION_GRANTED) {
    allowed = true;
} else {
    allowed = isDefaultIme(userId, callingPackage);
}

switch (op) {
    case OP_READ_CLIPBOARD:
        if (!allowed) {
            allowed = isDefaultDeviceAndUidFocused(intendingDeviceId, uid)
                    || isVirtualDeviceAndUidFocused(intendingDeviceId, uid)
                    || isInternalSysWindowAppWithWindowFocus(callingPackage);
        }
        // content capture, augmented autofill, VDM owner
        break;
    case OP_WRITE_CLIPBOARD:
        allowed = true;
        break;
}
if (!allowed) return false;
return mAppOps.noteOp(op, uid, callingPackage, attributionTag, null)
        == AppOpsManager.MODE_ALLOWED;
```

Properties relevant to the module:

1. There is no uid-based exemption. uid 0 does not pass `mAppOps.checkPackage`.
2. The default input method is exempt: `isDefaultIme(userId, packageName)` returns true for the configured IME package and bypasses the focus check.
3. `OP_READ_CLIPBOARD` requires focus, a privileged identity, or the IME exemption.
4. `OP_WRITE_CLIPBOARD` is not focus-restricted on AOSP (`allowed = true`), but the final `AppOps.noteOp` still applies, so an OEM policy can deny background writes.
5. The method returns before the final `AppOps` check if an earlier condition denies access. A hook that sets the result before the original method runs skips both the focus logic and the `AppOps` call.
6. `intendingDeviceId == DEVICE_ID_INVALID` causes an unconditional deny before any other check. On phones this value is normally `DEVICE_ID_DEFAULT`, but returning early from the hook also covers this branch.

### 2.3 Clipboard change dispatch

`addPrimaryClipChangedListener()` itself performs no access check. Notification dispatch evaluates the gate once per registered listener (`sendClipChangedBroadcast`, Android 16 around line 1049):

```java
if (clipboardAccessAllowed(OP_READ_CLIPBOARD,
        li.mPackageName, li.mAttributionTag, li.mUid,
        UserHandle.getUserId(li.mUid), clipboard.deviceId)) {
    clipboard.primaryClipListeners.getBroadcastItem(i).dispatchPrimaryClipChanged();
}
```

Consequence: granting access to a listener's package is sufficient for both the notification callback and the subsequent read.

### 2.4 Read notification

`getPrimaryClip()` calls `showAccessNotificationLocked(callingPackage, uid, userId, clipboard, accessDeviceId)`, which posts the system toast “pasted from your clipboard”. The method returns early when:

- the reading app is the clipboard owner,
- `isDefaultIme(userId, callingPackage)` is true,
- the caller is the content-capture or augmented-autofill service,
- the caller holds `SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION`,
- the uid was already notified for the current clip (`clipboard.mNotifiedUids`; cleared on each new clip).

`getPrimaryClipDescription()`, `hasPrimaryClip()` and `hasClipboardText()` call the gate with `shouldNoteOp = false` and do not show the notification.

### 2.5 Device lock

`getPrimaryClip()` additionally returns `null` when `isDeviceLocked(intendingUserId, deviceId)` is true, independent of the access decision. A locked device therefore blocks reads even for a granted package.

## 3. KDE Connect clipboard behaviour

### 3.1 Plugin lifecycle

`PluginFactory` instantiates plugins per device. `Device.reloadPluginsFromSettings()` calls `addPlugin()` for every supported plugin when the device `isPaired` and `isReachable`; a plugin is created only when enabled for that device. `ClipboardPlugin` does not override `isEnabledByDefault` (default `true`) and declares no required permissions, so it is created by default when the device connects.

### 3.2 Listener registration and send path

`ClipboardPlugin.onCreate()` registers an observer on the process-wide listener and sends the current clipboard with a timestamp:

```kotlin
override fun onCreate() {
    ClipboardListener.instance(context).registerObserver(observer)
    sendConnectPacket()
}
```

The listener registers the system callback unconditionally in its constructor:

```kotlin
Handler(Looper.getMainLooper()).post {
    cm = context.getSystemService<ClipboardManager>()!!
    cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
}
```

On a change, `onClipboardChanged()` reads `cm.primaryClip`, compares it with the cached content, and notifies the observers. The comparison against `currentContent == content` prevents duplicate sends for identical content. A `SecurityException` or `null` clip is caught by the surrounding `try/catch` and ignored, which is why a denied background read produces no packet.

The plugin observer sends the packet:

```kotlin
fun propagateClipboard(content: String) {
    val np = NetworkPacket("kdeconnect.clipboard")
    np["content"] = content
    device.sendPacket(np)
}
```

`Device.sendPacket()` enqueues the packet; it is dropped only when the device has no active link, in which case the failure callback runs. Background state does not affect the send path.

### 3.3 READ_LOGS condition

`ClipboardPlugin.canSyncAutomatically(context)` returns true when the app holds `READ_LOGS`. The condition controls two things only:

1. `ClipboardListener` starts a `logcat` process that watches for `ClipboardService` denial lines and launches `ClipboardFloatingActivity` to obtain window focus and read the clipboard.
2. The plugin UI shows either a “Send clipboard” button or a menu entry.

Observer registration, clipboard reads and packet sending are not conditioned on `canSyncAutomatically()`. With this module installed, `READ_LOGS` is not required; the listener callback fires normally and the read succeeds.

### 3.4 Receiving direction and duplicate suppression

`ClipboardPlugin.onPacketReceived()` handles two packet types:

- `kdeconnect.clipboard`: calls `ClipboardListener.setText(content)`.
- `kdeconnect.clipboard.connect`: compares the packet `timestamp` with the listener's update timestamp, then calls `setText()` if `content` is present.

`setText()` updates `currentContent` before writing the clipboard:

```kotlin
if (text == currentContent) return
updateTimestamp = System.currentTimeMillis()
currentContent = text
currentContentType = ClipboardContentType.Text
cm.setPrimaryClip(ClipData.newPlainText(null, text))
```

The subsequent listener callback sees `content == currentContent` and returns, so a received clipboard packet is not sent back.

`SharePlugin.receiveText()` writes the clipboard directly:

```kotlin
private fun receiveText(np: NetworkPacket) {
    val cm = context.getSystemService<ClipboardManager>()!!
    val text = np.getString("text")
    cm.setPrimaryClip(ClipData.newPlainText(null, text))
    ...
}
```

This bypasses `ClipboardListener`, so `currentContent` remains stale. With the module installed, a text received through the share plugin is processed as a local clipboard change and sent to the paired devices once. The desktop receives identical content, so its own change detection stops further propagation. This is an application-level behaviour, not a module defect; v1.0.0 does not suppress it.

### 3.5 Upstream fallback and Android 16

The upstream workaround uses `READ_LOGS` + `SYSTEM_ALERT_WINDOW` + a transparent focused activity. It is not usable as a base for this module:

- `READ_LOGS` is a protected permission and requires ADB or root to grant.
- HyperOS/MIUI denies background activity starts by default (`后台弹出界面`), so the transparent activity may never appear.
- Android 16 changed the `ClipboardService` log output; the current filter no longer matches, and upstream automatic synchronization fails on that release (KDE bug 525992). The proposed fix (KDE merge request 796) was not merged at the time of writing.
- The Shizuku variants of KDE Connect (upstream merge request 779) follow the same approach of running `logcat` as the shell user and using a focus-taking activity.

## 4. Alternatives considered

| Approach | Requirement | Reason not used |
|---|---|---|
| Reimplement the KDE Connect protocol in a root daemon | Pairing certificates, TLS identity handling, duplicate connection with the app | High complexity, conflicts with the app's own connection |
| KernelSU/Magisk module granting `READ_LOGS` and overlay permission | Protected permissions, background activity start | Fails on Android 16, blocked by MIUI background-start policy |
| Grant KDE Connect root and read the clipboard via `su`/`app_process` | Root authorization for an app, hidden-API access, binder reflection | Fragile across releases, expands privilege of the target app |
| Hook the default IME (Gboard) and forward the clipboard to KDE Connect | Cross-process channel, IME lifecycle | Additional IPC layer, no benefit over a framework hook |
| Poll the clipboard inside the KDE Connect process | Clipboard access must already be granted | Does not address the restriction; redundant if the framework path works |
| Hook `ClipboardService` in `system_server` (selected) | LSPosed System Framework scope | The only change required is the access decision; the application's existing send path then works |

The framework hook was selected because it requires no protocol changes, no additional permissions, no modification of the target application, and no persistent state. The cost is the requirement for the LSPosed System Framework scope.

## 5. Implementation

### 5.1 Module layout

| File | Responsibility |
|---|---|
| `ModuleEntry.java` | `IXposedHookLoadPackage`; acts only when `packageName == "android"`; installs both hook sets; contains the top-level exception guard |
| `SystemHooks.java` | Resolves `ClipboardService`; installs the three AOSP hooks; logs method signatures and relevant method names |
| `MiuiHooks.java` | Probes vendor clipboard stub classes; installs gate hooks filtered to KDE Connect |
| `Target.java` | Target package constant, argument matching, uid/appId resolution, bounded argument rendering |
| `ModuleLog.java` | Logging to both LSPosed log and logcat, with per-key rate limits |

### 5.2 Hooks

All hooks are installed with `XposedBridge.hookAllMethods` by method name, so overloads and vendor variants are covered, and no `Method` object is cached.

| Method | Filter | Action | Purpose |
|---|---|---|---|
| `clipboardAccessAllowed` (all overloads) | Any `String` argument equals `org.kde.kdeconnect_tp`, or any `int` argument equals the target uid/appId; method returns `boolean` | `setResult(true)` | Skips focus check, `AppOps`, and vendor additions for KDE Connect |
| `isDefaultIme` (all overloads) | Same; method returns `boolean` | `setResult(true)` | Second access path; suppresses the read notification for the package |
| `showAccessNotificationLocked` (all overloads) | `args[0]` equals the target package; method returns `void` | `setResult(null)` | Toast suppression when the IME hook does not apply |

Implementation notes:

- The `clipboardAccessAllowed` hook does not filter on the `op` argument. The method handles only `OP_READ_CLIPBOARD` and `OP_WRITE_CLIPBOARD` on AOSP; some OEM variants reorder the integer arguments (One UI 7 uses `(int op, int uid, int userId, int intendingDeviceId, String pkg, String attributionTag, boolean shouldNoteOp)`), so argument positions cannot be assumed.
- Return-type checks are required before `setResult`: Xposed cannot assign `null` to an arbitrary return type.
- `isDefaultIme` causes the `showAccessNotificationLocked` early return for the target package, so the third hook is a fallback rather than the primary toast suppression.

### 5.3 Matching and safety boundary

```java
public static boolean matchesArgs(Object[] args) {
    for (Object arg : args) {
        if (arg instanceof String && PACKAGE.equals(arg)) return true;
    }
    int uid = sUid, appId = sAppId;
    for (Object arg : args) {
        if (arg instanceof Integer && (arg == uid || arg == appId)) return true;
    }
    return false;
}
```

- Package-name equality is the primary match. The framework passes the calling package in every clipboard access path.
- The uid/appId comparison exists for vendor methods that pass no package name. `appId` is computed as `uid % 100000` because `UserHandle.getAppId()` is a hidden API.
- Initialization is lazy and occurs on the first clipboard access; no package-manager call runs during hook installation.
- If the target package is not installed, uid resolution fails and matching falls back to package name, which cannot produce a false positive for other packages.
- The only package constant in the module is `org.kde.kdeconnect_tp`.

### 5.4 HyperOS / MIUI compatibility

HyperOS retains the AOSP `ClipboardService` and adds a vendor stub layer around clipboard operations. The module probes the following classes at runtime:

- `com.android.server.clipboard.ClipboardServiceStubImpl`
- `com.android.server.clipboard.ClipboardServiceInjector`
- `com.miui.server.clipboard.ClipboardServiceStubImpl`
- `com.miui.server.clipboard.MiuiClipboardService`

For each class that exists, methods with these names are hooked:

| Method | Returned value |
|---|---|
| `clipboardAccessResult` | `0` (allow) |
| `waitUserChoice` | `0` (allow) |
| `checkProviderWakePathForClipboard` | `true` |
| `clipboardAccessAllowed` | `true` |

Rules:

- A value is overridden only when the arguments identify KDE Connect (same matching function as above). Unmatched calls are logged a limited number of times and otherwise pass through.
- The override is applied only for `int` or `boolean` return types.
- All candidate classes are optional; absence is not treated as an error.
- The startup log lists detected classes and their gate-like method names, which allows the hook set to be adjusted from a device log.

The module has not collected a device log confirming whether the vendor gates are on the read path on the tested device; see Section 9.

### 5.5 Logging

- Output goes to `XposedBridge.log` (LSPosed log) and `android.util.Log` with tag `KdeAutoClip`.
- `ModuleLog.limited(key, message)` logs the first 5 occurrences per key and then every 200th.
- `ModuleLog.first(key, max, message)` bounds the unmatched-vendor-call messages.
- `ModuleLog.errorLimited(...)` logs at most 3 occurrences per key.
- `Target.describe(args)` renders at most 320 characters to bound the log entry size for large `ClipData` objects.

### 5.6 Failure containment

- `ModuleEntry.handleLoadPackage` and every hook body are wrapped in `try/catch(Throwable)`.
- No hook calls back into the hooked method.
- No I/O, network access or UI work occurs in any hook.
- Installation is guarded by an `AtomicBoolean`, so a repeated callback cannot install duplicate hooks.
- A missing class or method is logged and skipped; it does not abort the remaining hooks.

### 5.7 No KDE Connect scope in v1.0.0

Section 2.3 shows that the listener notification and the read both pass through the same gate. With the gate granted, the application's existing listener sends the packet without any code running inside the KDE Connect process. Consequently, the module is enabled only for the `android` package; no KDE Connect scope is required, which reduces the loaded code surface.

### 5.8 Legacy Xposed API

The module uses the legacy Xposed API 82 (`assets/xposed_init` plus the `xposedmodule` manifest metadata). LSPosed supports legacy modules across its Android 16 builds, so this avoids a dependency on a specific libxposed API level. The same hook logic can be moved to the libxposed API (`META-INF/xposed/java_init.list`, `onSystemServerStarting`) if required by a future LSPosed branch.

## 6. Build pipeline

### 6.1 Toolchain

`tools/bootstrap.sh` downloads the following into `.toolchain/` (approximately 800 MB):

| Component | Source (first working) | Use |
|---|---|---|
| JDK 17 | Tsinghua Adoptium mirror; fallback: Adoptium API, Corretto, Microsoft OpenJDK | `javac`, `jar`, `keytool` |
| Android build-tools r35 | Tencent Android SDK mirror; fallback `dl.google.com` | `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android platform 36 | Tencent Android SDK mirror; fallback `dl.google.com` | Compile-time `android.jar` (API 36) |
| Xposed API 82 | Huawei Cloud / Aliyun Maven mirrors; fallback repository-vendored jar | Compile-time Xposed API |

The official JDK download path measured about 17 KB/s in the build environment, so the script prefers the mirrors. `https_proxy`/`http_proxy` are honoured.

### 6.2 Steps

`tools/build.sh`:

1. `javac -source 8 -target 8 -bootclasspath android.jar -classpath api-82.jar -d build/classes <sources>`
2. `jar cf build/classes.jar -C build/classes .`
3. `d8 --min-api 29 --lib android.jar --output build/dex build/classes.jar`
4. `aapt2 link -o build/base.apk -I android.jar --manifest AndroidManifest.xml -A assets`
5. Append `classes.dex` to the APK with Python `zipfile`
6. `zipalign -f -p 4`
7. Generate `tools/kdeautoclip.keystore` with `keytool` on first build (RSA 2048, 10000 days)
8. `apksigner sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`
9. Verify: `apksigner verify`, `aapt2 dump badging`, and check for `AndroidManifest.xml`, `classes.dex` and `assets/xposed_init` in the archive

`minSdkVersion` is 29 (Android 10, the first release with the clipboard restriction). `targetSdkVersion` is 36 to match the compile platform.

### 6.3 Issues encountered

| Symptom | Cause | Resolution |
|---|---|---|
| `AndroidAppHelper` not found at compile time | The legacy API 82 jar does not include that runtime helper | Resolve the application context through `ActivityThread.currentActivityThread()` reflection |
| `UserHandle.getAppId(int)` not found | Hidden API, absent from the SDK `android.jar` | Compute `uid % 100000` |
| `apksigner verify` reports v1/v2 as `false` | With `minSdk 29`, the tool verifies only the scheme required for that minimum (v3) | Check with `--min-sdk-version 21`; all three schemes verify |
| `*.idsig` file created | v4 signing enabled by default | `--v4-signing-enabled false` |
| Potential mismatch from filtering on the `op` argument | One UI 7 and other variants reorder integer arguments | No `op` filter; the decision is based on package/uid only |
| `showAccessNotificationLocked` on a ROM with a non-void return type | `setResult(null)` type mismatch | Skip the method unless its return type is `void` |

## 7. Verification

### 7.1 Local static checks

- Compilation completes without errors.
- `classes.dex` is 16,628 bytes; the signed APK is 16,851 bytes.
- `apksigner verify --min-sdk-version 21` reports v1, v2 and v3 signatures as valid.
- `aapt2 dump badging`: `package=com.kdeautoclip.module`, `minSdkVersion 29`, `targetSdkVersion 36`, expected label.
- APK contains `AndroidManifest.xml`, `resources.arsc`, `assets/xposed_init`, `classes.dex`, `META-INF/*`.
- Dex string check: `ModuleEntry`, `clipboardAccessAllowed`, `isDefaultIme`, `showAccessNotificationLocked`, `ClipboardServiceStubImpl`, `org.kde.kdeconnect_tp` are present.
- `aapt2 dump xmltree` shows the three Xposed metadata entries.

### 7.2 Device test

| Item | Result |
|---|---|
| Install, enable in LSPosed, System Framework scope only, reboot | Completed |
| Phone in background, copy text, paste on desktop | Automatic synchronization confirmed |
| Desktop → phone direction | Unchanged from the pre-module state |
| Module log, vendor gate hits, Android 12–15 behaviour, HyperOS privacy prompt behaviour | Not collected |

### 7.3 Log interpretation

Command:

```bash
adb logcat -d -s KdeAutoClip ClipboardService
```

Expected on Android 16:

```text
init: system_server (android) sdk=36 ...
ClipboardService found: com.android.server.clipboard.ClipboardService
signature clipboardAccessAllowed: private final boolean ...clipboardAccessAllowed(int, java.lang.String, java.lang.String, int, int, int, boolean)
system hooks: clipboardAccessAllowed=2 isDefaultIme=1 showAccessNotificationLocked=1 opRead=29 opWrite=30
target: org.kde.kdeconnect_tp uid=10xxx appId=xxx version=1.3x.x
allow clipboardAccessAllowed op=29 [...]
```

| Log state | Interpretation | Action |
|---|---|---|
| No `init:` line | Module not loaded by LSPosed | Check module enabled, System Framework scope, reboot; verify LSPosed supports legacy API modules |
| `ClipboardService not found` | Clipboard service class differs on the ROM | Adapt the class name |
| `system hooks: ...=0` with `no usable clipboard hook installed` | Expected methods absent | Adapt to the new signatures |
| No `allow ...` lines | Access path does not reach the hooked method | Inspect `vendor` lines; the vendor layer may replace the AOSP path |
| `allow ...` lines present but no desktop update | Failure is on the KDE Connect side | Check connection, pairing, plugin enablement, process state |
| `vendor class found` plus `vendor allow` | Vendor gates are on the path and were overridden | None |
| `vendor ... (not KDE)` | Vendor gate called with arguments that do not identify KDE Connect | Extend matching based on the logged argument shapes |

## 8. Edge cases

### 8.1 Loop prevention

| Event | Path | Result |
|---|---|---|
| Desktop sends `kdeconnect.clipboard` | `setText()` sets `currentContent` before `setPrimaryClip` | Listener callback compares equal and returns; no send back |
| Desktop sends `kdeconnect.clipboard.connect` | `timestamp` comparison in `onPacketReceived` | Older content does not overwrite newer content |
| Same content copied twice on the phone | Listener content comparison | One send |
| Text received through `SharePlugin` | Direct `setPrimaryClip`, `currentContent` stale | One echo back to the sender; desktop content is unchanged, propagation stops |
| Multiple paired devices | Each device has its own `ClipboardPlugin` observer | Each connected device receives the packet |

### 8.2 Locked device

As described in Section 2.5, reads are denied while the device is locked even for a granted package. Clipboard synchronization therefore requires an unlocked device at the moment of the read. For phone → PC synchronization this is satisfied in normal use; a copy immediately followed by locking the device may not be sent.

### 8.3 Multi-user and cloned applications

The uid/appId match can also match the same application installed in another user profile or a cloned instance. The package name match alone would not. This is accepted in v1.0.0.

### 8.4 Non-text clips

The KDE Connect clipboard packet carries text (`content`). Other clip types are handled by the application (`coerceToText`); the module does not alter content handling.

## 9. Unverified behaviour and risks

| Item | Status | Handling |
|---|---|---|
| Whether HyperOS vendor gates are on the tested device's read path | No log collected | Vendor hooks and logging are present; adjust from a device log if needed |
| Whether the AOSP read notification is fully suppressed on HyperOS | Not verified visually | `isDefaultIme` hook applies on the framework path; a remaining prompt is the vendor's own system prompt |
| Android 12–15 | Not device-tested | Hook-by-name design covers the known signatures; a device log may still require adjustment |
| LSPosed builds that support only the libxposed API | Not tested | The hooks can be ported to the libxposed API |
| Future KDE Connect change to listener registration | Not applicable to current versions | v1.1 fallback poller (Section 10) |
| Vendor ROMs that also filter clipboard-change dispatch | Not observed | v1.1 fallback poller |
| Cloned applications / secondary users | Accepted match scope | Documented in Section 8.3 |

## 10. Future work

### 10.1 v1.1 fallback poller

For ROMs where the clipboard-change notification is not delivered to a background application, the module can additionally load into the KDE Connect process:

- Hook `org.kde.kdeconnect.KdeConnect#onCreate` (Application) and start a main-thread `Handler` loop.
- Every 4 s while `PowerManager.isInteractive()` is true, or every 60 s otherwise, read `ClipboardManager.getPrimaryClipDescription()`.
- Compare `ClipDescription.getTimestamp()` with the last observed value.
- On change, reflectively call `ClipboardListener.Companion.instance(context).onClipboardChanged()`.

Class and method names are stable in release builds: KDE Connect's `proguard-rules.pro` contains `-dontobfuscate` and `-keep class org.kde.kdeconnect.** { *; }`. The poller would read only the clip description in the common case, which does not post the read notification on AOSP, and one content read per change.

### 10.2 Other items

- libxposed API variant.
- Optional settings: suppress-notification toggle, text-only synchronization, additional package allowlist (would require an explicit design for the security model).
- Localized module label.
- CI build and signing.

## Appendix A: Hook signatures (Android 16)

```text
ClipboardService#clipboardAccessAllowed(int op, String callingPackage, String attributionTag,
                                        int uid, int userId, int intendingDeviceId)
ClipboardService#clipboardAccessAllowed(int op, String callingPackage, String attributionTag,
                                        int uid, int userId, int intendingDeviceId, boolean shouldNoteOp)
ClipboardService#isDefaultIme(int userId, String packageName)
ClipboardService#showAccessNotificationLocked(String callingPackage, int uid, int userId,
                                              Clipboard clipboard, int accessDeviceId)
ClipboardService#sendClipChangedBroadcast(Clipboard clipboard)
```

## Appendix B: Log messages

| Message | Meaning |
|---|---|
| `init: system_server (android) sdk=...` | Module entry executed in system_server |
| `ClipboardService found: ...` | Target class resolved |
| `signature clipboardAccessAllowed: ...` | Installed overloads and their descriptors |
| `system hooks: clipboardAccessAllowed=N isDefaultIme=N showAccessNotificationLocked=N` | Hook installation counts |
| `target: org.kde.kdeconnect_tp uid=... appId=... version=...` | UID and app version resolution |
| `allow clipboardAccessAllowed op=... [...]` | Access granted to KDE Connect |
| `allow isDefaultIme [...]` | Access granted through the IME path |
| `suppressed clipboard access toast for ...` | Read notification suppressed |
| `vendor class found: ...` | Vendor stub class detected |
| `vendor hook installed: ...` | Vendor gate hook installed |
| `vendor allow ...` | Vendor gate overridden for KDE Connect |
| `vendor ... (not KDE)` | Vendor gate invoked with arguments not matching KDE Connect |

## Appendix C: References

**AOSP**

- `ClipboardService` (android16-release): <https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/clipboard/ClipboardService.java>
- `READ_CLIPBOARD_IN_BACKGROUND` permission declaration (`signature|role`), `core/res/AndroidManifest.xml`.

**KDE Connect Android**

- Plugin sources: <https://invent.kde.org/network/kdeconnect-android/-/tree/master/src/main/java/org/kde/kdeconnect/plugins>
- Clipboard automatic-sync history (bug 428167): <https://bugs.kde.org/show_bug.cgi?id=428167>
- Android 16 automatic synchronization failure (bug 525992): <https://bugs.kde.org/show_bug.cgi?id=525992>
- Shizuku proposal (merge request 779): <https://invent.kde.org/network/kdeconnect-android/-/merge_requests/779>
- Overlay permission fix, unmerged (merge request 796): <https://invent.kde.org/network/kdeconnect-android/-/merge_requests/796>

**Related modules and vendor references**

- entr0pia/xposed-clipboard-whitelist: <https://github.com/entr0pia/xposed-clipboard-whitelist>
- tehcneko ClipboardWhitelist (API 102 fork): <https://modules.lsposed.org/module/io.github.tehcneko.clipboardwhitelist/>
- fei-ke/ClipboardWhitelist: <https://github.com/fei-ke/ClipboardWhitelist>
- Cail-Gainey/FuckClip: <https://github.com/Cail-Gainey/FuckClip>
- draumaz/kdeconnectbidirectionalclipboard (Magisk permission re-grant approach): <https://github.com/draumaz/kdeconnectbidirectionalclipboard>

## Appendix D: Version history

- **1.0.0** (2026-11): initial release. Three framework hooks plus HyperOS/MIUI vendor gate handling. Device-tested on HyperOS 3 / Android 16. Bilingual README; no UI, no network access, no root dependency.
