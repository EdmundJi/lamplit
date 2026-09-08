# Better Self Product UI Contract

## Stance

Modern personal-growth workspace for people building repeatable routines. The interface should feel clear, fresh, and lightly responsive, while staying practical and low-pressure. Experience points describe personal history only; no ranking, diagnosis, or personality claims.

## Layout

- Desktop: 240px translucent left navigation, compact top context bar, one primary work column up to 1120px.
- Mobile: single content column with five-item fixed bottom navigation and safe-area padding.
- Use full-width bands for page flow, and use light cards for repeated operational items such as tasks, badges, metrics, sessions, and dialogs. Never nest cards.
- Controls are 40px high, icon controls are 40px square, default radius is 8px, crisp mode uses 4px.

## Tokens

- Default canvas `#f5f7fb`, surface `#ffffff`, ink `#17202c`, muted `#667487`.
- Default primary `#2563eb`, accent `#10b981`, amber `#d97706`, danger `#dc2626`.
- Presets provide distinct product moods: clear morning focus, deep-ocean immersion, energetic growth, warm paper, and minimalist efficiency.
- Borders use `#d8e0ea`; shadows are soft and mostly reserved for active surfaces, repeated cards, dialogs, and mobile navigation.
- Type: Chinese-first readable system sans. Avoid serif headings as a default visual motif; reserve display weight for true page titles and key metrics.

## Behavior

- Every route supports loading, empty, error, offline, and permission states.
- Writes use Cookie sessions plus the readable CSRF cookie; tokens never enter web storage.
- Visible focus, semantic labels, keyboard-complete dialogs, WCAG 2.2 AA contrast, reduced-motion support.
- AI output is text-only. L3 replaces the normal conversation surface with reviewed crisis support.
- AI conversations are recoverable from a recent-history panel on the assistant page. Selecting a history item loads its messages, preserves the original scene, and sends follow-up prompts into the same session.
- First-time signed-in users see a dismissible welcome guide with four concise slides: daily execution, personal insight, AI confirmation, and data/style control. It is remembered per user on the current device and can be replayed from Settings.
- Motion is purposeful and quiet: login journey animation, page reveals, list row feedback, progress movement, dialog entrance, AI message arrival, badge unlock emphasis, and calendar hover. Users can choose standard, reduced, or off.
- Five interactions carry that motion further, and all of them land on their final state immediately at reduced or off: appearance changes expand from the pressed control, checklist rows are carried by pointer or arrow keys while their neighbours slide aside, tick-based sliders drag freely and settle on a tick, step indicators separate done, current and upcoming, and <details> panels grow to their measured content height.
- Style personalization is controlled through presets only: light/dark/system theme, five approved visual styles, comfortable/compact density, modern/crisp radius, and motion level. Avoid unrestricted color pickers or arbitrary CSS controls.
- Daily execution starts with a lightweight state check: energy, available minutes, and a recommended plan mode. The recommendation never mutates tasks automatically; user intent remains explicit.
- Focus mode is task-scoped and minimal: timer, current task, complete, partial complete, and exit. It uses the same task event semantics as the normal task row.
- Recovery plan language must be non-punitive. It can suggest keeping one task, shrinking the next action, or restarting tomorrow, but must not imply loss of worth or streak failure.
- Goal templates are editable starters, not one-click commitments. Selecting a template fills the goal form and explains starter tasks; saving still requires user confirmation.
- Goals own periodic tasks directly in the primary UI. Weekly plans remain an internal compatibility model; saving a task automatically expands its recurrence across the selected date range.
- Insights include calendar, personal badges, role progress, trends, and a weekly review guide. Badges are private milestones only, with no ranking or social comparison. Keep the badge system around 20 achievements, each with a distinct icon, an explicit trigger condition, and clear earned/locked states; locked badges are not presented as awarded.
- Attributes are an action-history visualization, not an assessment. The five system dimensions appear as 智力、体力、执行力、社交力和心境力; raw experience comes from completed task events and the radar uses a bounded display index for comparison across the same user.
- AI suggestion explanations must show time, difficulty, boundary, and confirmation criteria. AI remains a drafting aid; users explicitly confirm before turning suggestions into tasks.
- AI may draft one schema-validated goal template from the active conversation. The draft remains editable, exposes its JSON payload, and only pre-fills the goal form; it never creates a goal without explicit confirmation.
- The profile page is the personal hub for identity, overall growth level, action totals, wallet and selected pet. On mobile it also provides access to lower-frequency destinations omitted from the five-item bottom navigation.
- Admin surfaces are dense governance tools, not marketing pages. `/admin` is available only to non-USER roles after MFA, and covers overview metrics, privileged account creation, user status control, AI safety review, and audit logs.
