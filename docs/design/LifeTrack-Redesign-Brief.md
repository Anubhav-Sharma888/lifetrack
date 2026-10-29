# LifeTrack: compact dashboard redesign

Design brief for approval · 29 October 2026

## Intent

Make LifeTrack faster to use every day. Show actionable habits immediately, make check-ins easy to distinguish from opening habit details, and present progress in a compact, understandable dashboard. Preserve the existing offline, private, self-comparison product.

The user selected **Compact dashboard — denser habits and analytics**. Color, typography, dimensions, and the screen structure below are proposed design decisions, rather than preferences the user has separately specified.

## Current evidence and limits

Z Code's completed report describes fixes for focus restoration, rest-day and future-habit labels, habit-specific heatmaps, input composition, sorting, and form drafts. I separately reran the browser preview's test suite: **54 passed, 0 failed**. I opened Today, Analytics, and Settings in the local preview.

The current Today screen spends most of the initial viewport on a large heading, progress card, search field, and two filters; the habit list starts near the bottom. Analytics puts a year-long heatmap before its summary metrics. These are the primary hierarchy problems the redesign addresses.

The native Kotlin/Compose Android application and the browser preview are separate implementations. A passing preview suite does not verify Android behavior. The Z Code report leaves Android build/device testing and browser import/export interaction checks unresolved. Design can proceed; release readiness is not established.

## Approaches considered

1. **Calm premium:** more whitespace and editorial hierarchy; attractive but shows fewer actionable habits at once.
2. **Energetic:** brighter colors and milestones; changes the product's emotional emphasis toward achievement.
3. **Compact dashboard — selected:** concise headers, efficient rows, and summary-first analytics. It makes more information visible, while requiring careful touch targets and text sizing.

## Scope and medium

First deliver a new, editable Figma Design file for the redesign; retain existing source files and existing designs. The Figma file will include mobile screens for the Android product, a responsive wide-screen browser-preview concept, shared components/tokens, and behavior notes for implementation. The first pass is a design deliverable, not a claim that either app has been updated.

Use fictional sample habits and illustrative, internally consistent progress data. Do not upload the user's real habit history, notes, backup files, or credentials. Use the connected Figma account without buying a seat, changing access permissions, or using paid AI generation. Report any edit-access restriction rather than changing the account.

After design acceptance, create the implementation plan for Z Code, distinguishing browser changes from Kotlin/Compose work. No deployment, publication, account system, cloud sync, or data migration is part of this redesign.

## Information architecture and screens

Keep the familiar three destinations: **Today, Analytics, Settings**. Habit detail and habit creation/editing remain secondary screens. Mobile uses bottom navigation; the wide-screen preview uses a compact sidebar. Keep navigation and primary actions labeled.

### Today

- A compact date header with accessible previous-day, next-day, and date-picker controls. Future check-ins remain unavailable.
- A small daily summary showing completed versus due habits. A thin progress indicator supports the number without dominating the page.
- Habits start within the first viewport: show at least three complete rows on a 390 × 844 reference screen with default text size, including one numeric-goal row.
- Each row contains an icon, name, relevant schedule or category, and a clearly separate check-in control. Show quantity and increment/decrement controls only for numeric habits. Opening details must not accidentally record progress.
- One compact filter control opens category/status options; search expands on demand. Keep active filters visible and easy to clear. Preserve keyboard-friendly reordering through an explicit action.
- Add habit is a labeled primary action. Skip, note, edit, and freeze actions remain discoverable through an accessible menu instead of depending on long-press alone.

### Analytics

- Lead with a selected period and its completion rate, completed/due count, and comparison with the preceding equivalent elapsed period.
- Show a compact trend chart followed by habit-level consistency. Use the existing calculations and describe their denominators; do not invent a health score or imply causation.
- Move the all-time/year heatmap to a secondary section. Provide an accessible date-selection alternative. Label rest days, skipped days, and frozen days distinctly; do not rely on color alone.
- Keep category breakdown and longer history available below the primary summary. Replace competitive wording such as “leaderboard” with self-comparison language.

### Habit detail

Show the habit's name, schedule, goal, current day relative to its start, and the selected date's check-in action. Include habit-specific history and trend, plus notes and explicit skip/freeze/edit actions where valid. Scheduled rest days and not-yet-started habits have explanatory, inactive check-in states. Archive and delete actions require clear consequences and remain visually secondary.

### Add or edit habit

Show name, icon/color, start date, schedule, and binary/numeric goal in a concise form. Put optional description, reminder, and habit stacking in an expandable section. Preserve drafts, validation, saved-data restrictions, and accessible focus behavior. Represent validation errors next to their fields.

### Settings

Group backups, appearance, accessibility, and app information. Make backup status and the local-only data model clear. Distinguish Android-only capabilities from preview behavior in the preview design. Export/import, password errors, and destructive consequences need clear feedback.

## Visual system and responsive behavior

Proposed palette: neutral off-white canvas, dark charcoal text, deep green primary actions, restrained semantic accents. Use the existing Inter family as the design reference, with 14–16 px body text and 12 px secondary labels at default scale. Avoid squeezing content by shrinking text.

Use an 8 px spacing rhythm, subtle dividers, modest corner radii, and reusable components. Interactive mobile targets are at least 48 × 48 px; visible icons may be smaller within them. Dense information must not mean tiny controls. Support text enlargement, visible keyboard focus, reduced motion, light and dark themes, and at least 4.5:1 contrast for normal text.

Mobile reference is 390 × 844 with a narrow-width check at 360 px. At larger text sizes rows grow vertically and labels wrap. Wide-screen reference is 1440 × 1000 with real application content using the space, rather than a phone frame and promotional copy. Narrow web layouts collapse to the mobile arrangement without horizontal page overflow.

## Figma deliverables and acceptance

- Editable Today, Analytics, Habit detail, Add/edit habit, and Settings screens, plus the wide-screen dashboard concept.
- Light-theme primary screens and representative dark-theme Today and Analytics screens.
- Reusable navigation, habit rows, numeric progress controls, summary tiles, buttons, fields, and state components, with shared typography/color/spacing tokens.
- Examples of empty, filtered-empty, completed, partial, skipped, rest-day, future-habit, validation-error, and backup-feedback states. Use separate state examples or component variants as appropriate.
- A linked prototype for the main navigation and representative habit/detail/add-habit flows. Prototype data is illustrative and does not persist real records.
- Implementation notes covering responsive behavior, accessibility, state mappings, and separate browser versus native work.
- Structural inspection to confirm editable text/components and screenshot review for clipping, overlap, hierarchy, and legibility. Do not represent a flattened UI image as an editable design.

## Decisions left to the builder

Choose exact token values, chart treatment, icon-library assets, Figma canvas organization, component names, and small-screen wrapping based on the constraints above. Reuse suitable accessible library assets if available; otherwise create local foundations. Keep the existing domain rules and feature set. Do not add social features, a subscription tier, or new behavioral requirements.

## Deferred verification

Figma approval is not app acceptance. Subsequent implementation must retain the existing 54 preview tests and verify changed check-in, filtering, focus, form-draft, and backup flows. Native Android work needs its own build, device, and TalkBack evidence. Browser backup download/upload round trips also remain to be exercised. Preserve these gaps in the handoff rather than marking them complete.
