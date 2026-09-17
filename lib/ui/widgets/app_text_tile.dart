import 'package:flutter/material.dart';
import '../../models/app_item.dart';

class AppTextTile extends StatelessWidget {
  final AppItem app;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final bool showTags;
  final double fontSize;

  const AppTextTile({
    super.key,
    required this.app,
    required this.onTap,
    this.onLongPress,
    this.showTags = true,
    this.fontSize = 18,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return InkWell(
      onTap: onTap,
      onLongPress: onLongPress,
      splashColor: theme.colorScheme.primary.withOpacity(0.08),
      highlightColor: Colors.transparent,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 13),
        child: Row(
          children: [
            Expanded(
              child: Text(
                app.appName,
                style: theme.textTheme.bodyLarge?.copyWith(
                  fontSize: fontSize,
                  fontWeight: FontWeight.w300,
                  letterSpacing: 0.3,
                  color: theme.colorScheme.onSurface.withOpacity(0.9),
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (showTags) ...[
              if (app.isDopamineApp)
                _buildTag(
                  context,
                  label: '冷静',
                  color: Colors.amber.withOpacity(0.7),
                ),
              if (app.isHidden)
                _buildTag(
                  context,
                  label: '隐藏',
                  color: theme.colorScheme.onSurface.withOpacity(0.4),
                ),
              if (app.isFavorite)
                _buildTag(
                  context,
                  label: '置顶',
                  color: theme.colorScheme.primary.withOpacity(0.6),
                ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildTag(BuildContext context, {required String label, required Color color}) {
    return Container(
      margin: const EdgeInsets.only(left: 6),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: color.withOpacity(0.4), width: 0.8),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 10,
          fontWeight: FontWeight.w300,
        ),
      ),
    );
  }
}
