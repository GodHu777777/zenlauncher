import 'dart:async';
import 'package:flutter/material.dart';

class FrictionDialog extends StatefulWidget {
  final String appName;
  final int totalSeconds;
  final String prompt;
  final VoidCallback onConfirmed;

  const FrictionDialog({
    super.key,
    required this.appName,
    required this.totalSeconds,
    required this.prompt,
    required this.onConfirmed,
  });

  @override
  State<FrictionDialog> createState() => _FrictionDialogState();
}

class _FrictionDialogState extends State<FrictionDialog> with SingleTickerProviderStateMixin {
  late int _remainingSeconds;
  Timer? _timer;
  late AnimationController _animController;

  @override
  void initState() {
    super.initState();
    _remainingSeconds = widget.totalSeconds;
    _animController = AnimationController(
      vsync: this,
      duration: Duration(seconds: widget.totalSeconds),
    )..forward();

    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_remainingSeconds > 1) {
        setState(() {
          _remainingSeconds--;
        });
      } else {
        setState(() {
          _remainingSeconds = 0;
        });
        timer.cancel();
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _animController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final canOpen = _remainingSeconds == 0;

    return Dialog(
      backgroundColor: theme.dialogBackgroundColor,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(
          color: theme.colorScheme.outline.withOpacity(0.4),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Breathing countdown circle
            Stack(
              alignment: Alignment.center,
              children: [
                SizedBox(
                  width: 72,
                  height: 72,
                  child: AnimatedBuilder(
                    animation: _animController,
                    builder: (context, child) {
                      return CircularProgressIndicator(
                        value: _animController.value,
                        strokeWidth: 3,
                        color: theme.colorScheme.primary,
                        backgroundColor: theme.colorScheme.outline.withOpacity(0.3),
                      );
                    },
                  ),
                ),
                Text(
                  canOpen ? '✓' : '$_remainingSeconds',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.w300,
                    color: theme.colorScheme.onSurface,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Text(
              '即将打开「${widget.appName}」',
              style: theme.textTheme.titleLarge?.copyWith(
                fontSize: 17,
                fontWeight: FontWeight.w500,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 12),
            Text(
              widget.prompt,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontSize: 14,
                height: 1.5,
                color: theme.colorScheme.onSurface.withOpacity(0.7),
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 28),
            // Primary recommendation: Give up and stay focused
            SizedBox(
              width: double.infinity,
              height: 46,
              child: ElevatedButton(
                onPressed: () => Navigator.of(context).pop(),
                style: ElevatedButton.styleFrom(
                  backgroundColor: theme.colorScheme.primary,
                  foregroundColor: theme.colorScheme.onPrimary,
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(23),
                  ),
                ),
                child: const Text(
                  '放弃打开，保持专注',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w500),
                ),
              ),
            ),
            const SizedBox(height: 10),
            // Secondary button: Unlock after countdown
            SizedBox(
              width: double.infinity,
              height: 42,
              child: TextButton(
                onPressed: canOpen
                    ? () {
                        Navigator.of(context).pop();
                        widget.onConfirmed();
                      }
                    : null,
                style: TextButton.styleFrom(
                  foregroundColor: theme.colorScheme.onSurface.withOpacity(canOpen ? 0.8 : 0.25),
                ),
                child: Text(
                  canOpen ? '继续打开' : '冷静缓冲中 (${_remainingSeconds}s)',
                  style: const TextStyle(fontSize: 14),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
