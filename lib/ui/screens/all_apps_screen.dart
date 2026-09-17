import 'package:flutter/material.dart';
import '../../models/app_item.dart';
import '../widgets/app_text_tile.dart';

class AllAppsScreen extends StatefulWidget {
  final List<AppItem> apps;
  final ValueChanged<AppItem> onAppTap;
  final ValueChanged<AppItem> onAppLongPress;

  const AllAppsScreen({
    super.key,
    required this.apps,
    required this.onAppTap,
    required this.onAppLongPress,
  });

  @override
  State<AllAppsScreen> createState() => _AllAppsScreenState();
}

class _AllAppsScreenState extends State<AllAppsScreen> {
  final TextEditingController _filterController = TextEditingController();
  bool _showHidden = false;
  String _filter = '';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    // Sort apps alphabetically by pinyin/name
    final sortedList = List<AppItem>.from(widget.apps)
      ..sort((a, b) {
        final p1 = a.pinyin.isNotEmpty ? a.pinyin : a.appName.toLowerCase();
        final p2 = b.pinyin.isNotEmpty ? b.pinyin : b.appName.toLowerCase();
        return p1.compareTo(p2);
      });

    // Filter by search text and hidden status
    final visibleApps = sortedList.where((app) {
      if (!_showHidden && app.isHidden) return false;
      if (_filter.isEmpty) return true;
      final q = _filter.toLowerCase();
      return app.appName.toLowerCase().contains(q) ||
          app.pinyin.contains(q) ||
          app.pinyinShort.contains(q);
    }).toList();

    final hiddenCount = widget.apps.where((a) => a.isHidden).length;

    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _filterController,
          autofocus: false,
          onChanged: (val) {
            setState(() {
              _filter = val.trim();
            });
          },
          style: theme.textTheme.titleLarge?.copyWith(fontSize: 17),
          decoration: InputDecoration(
            hintText: '检索应用列表...',
            hintStyle: TextStyle(
              color: theme.colorScheme.onSurface.withOpacity(0.4),
              fontSize: 16,
              fontWeight: FontWeight.w300,
            ),
            border: InputBorder.none,
          ),
        ),
        actions: [
          if (_filterController.text.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.close, size: 20),
              onPressed: () {
                _filterController.clear();
                setState(() {
                  _filter = '';
                });
              },
            ),
        ],
      ),
      body: visibleApps.isEmpty
          ? Center(
              child: Text(
                '没有找到符合条件的应用',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurface.withOpacity(0.4),
                ),
              ),
            )
          : ListView.builder(
              itemCount: visibleApps.length + (hiddenCount > 0 ? 1 : 0),
              itemBuilder: (ctx, index) {
                if (index == visibleApps.length) {
                  // Toggle hidden apps button at bottom
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 20),
                    child: Center(
                      child: TextButton.icon(
                        onPressed: () {
                          setState(() {
                            _showHidden = !_showHidden;
                          });
                        },
                        icon: Icon(
                          _showHidden ? Icons.visibility_off : Icons.visibility,
                          size: 16,
                          color: theme.colorScheme.onSurface.withOpacity(0.5),
                        ),
                        label: Text(
                          _showHidden ? '隐藏防沉迷应用' : '显示已隐藏的 $hiddenCount 个应用',
                          style: TextStyle(
                            color: theme.colorScheme.onSurface.withOpacity(0.5),
                            fontSize: 13,
                          ),
                        ),
                      ),
                    ),
                  );
                }

                final app = visibleApps[index];
                return AppTextTile(
                  app: app,
                  onTap: () => widget.onAppTap(app),
                  onLongPress: () => widget.onAppLongPress(app),
                );
              },
            ),
    );
  }
}
