---
name: ZDT-D Design System
platform: Android (Jetpack Compose, Material 3 base)
source_of_truth: application/app/src/main/java/com/android/zdtd/service/ui/theme/Theme.kt
themes: [system, light, dark, amoled]
---

# ZDT-D — DESIGN.md

Machine-readable design system for AI coding agents. **Read this file before
creating or modifying any UI.** All rules below are extracted from the actual
codebase; when in doubt, `Theme.kt` wins.

## 1. Core principles

1. **Never hardcode colors, dp or sp in composables.** Use
   `MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*` and the spacing /
   radius conventions below. The only hardcoded values allowed are the status
   palette in section 3.3.
2. **Reuse shared components** (section 5) instead of building new cards,
   pills or progress indicators. A new shared component goes to `CommonCards.kt`
   / `EditorCards.kt`, not into a screen file.
3. **Every visual decision must work in all 4 theme modes**
   (system / light / dark / AMOLED). If a color only looks right in dark, it is
   wrong — pick a scheme token instead.
4. **Respect adaptive layout helpers** (`Adaptive.kt`). Never assume a fixed
   screen width; use the `rememberIs*` helpers.

## 2. Theme modes

User-selectable, persisted in `RootConfigManager` (`ZdtdThemeMode`):

| Mode | Behavior |
|---|---|
| `system` | Follows OS light/dark |
| `light` | `LightScheme` |
| `dark` | `DarkScheme` |
| `amoled` | `AmoledScheme` — true-black `#000000` surfaces, amber accent |

Status bar stays transparent / edge-to-edge; only icon contrast is synced
(`isAppearanceLightStatusBars`). Never paint a scrim behind the system bar.

## 3. Colors

### 3.1 Brand seeds (identity, same across schemes)

| Token | Value | Role |
|---|---|---|
| BrandRed | `#FF2A3D` (dark) / `#C11623` deep (light) | primary accent |
| BrandBlue | `#2AA6FF` (dark) / `#0A6FC2` deep (light) | secondary accent |
| BrandYellow | `#FFD12A` (dark) / `#8A6D00` deep (light) | tertiary accent |
| AmberSeed | `#FFB300` | AMOLED-mode primary |

### 3.2 Scheme surfaces (dark mode reference)

| Token | Dark | Light |
|---|---|---|
| background | `#0B0D10` | `#FCFCFF` |
| surface | `#121419` | `#FCFCFF` |
| surfaceVariant | `#42474E` | `#DDE2EB` |
| surfaceContainer …Highest | `#06080B` … `#2D2F35` | `#FFFFFF` … `#E2E3E7` |
| outline / outlineVariant | `#8C9199` / `#42474E` | `#72777F` / `#C2C7CF` |

Always reference via `MaterialTheme.colorScheme`, never by hex.

### 3.3 Status / semantic palette

The app uses a Tailwind-like status palette for states, badges and accents.
Use the nearest existing value instead of inventing a new hue:

| Value | Usage |
|---|---|
| `#22C55E` | success / running / enabled |
| `#38BDF8` | info / links / neutral accent |
| `#A78BFA` | secondary info, special features |
| `#EF4444` | error / destructive / stopped-alert |
| `#FACC15`, `#F59E0B` | warning |
| `#F97316` | warning-strong / attention |
| `#E53935` | power button active (`PowerWaveButton`) |
| `#8E8E8E` | power button inactive |
| `#FF5252` | power wave (stopping) |

### 3.4 Per-program accents

Each program/profile screen may carry its own accent hue for identity
(established in the codebase — reuse, don't proliferate):

| Value | Where |
|---|---|
| `#7DD3FC` (sky) | default profile-screen accent (Hysteria2, Mieru, MyProxy, SingBox, WireProxy, ProgramScreen, dialogs) |
| `#A855F7` / `#C4B5FD` / `#8B5CF6` / `#7C3AED` (violet family) | SingBox, Hysteria2, Tor, T2s panel, ConstructionStudio |
| `#FFBC00` / `#FFD166` (amber) | ConstructionStudio, Hysteria2 highlights |
| `#FF4D7D` (pink-red) | ProgramScreen, StyledCreateProfileDialog |
| `#FF2D55` | DeleteModuleDialog (destructive) |
| `#2ECC71` | SetupScreens success |
| `#94A3B8` / `#64748B` (slate) | muted/secondary text accents |

**Rule:** new screens pick an accent from this list; new hues need a reason.

### 3.5 Card surface treatment (signature look)

Shared cards are **translucent surfaces with a tinted accent border**:

```kotlin
Surface(
  color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
  border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
  tonalElevation = 0.dp,
  shadowElevation = 0.dp,
)
```

Do not introduce elevated/opaque card styles; this flat, glassy treatment is
the product's visual identity.

## 4. Typography

Stock Material 3 type scale (`MaterialTheme.typography`, no custom fonts).
Usage mapping observed in the codebase — follow it:

| Style | Use for |
|---|---|
| `bodySmall` | default dense UI text (dominant style) |
| `titleMedium` | card/section titles |
| `titleSmall` | sub-titles, list headers |
| `labelLarge` | buttons, pills, emphasis labels |
| `labelMedium` / `labelSmall` | metadata, badges, captions |
| `bodyMedium` | descriptions, secondary paragraphs |
| `titleLarge` / `headlineSmall` | screen headers, hero numbers |

Hardcoded `sp` is essentially absent. Two sanctioned exceptions:
`ProgramScreen.kt` big timer digits (30/42sp) and `StatsScreen.kt`
length-adaptive values (9/10sp). Don't add new ones.

## 5. Spacing & shape

- Base grid: **4dp**. Dominant values: `8, 10, 12, 16` dp (card paddings,
  gaps). Allowed scale: `0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 14, 16, 18, 20, 22, 24`.
  Prefer `8/10/12/16`; introduce a new step only if none fits.
- Screen padding is adaptive via `rememberAdaptiveScreenPadding()`:
  `<360dp → 16`, `<420dp → 20`, else `24`.
- Theme shapes (`ZdtdShapes`): 8 / 12 / 16 / 24 / 32 dp.
- Card corner radius: `20dp` compact width, `24dp` normal (see `SectionCard`).
- Pills/chips: fully rounded (`RoundedCornerShape(100.dp)` / `999.dp`).
- Observed radius set: 12, 14, 16, 18, 20, 22, 24, 28 — reuse, don't invent.

## 6. Shared components (reuse these)

| Component | File | Purpose |
|---|---|---|
| `SectionCard` | `CommonCards.kt` | titled translucent section card, accent border |
| `EnabledCard` | `CommonCards.kt` | toggle row card |
| `ProfileStatusCard` | `CommonCards.kt` | profile state w/ icon badge + enabled pill |
| `StableLinearProgressIndicator` | `CommonCards.kt` | progress without flicker |
| `TextEditorCard` / `JsonEditorCard` | `EditorCards.kt` | config editors w/ save flow |
| `PowerWaveButton` | `PowerWaveButton.kt` | big circular power toggle + wave animation |

Adaptive helpers (`Adaptive.kt`): `rememberIsCompactWidth` (<360),
`rememberIsNarrowWidth` (<400), `rememberIsTabletLayout` (sw≥600 or landscape
wide), `rememberIsShortHeight` (<760), `rememberAdaptivePowerButtonSize`,
`rememberAdaptiveScreenPadding`, `rememberUseScrollableTabs` (<420),
`rememberUseLandscapeControlLayout`, `MinWidthScaleContainer`.

## 7. Motion

- Standard: `tween` with `FastOutSlowInEasing` for one-shot transitions.
- Infinite/status animations: `rememberInfiniteTransition` + `LinearEasing`
  (power waves: 950ms cycle, 3 phase-shifted rings).
- No spring-physics showoffs; motion is functional (state feedback), not
  decorative.

## 8. Do / Don't for AI agents

**Do**
- Read `Theme.kt` and this file before writing UI code.
- Use `MaterialTheme.colorScheme.*` tokens for anything theme-dependent.
- Verify the result mentally against all 4 theme modes.
- Put reusable UI into `CommonCards.kt` / `EditorCards.kt`.
- Use status palette (3.3) for state colors.

**Don't**
- Don't hardcode `Color(0x…)` outside sections 3.1/3.3.
- Don't hardcode `sp` font sizes or create new type styles.
- Don't add elevation/shadow to cards (identity is flat + translucent).
- Don't paint the status bar or add scrims behind it.
- Don't introduce new corner radii or spacing steps without need.
- Don't use `MaterialTheme` defaults blindly where a shared component exists.

## 9. Consistency audit (run after UI changes)

```bash
UI=application/app/src/main/java/com/android/zdtd/service/ui
# hardcoded colors outside the allowed palette:
grep -rhoE 'Color\(0xFF......\)' $UI --include='*.kt' | grep -v theme/ | sort -u
# font sizes (should be ~empty):
grep -rn '\.sp' $UI --include='*.kt'
# corner radii in use:
grep -rhoE 'RoundedCornerShape\([0-9]+\.dp\)' $UI --include='*.kt' | sort -u
```
