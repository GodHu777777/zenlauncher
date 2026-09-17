import 'dart:convert' if (dart.library.io) 'dart:convert';

class LauncherSettings {
  final int frictionSeconds;
  final String frictionPrompt;
  final bool isOledDark;
  final bool showClock;
  final bool showDate;
  final String customMotto;
  final String defaultSearchEngine;

  LauncherSettings({
    this.frictionSeconds = 5,
    this.frictionPrompt = '停顿 5 秒。\n确认这是你真正想做的事，还是下意识的习惯？',
    this.isOledDark = true,
    this.showClock = true,
    this.showDate = true,
    this.customMotto = '保持专注，活在当下',
    this.defaultSearchEngine = 'Baidu',
  });

  LauncherSettings copyWith({
    int? frictionSeconds,
    String? frictionPrompt,
    bool? isOledDark,
    bool? showClock,
    bool? showDate,
    String? customMotto,
    String? defaultSearchEngine,
  }) {
    return LauncherSettings(
      frictionSeconds: frictionSeconds ?? this.frictionSeconds,
      frictionPrompt: frictionPrompt ?? this.frictionPrompt,
      isOledDark: isOledDark ?? this.isOledDark,
      showClock: showClock ?? this.showClock,
      showDate: showDate ?? this.showDate,
      customMotto: customMotto ?? this.customMotto,
      defaultSearchEngine: defaultSearchEngine ?? this.defaultSearchEngine,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'frictionSeconds': frictionSeconds,
      'frictionPrompt': frictionPrompt,
      'isOledDark': isOledDark,
      'showClock': showClock,
      'showDate': showDate,
      'customMotto': customMotto,
      'defaultSearchEngine': defaultSearchEngine,
    };
  }

  factory LauncherSettings.fromMap(Map<String, dynamic> map) {
    return LauncherSettings(
      frictionSeconds: (map['frictionSeconds'] as int?) ?? 5,
      frictionPrompt: (map['frictionPrompt'] as String?) ??
          '停顿 5 秒。\n确认这是你真正想做的事，还是下意识的习惯？',
      isOledDark: (map['isOledDark'] as bool?) ?? true,
      showClock: (map['showClock'] as bool?) ?? true,
      showDate: (map['showDate'] as bool?) ?? true,
      customMotto: (map['customMotto'] as String?) ?? '保持专注，活在当下',
      defaultSearchEngine: (map['defaultSearchEngine'] as String?) ?? 'Baidu',
    );
  }

  String toJson() => jsonEncode(toMap());

  factory LauncherSettings.fromJson(String source) =>
      LauncherSettings.fromMap(jsonDecode(source) as Map<String, dynamic>);
}
