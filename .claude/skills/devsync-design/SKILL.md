---
name: devsync-design
description: DevSync design system for dense developer collaboration UI. Use when designing, coding, or reviewing DevSync-branded dashboards, kanban boards, issue trackers, repository browsers, data tables, marketing pages, slides, or prototypes. Provides color/type/spacing tokens, brand voice, icon guidance, logos, previews, and React UI kit examples.
---

# DevSync Design

Use this skill to make DevSync UI feel sharp, dense, quiet, and developer-grade.

## Source Order

Read only the files needed for the task:

- `references/design-system.md`: full design rules for color, type, spacing, voice, iconography, layout, and caveats. Read this first for brand/design work.
- `colors_and_type.css`: token source of truth. Import or copy these CSS variables instead of retyping hex values.
- `references/ui-kit.md`: short map of the React kit files and prototype behavior.
- `references/iconography.md`: Lucide usage, icon sizing, stroke, and substitution guidance.
- `ui_kits/devsync-app/`: React examples for production-style product screens. Inspect the relevant component files before building dashboards, issues, kanban, repository, sidebar, or topbar surfaces.
- `preview/`: token and component specimen pages. Open only the relevant preview file when a concrete token or component example is needed.
- `assets/`: DevSync logo, mark, and wireframe reference images.

## Application Rules

1. Use tokens, not raw values. Prefer `var(--ds-...)` from `colors_and_type.css`; do not inline brand hex colors unless exporting to a format that cannot consume CSS variables.
2. Default to dark mode for product UI. Set `data-theme="dark"` on the root for HTML prototypes unless the user asks for light mode.
3. Preserve density. Default body text is 13px, table text is 12px, controls are 28px tall, rows are 36px, and layout spacing follows a 4px grid.
4. Keep radius sharp. Use 2px, 4px, or 6px; reserve pill radius for status dots and avatars.
5. Prefer borders over shadows. Panels and cards use 1px borders; shadows are for popovers, menus, modals, and toasts.
6. Keep product copy flat and factual. Use sentence case, imperative buttons, no emoji in product UI, and no exclamation marks.
7. Use Lucide-style outline icons at 16px with `currentColor` unless proprietary DevSync icons are provided.
8. Reuse the React kit primitives and patterns where possible instead of recreating buttons, badges, avatars, tables, sidebars, kanban cards, or repository views from scratch.

## Output Guidance

- For HTML artifacts, link or copy `colors_and_type.css`, set the theme on the root element, and mirror the UI kit's component density.
- For production code, adapt the tokens and the smallest relevant component patterns into the target stack.
- For visual reviews, compare the result against the relevant `preview/` file or `ui_kits/devsync-app/index.html`.

## Compatibility Notes

This package follows the Agent Skills format: the folder name and `name` are `devsync-design`, `SKILL.md` contains the required `name` and `description`, and detailed material lives in `references/`, `assets/`, and `ui_kits/` for progressive disclosure.

In this repo, the actual skill directory is `.claude/skills/devsync-design` for Claude Code project discovery. Codex discovers the same package through `.agents/skills/devsync-design`, which is a symlink to the Claude Code directory. The root `devsync-design` path is a convenience symlink to the same package. Codex-specific UI metadata lives in `agents/openai.yaml`.
