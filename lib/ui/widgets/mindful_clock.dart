import 'dart:async';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

class MindfulClock extends StatefulWidget {
  final bool showClock;
  final bool showDate;
  final String motto;

  const MindfulClock({
    super.key,
    this.showClock = true,
    this.showDate = true,
    this.motto = '',
  });

  @override
  State<MindfulClock> createState() => _MindfulClockState();
}

class _MindfulClockState extends State<MindfulClock> {
  late Timer _timer;
  DateTime _now = DateTime.now();

  @override
  void initState() {
    super.initState();
    _now = DateTime.now();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (mounted) {
        setState(() {
          _now = DateTime.now();
        });
      }
    });
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final timeStr = DateFormat('HH:mm').format(_now);
    final dateStr = DateFormat('M月d日 EEEE', 'zh_CN').format(_now);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (widget.showClock) ...[
          Text(
            timeStr,
            style: theme.textTheme.displayLarge?.copyWith(
              fontWeight: FontWeight.w200,
              letterSpacing: -1.5,
            ),
          ),
        ],
        if (widget.showDate) ...[
          const SizedBox(height: 4),
          Text(
            dateStr,
            style: theme.textTheme.bodyMedium?.copyWith(
              letterSpacing: 0.5,
            ),
          ),
        ],
        if (widget.motto.isNotEmpty) ...[
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              border: Border(
                left: BorderSide(
                  color: theme.colorScheme.primary.withOpacity(0.4),
                  width: 2,
                ),
              ),
            ),
            child: Text(
              widget.motto,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurface.withOpacity(0.6),
                fontSize: 13,
                fontStyle: FontStyle.italic,
              ),
            ),
          ),
        ],
      ],
    );
  }
}
