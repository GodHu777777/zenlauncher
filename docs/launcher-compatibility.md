# 桌面兼容性验证

## 验证范围

应用声明的最低 Android 版本是 Android 7.0 / API 24，当前编译与目标 SDK 为 34。此声明不构成对所有厂商、ROM、折叠屏、多用户环境的实测承诺。HOME 角色由 Android 系统管理；用户选择其他桌面、厂商策略或设备管理策略都可能改变最终行为。

v2.1.4 的修复目标是：默认桌面请求能正确反映系统结果；返回键不结束桌面根 Activity；再次接收 HOME Intent 时回到干净的主页；设置、抽屉、搜索和弹窗保持正常退出顺序。

## 验证环境与当前覆盖

2026-10-03 检查结果：

| 环境 | 可验证内容 | 当前状态 |
| --- | --- | --- |
| 本地 macOS | 源码与 Git 状态 | 无可用 JDK、Android SDK、adb、模拟器 |
| 远程构建机 | JDK 17、SDK 34、Build Tools 34.0.0 | v2.1.5 构建成功；58 项测试通过；Lint 0 错误、108 警告；原证书 V1/V2 验证通过 |
| Robolectric API 24/28/29/34 | Activity、对话框、HOME/角色判断及设置回调 | Main 27、Manager 25、Settings 6，全部通过，无跳过 |
| 远程 Android 设备 | 实际 Home/Back、SystemUI、锁屏 | 无真机连接；API 34 AOSP 软件模拟器中旧 APK 安装成功，但系统服务频繁 ANR/崩溃，已停止本次尝试并保留 AVD |
| GitHub KVM / Android 7.0（API 24） | 实际系统授权/取消、Home/Back、抽屉、Activity 销毁及进程终止后的恢复 | 发布提交 `21e1fbb`：11 项设备测试、3 项进程恢复检查全部通过，0 失败、0 跳过 |
| GitHub KVM / Android 14（API 34） | 相同导航、实际系统授权及进程终止后的恢复 | 同一发布提交：11 项设备测试、3 项进程恢复检查全部通过，0 失败、0 跳过 |
| AOSP / Pixel 真机 | 实际手势、锁屏及升级 | 待实测 |
| 小米 / HyperOS / MIUI | 按键、全面屏手势、默认桌面入口 | 待实测，尤其关注厂商手势限制 |
| 华为 / EMUI（支持 APK） | 默认桌面与系统导航 | 待实测 |
| OPPO / ColorOS、vivo / OriginOS | 默认桌面与系统导航 | 待实测 |
| 三星 / One UI | 默认桌面与系统导航 | 待实测 |

测试报告与签名验证结果由 GitHub Actions 的 `verification-reports` artifact 保存。构建通过不能标记上表的真机项目为通过。

最终验证来自 [CI 37118600772](https://github.com/GodHu777777/zenlauncher/actions/runs/37118600772)：构建、两个设备任务和发布全部成功，均对应提交 `21e1fbb7833113f9c64de00205c9371e750aac2a`。[v2.1.5 正式 APK](https://github.com/GodHu777777/zenlauncher/releases/tag/v2.1.5) 为 4,912,929 字节，SHA-256 为 `62ea992172ad1624fe7666c1b852968e3d7e356b35b708ca9b19b584acadfce9`；下载后已与 GitHub asset digest 比对。

用户所报告的回到原厂桌面问题，仍需具体机型、ROM 版本、触发方式及新版诊断来完成闭环。v2.1.3、v2.1.4 与 v2.1.5 使用相同签名证书，SHA-256 为 `337b92f7bda67e7c2ea553ba969e707257c3d438c3a9320c94f58b3414e7820c`。本轮未把真机、厂商手势、锁屏、重启、配置保留或 Release 覆盖升级标为通过。

### AOSP 软件模拟尝试的边界

本轮创建了独立 API 34 AOSP x86_64（镜像 revision 4、Android Emulator 37.2.12）设备，配置 2 个虚拟 CPU、2 GB 客体内存。构建用户无法访问 KVM，因而使用软件模拟；模拟器进程限制为 2 个宿主 CPU。SDK、AVD 和测试证据保留在远程数据分区。

系统首次启动出现主线程阻塞 92 秒的 Watchdog 重启。为适应软件模拟，测试环境将 `watchdog_timeout_millis` 增至 600000，并关闭界面动画；其后仍发生 SystemUI、NetworkStack、Bluetooth 的 ANR，最终由 `IllegalStateException: Lost network stack` 导致系统进程再次退出。上述故障发生时尚未启动 ZenLauncher，不能据此认定应用失败。

曾读取到 `sys.boot_completed=1`，旧 v2.1.3 APK 安装后的包名也已出现在系统包列表。但系统服务随即再次失效，无法完成新版覆盖升级、HOME 解析、返回键、应用抽屉、锁屏或角色授权 UI 验证。已保存启动事件、系统崩溃日志和黑屏截图，并停止本次模拟器进程。这些材料用于解释验证环境的限制，不构成任何应用运行时场景的通过证据；后续应使用可访问 KVM 的模拟器或真机继续验证。

## 自动化检查

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

- Robolectric：覆盖测试文件中明确列出的导航与默认桌面判断分支，测试报告在 `app/build/reports/tests/testDebugUnitTest/`。
- Android Lint：报告在 `app/build/reports/lint-results-debug.html`。
- Release：输出 `app/build/outputs/apk/release/app-release.apk`。
- 使用 Build Tools 的 `apksigner verify --min-sdk-version 23 --verbose --print-certs` 显式验证 V1/V2 均为 true，并比对证书 SHA-256 与现有 `app/zenlauncher.jks`。此参数仅让校验器检查 V1，不改变应用最低 API 24；默认校验可能跳过 V1。

不通过降低 Lint 严重性、忽略测试失败或替换签名来让发布通过。

### Android 系统导航自动化

CI 新增 API 24 和 API 34 的独立 KVM 模拟器任务。先在系统内实际执行 `HomeNavigationDeviceTest`，检查 Home/Back、内部与系统设置页、搜索与抽屉退出、已安装应用的可见性和启动，以及 Activity 销毁后的恢复。随后从应用进程外执行 `scripts/verify-home-recovery.py`：在系统设置前台终止 ZenLauncher，验证旧进程已退出，再发送系统 Home 键并检查新进程、实际前台页面和返回行为。

`DefaultHomeSelectionDeviceTest` 另有授权与取消两例：以原桌面为起点，从普通应用入口打开 ZenLauncher，点击应用内的设置按钮，在 Android 7 的默认桌面设置或 Android 14 的 HOME 角色弹窗完成真实选择。此过程中不会用 shell 将 ZenLauncher 设为默认；完成后核验角色、HOME 解析、设置提示和实际按键去向，取消时检查仍保留原桌面。两例均已包含在上表的 11 项设备测试中。

两个系统采用各自真实的操作路径：Android 7 的 `ACTION_HOME_SETTINGS` 先打开默认应用配置页，再进入“Home app”列表弹窗；Android 14 通过 HOME 角色弹窗选择并确认。进程恢复检查也按系统版本读取实际焦点：API 29 起使用 `dumpsys window displays`，旧版使用 `dumpsys window windows`，避免遗漏新版焦点字段或读取历史 ANR 快照。

本机手动执行时也必须使用可重置的模拟器。先记录尚未安装 ZenLauncher 时的原桌面组件，并将其传入测试；Android 7 安装第二个 HOME 应用后可能重新显示选择器，不能依赖安装前的选择仍然有效：

```bash
original_home="$(adb shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.emulator=true "-Pandroid.testInstrumentationRunnerArguments.originalHome=$original_home" -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
python3 scripts/verify-home-recovery.py --apk app/build/outputs/apk/debug/app-debug.apk --output build/device-verification/process-recovery.json
```

测试改变默认桌面前会检查模拟器特征，并验证原组件是已安装、启用且可导出的 HOME 候选；安装后重新选定并读回该组件，每例结束后恢复并核验原 HOME。Home/Back 使用系统按键注入，导航结果另以实际前台、Activity 生命周期和窗口焦点判断；消费返回键而界面不变时，不要求产生无障碍界面变化事件。不满足环境条件、无实际测试结果、失败或跳过都会使 CI 失败。报告、日志、截图和进程恢复结果保存为 `navigation-api-24-evidence` / `navigation-api-34-evidence`。后续版本的发布还需通过这两项检查。

这些测试运行普通 Android 系统的 Debug 包，不代表厂商 ROM、实际侧滑/预测返回手势、锁屏、重启、配置保留或 Release 覆盖升级已经验证。新增任务的运行结果需以对应 GitHub 提交的检查记录为准，不能仅凭工作流或测试文件存在填为通过。

### 正在扩展的恢复场景

下一轮设备验证增加 Android 14 的真实底部上滑 Home 和左右边缘侧滑 Back。先核验系统已启用手势导航，并从系统设置或应用设置实际滑回主页，证明手势识别正常；再持续观察根桌面的返回行为。Android 7 没有这套系统手势，因此明确排除该测试类，原有按键检查继续执行。测试结束时恢复原导航配置和默认桌面。

进程外的恢复检查还将覆盖无密码模拟器的熄屏/唤醒和系统重启：确认屏幕确实进入睡眠、重启前后 boot ID 不同，且不重新设置 ZenLauncher 为默认就能经 Home 回到应用。新检查尚需 CI 实测，不能据此提前将上表标为通过，也不覆盖 PIN、生物识别、厂商 ROM 或同签名升级后的配置保留。

## 真机回归清单

先记下品牌/型号、Android 版本、完整 ROM 版本、导航方式、用户/工作资料模式、APK 版本，以及测试前系统选定的默认桌面。优先覆盖 Android 7–9、10–12、13–14 和更新系统，按键导航与手势导航分别测试。

| 场景 | 操作 | 预期 |
| --- | --- | --- |
| 尚未设为默认 | 从原桌面应用图标打开 ZenLauncher，再按 Home | 系统仍可回原默认桌面；ZenLauncher 应准确提示未设为默认 |
| 授予默认桌面 | 点设为默认，在系统选择 ZenLauncher，然后返回 | 主屏与设置中的状态更新；Home 指向 ZenLauncher |
| 取消授权 | 进入系统授权后点取消/返回 | 不误报设置成功；可再次主动发起设置 |
| 厂商限制 | 测试角色请求不受支持、默认列表不列出第三方桌面 | 给出可执行的设置引导；不承诺系统已修改成功、不崩溃 |
| 桌面根返回 | 连续按返回键、侧滑返回；Android 13+ 测预测返回及取消手势 | 完成返回操作后仍停留在 ZenLauncher，不结束根桌面 |
| 搜索与键盘 | 输入搜索，返回；再返回到桌面 | 键盘及搜索按正常顺序收起，最终留在根桌面 |
| 子界面 | 打开设置、抽屉、应用操作弹窗、冷静倒计时，然后返回 | 先退出当前子界面，回到 ZenLauncher |
| 子界面按 Home | 设置页、抽屉、弹窗和搜索状态分别按 Home | 回到 ZenLauncher 主页，临时界面和搜索收起 |
| 外部应用返回 | 从 ZenLauncher 打开普通应用，按返回或 Home | 返回到 ZenLauncher；Home 的前提为默认桌面仍是 ZenLauncher |
| 锁屏与解锁 | 从主页/子界面锁屏，解锁；再按 Home | 保持合理恢复路径，Home 最终回 ZenLauncher |
| 重建与进程回收 | 旋转、切换深色模式（若支持）、系统回收进程后按 Home | 重建成功，不崩溃、不丢失持久化配置 |
| 角色被改变 | 在系统设置主动改成其他默认桌面，再打开 ZenLauncher | 正确显示未设为默认，遵守系统选择 |
| 覆盖升级 | 从此前同签名版本通过 `adb install -r` 或安装器升级 | 签名兼容、版本号递增、配置保留 |
| 分身与多用户 | 主用户、工作资料、厂商分身分别启动应用并返回 | 可访问的配置文件能正常运行；默认桌面按对应用户判断 |

## 定位“回到系统桌面”

需区分「返回（Back）」与「回桌面（Home）」：前者交给 Activity/对话框处理；后者由系统默认 HOME 组件选择。请记录触发问题的具体手势，避免把系统未授予默认桌面的问题当作返回键拦截失效。

设备开启并授权 USB 调试后，可以只读检查实际状态：

```bash
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id
adb shell am get-current-user
adb shell cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell dumpsys activity activities
adb logcat -d -s AndroidRuntime ActivityTaskManager
```

部分旧版本的 `cmd package` 不支持上述参数；改为检查系统默认应用界面和 `dumpsys package` 的 HOME preferred activity。完整 `dumpsys` 或 `logcat` 可能包含应用与设备信息，分享前只保留与此次复现有关的片段。

如果解析到 `com.zenlauncher.app/.MainActivity` 但 Home 仍进入其他桌面，记录导航模式、前台 Activity 和系统日志，继续定位 SystemUI/OEM 路由或崩溃。如果解析到原厂桌面，应优先检查默认角色是否真的授予或被系统更改。

ADB 设置默认 HOME 是显式调试操作，需要用户已授权调试，并且仍可能受 ROM 策略限制。不要禁用或卸载系统桌面来“修复”导航；部分系统的最近任务与手势依赖该组件。

v2.1.5 的高级帮助命令在 Android 设备 shell 内执行 `am get-current-user`，再以返回的数字指定 `set-home-activity --user`。省略 `--user` 会默认操作主用户 0；Android 7/9 的该命令也不能直接使用 `--user current`，因其未将 `current` 转换为实际编号。此修复针对命令的用户范围，不能据此认定厂商第二空间或分身功能已经实测通过。

## 远程构建约束

现有构建服务器所有写入必须位于 `/data1/hgh/`。使用 `/data1/hgh/env_flutter.sh` 提供的 JDK/SDK/Gradle 缓存环境，在独立工作目录构建当前提交，避免覆盖其他工作区。不要向根分区安装 SDK、系统镜像或创建临时产物。

Java 不会自动使用 `TMPDIR` 作为临时目录，也不会把 `https_proxy` 自动传给 Robolectric。远程运行时还需通过 `JAVA_TOOL_OPTIONS` 设置 `-Duser.home=/data1/hgh -Djava.io.tmpdir=/data1/hgh/cache/tmp`；需要代理时，根据服务器实际代理配置传入 `-Drobolectric.dependency.proxy.host=… -Drobolectric.dependency.proxy.port=…`，避免依赖下载直连等待。这些为服务器运行参数，不写入应用或 CI 的通用配置。
