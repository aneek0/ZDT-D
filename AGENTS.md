# ZDT-D — Agent Guidelines

ZDT-D is an Android Magisk/KernelSU module for DPI bypass. Kotlin UI app + Rust daemon + Lua packet manipulation.

## Project Structure

```
application/          — Android app (Kotlin, Jetpack Compose)
  app/src/main/java/com/android/zdtd/service/
    ui/               — Compose screens (safe to edit)
    diagnostics/      — DPI detection tools (safe to edit)
    api/              — API client models
    widgets/          — Home screen widgets
    ZdtdActions.kt    — Action dispatcher
    MainViewModel.kt  — Main state holder
    RootConfigManager.kt — Prefs + module config
rust/
  zdtd/               — Rust daemon (DO NOT touch without explicit request)
  dpi-detector/       — Rust DPI detector (safe to edit)
  nfqws-tester/       — NFQWS tester binary (safe to edit)
module_template/      — Magisk module template (shipped to device)
  strategic/
    list/             — Host lists (one host per line, # comments)
    lua/              — nfqws Lua scripts
    strategicvar/     — Strategy configs for byedpi/dpitunnel/nfqws
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
- `.github/workflows/build.yml`, `.github/workflows/publish_service.yml`

## Safe to Edit

- `application/app/src/main/java/com/android/zdtd/service/ui/` — UI screens
- `application/app/src/main/java/com/android/zdtd/service/diagnostics/` — diagnostics
- `rust/dpi-detector/**`, `rust/nfqws-tester/**` — standalone Rust tools
- `module_template/strategic/list/**` — host lists
- `module_template/strategic/lua/**` — Lua scripts
- `module_template/strategic/strategicvar/**` — strategy configs
- `README.md`, docs

## Conventions

- Kotlin: follow existing code style (no mass reformatting)
- Rust: `cargo check` must pass
- Host lists: one entry per line, lowercase, no duplicates, `#` comments
- Commits: conventional style (`feat:`, `fix:`, `perf:`, `refactor:`)
- Package name: `com.android.zdtd.service` — DO NOT change

## Build

- Android: `./gradlew assembleRelease`
- Rust daemon: in `rust/zdtd/`
- Module zip: `build.sh` (Termux only)
- CI: `.github/workflows/build.yml`
