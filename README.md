# KdeAutoClip

**Automatic clipboard synchronization for KDE Connect on Android, without manual sending.**

[English](README.md) | [简体中文](README.zh-CN.md)

KdeAutoClip is an LSPosed module that allows the official [KDE Connect](https://kdeconnect.kde.org/) Android app to synchronize the phone clipboard to the computer while the app remains in the background.

<p align="left">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-brightgreen">
  <img alt="LSPosed" src="https://img.shields.io/badge/LSPosed-legacy%20API%2082-blueviolet">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

## Status

| Device / ROM | Android | Root manager | Framework | Result |
|---|---|---|---|---|
| Xiaomi HyperOS 3 | 16 (API 36) | KernelSU | LSPosed | ✅ phone → PC automatic; PC → phone unchanged |

The module was built and statically verified locally, and device-tested on the configuration above. Other Android 12–16 AOSP-based ROMs use the same framework path; see [Compatibility](#compatibility-and-limitations).

## How it works

Android 10 restricts background clipboard reads: only the focused app, the default input method, or a component holding the system permission may read the clipboard.

KDE Connect already implements clipboard synchronization. Its clipboard plugin registers an `OnPrimaryClipChangedListener`, reads the clipboard when the content changes, and sends a `kdeconnect.clipboard` packet to the paired computer. When the app is in the background, the read is rejected by the framework; the resulting exception is caught and ignored in `ClipboardListener.onClipboardChanged()`, so no packet is sent. The plugin UI therefore offers the clipboard content for manual sending only.

KdeAutoClip grants clipboard access inside `system_server` to `org.kde.kdeconnect_tp` only:

```
An app on the phone copies text
  → ClipboardService broadcasts the clipboard change
      → [KdeAutoClip] allows org.kde.kdeconnect_tp to receive the notification and read
  → KDE Connect (background) reads the clipboard and sends kdeconnect.clipboard
  → the desktop KDE Connect writes it to the desktop clipboard
```

The module does not depend on KDE Connect internals, and no part of the KDE Connect app is modified or injected.

## Requirements

- Android 10+ (device-tested on Android 16 / HyperOS 3)
- **LSPosed** for the installed Android version (Android 16 requires a maintained LSPosed build with API 36 support)
- KDE Connect (`org.kde.kdeconnect_tp`) installed and paired, with the Clipboard plugin enabled on the computer
- No root/KernelSU capability is required by the module (root is a prerequisite for LSPosed on the device)

## Installation

1. Download the APK from the **Releases** page of this repository, or build it (see below).
2. Install it on the phone. It has no launcher icon.
3. Open **LSPosed Manager → Modules → KDE 自动剪贴板** and enable it (the module label is in Chinese and means “KDE AutoClip”).
4. Open its **Scope** page and enable **System Framework** only (package `android`). KDE Connect is not required in the scope for v1.
5. Reboot the phone; LSPosed applies scope changes after a reboot.

### Keeping KDE Connect alive

- Set the KDE Connect battery policy to **Unrestricted**, allow **Autostart**, and keep its notifications enabled.
- Keep the KDE Connect persistent notification visible.
- On ROMs with aggressive background management (HyperOS/MIUI, ColorOS, One UI), lock KDE Connect in the recent-apps list.
- In KDE Connect, open *Plugin settings → Clipboard* and confirm the plugin is enabled. Optionally enable **Skip sensitive clipboard content** to exclude content marked as sensitive, such as entries copied by password managers.

## Verification

Logs:

- LSPosed Manager → **Logs** → search for `KdeAutoClip`.
- `adb logcat -d -s KdeAutoClip ClipboardService`
- On the phone with root (Termux): `su -c 'logcat -d -s KdeAutoClip'`

Expected output:

```text
init: system_server (android) sdk=36 ...
ClipboardService found: com.android.server.clipboard.ClipboardService
signature clipboardAccessAllowed: private final boolean ...clipboardAccessAllowed(int, java.lang.String, java.lang.String, int, int, int, boolean)
system hooks: clipboardAccessAllowed=2 isDefaultIme=1 showAccessNotificationLocked=1 opRead=29 opWrite=30
target: org.kde.kdeconnect_tp uid=10xxx appId=xxx version=1.3x.x
allow clipboardAccessAllowed op=29 [...]
```

## Implementation

The module runs inside `system_server` and installs three hooks on `com.android.server.clipboard.ClipboardService`, each filtered by package name:

| Target | Effect |
|---|---|
| `clipboardAccessAllowed(...)` (all overloads) | For KDE Connect only, skips the focus check, the `AppOps` check and any vendor logic added inside the method |
| `isDefaultIme(...)` (all overloads) | Second grant path; also suppresses the AOSP clipboard-access toast, which is skipped for the default IME |
| `showAccessNotificationLocked(...)` | Fallback toast suppression for ROMs where the IME hook does not apply |

On HyperOS/MIUI, the module detects the vendor clipboard stub layer at runtime (`ClipboardServiceStubImpl` and others) and answers its gates with “allow”, again only when the arguments identify KDE Connect. All hooks use exception handling and rate-limited logging, and do not change the behaviour of other packages.

The full implementation notes (AOSP/MIUI source analysis, per-version signatures, build pipeline, issues encountered, remaining risks) are in [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) (English); the Chinese version is [docs/IMPLEMENTATION.zh-CN.md](docs/IMPLEMENTATION.zh-CN.md).

## Security and privacy

- One package is allowed: `org.kde.kdeconnect_tp`. Other apps remain subject to the Android clipboard restrictions.
- The module declares no permissions, has no network access, no activities, services or receivers, and no launcher entry. It writes no files and stores no clipboard content.
- Disabling the module in LSPosed removes the hooks; no state is persisted.
- The module does not transmit clipboard content. The content is sent by KDE Connect over its own paired TLS connection.

## Compatibility and limitations

- **Device-tested:** Android 16 / HyperOS 3 (framework hooks active).
- **Implemented, not device-tested:** the AOSP paths for Android 12–15. Hooks are resolved by method name and tolerate the signature differences between versions.
- HyperOS may still display its own clipboard privacy prompt. This is a system prompt, configurable in system settings, and does not indicate a module error.
- The module relies on the framework delivering clipboard-change notifications to KDE Connect. If a vendor ROM also filters that dispatch, the log shows `allow ...` but no synchronization occurs. A fallback poller inside the KDE Connect process (comparing `ClipDescription` timestamps) is planned for v1.1.
- The KDE Connect “Send clipboard” action remains available; the app hides it only when `READ_LOGS` is granted, which this module does not do.
- LSPosed must support legacy Xposed API 82 modules. For LSPosed builds that support the libxposed API only, a libxposed variant can be produced from the same hooks.

## Building from source

Gradle is not used. The build runs `javac`, `d8`, `aapt2`, `zipalign` and `apksigner` directly:

```bash
cd kdeconnect-auto-clipboard
bash tools/bootstrap.sh    # downloads JDK 17, Android build-tools r35, platform 36, Xposed API 82 (~800 MB, stored in .toolchain/)
bash tools/build.sh        # compile, dex, package, align, sign -> dist/KdeAutoClip-1.0.0.apk
```

Notes:

- `tools/bootstrap.sh` prefers mirrors in mainland China (Tsinghua Adoptium mirror, Tencent Android SDK mirror) and falls back to the official sources. It honours `https_proxy` / `http_proxy`.
- The first build generates `tools/kdeautoclip.keystore` (debug signing, password `kdeautoclip`). For a release build, remove it and sign with your own key by editing the `apksigner` invocation in `tools/build.sh`.
- `.toolchain/` can be deleted after building. The module APK is 17 KB.

## Repository layout

```
kdeconnect-auto-clipboard/
├── AndroidManifest.xml              # module metadata (xposedmodule, xposedminversion=82)
├── assets/xposed_init               # legacy Xposed entry point
├── src/main/java/com/kdeautoclip/module/
│   ├── ModuleEntry.java             # IXposedHookLoadPackage (system_server only)
│   ├── SystemHooks.java             # AOSP clipboard hooks
│   ├── MiuiHooks.java               # HyperOS/MIUI vendor gate hooks
│   ├── Target.java                  # package/uid matching, argument rendering
│   └── ModuleLog.java               # rate-limited logging
├── tools/bootstrap.sh               # toolchain downloader
├── tools/build.sh                   # build, sign, verify
├── tools/kdeautoclip.keystore       # generated debug keystore
├── docs/IMPLEMENTATION.md           # implementation notes (English)
├── docs/IMPLEMENTATION.zh-CN.md     # implementation notes (Chinese)
├── dist/KdeAutoClip-1.0.0.apk       # build output
├── README.md                        # this file (English)
└── README.zh-CN.md                  # Chinese README
```

## Roadmap

- v1.1, if required by a device: a fallback poller inside the KDE Connect process for ROMs that also filter the clipboard-change broadcast.
- An optional **libxposed API** build for LSPosed branches that support the new API only.
- Localized module label and an optional settings UI (headless by design in v1.0.0).
- CI that builds and signs releases.

## Contributing

If the module does not work on a device, include the following in the issue:

1. ROM, Android version, LSPosed build (and fork), KDE Connect version.
2. Full output of `adb logcat -d -s KdeAutoClip ClipboardService`.
3. Whether the log contains `vendor class found` / `vendor allow` lines.

Pull requests should keep the module headless, filtered by package name, and free of any code path that can crash `system_server`.

## License

[GPL-3.0](LICENSE)

## Acknowledgements

- Android Open Source Project: the `ClipboardService` access-control logic that the module modifies for one package.
- [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android): the clipboard listener and packet sending are implemented by the application itself.
- Prior LSPosed modules that document the hook points: [entr0pia/xposed-clipboard-whitelist](https://github.com/entr0pia/xposed-clipboard-whitelist), [tehcneko's ClipboardWhitelist](https://modules.lsposed.org/module/io.github.tehcneko.clipboardwhitelist/), [fei-ke/ClipboardWhitelist](https://github.com/fei-ke/ClipboardWhitelist).
- [Cail-Gainey/FuckClip](https://github.com/Cail-Gainey/FuckClip): documentation of the MIUI vendor clipboard gate method names.

No source code from these projects was copied; only public framework/API facts and observed behaviour were used.

> **Unofficial project.** KDE and KDE Connect are trademarks of the KDE community. This module is not affiliated with, endorsed by, or supported by the KDE project. Issues in KDE Connect itself belong to <https://bugs.kde.org>.
