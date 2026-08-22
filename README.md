# ZDT-D Root Module (Magisk / KernelSU / APatch)

> **⚠️ Disclaimer — Personal Fork**
>
> This is an **unofficial personal fork** of [ZDT-D by GAME-OVER-op](https://github.com/GAME-OVER-op/ZDT-D), created exclusively for personal use.
>
> **This fork is NOT supported, endorsed, or maintained by the original author.** Do not contact the original author regarding issues, questions, or support for this fork — all changes here are independent and may diverge from the upstream project.
>
> If you are looking for the official version, go to [github.com/GAME-OVER-op/ZDT-D](https://github.com/GAME-OVER-op/ZDT-D).

<div align="center">
  <img src="https://github.com/GAME-OVER-op/ZDT-D/blob/main/images/module_icon.png" alt="ZDT-D Logo" width="300" />
</div>

<p align="center">
  <b>ZDT-D</b> is an Android root module for traffic routing, DPI bypass, proxy chaining, DNS control, and per-app network management.
</p>

**ZDT-D** is a root-based Android network orchestration project for advanced traffic routing, DPI circumvention, DNS handling, local proxy pipelines, and selective VPN/TUN binding.

It is not a classic Android VPN application and it is not limited to one bundled engine. ZDT-D uses a local root daemon, Android application UIDs, `iptables` / `ip6tables`, NFQUEUE, local loopback services, and Android `netd` to route selected applications through different processing paths.

The project includes:

- a local Rust daemon (`zdtd`)
- an Android application for configuration and status control
- bundled networking tools for different routing and compatibility scenarios
- internal builders for UID-based redirection and Android `netd`-based TUN binding

> The Android app is available in Russian and English.

## What makes ZDT-D different

Most Android VPN or proxy applications use a single `VpnService` instance, create one virtual TUN interface, and route all or selected traffic through one global pipeline.

ZDT-D uses a different model:

- it works with root privileges
- it does not depend on Android `VpnService` as its main traffic engine
- it can route traffic by Android application UID
- it can apply `iptables` / `ip6tables` rules
- it can send traffic to NFQUEUE-based DPI engines
- it can redirect selected applications to local proxy services on `127.0.0.1`
- it can bind selected applications to existing or generated TUN interfaces through Android `netd`
- it can run several engines and profiles at the same time

Because of this, ZDT-D is closer to a root-based traffic management platform than to a traditional VPN client.

## Split tunneling and app-based control

ZDT-D does not blindly route the whole device through one tunnel.

The user selects Android applications, the daemon resolves package names into Linux UIDs, and those UIDs are used by the routing layer. Depending on the selected program, traffic can be sent through `iptables`, NFQUEUE, a local transparent proxy pipeline, or an Android `netd` VPN binding.

This makes it possible to build flexible scenarios such as:

- one application through OpenVPN + Android `netd`
- another application through tun2socks + Android `netd`
- another application through a local sing-box or wireproxy pipeline
- selected applications through NFQUEUE-based DPI circumvention
- selected applications through an Opera proxy pipeline
- selected applications through a custom TUN interface exposed by `myvpn`

ZDT-D is designed for selective routing. It does not force every application into the same path.

## Flexible program architecture

ZDT-D is built around profile-based programs rather than a single fixed binary.

Different programs can have their own profiles, settings, app lists, logs, and runtime behavior. The daemon collects enabled profiles, validates conflicts, starts the required engines, and applies the correct routing model for each one.

The project supports several categories of components:

- DPI and NFQUEUE engines
- transparent proxy engines
- local proxy pipelines
- DNS components
- VPN/TUN + Android `netd` binding
- user-defined process launchers
- port protection and diagnostic helpers

This architecture makes the project flexible: new engines can be added without redesigning the entire routing system.

## Custom programs and extensibility

A major goal of ZDT-D is extensibility.

The project is not limited to pre-defined tools. Users can add their own network programs and combine them with ZDT-D routing features.

For example:

- `myprogram` can launch a user-provided binary or script
- that binary can create a local proxy, a service, or a TUN interface
- `myvpn` can bind selected applications to an already existing TUN interface
- the daemon can still handle UID parsing, conflict checks, and Android `netd` binding

This allows ZDT-D to be used as a base for custom Android networking setups, not only as a ready-made module with fixed behavior.

## Routing models

ZDT-D supports multiple independent traffic handling paths.

### NFQUEUE path

Selected application traffic can be matched by UID and sent into NFQUEUE. A userspace DPI engine can then inspect or modify packets.

### Transparent local redirection

Selected application traffic can be redirected to a local listener on `127.0.0.1:<port>`. Local helper programs then forward or process the stream.

### Android netd / TUN binding

When a supported program creates or exposes a TUN interface, ZDT-D can bind selected application UIDs to that interface through Android `netd`.

This model is used by OpenVPN, tun2socks, and the universal `myvpn` binding.

### DNS handling

ZDT-D can manage local DNS components such as dnscrypt-proxy and route DNS-related traffic in controlled scenarios.

## Conflict control

Because several programs can target the same applications, ZDT-D checks app-list conflicts.

An application should not be assigned to multiple incompatible network pipelines at the same time. This reduces broken routing, duplicated redirection, and hard-to-debug conflicts between profiles.

Some helper features, such as QUIC blocking, can be used alongside other routing modes when they do not conflict with the main traffic path.

## Documentation

Detailed information about supported programs and internal components is available in:

- [`docs/PROGRAMS.md`](docs/PROGRAMS.md)

Practical usage notes, troubleshooting, and advanced examples may be kept in:

- `INSTRUCTIONS.md`

## Privacy

ZDT-D does not collect, transmit, sell, share, or use personal data.

All configuration, routing, rule management, and runtime control required for the module to work are performed locally on the installed device.

The project does not require remote telemetry or analytics for core functionality.

If the application connects to external resources, it does so only for actions explicitly requested by the user, such as checking releases or downloading updates from official upstream sources.

## Safety and compatibility

ZDT-D works with low-level Android networking components. Compatibility may vary depending on:

- ROM behavior
- root implementation
- SELinux behavior
- kernel features
- `iptables` / `ip6tables` support
- Android `netd` behavior
- bundled binary compatibility

Some antivirus products may flag DPI-related tools because they work with low-level network traffic. This does not mean that ZDT-D collects data or performs remote telemetry.

ZDT-D is intended for advanced users, network compatibility research, routing control, and enthusiast use.

## Differences from upstream (GAME-OVER-op/ZDT-D)

This is an independent personal fork. It merges from upstream `main` regularly
(branches like `merge-upstream-390`, `upstream/ai-polish`), so most programs,
the routing model, the UI shell, the world map / web panel / captive-portal /
app-hiding / backup / tablet-TV features, arm32 support and the `nfqws2`
strategy set are inherited from the original project — not added here.

The changes that are **actually introduced in this fork** (199 fork-only commits
on top of the merge base) are a smaller, focused set:

- **In-app blockcheck (strategy autotest).** A `diagnostics/blockcheck/` UI plus
  a native `nfqws-tester auto` sweep that probes a host list with no strategy
  (baseline) and then tests every `nfqws`/`nfqws2` strategy file, showing a
  per-strategy opened percentage and a `Works` / `Partial` / `Failed` /
  `No baseline block` verdict (with a share/test-results function). This does
  not exist upstream.
- **Blockcheck UI / appearance.** The fork adds the `BlockcheckScreen` Compose
  screen (start/stop, host-file picker, live strategy list with progress bars
  and `Works` / `Partial` / `Failed` chips), matching the app-wide card/button
  style. The home screen, stats, power-wave button, construction studio and apps
  host already exist upstream — only the blockcheck screen is new here.
- **AMOLED theme (`AmberOLED`).** The fork adds a dedicated `AMOLED` theme mode
  (`ui/theme/Theme.kt`, `ZdtdThemeMode.AMOLED`) selectable in Settings
  (`SettingsContent`). It uses a true-black `#000000` background to save power on
  AMOLED panels and an amber accent (`#FFB300`) to avoid blue light in low light.
  The `system` / `light` / `dark` modes already exist upstream — only the AMOLED
  mode is new here.
- **User IP-set / hostlist selection.** A strategy-config card lets the user pick
  domain host lists and IP sets (`--hostlist`, `--hostlist-exclude`, `--ipset`,
  `--ipset-exclude`); the daemon strips those args from each `--new` block and
  re-injects the selection, and blockcheck reuses the configured profile's
  hostlists.
- **Custom nfqws Lua primitives.** Added `fakemultisplit.lua`,
  `fakemultidisorder.lua`, `zapret-multishake.lua` and `custom_funcs.lua` under
  `module_template/strategic/lua/` (upstream ships only `zapret-sni.lua` and
  `zapret-wgobfs.lua` there).
- **Imported magisk-zapret2 data for `nfqws2` "second ban".** Added the
  per-service host lists / `ipset-*` files and fake TLS/QUIC/`syn` packet bins
  under `module_template/strategic/bin/` and `strategic/list/` that are not in
  upstream.
- **Host-list maintenance tooling** in `scripts/lists/`: `dedup_check.py` (local
  + upstream-zapret2 duplicate/baseline checks), `strategy_dedup.py` (mirrors
  the daemon's selection stripping for local edits) and a validation harness.
  None of this exists upstream.
- **Fork-specific build/CI tweaks** (fast-build workflow, prebuilt auto-sync,
  module update checks pointed at the `aneek0/ZDT-D` fork) and small fixes such
  as restoring fork-specific strings/host lists lost during upstream merges and
  hardening `zdtd` stop/cleanup logic.

If you are looking for the official project, go to
[github.com/GAME-OVER-op/ZDT-D](https://github.com/GAME-OVER-op/ZDT-D). This fork
is **not** supported by, endorsed by, or affiliated with the original author.

## License

GPL-3.0 License — see [LICENSE](https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE).

---

> **Note:** This is a personal, unofficial fork. It is not affiliated with or supported by the original author. For the official project, visit [github.com/GAME-OVER-op/ZDT-D](https://github.com/GAME-OVER-op/ZDT-D).
