import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/launcher_settings.dart';

class StorageService {
  static const String _keyFavorites = 'pref_favorites';
  static const String _keyHiddenApps = 'pref_hidden_apps';
  static const String _keyDopamineApps = 'pref_dopamine_apps';
  static const String _keyAliases = 'pref_aliases';
  static const String _keySettings = 'pref_settings';

  final SharedPreferences _prefs;

  StorageService(this._prefs);

  static Future<StorageService> init() async {
    final prefs = await SharedPreferences.getInstance();
    return StorageService(prefs);
  }

  // Favorites
  List<String> getFavorites() {
    return _prefs.getStringList(_keyFavorites) ?? [];
  }

  Future<void> saveFavorites(List<String> list) async {
    await _prefs.setStringList(_keyFavorites, list);
  }

  Future<void> toggleFavorite(String packageName) async {
    final list = getFavorites();
    if (list.contains(packageName)) {
      list.remove(packageName);
    } else {
      list.add(packageName);
    }
    await saveFavorites(list);
  }

  // Hidden Apps
  List<String> getHiddenApps() {
    return _prefs.getStringList(_keyHiddenApps) ?? [];
  }

  Future<void> saveHiddenApps(List<String> list) async {
    await _prefs.setStringList(_keyHiddenApps, list);
  }

  Future<void> toggleHidden(String packageName) async {
    final list = getHiddenApps();
    if (list.contains(packageName)) {
      list.remove(packageName);
    } else {
      list.add(packageName);
    }
    await saveHiddenApps(list);
  }

  // Dopamine / Friction Apps
  List<String> getDopamineApps() {
    return _prefs.getStringList(_keyDopamineApps) ?? [];
  }

  Future<void> saveDopamineApps(List<String> list) async {
    await _prefs.setStringList(_keyDopamineApps, list);
  }

  Future<void> toggleDopamineApp(String packageName) async {
    final list = getDopamineApps();
    if (list.contains(packageName)) {
      list.remove(packageName);
    } else {
      list.add(packageName);
    }
    await saveDopamineApps(list);
  }

  // Custom Aliases
  Map<String, String> getAliases() {
    final raw = _prefs.getString(_keyAliases);
    if (raw == null) return {};
    try {
      final map = jsonDecode(raw) as Map<String, dynamic>;
      return map.map((key, value) => MapEntry(key, value.toString()));
    } catch (_) {
      return {};
    }
  }

  Future<void> setAlias(String packageName, String alias) async {
    final map = getAliases();
    if (alias.trim().isEmpty) {
      map.remove(packageName);
    } else {
      map[packageName] = alias.trim();
    }
    await _prefs.setString(_keyAliases, jsonEncode(map));
  }

  // General Settings
  LauncherSettings getSettings() {
    final raw = _prefs.getString(_keySettings);
    if (raw == null) return LauncherSettings();
    try {
      return LauncherSettings.fromJson(raw);
    } catch (_) {
      return LauncherSettings();
    }
  }

  Future<void> saveSettings(LauncherSettings settings) async {
    await _prefs.setString(_keySettings, settings.toJson());
  }
}
