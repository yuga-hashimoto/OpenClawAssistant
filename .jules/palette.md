## 2025-02-13 - Dynamic Content Descriptions for Multi-state Buttons
**Learning:** Hardcoded accessibility descriptions (`contentDescription`) on multi-purpose Compose elements (like a FAB that acts as "Send", "Stop", or "Mic" depending on state) will cause screen readers to announce misleading actions.
**Action:** When evaluating or creating multi-state interactive icons or buttons in Compose, always ensure the `contentDescription` string is computed dynamically using the same state rules as the `imageVector` or `onClick` handler.

## 2024-05-19 - Standardize contentDescription for File Attachments
**Learning:** Hardcoded accessibility descriptions in Jetpack Compose (e.g., `contentDescription = "Attach file"`) prevent localization for screen readers, diminishing the experience for non-English users.
**Action:** Always extract `contentDescription` strings to `strings.xml` and use `stringResource(R.string.key)` to ensure accessibility labels are fully localizable.

## 2024-11-20 - Expand/Collapse Accessibility Pattern
**Learning:** Adding a `contentDescription` to an expand/collapse `Icon` inside a clickable row that already has adjacent descriptive text causes duplicate/confusing screen reader announcements.
**Action:** Always set the `onClickLabel` of the `Modifier.clickable` parent `Row` to describe the action (e.g. "Expand" or "Collapse") dynamically based on state, assign a semantic `role = Role.Button`, and set the child `Icon`'s `contentDescription = null` to ensure a single, clear semantic interaction.

## 2025-02-13 - Redundant Screen Reader Announcements on Icons
**Learning:** Adding a `contentDescription` to an `Icon` when the adjacent `Text` provides the exact same descriptive string causes screen readers (like TalkBack) to announce the action twice (e.g., "Scan QR Code, Scan QR Code").
**Action:** When an `Icon` is used alongside descriptive text within a clickable area, set the `Icon`'s `contentDescription` to `null` so the screen reader only reads the text once.

## 2025-03-27 - Expand/Collapse Accessibility Pattern for MissingScopeCard
**Learning:** Using `Card(onClick=)` without an `onClickLabel` leads to generic, unhelpful screen reader announcements. Expandable cards require explicit labels that change based on state (e.g. Expand / Collapse).
**Action:** When converting `Card(onClick=)` to use `Modifier.clickable()`, use `onClickLabel = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand)` and assign `role = Role.Button` so the action changes dynamically for screen readers.

## 2025-05-18 - RadioButton Grouping Accessibility
**Learning:** Placing a `RadioButton` inside a clickable layout element (like a `Row`) using `Modifier.clickable` creates conflicting semantics, confusing screen readers by presenting multiple disjointed click targets.
**Action:** Replace `Modifier.clickable` on the parent layout with `Modifier.selectable(role = Role.RadioButton)` and explicitly set the inner `RadioButton`'s `onClick` parameter to `null` to ensure the group is treated as a single, cohesive radio button element.

## 2025-10-24 - Dropdown Trigger Accessibility
**Learning:** Using `Modifier.clickable` without a specific role and label for a row that opens a dropdown causes screen readers to misidentify its function.
**Action:** When a clickable element opens a dropdown menu, always use `Modifier.clickable(onClickLabel = "...", role = Role.DropdownList)` to ensure proper screen reader announcement.

## 2024-05-19 - Standardize contentDescription for File Attachments
**Learning:** Hardcoded accessibility descriptions in Jetpack Compose (e.g., `contentDescription = "Attach file"`) prevent localization for screen readers, diminishing the experience for non-English users.
**Action:** Always extract `contentDescription` strings to `strings.xml` and use `stringResource(R.string.key)` to ensure accessibility labels are fully localizable.
