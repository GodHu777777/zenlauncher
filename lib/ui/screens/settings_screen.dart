import 'package:flutter/material.dart';
import '../../models/app_item.dart';
import '../../models/launcher_settings.dart';
import '../../services/app_launcher_service.dart';
import '../../services/storage_service.dart';

class SettingsScreen extends StatefulWidget {
  final StorageService storage;
  final List<AppItem> allApps;

  const SettingsScreen({
    super.key,
    required this.storage,
    required this.allApps,
  });

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late LauncherSettings _settings;
  bool _isDefaultLauncher = false;

  @override
  void initState() {
    super.initState();
    _settings = widget.storage.getSettings();
    _checkDefaultLauncher();
  }

  Future<void> _checkDefaultLauncher() async {
    final isDef = await AppLauncherService.isDefaultLauncher();
    if (mounted) {
      setState(() {
        _isDefaultLauncher = isDef;
      });
    }
  }

  Future<void> _updateSettings(LauncherSettings newSettings) async {
    setState(() {
      _settings = newSettings;
    });
    await widget.storage.saveSettings(newSettings);
  }

  void _showMottoDialog() {
    final controller = TextEditingController(text: _settings.customMotto);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改专注标语 / 座右铭'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            hintText: '例如：保持专注，活在当下',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              _updateSettings(_settings.copyWith(customMotto: controller.text.trim()));
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  void _showPromptDialog() {
    final controller = TextEditingController(text: _settings.frictionPrompt);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改冷静倒计时提示词'),
        content: TextField(
          controller: controller,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: '确认这是你真正想做的事，还是下意识的习惯？',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(ctx);
              _updateSettings(_settings.copyWith(frictionPrompt: controller.text.trim()));
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  void _showDopamineAppsManager() {
    final theme = Theme.of(context);
    final dopamineList = List<String>.from(widget.storage.getDopamineApps());

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: theme.dialogBackgroundColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            return DraggableScrollableSheet(
              initialChildSize: 0.8,
              maxChildSize: 0.95,
              minChildSize: 0.5,
              expand: false,
              builder: (_, scrollController) {
                return Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(18.0),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              '选择沉迷/冲动类应用 (开启5秒缓冲)',
                              style: theme.textTheme.titleLarge?.copyWith(fontSize: 16),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.check),
                            onPressed: () => Navigator.pop(ctx),
                          ),
                        ],
                      ),
                    ),
                    const Divider(height: 1),
                    Expanded(
                      child: ListView.builder(
                        controller: scrollController,
                        itemCount: widget.allApps.length,
                        itemBuilder: (_, index) {
                          final app = widget.allApps[index];
                          final isSelected = dopamineList.contains(app.packageName);

                          return CheckboxListTile(
                            value: isSelected,
                            title: Text(app.appName),
                            subtitle: Text(app.packageName, style: const TextStyle(fontSize: 11)),
                            activeColor: Colors.amber,
                            onChanged: (val) async {
                              if (val == true) {
                                dopamineList.add(app.packageName);
                              } else {
                                dopamineList.remove(app.packageName);
                              }
                              await widget.storage.saveDopamineApps(dopamineList);
                              setModalState(() {});
                              setState(() {});
                            },
                          );
                        },
                      ),
                    ),
                  ],
                );
              },
            );
          },
        );
      },
    );
  }

  void _showDefaultLauncherHelpDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('如何设为默认桌面'),
        content: const SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '💡 国产手机（小米/HyperOS、华为/鸿蒙、vivo、OPPO 等）系统对桌面权限有严格的安全保护限制，如果未自动弹出系统切换框，可通过以下方式设置：',
                style: TextStyle(fontSize: 13, height: 1.5),
              ),
              SizedBox(height: 14),
              Text(
                '【最快方式】：',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
              ),
              Text(
                '直接按手机底部的 Home 键（或从屏幕底部上滑返回桌面），系统通常会直接弹出「选择主屏幕应用」对话框，选中 ZenLauncher 并点击「始终」。',
                style: TextStyle(fontSize: 13, height: 1.4),
              ),
              SizedBox(height: 14),
              Text(
                '【各机型手动设置路径】：',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
              ),
              Text(
                '• 小米 / Redmi：设置 → 应用设置 → 应用管理 → 右上角三个点「默认应用设置」 → 桌面 → 选择 ZenLauncher\n'
                '• 华为 / 荣耀：设置 → 应用和服务 → 默认应用 → 桌面 → 选择 ZenLauncher\n'
                '• vivo / iQOO：设置 → 应用与权限 → 默认应用设置 → 桌面 → 选择 ZenLauncher\n'
                '• OPPO / 一加：设置 → 应用 → 默认应用 → 桌面 → 选择 ZenLauncher',
                style: TextStyle(fontSize: 12.5, height: 1.6),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('我知道了'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('ZenLauncher 设置'),
      ),
      body: ListView(
        children: [
          // Launcher Status Card
          Container(
            margin: const EdgeInsets.all(16),
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: theme.colorScheme.surface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: theme.colorScheme.outline.withOpacity(0.5),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      _isDefaultLauncher ? Icons.check_circle : Icons.home_outlined,
                      color: _isDefaultLauncher ? Colors.green : theme.colorScheme.primary,
                      size: 20,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      _isDefaultLauncher ? '已设为系统默认桌面' : '未设为默认桌面',
                      style: const TextStyle(fontWeight: FontWeight.w500),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  '将 ZenLauncher 设为主屏幕应用后，按 Home 键或上滑返回将直接进入专注纯净桌面。',
                  style: theme.textTheme.bodyMedium?.copyWith(fontSize: 13),
                ),
                const SizedBox(height: 12),
                OutlinedButton(
                  onPressed: () {
                    AppLauncherService.openDefaultLauncherSettings();
                    _showDefaultLauncherHelpDialog();
                  },
                  child: const Text('去系统设置中更改默认桌面'),
                ),
              ],
            ),
          ),

          _buildSectionHeader('防沉迷机制 (Anti-Distraction)'),
          ListTile(
            title: const Text('管理冷静应用 (黑名单)'),
            subtitle: Text('已配置 ${widget.storage.getDopamineApps().length} 个需倒计时应用 (如抖音、小红书等)'),
            trailing: const Icon(Icons.chevron_right),
            onTap: _showDopamineAppsManager,
          ),
          ListTile(
            title: const Text('冷静缓冲时长'),
            subtitle: Text('${_settings.frictionSeconds} 秒'),
            trailing: DropdownButton<int>(
              value: _settings.frictionSeconds,
              dropdownColor: theme.dialogBackgroundColor,
              underline: const SizedBox(),
              items: const [
                DropdownMenuItem(value: 3, child: Text('3 秒')),
                DropdownMenuItem(value: 5, child: Text('5 秒 (推荐)')),
                DropdownMenuItem(value: 10, child: Text('10 秒')),
                DropdownMenuItem(value: 15, child: Text('15 秒 (极度克制)')),
              ],
              onChanged: (val) {
                if (val != null) {
                  _updateSettings(_settings.copyWith(frictionSeconds: val));
                }
              },
            ),
          ),
          ListTile(
            title: const Text('冷静提示词'),
            subtitle: Text(
              _settings.frictionPrompt.replaceAll('\n', ' '),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            trailing: const Icon(Icons.edit, size: 18),
            onTap: _showPromptDialog,
          ),

          _buildSectionHeader('桌面与排版'),
          ListTile(
            title: const Text('自定义专注标语'),
            subtitle: Text(_settings.customMotto.isEmpty ? '未设置' : _settings.customMotto),
            trailing: const Icon(Icons.edit, size: 18),
            onTap: _showMottoDialog,
          ),
          SwitchListTile(
            title: const Text('显示极简大数字时钟'),
            value: _settings.showClock,
            onChanged: (val) => _updateSettings(_settings.copyWith(showClock: val)),
          ),
          SwitchListTile(
            title: const Text('显示日期与星期'),
            value: _settings.showDate,
            onChanged: (val) => _updateSettings(_settings.copyWith(showDate: val)),
          ),
          SwitchListTile(
            title: const Text('OLED 纯黑底色 (省电且护眼)'),
            subtitle: const Text('关闭时使用极简浅灰背景'),
            value: _settings.isOledDark,
            onChanged: (val) => _updateSettings(_settings.copyWith(isOledDark: val)),
          ),

          _buildSectionHeader('搜索体验'),
          ListTile(
            title: const Text('默认网页搜索引擎'),
            subtitle: Text(_settings.defaultSearchEngine),
            trailing: DropdownButton<String>(
              value: _settings.defaultSearchEngine,
              dropdownColor: theme.dialogBackgroundColor,
              underline: const SizedBox(),
              items: const [
                DropdownMenuItem(value: 'Baidu', child: Text('百度')),
                DropdownMenuItem(value: 'Google', child: Text('Google')),
                DropdownMenuItem(value: 'Bing', child: Text('Bing (必应)')),
                DropdownMenuItem(value: 'DuckDuckGo', child: Text('DuckDuckGo')),
              ],
              onChanged: (val) {
                if (val != null) {
                  _updateSettings(_settings.copyWith(defaultSearchEngine: val));
                }
              },
            ),
          ),

          _buildSectionHeader('关于 ZenLauncher'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(
              '为什么使用极简启动器？\n'
              '现代智能手机通过鲜艳的红点角标、精美渐变的 App 图标和开屏算法推荐，不断刺激人类的大脑释放多巴胺。'
              'ZenLauncher 剥离一切视觉噪音，以「纯文本」与「意图搜索」为核心，让你重新成为自己注意力的主人。',
              style: theme.textTheme.bodyMedium?.copyWith(
                height: 1.6,
                color: theme.colorScheme.onSurface.withOpacity(0.6),
                fontSize: 13,
              ),
            ),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
      child: Text(
        title,
        style: TextStyle(
          color: Theme.of(context).colorScheme.primary.withOpacity(0.5),
          fontSize: 12,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
        ),
      ),
    );
  }
}
