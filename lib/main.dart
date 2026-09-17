import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'services/storage_service.dart';
import 'ui/theme/app_theme.dart';
import 'ui/screens/home_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Set immersive system bars
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.black,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );

  final storage = await StorageService.init();

  runApp(ZenLauncherApp(storage: storage));
}

class ZenLauncherApp extends StatefulWidget {
  final StorageService storage;

  const ZenLauncherApp({super.key, required this.storage});

  @override
  State<ZenLauncherApp> createState() => _ZenLauncherAppState();
}

class _ZenLauncherAppState extends State<ZenLauncherApp> {
  @override
  Widget build(BuildContext context) {
    final settings = widget.storage.getSettings();

    return MaterialApp(
      title: 'ZenLauncher',
      debugShowCheckedModeBanner: false,
      theme: settings.isOledDark ? AppTheme.oledDarkTheme() : AppTheme.lightTheme(),
      home: HomeScreen(storage: widget.storage),
    );
  }
}
