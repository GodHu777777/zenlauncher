import 'package:flutter/material.dart';

class SearchBarWidget extends StatelessWidget {
  final TextEditingController controller;
  final ValueChanged<String> onChanged;
  final ValueChanged<String> onSubmitted;
  final VoidCallback onClear;
  final FocusNode? focusNode;
  final String hintText;

  const SearchBarWidget({
    super.key,
    required this.controller,
    required this.onChanged,
    required this.onSubmitted,
    required this.onClear,
    this.focusNode,
    this.hintText = '输入意图 / 搜索应用或网页...',
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      height: 52,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        borderRadius: BorderRadius.circular(26),
        border: Border.all(
          color: theme.colorScheme.outline.withOpacity(0.6),
          width: 1,
        ),
      ),
      child: Row(
        children: [
          Icon(
            Icons.search,
            size: 20,
            color: theme.colorScheme.onSurface.withOpacity(0.5),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: TextField(
              controller: controller,
              focusNode: focusNode,
              onChanged: onChanged,
              onSubmitted: onSubmitted,
              style: theme.textTheme.bodyLarge?.copyWith(fontSize: 16),
              cursorColor: theme.colorScheme.primary,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: hintText,
                hintStyle: TextStyle(
                  color: theme.colorScheme.onSurface.withOpacity(0.4),
                  fontSize: 15,
                  fontWeight: FontWeight.w300,
                ),
                border: InputBorder.none,
                isDense: true,
                contentPadding: EdgeInsets.zero,
              ),
            ),
          ),
          if (controller.text.isNotEmpty)
            GestureDetector(
              onTap: onClear,
              child: Padding(
                padding: const EdgeInsets.all(4.0),
                child: Icon(
                  Icons.close,
                  size: 18,
                  color: theme.colorScheme.onSurface.withOpacity(0.5),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
