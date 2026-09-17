# ZenLauncher (极简防沉迷 Android 启动器)

> 一个受 KISS Launcher 与 Minimalist Phone 启发的极简 Android 桌面启动器，基于 **Flutter + Kotlin** 构建。
> 宗旨：**消除视觉多巴胺刺激，打破肌肉记忆，以搜索与初衷为导向**。

---

## ✨ 核心特性

- **去多巴胺化纯文本 UI (Zero-Dopamine UI)**：
  - 彻底剔除色彩缤纷的应用图标、红点未读角标与杂乱 Widget。
  - 纯黑 OLED 高对比度排版，极致克制、省电护眼。
- **意图搜索优先 (Intent-First Search)**：
  - 底部单手舒适区常驻搜索栏，支持中文**拼音全拼与首字母模糊匹配**（输入 `wx` 找微信，输入 `tb` 找淘宝）。
  - **网页直搜**：输入关键词直接回车唤起默认浏览器查询，无需打开浏览器被信息流和热搜分心。
- **5秒防沉迷冷静阻断 (Mindful Friction)**：
  - 针对抖音、小红书、微博、游戏等高频沉迷应用，点击后触发 **5 秒呼吸倒计时弹窗**。
  - 启发式反思提问（“停顿 5 秒，你打开手机的初衷是什么？”），打破机械下意识的肌肉记忆。
- **应用隐藏与负向重命名**：
  - 支持将娱乐类应用从主列表彻底隐藏，仅能通过搜索框主动输入完整名称才能唤起。
  - 支持自定义别名（如将“抖音”重命名为“消耗时间的短视频”）。
- **常用工具快捷置顶**：
  - 主屏仅保留 4~6 个纯文本高频工具（电话、浏览器、备忘录等）。

---

## 🛠️ 项目结构

```
zen_launcher/
├── android/
│   └── app/src/main/
│       ├── AndroidManifest.xml   # 声明 HOME 启动器类别与 QUERY_ALL_PACKAGES 权限
│       └── kotlin/.../MainActivity.kt # 原生 MethodChannel：获取应用、启动App、设为默认桌面
├── lib/
│   ├── main.dart                 # 入口，沉浸式状态栏与主题注入
│   ├── models/
│   │   ├── app_item.dart         # 应用模型
│   │   └── launcher_settings.dart# 启动器偏好配置模型
│   ├── services/
│   │   ├── app_launcher_service.dart # Android 原生通道交互
│   │   ├── search_service.dart   # 拼音模糊检索与浏览器直搜
│   │   └── storage_service.dart  # 本地持久化 (置顶/隐藏/冷静名单/别名)
│   ├── ui/
│   │   ├── screens/
│   │   │   ├── home_screen.dart  # 极简主屏
│   │   │   ├── all_apps_screen.dart # 全应用字母抽屉
│   │   │   └── settings_screen.dart # 防沉迷与系统桌面设置
│   │   ├── widgets/
│   │   │   ├── mindful_clock.dart   # 极简时钟与专注标语
│   │   │   ├── search_bar_widget.dart # 意图输入框
│   │   │   ├── app_text_tile.dart   # 纯文本应用项
│   │   │   └── friction_dialog.dart # 5秒冷静倒计时弹窗
│   │   └── theme/
│   │       └── app_theme.dart    # OLED 纯黑与极简浅色主题
└── pubspec.yaml
```

---

## 🚀 编译与真机安装指南

### 1. 本地环境准备
如果你尚未配置 Flutter 与 Android SDK，请在终端执行：
```bash
# 1. 使用 Homebrew 安装 Flutter
brew install --cask flutter

# 2. 检查开发环境并安装 Android SDK
flutter doctor
```

### 2. 获取依赖
```bash
cd /Users/genghong/.gemini/antigravity/scratch/zen_launcher
flutter pub get
```

### 3. 连接 Android 手机并运行
1. 打开手机的「开发者选项」，开启「USB 调试」。
2. 用数据线将手机连接到 Mac：
```bash
# 检查设备连接
flutter devices

# 直接在真机上调试运行
flutter run --release
```

### 4. 构建独立 APK 安装包
如需打包成 APK 发送给手机安装：
```bash
flutter build apk --release
# 生成的 APK 位于: build/app/outputs/flutter-apk/app-release.apk
```

---

## 📱 设为系统默认桌面

1. 打开应用后，点击右上角设置图标（调谐图标）进入设置。
2. 点击顶部的 **「去系统设置中更改默认桌面」**。
3. 在系统主屏幕应用列表中，将 **ZenLauncher** 选为默认主屏幕。
4. 现在按 Home 键或底部上滑返回，即可享受无多巴胺刺激的纯净专注体验！
