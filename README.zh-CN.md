# KdeAutoClip

**在 Android 后台自动同步 KDE Connect 剪贴板，无需手动发送。**

[English](README.md) | [简体中文](README.zh-CN.md)

KdeAutoClip 是一个 LSPosed 模块，使官方 [KDE Connect](https://kdeconnect.kde.org/) 安卓应用在后台运行时，将手机剪贴板同步到已配对的电脑。

<p align="left">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-brightgreen">
  <img alt="LSPosed" src="https://img.shields.io/badge/LSPosed-legacy%20API%2082-blueviolet">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

## 状态

| 设备 / ROM | Android | Root 管理器 | 框架 | 结果 |
|---|---|---|---|---|
| 小米 HyperOS 3（澎湃 3） | 16（API 36） | KernelSU | LSPosed | ✅ 手机 → 电脑 自动同步；电脑 → 手机 保持原状 |

模块在本机完成构建与静态校验，并在上表设备上完成真机测试。其它 Android 12–16 的 AOSP 系 ROM 使用相同的框架路径，详见[兼容性与限制](#兼容性与限制)。

## 原理

Android 10 起，后台应用不能读取剪贴板：只有当前获得焦点的应用、默认输入法，或持有系统权限的组件可以读取。

KDE Connect 的剪贴板插件在进程存活时注册 `OnPrimaryClipChangedListener`，内容变化后读取剪贴板并向已配对的电脑发送 `kdeconnect.clipboard` 数据包。应用处于后台时，该读取被框架拒绝，异常在 `ClipboardListener.onClipboardChanged()` 中被捕获并忽略，因此不会发送数据包；插件界面因此只提供手动“发送剪贴板”操作。

KdeAutoClip 在 `system_server` 中仅对 `org.kde.kdeconnect_tp` 放行剪贴板访问：

```
手机任意应用复制文本
  → 系统 ClipboardService 广播剪贴板变化
      → [KdeAutoClip] 允许 org.kde.kdeconnect_tp 接收通知并读取
  → KDE Connect（后台）读取剪贴板并发送 kdeconnect.clipboard
  → 电脑端 KDE Connect 写入电脑剪贴板
```

模块不依赖 KDE Connect 内部实现，也不修改或注入 KDE Connect；应用升级通常不影响模块行为。

## 环境要求

- Android 10+（真机测试：Android 16 / HyperOS 3）
- 支持所装系统版本的 **LSPosed**（Android 16 需要仍在维护、支持 API 36 的 LSPosed 构建）
- 已安装 KDE Connect（`org.kde.kdeconnect_tp`），与电脑配对，且电脑端已启用「剪贴板」插件
- 模块本身不需要 root/KernelSU 能力（root 为 LSPosed 的前提）

## 安装

1. 从本仓库的 **Releases** 页面下载 APK，或自行编译（见下文）。
2. 安装到手机。该 APK 没有桌面图标。
3. 打开 **LSPosed 管理器 → 模块 → KDE 自动剪贴板**，启用模块。
4. 进入「作用域」，**只勾选「系统框架」**（包名 `android`）。v1 不需要勾选 KDE Connect。
5. 重启手机；LSPosed 在重启后应用作用域变更。

### 保活 KDE Connect

- KDE Connect 的省电策略设为**无限制**；允许**自启动**；保持通知开启。
- 保留 KDE Connect 的常驻通知，不要隐藏。
- 在 HyperOS/MIUI、ColorOS、One UI 等后台管理较严格的 ROM 上，将 KDE Connect 在最近任务中锁定。
- 在 KDE Connect 的「插件设置 → 剪贴板」中确认插件已启用；可选勾选「跳过敏感内容」，以排除密码管理器等标记为敏感的内容。

## 验证

日志：

- LSPosed 管理器 →「日志」→ 搜索 `KdeAutoClip`。
- `adb logcat -d -s KdeAutoClip ClipboardService`
- Termux（需 root）：`su -c 'logcat -d -s KdeAutoClip'`

期望输出：

```text
init: system_server (android) sdk=36 ...
ClipboardService found: com.android.server.clipboard.ClipboardService
signature clipboardAccessAllowed: private final boolean ...clipboardAccessAllowed(int, java.lang.String, java.lang.String, int, int, int, boolean)
system hooks: clipboardAccessAllowed=2 isDefaultIme=1 showAccessNotificationLocked=1 opRead=29 opWrite=30
target: org.kde.kdeconnect_tp uid=10xxx appId=xxx version=1.3x.x
allow clipboardAccessAllowed op=29 [...]
```

## 工作原理

模块运行在 `system_server`，在 `com.android.server.clipboard.ClipboardService` 上安装三个按包名过滤的钩子：

| 目标 | 作用 |
|---|---|
| `clipboardAccessAllowed(...)`（所有重载） | 访问判定：仅对 KDE Connect 跳过焦点检查、`AppOps` 检查，以及厂商在该方法内追加的逻辑 |
| `isDefaultIme(...)`（所有重载） | 第二条放行路径；同时抑制 AOSP 的剪贴板读取提示（默认输入法不显示该提示） |
| `showAccessNotificationLocked(...)` | IME 钩子在某些 ROM 上不适用时，兜底抑制提示 |

在 HyperOS/MIUI 上，模块在运行时探测厂商剪贴板桩层（`ClipboardServiceStubImpl` 等），同样仅在参数识别为 KDE Connect 时返回允许值。所有钩子均有异常保护与限流日志，不改变其它应用的行为。

实现细节（AOSP/MIUI 源码分析、各版本签名、构建流水线、踩坑记录与剩余风险）见 [docs/IMPLEMENTATION.zh-CN.md](docs/IMPLEMENTATION.zh-CN.md)（中文）；英文版见 [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md)。

## 安全与隐私

- 仅放行 `org.kde.kdeconnect_tp` 一个包名，其它应用仍受 Android 原有剪贴板限制。
- 模块不声明任何权限，无网络访问，无 Activity/Service/Receiver，无桌面入口；不写任何文件，不保存剪贴板内容。
- 在 LSPosed 中停用后钩子立即失效，无残留状态。
- 模块不发送剪贴板内容；内容由 KDE Connect 通过已配对的 TLS 连接发送。

## 兼容性与限制

- **真机测试**：Android 16 / HyperOS 3（已确认框架钩子生效）。
- **已实现、未真机验证**：Android 12–15 的 AOSP 路径。钩子按方法名查找，可容忍各版本签名差异。
- HyperOS 可能仍显示其自身的剪贴板隐私提示。该提示由系统控制，可在系统设置中调整，不代表模块异常。
- 模块依赖框架将剪贴板变化通知投递给 KDE Connect。若某厂商 ROM 同时过滤该派发，日志会出现 `allow ...` 但不会同步；v1.1 计划增加 KDE Connect 进程内的兜底轮询（比对 `ClipDescription` 时间戳）。
- KDE Connect 的「发送剪贴板」手动入口仍然保留；应用仅在授予 `READ_LOGS` 时隐藏该入口，本模块不授予该权限。
- LSPosed 必须支持 legacy Xposed API 82 模块。若 LSPosed 构建仅支持 libxposed API，可使用同一套钩子生成 libxposed 版本。

## 自行编译

不使用 Gradle，直接调用 `javac`、`d8`、`aapt2`、`zipalign`、`apksigner`：

```bash
cd kdeconnect-auto-clipboard
bash tools/bootstrap.sh    # 下载 JDK 17、Android build-tools r35、platform 36、Xposed API 82（约 800MB，存放于 .toolchain/）
bash tools/build.sh        # 编译、dex、打包、对齐、签名 -> dist/KdeAutoClip-1.0.0.apk
```

说明：

- `tools/bootstrap.sh` 优先使用国内镜像（清华 Adoptium 镜像、腾讯 Android SDK 镜像），失败时回退官方源；会读取 `https_proxy` / `http_proxy` 环境变量。
- 首次构建会生成 `tools/kdeautoclip.keystore`（调试签名，口令 `kdeautoclip`）。发布正式版本时请删除该文件，并修改 `tools/build.sh` 中的 `apksigner` 参数以使用自己的密钥。
- `.toolchain/` 可在编译完成后删除；模块 APK 为 17 KB。

## 项目结构

```
kdeconnect-auto-clipboard/
├── AndroidManifest.xml              # 模块元数据（xposedmodule、xposedminversion=82）
├── assets/xposed_init               # legacy Xposed 入口
├── src/main/java/com/kdeautoclip/module/
│   ├── ModuleEntry.java             # IXposedHookLoadPackage（只处理 system_server）
│   ├── SystemHooks.java             # AOSP 剪贴板钩子
│   ├── MiuiHooks.java               # HyperOS/MIUI 厂商闸门钩子
│   ├── Target.java                  # 包名/uid 匹配、参数渲染
│   └── ModuleLog.java               # 限流日志
├── tools/bootstrap.sh               # 工具链下载脚本
├── tools/build.sh                   # 构建、签名、校验
├── tools/kdeautoclip.keystore       # 自动生成的调试签名
├── docs/IMPLEMENTATION.md           # 实现文档（英文）
├── docs/IMPLEMENTATION.zh-CN.md     # 实现文档（中文）
├── dist/KdeAutoClip-1.0.0.apk       # 构建产物
├── README.md                        # 英文主 README
└── README.zh-CN.md                  # 本文件
```

## 路线图

- v1.1（视设备需要）：KDE Connect 进程内兜底轮询，用于同时过滤剪贴板变化广播的 ROM。
- 可选的 **libxposed API** 版本，适配仅支持新 API 的 LSPosed 分支。
- 模块名称本地化与可选的设置界面（v1.0.0 为无界面设计）。
- 用 CI 自动构建并签名 Release。

## 参与贡献

如果模块在设备上不生效，请在 Issue 中附上：

1. ROM、Android 版本、LSPosed 构建（及分支）、KDE Connect 版本。
2. `adb logcat -d -s KdeAutoClip ClipboardService` 的完整输出。
3. 日志中是否出现 `vendor class found` / `vendor allow` 行。

PR 请保持模块无界面、仅按包名放行，并且任何代码路径都不会导致 `system_server` 崩溃。

## 许可证

[GPL-3.0](LICENSE)

## 致谢

- Android Open Source Project：`ClipboardService` 的访问控制逻辑。
- [KDE Connect Android](https://invent.kde.org/network/kdeconnect-android)：剪贴板监听与数据包发送由应用自身实现。
- 提供钩子参考的 LSPosed 模块：[entr0pia/xposed-clipboard-whitelist](https://github.com/entr0pia/xposed-clipboard-whitelist)、[tehcneko 的 ClipboardWhitelist](https://modules.lsposed.org/module/io.github.tehcneko.clipboardwhitelist/)、[fei-ke/ClipboardWhitelist](https://github.com/fei-ke/ClipboardWhitelist)。
- [Cail-Gainey/FuckClip](https://github.com/Cail-Gainey/FuckClip)：MIUI 厂商剪贴板闸门方法名记录。

未复制上述项目的任何源码；仅使用了公开的框架/API 事实与行为。

> **非官方项目。** KDE、KDE Connect 是 KDE 社区的商标；本模块与 KDE 项目无隶属关系，也未获其背书或支持。KDE Connect 本身的问题请反馈到 <https://bugs.kde.org>。
