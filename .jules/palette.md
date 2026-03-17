## 2025-02-13 - Dynamic Content Descriptions for Multi-state Buttons
**Learning:** Hardcoded accessibility descriptions (`contentDescription`) on multi-purpose Compose elements (like a FAB that acts as "Send", "Stop", or "Mic" depending on state) will cause screen readers to announce misleading actions.
**Action:** When evaluating or creating multi-state interactive icons or buttons in Compose, always ensure the `contentDescription` string is computed dynamically using the same state rules as the `imageVector` or `onClick` handler.

## 2024-05-19 - Standardize contentDescription for File Attachments
**Learning:** Hardcoded accessibility descriptions in Jetpack Compose (e.g., `contentDescription = "Attach file"`) prevent localization for screen readers, diminishing the experience for non-English users.
**Action:** Always extract `contentDescription` strings to `strings.xml` and use `stringResource(R.string.key)` to ensure accessibility labels are fully localizable.
