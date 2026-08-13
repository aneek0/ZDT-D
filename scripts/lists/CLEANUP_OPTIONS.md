# ZDT-D Strategy Cleanup — Findings & Options

> Status: RESEARCH COMPLETE. No protected files modified. This document is a
> decision aid; every remediation option below touches `strategicvar/*.txt`
> (protected) and needs explicit user approval before any change.

## 1. What is already clean (verified)

- **The "second ban" (`nfqws2/`) is already zapret2 architecture.**
  - 63/63 strategy files use `--lua-init=.../zapret-lib.lua --lua-init=.../zapret-antidpi.lua`
    and `--lua-desync=...`. The Lua libs ship in `prebuilt/bin/<arch>/` and in `nfqws2`
    references.
  - Hostlist binding uses a small, clean set: `custom.txt`, `default.txt`, `google.txt`,
    `russia-blacklist.txt`, `sni_list.txt` — via `--new` preset sections.
  - This matches the upstream zapret2 design doc exactly (C-core `nfqws2` + Lua desync
    primitives; Lua never reads hostlist files — confirmed by upstream `структура проекта.md`
    and reference-repo inspection).
- **Conclusion: no Lua port is needed or meaningful.** A "Lua hostlist layer" (suggested
  during research) is contradicted by both the reference repo and upstream docs — Lua
  supplies desync primitives only.

## 2. Where the real clutter is (verified)

- **Legacy `nfqws/` (zapret1) block: 47 strategy files.**
  - 19 unique hostlist files are hardcoded inside `--new` blocks, e.g.
    `youtube.txt` + `list-youtube.txt`, `russia-youtube.txt` + `russia-youtubeGV.txt`
    + `russia-youtubeQ.txt`, plus `discord/telegram/general/instagram/netrogat/reestr/ips/
    myhostlist/sni_list`.
  - `nfqws/zdt_d_v1.1.4v0.txt` duplicates its entire block set (L1–115 ≈ L116–168).
  - `youtube.txt` appears hardcoded in 9 strategy files.
- **Why it is "safe-ish" clutter:** the Rust daemon (`rust/zdtd/src/api.rs:727
  apply_hostlists_to_config`) strips every `--hostlist*` token from each `--new` block and
  re-injects the user's flat, app-selected list. So the hardcoded hostlists in `nfqws/`
  are effectively dead config for runtime filtering; the user's choice still wins.

## 3. Concrete cleanup options (awaiting your definition of "messiness")

| # | Option | Scope | Risk | Notes |
|---|--------|-------|------|-------|
| A | **Dedup CI gate** (already built) | `scripts/lists/dedup_check.py` | none | Catches new dups in host lists going forward. Done & committed. |
| B | **De-duplicate legacy `nfqws/` contents** | `strategicvar/nfqws/*.txt` (protected) | low | Remove duplicated block sets, collapse redundant hostlist refs. Runtime-agnostic (daemon re-injects). |
| C | **Drop dead zapret1 hostlists** | `list/*.txt` + `strategicvar/nfqws/*.txt` (protected) | low–med | Remove `russia-youtubeGV/Q`, `list-youtube` vs `youtube`, `netrogat`, `reestr`, `myhostlist` if unused by `nfqws2`. Needs a usage grep first. |
| D | **Retire legacy `nfqws/` entirely** | `strategicvar/nfqws/` (protected) | med | If the app still offers zapret1 variants, this breaks them. Requires confirming `nfqws2` covers all use cases. |
| E | **Full Lua/structure refactor** | large, protected | high | NOT recommended — `nfqws2` already is zapret2. Would duplicate work for no architectural gain. |

## 4. Open question for you (blocks any remediation)

- What did you mean by "messiness" / "привести к красоте"?
  - (a) the legacy `nfqws/` hostlist zoo → Options B/C/D
  - (b) something else (redundant `nfqws2` strategy families: flowseal/ggover/game_over_op/z2_strategy)?
  - (c) the dedup tool scope only?

Until that is confirmed, I will not modify any `strategicvar/*.txt` or `list/*.txt`.
