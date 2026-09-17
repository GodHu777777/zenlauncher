import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../models/app_item.dart';

class AppLauncherService {
  static const MethodChannel _channel =
      MethodChannel('com.zenlauncher.app/launcher');

  /// Queries all launchable apps from the Android system
  static Future<List<AppItem>> fetchInstalledApps({bool includeSystem = true}) async {
    try {
      final List<dynamic>? result = await _channel.invokeMethod(
        'getInstalledApps',
        {'includeSystem': includeSystem},
      );

      if (result == null) return _getMockApps();

      return result.map((item) {
        final map = Map<String, dynamic>.from(item as Map);
        return AppItem.fromNativeMap(map);
      }).toList();
    } on MissingPluginException {
      debugPrint('AppLauncherService: MissingPluginException, returning mock apps for preview.');
      return _getMockApps();
    } catch (e) {
      debugPrint('Error fetching installed apps: $e');
      return _getMockApps();
    }
  }

  /// Launch an app by package name
  static Future<bool> launchApp(String packageName) async {
    try {
      final bool? success = await _channel.invokeMethod(
        'launchApp',
        {'packageName': packageName},
      );
      return success ?? false;
    } on MissingPluginException {
      debugPrint('Mock launch app: $packageName');
      return true;
    } catch (e) {
      debugPrint('Error launching app $packageName: $e');
      return false;
    }
  }

  /// Open Android App Info / Details settings
  static Future<void> openAppInfo(String packageName) async {
    try {
      await _channel.invokeMethod('openAppInfo', {'packageName': packageName});
    } catch (e) {
      debugPrint('Error opening app info for $packageName: $e');
    }
  }

  /// Open Android Default Home Launcher Settings
  static Future<void> openDefaultLauncherSettings() async {
    try {
      await _channel.invokeMethod('openDefaultLauncherSettings');
    } catch (e) {
      debugPrint('Error opening default launcher settings: $e');
    }
  }

  /// Check if ZenLauncher is currently the default launcher
  static Future<bool> isDefaultLauncher() async {
    try {
      final bool? isDefault = await _channel.invokeMethod('isDefaultLauncher');
      return isDefault ?? false;
    } catch (e) {
      return false;
    }
  }

  /// Built-in mock data for testing & desktop preview
  static List<AppItem> _getMockApps() {
    return [
      AppItem(appName: '电话', originalName: '电话', packageName: 'com.android.dialer', activityName: '', isFavorite: true),
      AppItem(appName: '短信', originalName: '短信', packageName: 'com.android.mms', activityName: '', isFavorite: true),
      AppItem(appName: '浏览器', originalName: '浏览器', packageName: 'com.android.chrome', activityName: '', isFavorite: true),
      AppItem(appName: '微信', originalName: '微信', packageName: 'com.tencent.mm', activityName: '', isFavorite: true),
      AppItem(appName: '相机', originalName: '相机', packageName: 'com.android.camera', activityName: ''),
      AppItem(appName: '相册', originalName: '相册', packageName: 'com.android.gallery', activityName: ''),
      AppItem(appName: '日历', originalName: '日历', packageName: 'com.android.calendar', activityName: ''),
      AppItem(appName: '备忘录', originalName: '备忘录', packageName: 'com.android.notes', activityName: '', isFavorite: true),
      AppItem(appName: '网易云音乐', originalName: '网易云音乐', packageName: 'com.netease.cloudmusic', activityName: ''),
      AppItem(appName: '高德地图', originalName: '高德地图', packageName: 'com.autonavi.minimap', activityName: ''),
      AppItem(appName: '支付宝', originalName: '支付宝', packageName: 'com.eg.android.AlipayGphone', activityName: ''),
      AppItem(appName: '淘宝', originalName: '淘宝', packageName: 'com.taobao.taobao', activityName: ''),
      AppItem(appName: '抖音', originalName: '抖音', packageName: 'com.ss.android.ugc.aweme', activityName: '', isDopamineApp: true, isHidden: true),
      AppItem(appName: '小红书', originalName: '小红书', packageName: 'com.xingin.xhs', activityName: '', isDopamineApp: true, isHidden: true),
      AppItem(appName: '哔哩哔哩', originalName: '哔哩哔哩', packageName: 'tv.danmaku.bili', activityName: '', isDopamineApp: true),
      AppItem(appName: '知乎', originalName: '知乎', packageName: 'com.zhihu.android', activityName: '', isDopamineApp: true),
    ];
  }
}
