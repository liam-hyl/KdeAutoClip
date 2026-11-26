# KdeAutoClip：实现文档

[English](IMPLEMENTATION.md) | [简体中文](IMPLEMENTATION.zh-CN.md)

版本：1.0.0

本文描述 KdeAutoClip 修改的框架访问控制路径、设计所依赖的 KDE Connect 行为、钩子实现、构建流程、验证结果与未验证项。

主要依据：

- AOSP `android16-release` 的 `services/core/java/com/android/server/clipboard/ClipboardService.java`（同时对照 `android12-release` 至 `android15-release`）。
- KDE Connect Android `master` 与 `v1.37.3` 标签（clipboard、share、device、plugin 源码）。
- 真机环境：小米 HyperOS 3、Android 16（API 36）、KernelSU、LSPosed。

## 1. 范围

KdeAutoClip 是一个运行在 `system_server` 中的 LSPosed 模块，修改 `org.kde.kdeconnect_tp` 的剪贴板访问判定。模块不修改 KDE Connect 应用，不实现 KDE Connect 协议，运行时不需要 root 权限。

模块仅使用 Android 框架钩子。KDE Connect 自身已实现剪贴板同步的客户端部分；模块只解除应用在后台运行时遇到的框架限制。

## 2. Android 剪贴板访问控制

### 2.1 判定方法

剪贴板读写判定集中在 `ClipboardService#clipboardAccessAllowed(...)`。该方法自 Android 10 起存在，每个版本都有两个签名（一个便捷重载以 `shouldNoteOp = true` 委托）。

| Android | 签名 |
|---|---|
| 12、13 | `clipboardAccessAllowed(int op, String callingPackage, int uid, int userId[, boolean shouldNoteOp])` |
| 14、15、16、main | `clipboardAccessAllowed(int op, String callingPackage, String attributionTag, int uid, int userId, int intendingDeviceId[, boolean shouldNoteOp])` |

`callingPackage` 始终是第 1 个参数；`uid` 在 14+ 为第 3 个参数，在 12/13 为第 2 个参数。

### 2.2 判定顺序（Android 16）

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
        // content capture、augmented autofill、VDM owner
        break;
    case OP_WRITE_CLIPBOARD:
        allowed = true;
        break;
}
if (!allowed) return false;
return mAppOps.noteOp(op, uid, callingPackage, attributionTag, null)
        == AppOpsManager.MODE_ALLOWED;
```

与模块相关的性质：

1. 没有基于 uid 的豁免。uid 0 无法通过 `mAppOps.checkPackage`。
2. 默认输入法被豁免：`isDefaultIme(userId, packageName)` 对系统配置的输入法包名返回 true，跳过焦点检查。
3. `OP_READ_CLIPBOARD` 需要焦点、特权身份或输入法豁免。
4. AOSP 中 `OP_WRITE_CLIPBOARD` 不受焦点限制（`allowed = true`），但最终仍执行 `AppOps.noteOp`，因此 OEM 策略可以拒绝后台写入。
5. 前置条件不满足时方法会提前返回，不执行最终 `AppOps` 检查。在原始方法执行前设置返回值，可同时跳过焦点逻辑与 `AppOps` 调用。
6. `intendingDeviceId == DEVICE_ID_INVALID` 会在其它检查之前直接拒绝。手机场景通常为 `DEVICE_ID_DEFAULT`，但提前返回同样覆盖该分支。

### 2.3 变化通知的派发

`addPrimaryClipChangedListener()` 本身不做访问检查。通知派发时对每个已注册 listener 单独执行一次判定（`sendClipChangedBroadcast`，Android 16 约第 1049 行）：

```java
if (clipboardAccessAllowed(OP_READ_CLIPBOARD,
        li.mPackageName, li.mAttributionTag, li.mUid,
        UserHandle.getUserId(li.mUid), clipboard.deviceId)) {
    clipboard.primaryClipListeners.getBroadcastItem(i).dispatchPrimaryClipChanged();
}
```

结论：对某个 listener 的包名放行后，通知回调和随后的读取都会通过。

### 2.4 读取提示

`getPrimaryClip()` 调用 `showAccessNotificationLocked(callingPackage, uid, userId, clipboard, accessDeviceId)` 显示系统提示 “pasted from your clipboard”。以下情况提前返回：

- 读取方是当前剪贴板所有者；
- `isDefaultIme(userId, callingPackage)` 为 true；
- 调用方是 content-capture 或 augmented autofill 服务；
- 调用方持有 `SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION`；
- 该 uid 已针对当前内容提示过（`clipboard.mNotifiedUids`，每次新内容时清空）。

`getPrimaryClipDescription()`、`hasPrimaryClip()`、`hasClipboardText()` 以 `shouldNoteOp = false` 调用判定，不显示提示。

### 2.5 设备锁定

`getPrimaryClip()` 在 `isDeviceLocked(intendingUserId, deviceId)` 为 true 时返回 `null`，与访问判定无关。因此设备锁定时，即使包名已放行也无法读取。

## 3. KDE Connect 剪贴板行为

### 3.1 插件生命周期

`PluginFactory` 按设备实例化插件。`Device.reloadPluginsFromSettings()` 在设备 `isPaired` 且 `isReachable` 时对受支持插件调用 `addPlugin()`；插件仅在该设备启用时创建。`ClipboardPlugin` 未覆盖 `isEnabledByDefault`（默认 `true`），也未声明必需权限，因此设备连接时默认创建。

### 3.2 监听注册与发送路径

`ClipboardPlugin.onCreate()` 注册 observer，并发送带时间戳的当前剪贴板：

```kotlin
override fun onCreate() {
    ClipboardListener.instance(context).registerObserver(observer)
    sendConnectPacket()
}
```

listener 在构造函数中无条件注册系统回调：

```kotlin
Handler(Looper.getMainLooper()).post {
    cm = context.getSystemService<ClipboardManager>()!!
    cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
}
```

内容变化时，`onClipboardChanged()` 读取 `cm.primaryClip`，与缓存内容比较后通知 observers。`currentContent == content` 的比较用于避免重复发送。`SecurityException` 或空剪贴板由外层 `try/catch` 捕获并忽略，因此后台读取被拒时不产生数据包。

插件 observer 的发送函数：

```kotlin
fun propagateClipboard(content: String) {
    val np = NetworkPacket("kdeconnect.clipboard")
    np["content"] = content
    device.sendPacket(np)
}
```

`Device.sendPacket()` 将数据包入队；仅当设备没有活动连接时丢弃并触发失败回调。应用的前后台状态不影响发送路径。

### 3.3 READ_LOGS 条件

`ClipboardPlugin.canSyncAutomatically(context)` 在应用持有 `READ_LOGS` 时返回 true。该条件只控制两件事：

1. `ClipboardListener` 启动 `logcat` 进程，监听 `ClipboardService` 的拒绝日志，并启动 `ClipboardFloatingActivity` 获取窗口焦点后读取剪贴板；
2. 插件界面显示“发送剪贴板”按钮或菜单项。

observer 注册、剪贴板读取与数据包发送均不受 `canSyncAutomatically()` 控制。安装本模块后不需要 `READ_LOGS`；listener 回调正常触发，读取成功。

### 3.4 接收方向与重复抑制

`ClipboardPlugin.onPacketReceived()` 处理两种数据包：

- `kdeconnect.clipboard`：调用 `ClipboardListener.setText(content)`。
- `kdeconnect.clipboard.connect`：比较数据包 `timestamp` 与 listener 的更新时间戳，若 `content` 存在则调用 `setText()`。

`setText()` 在写入剪贴板之前更新 `currentContent`：

```kotlin
if (text == currentContent) return
updateTimestamp = System.currentTimeMillis()
currentContent = text
currentContentType = ClipboardContentType.Text
cm.setPrimaryClip(ClipData.newPlainText(null, text))
```

随后触发的 listener 回调满足 `content == currentContent`，直接返回，因此收到的剪贴板数据包不会被回发。

`SharePlugin.receiveText()` 直接写入剪贴板：

```kotlin
private fun receiveText(np: NetworkPacket) {
    val cm = context.getSystemService<ClipboardManager>()!!
    val text = np.getString("text")
    cm.setPrimaryClip(ClipData.newPlainText(null, text))
    ...
}
```

该路径绕过 `ClipboardListener`，`currentContent` 保持旧值。安装模块后，通过分享功能收到的文本会被当作本地剪贴板变化，向已配对设备发送一次。桌面端收到相同内容，其自身的变化检测会终止继续传播。这是应用层行为，不是模块缺陷；v1.0.0 不做抑制。

### 3.5 上游备用方案与 Android 16

上游的备用方案依赖 `READ_LOGS`、`SYSTEM_ALERT_WINDOW` 和透明 Activity 获取焦点，不适合作为本模块的基础：

- `READ_LOGS` 是受保护权限，需要 ADB 或 root 授予。
- HyperOS/MIUI 默认拒绝后台弹出界面（`后台弹出界面`），透明 Activity 可能无法显示。
- Android 16 修改了 `ClipboardService` 的日志输出，现有过滤条件不再匹配，上游自动同步在该版本失效（KDE bug 525992）。对应修复（KDE merge request 796）在撰写时未合并。
- Shizuku 分支（上游 merge request 779）采用相同思路：以 shell 身份运行 `logcat`，并依赖获取焦点的 Activity。

## 4. 方案比较

| 方案 | 前提 | 未采用的原因 |
|---|---|---|
| 在 root 守护进程中重新实现 KDE Connect 协议 | 配对证书、TLS 身份处理、与应用自身连接共存 | 复杂度高，且与应用的连接冲突 |
| KernelSU/Magisk 模块授予 `READ_LOGS` 与悬浮窗权限 | 受保护权限、后台启动 Activity | Android 16 上失效，且被 MIUI 后台弹出策略阻止 |
| 授予 KDE Connect root，通过 `su`/`app_process` 读取剪贴板 | 应用的 root 授权、隐藏 API、binder 反射 | 跨版本不稳定，扩大目标应用权限 |
| Hook 默认输入法（Gboard）并转发剪贴板 | 跨进程通道、输入法生命周期 | 增加 IPC 层，相对框架钩子无优势 |
| 在 KDE Connect 进程内轮询剪贴板 | 剪贴板访问已放行 | 不解决权限问题；框架路径可用时多余 |
| Hook `system_server` 中的 `ClipboardService`（采用） | LSPosed 系统框架作用域 | 只需修改访问判定；应用既有发送路径即可工作 |

选择框架钩子的原因：不需要修改协议、不需要额外权限、不修改目标应用、无持久化状态。代价是需要 LSPosed 的系统框架作用域。

## 5. 实现

### 5.1 模块组成

| 文件 | 职责 |
|---|---|
| `ModuleEntry.java` | `IXposedHookLoadPackage`；仅处理 `packageName == "android"`；安装两组钩子；顶层异常保护 |
| `SystemHooks.java` | 解析 `ClipboardService`；安装三个 AOSP 钩子；输出方法签名与相关方法名 |
| `MiuiHooks.java` | 探测厂商剪贴板桩类；安装按 KDE Connect 过滤的闸门钩子 |
| `Target.java` | 目标包名常量、参数匹配、uid/appId 解析、参数渲染 |
| `ModuleLog.java` | LSPosed 日志与 logcat 双通道输出，按 key 限流 |

### 5.2 钩子

全部通过 `XposedBridge.hookAllMethods` 按方法名安装，覆盖所有重载与厂商标变体，不缓存 `Method` 对象。

| 方法 | 过滤条件 | 动作 | 目的 |
|---|---|---|---|
| `clipboardAccessAllowed`（全部重载） | 任一 `String` 参数等于 `org.kde.kdeconnect_tp`，或任一 `int` 参数等于目标 uid/appId；返回类型为 `boolean` | `setResult(true)` | 仅对 KDE Connect 跳过焦点检查、`AppOps` 与厂商追加逻辑 |
| `isDefaultIme`（全部重载） | 同上；返回类型为 `boolean` | `setResult(true)` | 第二条放行路径；同时抑制该包名的读取提示 |
| `showAccessNotificationLocked`（全部重载） | `args[0]` 等于目标包名；返回类型为 `void` | `setResult(null)` | IME 钩子不适用时抑制提示 |

实现说明：

- `clipboardAccessAllowed` 钩子不按 `op` 参数过滤。AOSP 中该方法只处理 `OP_READ_CLIPBOARD` 与 `OP_WRITE_CLIPBOARD`；部分 OEM 变体调整了整数参数顺序（One UI 7 为 `(int op, int uid, int userId, int intendingDeviceId, String pkg, String attributionTag, boolean shouldNoteOp)`），不能假定参数位置。
- 设置返回值前需要检查返回类型：Xposed 无法向任意返回类型赋值 `null`。
- `isDefaultIme` 会使 `showAccessNotificationLocked` 对目标包名提前返回，因此第三个钩子是备用手段，不是主要的提示抑制方式。

### 5.3 匹配与安全边界

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

- 包名精确相等是主匹配方式。框架在所有剪贴板访问路径中都会传入调用方包名。
- uid/appId 比较用于没有包名字符串的厂商方法。`appId` 以 `uid % 100000` 计算，因为 `UserHandle.getAppId()` 是隐藏 API。
- 初始化是惰性的，在首次剪贴板访问时执行；安装钩子阶段不调用 PackageManager。
- 目标应用未安装时 uid 解析失败，匹配退化为包名方式，不会误放行其它应用。
- 模块中只有 `org.kde.kdeconnect_tp` 一个包名常量。

### 5.4 HyperOS / MIUI 兼容

HyperOS 保留 AOSP `ClipboardService`，并在剪贴板操作外层增加厂商桩层。模块在运行时探测以下类：

- `com.android.server.clipboard.ClipboardServiceStubImpl`
- `com.android.server.clipboard.ClipboardServiceInjector`
- `com.miui.server.clipboard.ClipboardServiceStubImpl`
- `com.miui.server.clipboard.MiuiClipboardService`

对存在的类，按名称 hook 以下方法：

| 方法 | 返回值 |
|---|---|
| `clipboardAccessResult` | `0`（允许） |
| `waitUserChoice` | `0`（允许） |
| `checkProviderWakePathForClipboard` | `true` |
| `clipboardAccessAllowed` | `true` |

规则：

- 仅当参数识别为 KDE Connect 时覆盖返回值（匹配函数同上）。未匹配的调用以限定次数记录日志，其余情况不改变原行为。
- 仅在返回类型为 `int` 或 `boolean` 时覆盖。
- 所有候选类都是可选的；类不存在不视为错误。
- 启动日志列出检测到的类及其闸门类方法名，便于根据真机日志调整钩子集合。

模块尚未采集真机日志确认测试设备上的读取路径是否实际经过厂商闸门，见第 9 节。

### 5.5 日志

- 同时输出到 `XposedBridge.log`（LSPosed 日志）与 `android.util.Log`（tag `KdeAutoClip`）。
- `ModuleLog.limited(key, message)`：每个 key 记录前 5 次，之后每 200 次记录一次。
- `ModuleLog.first(key, max, message)`：限制厂商未匹配调用的日志条数。
- `ModuleLog.errorLimited(...)`：每个 key 最多记录 3 次。
- `Target.describe(args)` 最多渲染 320 个字符，限制大型 `ClipData` 参数的日志长度。

### 5.6 失败保护

- `ModuleEntry.handleLoadPackage` 与每个钩子函数体均包裹 `try/catch(Throwable)`。
- 钩子内不回调被 hook 的方法。
- 钩子内不做 IO、网络或 UI 操作。
- 使用 `AtomicBoolean` 防止重复安装。
- 类或方法缺失时记录日志并跳过，不中断其余钩子。

### 5.7 v1.0.0 不使用 KDE Connect 作用域

如 2.3 节所述，listener 通知与读取经过同一判定。判定放行后，应用已有的 listener 即发送数据包，不需要在 KDE Connect 进程内运行任何代码。因此模块仅为 `android` 包启用，不需要 KDE Connect 作用域，减少加载的代码范围。

### 5.8 Legacy Xposed API

模块使用 legacy Xposed API 82（`assets/xposed_init` 加 `xposedmodule` 清单元数据）。LSPosed 在其 Android 16 构建中支持 legacy 模块，因此不依赖特定 libxposed API 级别。若未来 LSPosed 分支要求 libxposed API，可将同一套钩子迁移到 `META-INF/xposed/java_init.list` 与 `onSystemServerStarting`。

## 6. 构建流程

### 6.1 工具链

`tools/bootstrap.sh` 下载以下组件到 `.toolchain/`（约 800 MB）：

| 组件 | 来源（首个可用） | 用途 |
|---|---|---|
| JDK 17 | 清华 Adoptium 镜像；回退 Adoptium API、Corretto、Microsoft OpenJDK | `javac`、`jar`、`keytool` |
| Android build-tools r35 | 腾讯 Android SDK 镜像；回退 `dl.google.com` | `aapt2`、`d8`、`zipalign`、`apksigner` |
| Android platform 36 | 腾讯 Android SDK 镜像；回退 `dl.google.com` | 编译期 `android.jar`（API 36） |
| Xposed API 82 | 华为云/Aliyun Maven 镜像；回退仓库内置 jar | 编译期 Xposed API |

构建环境中官方 JDK 下载路径实测约 17 KB/s，因此脚本优先使用镜像；支持 `https_proxy`/`http_proxy`。

### 6.2 步骤

`tools/build.sh`：

1. `javac -source 8 -target 8 -bootclasspath android.jar -classpath api-82.jar -d build/classes <sources>`
2. `jar cf build/classes.jar -C build/classes .`
3. `d8 --min-api 29 --lib android.jar --output build/dex build/classes.jar`
4. `aapt2 link -o build/base.apk -I android.jar --manifest AndroidManifest.xml -A assets`
5. 用 Python `zipfile` 将 `classes.dex` 追加到 APK
6. `zipalign -f -p 4`
7. 首次构建用 `keytool` 生成 `tools/kdeautoclip.keystore`（RSA 2048，10000 天）
8. `apksigner sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`
9. 校验：`apksigner verify`、`aapt2 dump badging`，并检查归档内 `AndroidManifest.xml`、`classes.dex`、`assets/xposed_init`

`minSdkVersion` 为 29（Android 10，剪贴板限制的起始版本）；`targetSdkVersion` 为 36，与编译平台一致。

### 6.3 遇到的问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 编译时找不到 `AndroidAppHelper` | legacy API 82 jar 不包含该运行时辅助类 | 通过 `ActivityThread.currentActivityThread()` 反射获取 Application/system context |
| 编译时找不到 `UserHandle.getAppId(int)` | 隐藏 API，SDK `android.jar` 中不存在 | 使用 `uid % 100000` |
| `apksigner verify` 显示 v1/v2 为 `false` | `minSdk 29` 时工具只校验该最低版本所需的方案（v3） | 用 `--min-sdk-version 21` 复核；三套方案均有效 |
| 生成 `*.idsig` 文件 | 默认启用 v4 签名 | `--v4-signing-enabled false` |
| 按 `op` 过滤可能失效 | One UI 7 等变体调整整数参数顺序 | 不按 `op` 过滤，仅依据包名/uid 判定 |
| 某些 ROM 的 `showAccessNotificationLocked` 非 void | `setResult(null)` 类型不匹配 | 仅在返回类型为 `void` 时跳过 |

## 7. 验证

### 7.1 本机静态校验

- 编译无错误。
- `classes.dex` 为 16,628 字节；签名后 APK 为 16,851 字节。
- `apksigner verify --min-sdk-version 21` 显示 v1、v2、v3 均有效。
- `aapt2 dump badging`：`package=com.kdeautoclip.module`、`minSdkVersion 29`、`targetSdkVersion 36`、label 正确。
- APK 包含 `AndroidManifest.xml`、`resources.arsc`、`assets/xposed_init`、`classes.dex`、`META-INF/*`。
- dex 字符串检查：存在 `ModuleEntry`、`clipboardAccessAllowed`、`isDefaultIme`、`showAccessNotificationLocked`、`ClipboardServiceStubImpl`、`org.kde.kdeconnect_tp`。
- `aapt2 dump xmltree` 显示三条 Xposed 元数据。

### 7.2 真机测试

| 项目 | 结果 |
|---|---|
| 安装、LSPosed 启用、仅系统框架作用域、重启 | 完成 |
| 手机后台复制文本 → 电脑粘贴 | 自动同步确认 |
| 电脑 → 手机方向 | 与安装模块前一致 |
| 模块日志、厂商闸门命中、Android 12–15 行为、HyperOS 隐私提示表现 | 未采集 |

### 7.3 日志判读

命令：

```bash
adb logcat -d -s KdeAutoClip ClipboardService
```

Android 16 期望输出：

```text
init: system_server (android) sdk=36 ...
ClipboardService found: com.android.server.clipboard.ClipboardService
signature clipboardAccessAllowed: private final boolean ...clipboardAccessAllowed(int, java.lang.String, java.lang.String, int, int, int, boolean)
system hooks: clipboardAccessAllowed=2 isDefaultIme=1 showAccessNotificationLocked=1 opRead=29 opWrite=30
target: org.kde.kdeconnect_tp uid=10xxx appId=xxx version=1.3x.x
allow clipboardAccessAllowed op=29 [...]
```

| 日志状态 | 解释 | 措施 |
|---|---|---|
| 无 `init:` 行 | 模块未被 LSPosed 加载 | 检查模块启用、系统框架作用域、重启；确认 LSPosed 支持 legacy API 模块 |
| `ClipboardService not found` | ROM 的剪贴板服务类不同 | 适配类名 |
| `system hooks: ...=0` 且 `no usable clipboard hook installed` | 预期方法不存在 | 适配新签名 |
| 无 `allow ...` 行 | 访问路径未到达被 hook 的方法 | 检查 `vendor` 行；厂商层可能替换了 AOSP 路径 |
| 有 `allow ...` 行但电脑端无更新 | 问题在 KDE Connect 侧 | 检查连接、配对、插件启用状态与进程状态 |
| `vendor class found` 且 `vendor allow` | 厂商闸门在路径上且已被覆盖 | 无需处理 |
| `vendor ... (not KDE)` | 厂商闸门被调用，但参数未识别为 KDE Connect | 根据日志中的参数形态扩展匹配 |

## 8. 边缘情况

### 8.1 回环抑制

| 事件 | 路径 | 结果 |
|---|---|---|
| 电脑发送 `kdeconnect.clipboard` | `setText()` 先设置 `currentContent`，再 `setPrimaryClip` | listener 回调比较相等后返回，不回发 |
| 电脑发送 `kdeconnect.clipboard.connect` | `onPacketReceived` 比较 `timestamp` | 旧内容不覆盖新内容 |
| 手机连续复制相同内容 | listener 内容比较 | 只发送一次 |
| 通过 `SharePlugin` 收到文本 | 直接 `setPrimaryClip`，`currentContent` 陈旧 | 向发送方回发一次；桌面内容未变化，传播终止 |
| 多台已配对设备 | 每台设备有独立的 `ClipboardPlugin` observer | 每台已连接设备均收到数据包 |

### 8.2 设备锁定

如 2.5 节所述，设备锁定时即使包名已放行也无法读取。剪贴板同步要求读取时设备处于解锁状态。手机 → 电脑方向在正常使用中满足该条件；复制后立即锁屏的情况可能不会发送。

### 8.3 多用户与应用克隆

uid/appId 匹配可能同时命中其他用户配置文件或克隆实例中的同一应用；仅按包名匹配则不会。v1.0.0 接受该范围。

### 8.4 非文本内容

KDE Connect 剪贴板数据包只承载文本（`content`）。其它类型由应用通过 `coerceToText` 处理；模块不改变内容处理逻辑。

## 9. 未验证项与风险

| 项目 | 状态 | 处理 |
|---|---|---|
| 测试设备上的读取路径是否经过 HyperOS 厂商闸门 | 未采集日志 | 已内置厂商钩子与日志；可按真机日志调整 |
| HyperOS 上的 AOSP 读取提示是否完全抑制 | 未做视觉验证 | 框架路径上 `isDefaultIme` 钩子生效；剩余提示为厂商自有系统提示 |
| Android 12–15 | 未真机测试 | 按名称 hook 覆盖已知签名；仍可能需要根据设备日志调整 |
| 仅支持 libxposed API 的 LSPosed 构建 | 未测试 | 钩子可迁移到 libxposed API |
| KDE Connect 未来修改 listener 注册方式 | 对当前版本不适用 | v1.1 兜底轮询（第 10 节） |
| 厂商 ROM 同时过滤剪贴板变化派发 | 未观察到 | v1.1 兜底轮询 |
| 应用克隆 / 多用户 | 匹配范围已接受 | 见 8.3 |

## 10. 后续工作

### 10.1 v1.1 兜底轮询

针对后台应用收不到剪贴板变化通知的 ROM，模块可额外加载到 KDE Connect 进程：

- hook `org.kde.kdeconnect.KdeConnect#onCreate`（Application），在主线程启动 `Handler` 循环。
- 当 `PowerManager.isInteractive()` 为 true 时每 4 秒、否则每 60 秒读取 `ClipboardManager.getPrimaryClipDescription()`。
- 比较 `ClipDescription.getTimestamp()` 与上次记录值。
- 变化时反射调用 `ClipboardListener.Companion.instance(context).onClipboardChanged()`。

KDE Connect 发布版本保留类与方法名：`proguard-rules.pro` 包含 `-dontobfuscate` 与 `-keep class org.kde.kdeconnect.** { *; }`。轮询在常规情况下只读取 clip description，AOSP 上不会触发读取提示；每次变化只读取一次内容。

### 10.2 其它

- libxposed API 版本。
- 可选设置：提示抑制开关、仅同步文本、额外包名白名单（需要明确的安全模型设计）。
- 模块名称本地化。
- CI 构建与签名。

## 附录 A：钩子签名（Android 16）

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

## 附录 B：日志信息

| 信息 | 含义 |
|---|---|
| `init: system_server (android) sdk=...` | 模块入口在 system_server 中执行 |
| `ClipboardService found: ...` | 目标类解析成功 |
| `signature clipboardAccessAllowed: ...` | 已安装的重载及其描述符 |
| `system hooks: clipboardAccessAllowed=N isDefaultIme=N showAccessNotificationLocked=N` | 钩子安装数量 |
| `target: org.kde.kdeconnect_tp uid=... appId=... version=...` | uid 与应用版本解析结果 |
| `allow clipboardAccessAllowed op=... [...]` | 对 KDE Connect 放行访问 |
| `allow isDefaultIme [...]` | 通过输入法路径放行 |
| `suppressed clipboard access toast for ...` | 已抑制读取提示 |
| `vendor class found: ...` | 检测到厂商桩类 |
| `vendor hook installed: ...` | 厂商闸门钩子安装成功 |
| `vendor allow ...` | 对 KDE Connect 覆盖厂商闸门 |
| `vendor ... (not KDE)` | 厂商闸门被调用，但参数不匹配 KDE Connect |

## 附录 C：参考资料

**AOSP**

- `ClipboardService`（android16-release）：<https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/clipboard/ClipboardService.java>
- `READ_CLIPBOARD_IN_BACKGROUND` 权限声明（`signature|role`）：`core/res/AndroidManifest.xml`。

**KDE Connect Android**

- 插件源码：<https://invent.kde.org/network/kdeconnect-android/-/tree/master/src/main/java/org/kde/kdeconnect/plugins>
- 剪贴板自动同步历史（bug 428167）：<https://bugs.kde.org/show_bug.cgi?id=428167>
- Android 16 自动同步失效（bug 525992）：<https://bugs.kde.org/show_bug.cgi?id=525992>
- Shizuku 方案（merge request 779）：<https://invent.kde.org/network/kdeconnect-android/-/merge_requests/779>
- 悬浮窗权限修复，未合并（merge request 796）：<https://invent.kde.org/network/kdeconnect-android/-/merge_requests/796>

**相关模块与厂商资料**

- entr0pia/xposed-clipboard-whitelist：<https://github.com/entr0pia/xposed-clipboard-whitelist>
- tehcneko ClipboardWhitelist（API 102 fork）：<https://modules.lsposed.org/module/io.github.tehcneko.clipboardwhitelist/>
- fei-ke/ClipboardWhitelist：<https://github.com/fei-ke/ClipboardWhitelist>
- Cail-Gainey/FuckClip：<https://github.com/Cail-Gainey/FuckClip>
- draumaz/kdeconnectbidirectionalclipboard（Magisk 权限授予方案）：<https://github.com/draumaz/kdeconnectbidirectionalclipboard>

## 附录 D：版本记录

- **1.0.0**（2026-11）：首个版本。三个框架钩子与 HyperOS/MIUI 厂商闸门处理。真机测试：HyperOS 3 / Android 16。README 中英双语；无界面、无网络、无 root 依赖。
