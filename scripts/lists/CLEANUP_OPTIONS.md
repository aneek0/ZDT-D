# ZDT-D Strategy Cleanup — Findings & Options

> Status: RESEARCH COMPLETE. No protected files modified. This document is a
> decision aid; every remediation option below touches `strategicvar/*.txt`
> (protected) and needs explicit user approval before any change.
>
> Tooling built since research: the dedup CI gate (`dedup_check.py`, option A)
> is committed and the `strategy_dedup.py` maintenance script now mirrors the
> daemon's selection stripping for local edits. Host-list internal/cross-subset
> dup checks and a remote baseline are in place (`remote_baseline.json`).

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

- **Why it is "safe-ish" clutter:** the Rust daemon (`rust/zdtd/src/api.rs:727
  apply_hostlists_to_config`) strips every `--hostlist*` token from each `--new` block and

## 3. Concrete cleanup options (awaiting your definition of "messiness")

| # | Option | Scope | Risk | Notes |
|---|--------|-------|------|-------|
| A | **Dedup CI gate** (already built) | `scripts/lists/dedup_check.py` | none | Catches new dups in host lists going forward. Done & committed. |
| C | **Drop dead zapret1 hostlists** | `list/*.txt` (protected) | low–med | Remove `russia-youtubeGV/Q`, `list-youtube` vs `youtube`, `netrogat`, `reestr`, `myhostlist` if unused by `nfqws2`. Needs a usage grep first. |
| E | **Full Lua/structure refactor** | large, protected | high | NOT recommended — `nfqws2` already is zapret2. Would duplicate work for no architectural gain. |

## 4. Open question for you (blocks any remediation)

- What did you mean by "messiness" / "привести к красоте"?

Until that is confirmed, I will not modify any `strategicvar/*.txt` or `list/*.txt`.
