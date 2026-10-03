# ZDT-D — Agent Guidelines

ZDT-D is an Android Magisk/KernelSU module for DPI bypass. Kotlin UI app + Rust daemon + Lua packet manipulation.

## Fork identity — this is the FORK, not the original

- This repository is the personal fork **`aneek0/ZDT-D`** (git remote `origin`),
  NOT the original project `GAME-OVER-op/ZDT-D` (git remote `upstream`).
  All work happens in this fork; commits, releases, and support belong here.
- Never attribute changes to the original repo, never open issues/PRs against
  it, never switch `origin` to it.
- Every user-facing URL — app update checks, APK/module release assets,
  geodb and tg_ws_proxy asset downloads, SupportScreen/MainActivity links,
  CI workflows, docs — MUST point at `aneek0/ZDT-D`. After every upstream
  merge, verify merged-in URLs still target `aneek0/ZDT-D`, not
  `GAME-OVER-op/ZDT-D` (app already does: `MainViewModel.kt`,
  `GeoLocationRepository.kt`, `TgWsProxyComponentRepository.kt`,
  `SupportScreen.kt`, `MainActivity.kt`, `.github/workflows/*`).
- Do not revert fork-specific changes during upstream merges:
  `AGENTS.md`, the fork banner and "Differences from upstream" sections in
  `README*.md`, `.github/workflows/fast-build.yml`, fork-specific strings and
  host lists.
- References to the original repo are allowed ONLY as attribution: the fork
  disclaimer in the READMEs and the `upstream` git remote itself.


## Project Structure

```
application/          — Android app (Kotlin, Jetpack Compose)
  app/src/main/java/com/android/zdtd/service/
    ui/               — Compose screens (safe to edit)
    diagnostics/      — DPI detection tools (safe to edit)
      blockcheck/     — blockcheck runner/store + UI state (safe to edit)
    api/              — API client models
    widgets/          — Home screen widgets
    ZdtdActions.kt    — Action dispatcher
    MainViewModel.kt  — Main state holder
    RootConfigManager.kt — Prefs + module config
rust/
  zdtd/               — Rust daemon (DO NOT touch without explicit request)
  dpi-detector/       — Rust DPI detector (safe to edit)
  nfqws-tester/       — NFQWS tester binary (safe to edit)
scripts/
  lists/              — host-list / strategy maintenance scripts (safe to edit)
module_template/      — Magisk module template (shipped to device)
  strategic/
    list/             — Host lists (one host per line, # comments)
    lua/              — nfqws2 Lua scripts
    strategicvar/     — Strategy configs for byedpi/nfqws2
  bin/                — Compiled binaries (prebuilt)
  customize.sh        — Module install script (DO NOT touch)
  service.sh          — Module boot script (DO NOT touch)
  uninstall.sh        — Module uninstall script (DO NOT touch)
prebuilt/             — Prebuilt binaries (DO NOT touch)
keystores/            — Signing keys (DO NOT touch)
zygisk/               — Zygisk native library (DO NOT touch)
```

## Protected Files (never modify without explicit user request)

- `module_template/customize.sh`, `service.sh`, `uninstall.sh`
- `module_template/module.prop`
- `prebuilt/**`, `keystores/**`
- `zygisk/**`, `rust/zdtd/**`
- `build.sh`
- `.github/workflows/build.yml`

## Safe to Edit

- `application/app/src/main/java/com/android/zdtd/service/ui/` — UI screens
- `application/app/src/main/java/com/android/zdtd/service/diagnostics/` — diagnostics
- `scripts/lists/**` — host-list / strategy maintenance scripts
- `rust/dpi-detector/**`, `rust/nfqws-tester/**` — standalone Rust tools
- `module_template/strategic/list/**` — host lists
- `module_template/strategic/lua/**` — Lua scripts
- `module_template/strategic/strategicvar/**` — strategy configs
- `README.md`, docs

## Conventions

- UI: read `DESIGN.md` (repo root) before creating/modifying any Compose UI —
  colors, typography, spacing, shapes and shared components are defined there
- Kotlin: follow existing code style (no mass reformatting)
- Rust: `cargo check` must pass
- Host lists: one entry per line, lowercase, no duplicates, `#` comments
- Commits: conventional style (`feat:`, `fix:`, `perf:`, `refactor:`)
- Package name: `com.android.zdtd.service` — DO NOT change

### nfqws2 strategy selection

The daemon owns hostlist/IP-set binding for `nfqws2` (and `byedpi` profiles). At apply time it
strips every `--hostlist*` / `--ipset*` token from each `--new` block and
re-injects the user selection into every section (`rust/zdtd/src/api.rs
apply_selection_to_config`). So do not rely on hardcoded `--hostlist*` /
`--ipset*` inside `strategicvar/*.txt`; the user's choice wins. `--hostlist-auto=`
blocks are data-driven and kept as-is. When editing those strategy files by hand,
use `scripts/lists/strategy_dedup.py` to strip/re-dedup consistently.

## Build

This machine has a full local build env (JDK 17 at `~/.local/opt/jdk17`, Gradle 9.6.0 at `~/.local/opt/gradle-9.6.0`, Android SDK 37 at `~/Android/Sdk`, debug keystore at `application/.tools/signing/`). Local APK build: create `out/module/zdt_module.zip` + `out/module/module.prop` from `module_template/` + `prebuilt/` (with `verify_sum` sha256 files), copy `prebuilt/bin/arm64-v8a/{dpi-detector,nfqws-tester}` into `application/app/build/generated/zdt-assets/main/...`, then `cd application && JAVA_HOME=~/.local/opt/jdk17 ~/.local/opt/gradle-9.6.0/bin/gradle assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Prefer local builds for iteration; GitHub Actions remains the release pipeline (push artifacts: APK `zdt-apk`, module zip `zdt-module-final`).

- Push to `main` triggers `.github/workflows/fast-build.yml` (quick: zdtd arm64 + APK only, change-gated per crate, arm64-v8a binaries only).
- Full build is `.github/workflows/build.yml` via `workflow_dispatch` (arm64-v8a only, third-party binaries, module zip, prebuilt sync, service publish). It compiles only when tracked build paths changed (`application/`, `rust/`, `module_template/`, `prebuilt/`, `zygisk/`, ...), unless the commit message contains `auto run compile` or it is a manual run.
- **Auto-versioning**: on every full build, the `bump_version` job increments the patch version and `versionCode` in `module.prop` and commits it back to `main` with `[skip ci]`; `pack_module`, `build_apk` and `publish_service` apply the bumped `module.prop` from `origin/main` before building/publishing.
- **Auto-release**: `publish_service` always refreshes the rolling `service-build` prerelease and creates/updates a stable release `V{X.Y.Z}` (APK asset + update meta) for the bumped version.
- After a build, `sync_prebuilt` auto-commits rebuilt binaries to `prebuilt/` (`sync prebuilt binaries [skip ci]` commits).
- Legacy Termux path `build.sh` still exists but is not the primary flow.
- Rust check locally: `cargo check` in `rust/zdtd/` must pass before pushing.
