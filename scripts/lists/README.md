# Host-list maintenance scripts

This folder holds tooling for the ZDT-D host lists under
`module_template/strategic/list/`. These scripts live **outside** the shipped
module, so editing them never touches the protected `module_template/`
scripts (`customize.sh`, `service.sh`, `uninstall.sh`).

## dedup_check.py

Checks the host lists for duplicate entries and tracks drift against the
upstream [zapret2 Gitea lists](https://git.zapret.moe/zapretdiscordyoutube/magisk-zapret2/src/branch/main/zapret2/lists).

What it checks:
1. **Local internal duplicates** — the same entry repeated inside one file.
2. **Local cross redundancy** — a list that is a full subset of another
   (e.g. `youtube.txt` ⊂ `list-youtube.txt`). These are reported but never
   auto-deleted, because they are referenced by `strategicvar` strategies.
3. **Remote lists** — parses the zapret2 Gitea lists, applies the same
   internal-duplicate check to them, and reports which remote files are fully
   covered by the local set.
4. **Newly appeared duplicates** — saved baseline (`remote_baseline.json`)
   lets a later run report entries / internal dups that are NEW vs the snapshot.

Usage:
```bash
python3 dedup_check.py                 # report local dups only
python3 dedup_check.py --fix           # rewrite local lists removing internal dups
python3 dedup_check.py --remote        # also fetch + compare upstream lists
python3 dedup_check.py --remote --update-baseline   # refresh the snapshot
```

IP/CIDR feeds (`ipset-*`, `russia-blacklist.txt`, `cloudflare-ipset*`) are
skipped when comparing against host lists — comparing them is meaningless and
slow.

## test_dedup_check.py

No-network validation harness for `dedup_check.py`. Exercises the pure logic
(normalization, parsing, fix idempotency, cross-subset detection, baseline
round-trip, and the remote-report drift logic with mocked network calls).

```bash
python3 test_dedup_check.py
```

## Workflow recommendation

Run `dedup_check.py --remote --update-baseline` on a schedule (e.g. a CI cron
job) so "did duplicates appear?" is answered automatically against the saved
baseline.
