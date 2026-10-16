---
name: SpecTrace
description: Label ledger — forest-ink framing, herb-green actions, ruled data panels
colors:
  primary: "#2d694d"
  primary-hover: "#24573f"
  surface: "#fff"
  foreground: "#1d2b24"
  canvas: "#f6f4ee"
  surface-tint: "#e6f0e9"
  text-muted: "#56635b"
  border-soft: "#e2ddd0"
  border-control: "#79847c"
  focus: "#2f7a56"
  navy: "#17271f"
  success: "#1f6b47"
  success-bg: "#e8f4ec"
  warning: "#7d5300"
  warning-bg: "#fcf3de"
  danger: "#9b2c36"
  danger-bg: "#fbeeed"
  saffron: "#c98a1b"
  rule: "#1d2b24"
  destructive: "#c73e4b"
  disabled-surface: "#ece9e1"
  chart-green: "#288b5d"
  chart-teal: "#5dcfc4"
  chart-blue: "#6fb8e5"
typography:
  headline:
    fontFamily: "'Iowan Old Style', 'Palatino Linotype', Palatino, Georgia, serif"
    fontSize: "32px"
    fontWeight: 650
    letterSpacing: "-0.8px"
  title:
    fontFamily: "-apple-system, 'Segoe UI', 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "18px"
    fontWeight: 650
  body:
    fontFamily: "-apple-system, 'Segoe UI', 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "15px"
    lineHeight: 1.6
  label:
    fontFamily: "-apple-system, 'Segoe UI', 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
    fontWeight: 600
  support:
    fontFamily: "-apple-system, 'Segoe UI', 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "12px"
rounded:
  surface: "14px"
  control: "8px"
  compact: "5px"
  notice: "6px"
spacing:
  compact: "8px"
  control-gap: "12px"
  inset-small: "16px"
  inset-mobile: "18px"
  inset: "24px"
  section: "32px"
  entry: "40px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "8px 16px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  button-outline:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.control}"
    padding: "8px 16px"
  button-ghost-hover:
    backgroundColor: "{colors.surface-tint}"
    textColor: "{colors.primary-hover}"
  button-disabled:
    backgroundColor: "{colors.disabled-surface}"
    textColor: "{colors.text-muted}"
  panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.surface}"
  field:
    textColor: "{colors.foreground}"
    rounded: "{rounded.control}"
  navigation-active:
    backgroundColor: "{colors.surface-tint}"
    textColor: "{colors.primary-hover}"
    rounded: "{rounded.control}"
  group-tag-success:
    backgroundColor: "{colors.success-bg}"
    textColor: "{colors.success}"
    rounded: "{rounded.compact}"
    padding: "4px 8px"
---

# Design System: SpecTrace

## Overview

**Creative North Star: "Label ledger"**

The workspace reads like a food label and its compliance ledger: warm label-stock canvas, a forest-ink header with a saffron edge, a leaf-green layered mark, herb-green actions, serif display headings, and data blocks modelled on the nutrition-facts panel (a heavy ink rule, hairline separators, tracked small-caps field names). Allergen and error states stay red; review attention uses amber. Dense tables and evidence forms stay readable through clear headings, restrained support text, and visible interaction states.

This records the implemented shared system after the frontend refinement. It does not introduce a new brand or page strategy. Frontmatter defines the reusable primitives; `frontend/src/styles.css` and the shared UI components remain implementation evidence. Some scoped legacy treatments remain, as described below.

**Key Characteristics:**

- Herb-green actions on forest-ink framing; neutral lifecycle badges (one badge shows every state).
- Compact data layouts with generous separation between tasks.
- Visible current navigation, keyboard focus, and explicit status text.

## Colors

Primary herb green marks actions and selected navigation; neutral surfaces carry most of the screen.

### Primary

Use primary for the main action and caret, primary-hover for shared action hover and active navigation, and surface-tint for selection and contextual emphasis.

### Neutral

Surface is white; canvas separates the workspace from its panels. Foreground carries names and headings; text-muted carries supporting content. Border-soft separates surfaces, border-control outlines fields and outline buttons, and navy anchors the header. Focus is a distinct interaction role.

### Semantic and data roles

Success and success-bg appear in validation results, health, and teal group tags. Danger and danger-bg appear in failed validation and error notices. Destructive is the UI component action/invalid-boundary role. Warning and warning-bg are declared root tokens reserved for warning use; current unresolved-evidence blocks retain scoped amber literals instead.

Chart-green, chart-teal, and chart-blue record the existing donut categories. Existing five-category bar gradients and featured metric gradients remain part of their chart contexts; they are not generic status colors. Preserve category assignments and legend meaning.

**The Meaning Rule.** Pair status color with explicit text. Preserve the distinction between action, status, and chart-category color.

## Typography

Use the existing Segoe UI / Microsoft YaHei stack throughout. This is an operational type system; it has no separate display face.

The frontmatter headline, title, body, label, and support roles capture recurring sizes. Catalog body cells and product names use (14px), toolbar and metric labels use (13px), and table headings, chart labels, metadata, and footers use the support role. Product names have semibold emphasis; numeric table cells use tabular numerals.

Catalog page headings step down to (25px) at the navigation breakpoint and (28px) on narrow phones. Authenticated page headings use `clamp(26px, 4vw, 34px)` with (1.2) line height; section headings use (23px) with (1.3) line height. Authenticated descriptive copy is (16px), line height (1.6), and maximum measure (65ch). These are scoped hierarchy variants, not replacements for the catalog ramp.

Metric values retain their existing (38px), weight (650), line height (1.2) treatment and reduce to (32px) on smaller layouts. Keep this emphasis local to actual metrics.

## Layout

The catalog frame has a sticky (64px) header, fixed (224px) sidebar, and matching content offset. Its content has a maximum width of (1740px), desktop padding (30px 34px), and recurring panel gaps around (23–24px). Authenticated content has a maximum width of (1120px) and padding (40px 32px 64px). The spacing tokens record observed recurring steps, not a claim that every existing gap is normalized.

Responsive behavior follows the implemented breakpoints:

- At (1600px) and above, catalog top padding and chart geometry expand.
- At (1180px) and below, the sidebar narrows to (195px), content padding contracts, and lower dashboard content becomes one column.
- Catalog label task layouts collapse at (1000px); authenticated entry forms become three columns at (1000px) and above.
- Catalog label overview collapses at (900px); validation action layouts collapse at (700px).
- At (800px) and below, catalog navigation becomes a hidden sliding sidebar with a viewport overlay; content loses its sidebar offset.
- At (600px) and below, authenticated identity, forms, facts, and evidence grids stack; filter and relevant action controls fill the available width.
- At (560px) and below, catalog charts and form grids stack, content has (16px) side padding, panel insets contract, and metric cards retain two columns.

Wide tables scroll within their own focusable region. Catalog tables retain a minimum width of (760px), reducing to (720px) at the navigation breakpoint; the authenticated impact table retains (580px). Wrap surrounding controls and long facts rather than creating whole-page overflow.

## Elevation & Depth

Borders and tonal surfaces establish structure. Shared panels and the sign-in entry use the root surface shadow, a very soft offset shadow. Featured metrics retain stronger local depth and their existing gradient. Selected segmented controls and Radix tabs use small local shadows; do not flatten these inherited states into a blanket no-shadow rule.

Motion is limited to state transitions: shared buttons and tabs use (150ms), navigation uses (180ms), and the mobile sidebar uses (200ms). The reduced-motion rule disables animation and transitions and restores automatic scrolling. No new entrance animation belongs to this refinement. Exact shadows and transition extensions are recorded in the sidecar.

## Shapes

Shared surfaces use the surface radius; shared buttons and workspace text fields use the control radius. Compact tags use the compact radius, while existing notices and feedback may use the notice radius. Avatars, data dots, and donut geometry remain circular.

Older task-specific containers retain corners around (9–12px), alongside their local borders and fills. These are bounded existing variants, not a new shared radius scale.

## Components

### Buttons

Primary is herb green with white text; outline is a white bordered alternative; ghost carries low-emphasis actions and navigation. Shared buttons have minimum height (44px), with icon-size buttons also given minimum width (44px). This minimum overrides shorter component height presets. Default padding is in frontmatter; icon-containing and size variants have their own padding.

The shared API also exposes secondary, destructive, and link variants. Preserve explicit action labels, disabled semantics, and busy state text. Disabled shared buttons have full-opacity muted text and a quiet fill. Small segmented chart controls retain a separate minimum height (36px).

### Cards / Containers

Use white bordered panels for task content, the shared surface radius, and restrained depth. Typical section interiors use the inset spacing; mobile interiors contract as described in Layout. The sign-in entry retains a larger desktop inset and a narrower reading width. Existing chart cards keep their own geometry and local emphasis.

### Inputs / Fields

Workspace text inputs and selects have minimum height (44px), a visible control border, shared control radius, foreground text, and a green caret. Textareas retain vertical resizing and content-driven height. Checkboxes and radio buttons are excluded from the full-size field rule.

Placeholders use text-muted at full opacity. Invalid fields use a visible error boundary and associated error text; retain the existing invalid attributes. Disabled fields retain their disabled semantics and a quiet background. Authenticated disabled fields and feedback blocks still have scoped legacy color variants.

### Navigation

Catalog navigation combines icon and text, a tinted active item, stronger active weight, and the retained narrow active marker. The current route remains apparent. Authenticated section buttons wrap and expose `aria-current="page"`. Radix tab lists wrap, have minimum height (50px), and their triggers have minimum height (44px); selected tabs retain their white surface and local shadow, or the line variant's indicator.

### Chips and status

Compact group tags identify categories; status and validation messages pair color with words. Existing version/state chips and feedback panels retain scoped colors and compact corners. Reuse a semantic role where it matches meaning rather than converting every tag to a primary action.

### Focus and browser details

Shared interactive elements use a (3px) focus outline with (3px) offset. Authenticated sign-in buttons, label fields, and impact controls retain more specific green focus variants, including a (4px) button offset. Shared CSS supersedes many older field border/radius literals; it does not supersede every scoped focus or feedback declaration.

Keep skip links visible when focused, table regions keyboard focusable, selection tinted green, underlines offset, and tabular numerals in data. Product names may be visually clamped to two lines while preserving their full accessible text and action labels.

## Do's and Don'ts

### Do:

- **Do** use the shared tokens and existing UI components for new workspace controls and surfaces.
- **Do** preserve readable support text, visible keyboard focus, and contained table scrolling.
- **Do** keep chart-category assignments, explicit status text, and current-page indications.
- **Do** preserve the existing distinction between preview/source notices and authenticated workflow states.

### Don't:

- **Don't** introduce a replacement visual identity during an incumbent refinement.
- **Don't** treat every older CSS literal as an alias of a root token.
- **Don't** turn chart gradients or local metric emphasis into generic action or status styling.
