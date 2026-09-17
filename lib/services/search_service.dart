import 'package:lpinyin/lpinyin.dart';
import 'package:url_launcher/url_launcher.dart';
import '../models/app_item.dart';

class SearchService {
  /// Enriches an AppItem with Pinyin full and initials
  static AppItem enrichWithPinyin(AppItem app) {
    try {
      final pinyin = PinyinHelper.getPinyin(
        app.appName,
        separator: '',
        format: PinyinFormat.WITHOUT_TONE,
      ).toLowerCase();

      final pinyinShort = PinyinHelper.getShortPinyin(app.appName).toLowerCase();

      return app.copyWith(
        pinyin: pinyin,
        pinyinShort: pinyinShort,
      );
    } catch (_) {
      final lower = app.appName.toLowerCase();
      return app.copyWith(pinyin: lower, pinyinShort: lower);
    }
  }

  /// Searches the list of apps against a user query
  static List<AppItem> searchApps(List<AppItem> allApps, String query) {
    final cleanQuery = query.trim().toLowerCase();
    if (cleanQuery.isEmpty) return [];

    final matched = <_ScoredApp>[];

    for (final app in allApps) {
      final nameLower = app.appName.toLowerCase();
      final pinyin = app.pinyin;
      final pinyinShort = app.pinyinShort;

      int score = 0;

      // 1. Exact match on app name
      if (nameLower == cleanQuery) {
        score = 100;
      }
      // 2. Name starts with query
      else if (nameLower.startsWith(cleanQuery)) {
        score = 80;
      }
      // 3. Pinyin initials exact match (e.g. "wx" == "wx")
      else if (pinyinShort == cleanQuery) {
        score = 75;
      }
      // 4. Pinyin initials start with query (e.g. "w" matches "wx")
      else if (pinyinShort.startsWith(cleanQuery)) {
        score = 70;
      }
      // 5. Full pinyin starts with query (e.g. "wei" matches "weixin")
      else if (pinyin.startsWith(cleanQuery)) {
        score = 65;
      }
      // 6. Name contains query
      else if (nameLower.contains(cleanQuery)) {
        score = 50;
      }
      // 7. Full pinyin contains query
      else if (pinyin.contains(cleanQuery)) {
        score = 40;
      }
      // 8. Pinyin initials contain query
      else if (pinyinShort.contains(cleanQuery)) {
        score = 30;
      }
      // 9. Package name contains query
      else if (app.packageName.toLowerCase().contains(cleanQuery)) {
        score = 10;
      }

      if (score > 0) {
        matched.add(_ScoredApp(app, score));
      }
    }

    // Sort by relevance score descending
    matched.sort((a, b) => b.score.compareTo(a.score));
    return matched.map((e) => e.app).toList();
  }

  /// Launches a web search in the user's default browser
  static Future<bool> launchWebSearch(String query, {String engine = 'Baidu'}) async {
    final encoded = Uri.encodeComponent(query.trim());
    String url;
    switch (engine.toLowerCase()) {
      case 'google':
        url = 'https://www.google.com/search?q=$encoded';
        break;
      case 'bing':
        url = 'https://www.bing.com/search?q=$encoded';
        break;
      case 'duckduckgo':
        url = 'https://duckduckgo.com/?q=$encoded';
        break;
      case 'baidu':
      default:
        url = 'https://www.baidu.com/s?wd=$encoded';
        break;
    }

    final uri = Uri.parse(url);
    try {
      return await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      return false;
    }
  }
}

class _ScoredApp {
  final AppItem app;
  final int score;
  _ScoredApp(this.app, this.score);
}
