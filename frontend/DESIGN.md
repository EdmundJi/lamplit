# Better Self Product UI Contract

## Stance

Modern personal-growth workspace for people building repeatable routines. The interface should feel clear, fresh, and lightly responsive, while staying practical and low-pressure. Experience points describe personal history only; no ranking, diagnosis, or personality claims.

## Layout

- Desktop: fixed sidebar `--sidebar` (208px, 220px in compact density), a single-row 56px topbar with breadcrumb-style context on the left and a `?` help popover (quiet disclosure, not a dialog) plus notifications on the right.
- Mobile: single content column with five-item fixed bottom navigation and safe-area padding; the topbar collapses to 52px.
- One content width site-wide: `.page` is 1144px (1080px content + 32px×2 padding); only scene-shaped pages (`/partners`) go wide at `.page--scene` 1440px.
- Use full-width bands (`.band`) for page flow, and use light cards only for repeated operational items such as tasks, badges, metrics, sessions, and dialogs. Never nest cards.
- Controls are 40px high (34px in compact density), icon controls are square at the same height. Radius: 8/12/16/20px (`--radius`/`--radius-card`/`--radius-panel`/`--radius-scene`); crisp mode: 4/6/8/10px.

## Tokens

- Canvas `#f7f6f2`, surface `#fffefa`, ink `#263d34`, muted `#716a6d`, border `#e3ddd8` — a warm, slightly grey-violet neutral palette drawn from the town's own pixel art rather than a generic UI grey.
- Primary `#255643`, accent `#698266`, amber `#916716`, danger `#b84248`. Five accent presets (default/ocean/plum/amber/graphite) swap canvas/surface/primary/accent together; dark theme keeps the same semantic roles on a green-black canvas.
- `--ease: cubic-bezier(.4, 0, .2, 1)` is the one motion curve site-wide.
- `--period-tint` shifts with the town's own day clock — morning/afternoon/evening/night, each with a light and dark value — and tints the `/today` hero background only (see Motion/de-templating: period tint follows the town, not a generic gradient).
- Shadows (`--shadow`, `--shadow-soft`) are soft, low-opacity, and reserved for elevated surfaces only (see Motion).

## Motion

- One easing curve everywhere: `var(--ease)`. Two durations carry almost all of it: `--motion-fast` (140ms, hover/focus/state colour changes) and `--motion-medium` (260ms, entrances, banners, dialogs). `--motion-slow` (520ms) is reserved for progress bars and celebratory moments, not general UI.
- Hover only changes colour (background/border/text) — never position or size.
- Shadows appear only on floating/overlay surfaces: dialogs, drawers, popovers, banners, the mobile bottom nav, and the desktop pet. Static in-flow surfaces (cards, bands) stay flat.
- Route switches carry no transition — `<RouterView>` swaps components directly. Nav links prefetch their route's chunk on `mouseenter`/`focus` (`pointerdown` on touch) so the click itself feels instant.
- Decorative animation (float, bounce, glow, gradient drift) is gone. Functional entrances stay but are capped at opacity plus ≤4px travel over `--motion-medium`: list rows and cards appearing (`*-enter` keyframes in the views), dialogs and drawers, banners. What's still intentionally kept beyond that: the login journey (seal/panel entrance, progress fill, dot pulse), pet actions and arrival (greet/play/comfort/celebrate/feed per species, desktop pet fade-in), the AI thinking indicator, AI message arrival, achievement/celebrate feedback banners, and `/today`'s live status breathing dot. Everything in this list (and the interaction primitives below) settles at its final state immediately under reduced or off motion.

## Interaction primitives

Shared, hand-tuned primitives in `src/shared/ui/interaction/` — each stays a real native/ARIA control (slider, radiogroup, `<details>`) so labels, keyboard and screen readers work unmodified; only the visual motion is custom, and all of it collapses to an immediate final-state jump under reduced/off motion.

- **SnapSlider** — a range input that drags continuously and glides to land on a tick. Used for numeric pickers: task duration/difficulty (`GoalEditorDrawer`, `TaskEditorDrawer`), today's available minutes and completion percent (`TodayView`), AI goal-draft duration/minutes/difficulty (`AiView`), onboarding daily minutes/weekly frequency (`OnboardingView`), and focus-session duration (`CompanionView`).
- **SegmentedControl** — a mutually-exclusive 2-5 option choice with the same glide-and-settle feel as SnapSlider, backed by a real `role="radio"` group. Used across `SettingsView`, `TodayView`, `AdminDashboard`, `TaskEditorDrawer`, `PartnersView`, `AiView` (scene tabs), `ChecklistView`, and `OnboardingView`.
- **useDragSort** — pointer reordering for a keyed list; rows stay in normal flow and are only translated, so drag never disturbs layout. Used by `ChecklistView`'s task list.
- **radialReveal** — expands an appearance change (theme/accent/density/radius) from the control the user pressed via the View Transitions API, instead of flashing the whole window. Wired in `UserLayout` and `SettingsView`.
- **StepperProgress** — a multi-step indicator where a finished step springs to full width instead of sliding, keeping done/current/upcoming visually distinct. Used in `UserLayout`'s guide popover, `WelcomeGuide`, `OperationGuideBar`, and `OnboardingView`.
- **v-disclose** — a directive for native `<details>` that grows the panel to its measured content height instead of snapping open/closed. Used in `GoalWorkspace`, `TodayView`, `AiView`, `ChecklistView`, and `CompanionView`.

## De-templating rules

- No filler eyebrows: `.eyebrow` is for a real contextual label (e.g. a drawer's kicker), not a generic kicker placed above every page/section heading.
- A section is `.section-title` (or the flex-wrapped `.section-head` variant) plus a hairline border — not a card.
- Cards are only for repeated items (tasks, badges, friends, conversations, templates …), never nested inside another card.
- Stats are inline definition lists (`dl.stats`-style: label + value pairs in a row), not boxed "big number" tiles.
- One `.primary` button per view — a page has a single clear main action.
- Empty states go through `EmptyState` (+ `PixelSprite` for the illustration): town-atlas animal frames only, no baked-background illustration frames.
- A hero band is only used on `/today`; every other page opens straight into `.page-head` + bands.
- Pixel art (`PixelSprite`, cut from the licensed town atlas/emotes sheet) is the product's only illustration language — never background texture, always a crisp in-content mark.
- The period tint (`--period-tint`) follows the town's own day/night clock and only appears on `/today`'s hero; it isn't a decorative gradient available to other pages.

## Behavior

- Every route supports loading, empty, error, offline, and permission states.
- Writes use Cookie sessions plus the readable CSRF cookie; tokens never enter web storage.
- Visible focus, semantic labels, keyboard-complete dialogs, WCAG 2.2 AA contrast, reduced-motion support.
- AI output is text-only. L3 replaces the normal conversation surface with reviewed crisis support.
- AI conversations are recoverable from a recent-history panel on the assistant page. Selecting a history item loads its messages, preserves the original scene, and sends follow-up prompts into the same session.
- First-time signed-in users see a dismissible welcome guide with four concise slides: daily execution, personal insight, AI confirmation, and data/style control. It is remembered per user on the current device and can be replayed from Settings.
- Style personalization is controlled through presets only: light/dark/system theme, five approved visual styles, comfortable/compact density, modern/crisp radius, and motion level (standard/reduced/off). Avoid unrestricted color pickers or arbitrary CSS controls.
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
