class AppItem {
  final String appName;
  final String originalName;
  final String packageName;
  final String activityName;
  final bool isSystemApp;
  final bool isFavorite;
  final bool isHidden;
  final bool isDopamineApp;
  final String pinyin;
  final String pinyinShort;

  AppItem({
    required this.appName,
    required this.originalName,
    required this.packageName,
    required this.activityName,
    this.isSystemApp = false,
    this.isFavorite = false,
    this.isHidden = false,
    this.isDopamineApp = false,
    this.pinyin = '',
    this.pinyinShort = '',
  });

  AppItem copyWith({
    String? appName,
    String? originalName,
    String? packageName,
    String? activityName,
    bool? isSystemApp,
    bool? isFavorite,
    bool? isHidden,
    bool? isDopamineApp,
    String? pinyin,
    String? pinyinShort,
  }) {
    return AppItem(
      appName: appName ?? this.appName,
      originalName: originalName ?? this.originalName,
      packageName: packageName ?? this.packageName,
      activityName: activityName ?? this.activityName,
      isSystemApp: isSystemApp ?? this.isSystemApp,
      isFavorite: isFavorite ?? this.isFavorite,
      isHidden: isHidden ?? this.isHidden,
      isDopamineApp: isDopamineApp ?? this.isDopamineApp,
      pinyin: pinyin ?? this.pinyin,
      pinyinShort: pinyinShort ?? this.pinyinShort,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'appName': appName,
      'originalName': originalName,
      'packageName': packageName,
      'activityName': activityName,
      'isSystemApp': isSystemApp,
      'isFavorite': isFavorite,
      'isHidden': isHidden,
      'isDopamineApp': isDopamineApp,
      'pinyin': pinyin,
      'pinyinShort': pinyinShort,
    };
  }

  factory AppItem.fromNativeMap(Map<dynamic, dynamic> map) {
    final name = (map['appName'] as String?) ?? 'Unknown';
    return AppItem(
      appName: name,
      originalName: name,
      packageName: (map['packageName'] as String?) ?? '',
      activityName: (map['activityName'] as String?) ?? '',
      isSystemApp: (map['isSystemApp'] as bool?) ?? false,
    );
  }
}
