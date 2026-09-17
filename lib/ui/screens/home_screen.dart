import 'package:flutter/material.dart';
import '../../models/app_item.dart';
import '../../models/launcher_settings.dart';
import '../../services/app_launcher_service.dart';
import '../../services/search_service.dart';
import '../../services/storage_service.dart';
import '../widgets/mindful_clock.dart';
import '../widgets/search_bar_widget.dart';
import '../widgets/app_text_tile.dart';
import '../widgets/friction_dialog.dart';
import 'all_apps_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  final StorageService storage;

  const HomeScreen({super.key, required this.storage});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();

  List<AppItem> _allApps = [];
  List<AppItem> _searchResults = [];
  bool _isLoading = true;
  late LauncherSettings _settings;

  @override
  void initState() {
    super.initState();
    _settings = widget.storage.getSettings();
    _loadApps();
  }

  Future<void> _loadApps() async {
    final rawApps = await AppLauncherService.fetchInstalledApps();

    final favorites = widget.storage.getFavorites();
    final hidden = widget.storage.getHiddenApps();
    final dopamine = widget.storage.getDopamineApps();
    final aliases = widget.storage.getAliases();

    // Default favorites on first run
    final effectiveFavorites = favorites.isEmpty
        ? ['com.android.dialer', 'com.android.chrome', 'com.tencent.mm', 'com.android.notes']
        : favorites;

    // Default dopamine apps on first run
    final effectiveDopamine = dopamine.isEmpty
        ? ['com.ss.android.ugc.aweme', 'com.xingin.xhs', 'tv.danmaku.bili', 'com.zhihu.android']
        : dopamine;

    final enriched = rawApps.map((app) {
      final customName = aliases[app.packageName] ?? app.originalName;
      final withName = app.copyWith(
        appName: customName,
        isFavorite: effectiveFavorites.contains(app.packageName),
        isHidden: hidden.contains(app.packageName),
        isDopamineApp: effectiveDopamine.contains(app.packageName),
      );
      return SearchService.enrichWithPinyin(withName);
    }).toList();

    if (mounted) {
      setState(() {
        _allApps = enriched;
        _isLoading = false;
      });
    }
  }

  void _onSearchChanged(String text) {
    if (text.trim().isEmpty) {
      setState(() {
        _searchResults = [];
      });
    } else {
      final results = SearchService.searchApps(_allApps, text);
      setState(() {
        _searchResults = results;
      });
    }
  }

  void _onSearchSubmitted(String text) {
    if (text.trim().isEmpty) return;

    if (_searchResults.isNotEmpty) {
      _tryLaunchApp(_searchResults.first);
    } else {
      // Direct Web search
      SearchService.launchWebSearch(text, engine: _settings.defaultSearchEngine);
    }
  }

  void _tryLaunchApp(AppItem app) {
    if (app.isDopamineApp) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => FrictionDialog(
          appName: app.appName,
          totalSeconds: _settings.frictionSeconds,
          prompt: _settings.frictionPrompt,
          onConfirmed: () {
            _searchController.clear();
            _searchFocusNode.unfocus();
            AppLauncherService.launchApp(app.packageName);
          },
        ),
      );
    } else {
      _searchController.clear();
      _searchFocusNode.unfocus();
      AppLauncherService.launchApp(app.packageName);
    }
  }

  void _showAppActions(AppItem app) {
    final theme = Theme.of(context);
    showModalBottomSheet(
      context: context,
      backgroundColor: theme.dialogBackgroundColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  app.appName,
                  style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w500),
                ),
                Text(
                  app.packageName,
                  style: theme.textTheme.bodyMedium?.copyWith(fontSize: 12),
                ),
                const Divider(height: 24),
                ListTile(
                  leading: Icon(app.isFavorite ? Icons.star_border : Icons.star, color: theme.colorScheme.onSurface),
                  title: Text(app.isFavorite ? '从主页取消置顶' : '置顶到主页'),
                  onTap: () async {
                    Navigator.pop(ctx);
                    await widget.storage.toggleFavorite(app.packageName);
                    _loadApps();
                  },
                ),
                ListTile(
                  leading: Icon(
                    app.isDopamineApp ? Icons.hourglass_disabled : Icons.hourglass_top,
                    color: Colors.amber.withOpacity(0.8),
                  ),
                  title: Text(app.isDopamineApp ? '取消冷静防沉迷' : '设为冷静应用 (倒计时阻断)'),
                  onTap: () async {
                    Navigator.pop(ctx);
                    await widget.storage.toggleDopamineApp(app.packageName);
                    _loadApps();
                  },
                ),
                ListTile(
                  leading: Icon(app.isHidden ? Icons.visibility : Icons.visibility_off, color: theme.colorScheme.onSurface),
                  title: Text(app.isHidden ? '取消隐藏' : '从列表隐藏此应用'),
                  onTap: () async {
                    Navigator.pop(ctx);
                    await widget.storage.toggleHidden(app.packageName);
                    _loadApps();
                  },
                ),
                ListTile(
                  leading: Icon(Icons.edit_outlined, color: theme.colorScheme.onSurface),
                  title: const Text('重命名 (防诱惑别名)'),
                  onTap: () {
                    Navigator.pop(ctx);
                    _showRenameDialog(app);
                  },
                ),
                ListTile(
                  leading: Icon(Icons.info_outline, color: theme.colorScheme.onSurface),
                  title: const Text('系统应用信息 / 卸载'),
                  onTap: () {
                    Navigator.pop(ctx);
                    AppLauncherService.openAppInfo(app.packageName);
                  },
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  void _showRenameDialog(AppItem app) {
    final textController = TextEditingController(text: app.appName);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('重命名「${app.originalName}」'),
        content: TextField(
          controller: textController,
          autofocus: true,
          decoration: const InputDecoration(
            hintText: '如：刷短视频浪费时间',
            border: UnderlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(ctx);
              await widget.storage.setAlias(app.packageName, textController.text);
              _loadApps();
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isSearching = _searchController.text.trim().isNotEmpty;
    final favoriteApps = _allApps.where((a) => a.isFavorite).toList();

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // Top action bar
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  IconButton(
                    icon: Icon(
                      Icons.tune,
                      size: 20,
                      color: theme.colorScheme.onSurface.withOpacity(0.5),
                    ),
                    onPressed: () async {
                      await Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => SettingsScreen(
                            storage: widget.storage,
                            allApps: _allApps,
                          ),
                        ),
                      );
                      setState(() {
                        _settings = widget.storage.getSettings();
                      });
                      _loadApps();
                    },
                  ),
                ],
              ),
            ),

            // Middle main area
            Expanded(
              child: isSearching
                  ? _buildSearchResultsView(theme)
                  : _buildNormalHomeView(theme, favoriteApps),
            ),

            // Bottom Search Bar & App drawer access
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SearchBarWidget(
                    controller: _searchController,
                    focusNode: _searchFocusNode,
                    onChanged: _onSearchChanged,
                    onSubmitted: _onSearchSubmitted,
                    onClear: () {
                      _searchController.clear();
                      _onSearchChanged('');
                    },
                  ),
                  const SizedBox(height: 8),
                  if (!isSearching)
                    GestureDetector(
                      onTap: () async {
                        await Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) => AllAppsScreen(
                              apps: _allApps,
                              onAppTap: _tryLaunchApp,
                              onAppLongPress: _showAppActions,
                            ),
                          ),
                        );
                        _loadApps();
                      },
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text(
                              '全部应用',
                              style: theme.textTheme.bodyMedium?.copyWith(
                                fontSize: 13,
                                color: theme.colorScheme.onSurface.withOpacity(0.4),
                              ),
                            ),
                            Icon(
                              Icons.keyboard_arrow_up,
                              size: 16,
                              color: theme.colorScheme.onSurface.withOpacity(0.4),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNormalHomeView(ThemeData theme, List<AppItem> favoriteApps) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator(strokeWidth: 2));
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 28),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 20),
          MindfulClock(
            showClock: _settings.showClock,
            showDate: _settings.showDate,
            motto: _settings.customMotto,
          ),
          const Spacer(),
          // Favorite text apps
          Text(
            '常用工具',
            style: theme.textTheme.bodyMedium?.copyWith(
              fontSize: 12,
              letterSpacing: 1.0,
              color: theme.colorScheme.onSurface.withOpacity(0.35),
            ),
          ),
          const SizedBox(height: 12),
          if (favoriteApps.isEmpty)
            Text(
              '暂无置顶应用，长按全应用列表中的任意软件可置顶到此处。',
              style: theme.textTheme.bodyMedium?.copyWith(
                fontSize: 14,
                color: theme.colorScheme.onSurface.withOpacity(0.4),
              ),
            )
          else
            ...favoriteApps.take(6).map((app) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: GestureDetector(
                  onTap: () => _tryLaunchApp(app),
                  onLongPress: () => _showAppActions(app),
                  child: Text(
                    app.appName,
                    style: theme.textTheme.displayLarge?.copyWith(
                      fontSize: 22,
                      fontWeight: FontWeight.w300,
                      color: theme.colorScheme.onSurface.withOpacity(0.9),
                    ),
                  ),
                ),
              );
            }),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _buildSearchResultsView(ThemeData theme) {
    final query = _searchController.text.trim();

    return ListView(
      padding: const EdgeInsets.symmetric(vertical: 10),
      children: [
        // Web Search Quick Tile
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
          leading: Icon(
            Icons.travel_explore,
            color: theme.colorScheme.primary.withOpacity(0.7),
          ),
          title: Text(
            '在 ${_settings.defaultSearchEngine} 中搜索「$query」',
            style: theme.textTheme.bodyLarge?.copyWith(
              fontSize: 16,
              fontWeight: FontWeight.w400,
              color: theme.colorScheme.primary,
            ),
          ),
          subtitle: Text(
            '直接唤起浏览器，杜绝社媒分心',
            style: theme.textTheme.bodyMedium?.copyWith(fontSize: 12),
          ),
          onTap: () {
            SearchService.launchWebSearch(query, engine: _settings.defaultSearchEngine);
          },
        ),
        const Divider(height: 16, indent: 20, endIndent: 20),
        // App Match Results
        if (_searchResults.isEmpty)
          Padding(
            padding: const EdgeInsets.all(24.0),
            child: Text(
              '未找到名称或拼音匹配的本机应用，点击上方直接搜索网页。',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurface.withOpacity(0.4),
              ),
            ),
          )
        else
          ..._searchResults.map((app) {
            return AppTextTile(
              app: app,
              fontSize: 17,
              onTap: () => _tryLaunchApp(app),
              onLongPress: () => _showAppActions(app),
            );
          }),
      ],
    );
  }
}
