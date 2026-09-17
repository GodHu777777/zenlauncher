import 'package:flutter/material.dart';

class AppTheme {
  static ThemeData oledDarkTheme() {
    return ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: Colors.black,
      canvasColor: Colors.black,
      dialogBackgroundColor: const Color(0xFF141414),
      colorScheme: const ColorScheme.dark(
        primary: Colors.white,
        onPrimary: Colors.black,
        surface: Color(0xFF121212),
        onSurface: Colors.white,
        outline: Color(0xFF2A2A2A),
      ),
      fontFamily: 'sans-serif',
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.black,
        elevation: 0,
        titleTextStyle: TextStyle(
          color: Colors.white,
          fontSize: 18,
          fontWeight: FontWeight.w400,
          letterSpacing: 0.5,
        ),
        iconTheme: IconThemeData(color: Colors.white),
      ),
      textTheme: const TextTheme(
        displayLarge: TextStyle(
          color: Colors.white,
          fontSize: 54,
          fontWeight: FontWeight.w200,
          letterSpacing: -1.0,
        ),
        headlineMedium: TextStyle(
          color: Colors.white,
          fontSize: 22,
          fontWeight: FontWeight.w300,
        ),
        titleLarge: TextStyle(
          color: Colors.white,
          fontSize: 18,
          fontWeight: FontWeight.w400,
          letterSpacing: 0.2,
        ),
        bodyLarge: TextStyle(
          color: Color(0xFFE0E0E0),
          fontSize: 16,
          fontWeight: FontWeight.w300,
          letterSpacing: 0.3,
        ),
        bodyMedium: TextStyle(
          color: Color(0xFF9E9E9E),
          fontSize: 14,
          fontWeight: FontWeight.w300,
        ),
      ),
    );
  }

  static ThemeData lightTheme() {
    return ThemeData(
      brightness: Brightness.light,
      scaffoldBackgroundColor: const Color(0xFFF7F7F7),
      canvasColor: const Color(0xFFF7F7F7),
      dialogBackgroundColor: Colors.white,
      colorScheme: const ColorScheme.light(
        primary: Colors.black,
        onPrimary: Colors.white,
        surface: Colors.white,
        onSurface: Colors.black,
        outline: Color(0xFFE0E0E0),
      ),
      fontFamily: 'sans-serif',
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xFFF7F7F7),
        elevation: 0,
        titleTextStyle: TextStyle(
          color: Colors.black,
          fontSize: 18,
          fontWeight: FontWeight.w400,
        ),
        iconTheme: IconThemeData(color: Colors.black),
      ),
    );
  }
}
