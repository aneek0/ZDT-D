#!/usr/bin/env python3
"""ZDT-D host-list maintenance tool.

Checks host lists for duplicate entries:
  * INTERNAL duplicates inside each local list file.
  * CROSS duplicates: entries already present in another (more general) local
    list, so a list is redundant.
  * REMOTE duplicates: entries in a remote list (zapret2 Gitea) that duplicate
    the local lists, or that newly appeared compared to a saved baseline.

It can optionally rewrite the local lists with duplicates removed (--fix) and
persist a baseline snapshot of the remote (most) lists for drift detection.

Usage:
  python3 dedup_check.py                 # report only
  python3 dedup_check.py --fix           # rewrite local lists without dups
  python3 dedup_check.py --remote        # also fetch+compare remote lists
  python3 dedup_check.py --remote --update-baseline   # refresh baseline

The script lives outside the shipped module, so editing it never touches the
protected module_template/ scripts.

Notes on workflow:
  * Run daily/weekly with --remote --update-baseline to refresh the baseline
    and detect NEW remote entries (coverage drift).
  * --fix only removes INTERNAL duplicates (a file repeating itself); it never
    deletes cross-duplicate files (e.g. youtube.txt is a subset of
    list-youtube.txt) because those are still referenced by strategies.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request

from collections import Counter, defaultdict
from pathlib import Path

# --- config -----------------------------------------------------------------
HERE = Path(__file__).resolve().parent
LIST_DIR = HERE.parents[1] / "module_template" / "strategic" / "list"
BASELINE_PATH = HERE / "remote_baseline.json"

# zapret2 Gitea repo that hosts the reference lists
GITEA_API = "https://git.zapret.moe/api/v1/repos/zapretdiscordyoutube/magisk-zapret2/contents/zapret2/lists"
GITEA_RAW = "https://git.zapret.moe/zapretdiscordyoutube/magisk-zapret2/raw/branch/main/zapret2/lists"

# Files that are intentionally not pure host lists (IP/CIDR sets, generated,
# or empty placeholders). Internal-dup checking still applies, but they are
# excluded from being treated as the "general" superset for cross checks.
# These are large generated feeds where cross-superfluousness is expected.
GENERATED_FEEDS = {
    "blocked.txt",
    "reestr.txt",
    "russia-blacklist.txt",
    "ipset.txt",
    "ipset-v4.txt",
    "ipset-v6.txt",
    "ips.txt",
    "whitelist.txt",
}

# Remote files that are IP/CIDR feeds or huge generated lists. Comparing host
# lists against them is meaningless and slow, so skip them when fetching.
REMOTE_SKIP_RE = re.compile(r"^(ipset-|cloudflare-ipset|russia-blacklist)")

def remote_skip(name: str) -> bool:
    return bool(REMOTE_SKIP_RE.match(name))

HTTP_TIMEOUT = 30

ENTRY_RE = re.compile(r"^\s*([^\s#][^\s]*)\s*(?:#.*)?$")


def normalize(entry: str) -> str:
    """Lowercase and strip, drop a single trailing dot (FQDN vs bare)."""
    e = entry.strip().lower()
    if e.endswith("."):
        e = e[:-1]
    return e


def parse_list(path: Path) -> list[str]:
    """Return kept lines (comments/blanks preserved-ish) plus normalized entries.

    Returns (raw_lines, entries) where entries are unique normalized tokens.
    Comments and blank lines are dropped from the entry set but tracked so we
    can rebuild a cleaned file.
    """
    raw = path.read_text(encoding="utf-8", errors="replace").splitlines()
    entries: list[str] = []
    for line in raw:
        m = ENTRY_RE.match(line)
        if m:
            entries.append(normalize(m.group(1)))
    return raw, entries


# --- local checks -----------------------------------------------------------
def check_local_internal() -> dict:
    """Find duplicate entries within each local file."""
    report: dict[str, dict] = {}
    for path in sorted(LIST_DIR.glob("*.txt")):
        _, entries = parse_list(path)
        counts = Counter(entries)
        dups = {k: c for k, c in counts.items() if c > 1}
        if dups:
            report[path.name] = {
                "total_entries": len(entries),
                "unique_entries": len(counts),
                "duplicated": dups,
            }
    return report


def check_local_cross() -> dict:
    """Find entries that are superfluous because a file is a subset of another.

    A local list A is reported as "contained in" B when every entry of A (that
    is not in a generated feed) already appears in B. We only compare among the
    curated host lists, skipping the big generated feeds as the superset.
    """
    files: dict[str, set[str]] = {}
    for path in sorted(LIST_DIR.glob("*.txt")):
        if path.name in GENERATED_FEEDS:
            continue
        _, entries = parse_list(path)
        files[path.name] = set(entries)

    report: dict[str, list[str]] = {}
    names = sorted(files)
    for a in names:
        supersets = []
        for b in names:
            if a == b:
                continue
            if files[a] and files[a] <= files[b]:
                supersets.append(b)
        if supersets:
            report[a] = sorted(supersets)
    return report


def fix_local_internal() -> list[str]:
    """Rewrite local lists removing internal duplicates (first occurrence kept,
    comments/blank lines dropped). Returns names that were changed."""
    changed = []
    for path in sorted(LIST_DIR.glob("*.txt")):
        raw, entries = parse_list(path)
        seen = set()
        cleaned = []
        for e in entries:
            if e in seen:
                continue
            seen.add(e)
            cleaned.append(e)
        if len(cleaned) != len(entries):
            path.write_text("\n".join(cleaned) + ("\n" if cleaned else ""), encoding="utf-8")
            changed.append(path.name)
    return changed


# --- remote checks ----------------------------------------------------------
def fetch_remote_index() -> list[dict]:
    req = urllib.request.Request(GITEA_API, headers={"User-Agent": "ZDT-D-dedup"})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:  # noqa: S310
        data = json.load(r)
    return [x for x in data if x["type"] == "file"]


def fetch_remote_file(name: str) -> set[str]:
    url = f"{GITEA_RAW}/{name}"
    req = urllib.request.Request(url, headers={"User-Agent": "ZDT-D-dedup"})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:  # noqa: S310
        text = r.read().decode("utf-8", "replace")
    out = set()
    for line in text.splitlines():
        m = ENTRY_RE.match(line)
        if m:
            out.add(normalize(m.group(1)))
    return out


def load_baseline() -> dict[str, set[str]]:
    if not BASELINE_PATH.exists():
        return {}
    data = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    return {k: set(v) for k, v in data.items()}


def save_baseline(snap: dict[str, set[str]]) -> None:
    data = {k: sorted(v) for k, v in snap.items()}
    BASELINE_PATH.write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")


def check_remote(local_entries_by_file: dict[str, set[str]]) -> dict:
    """Compare local lists against remote lists.

    For each remote file we report:
      * which local files already contain ALL its entries (redundant),
      * how many entries overlap with the local union (coverage),
      * which entries are NEW vs the saved baseline (newly appeared).
    """
    index = fetch_remote_index()
    local_union = set().union(*local_entries_by_file.values()) if local_entries_by_file else set()
    baseline = load_baseline()

    report: dict[str, dict] = {}
    snapshot: dict[str, set[str]] = {}
    skipped: list[str] = []
    for item in index:
        name = item["name"]
        if remote_skip(name):
            skipped.append(name)
            continue
        remote = fetch_remote_file(name)
        snapshot[name] = remote
        covering = [f for f, s in local_entries_by_file.items() if remote and remote <= s]
        overlap = len(remote & local_union) if local_union else 0
        new_vs_baseline = sorted(remote - baseline.get(name, set())) if baseline else []
        report[name] = {
            "remote_entries": len(remote),
            "overlap_with_local_union": overlap,
            "fully_covered_by_local": covering,
            "new_vs_baseline": new_vs_baseline,
        }
    return {"report": report, "snapshot": snapshot, "skipped": skipped}


# --- reporting --------------------------------------------------------------
def print_local(report_int: dict, report_cross: dict) -> None:
    print("=" * 70)
    print("LOCAL INTERNAL DUPLICATES")
    print("=" * 70)
    if not report_int:
        print("  none")
    for name, info in report_int.items():
        print(f"  {name}: {len(info['duplicated'])} duplicated entries "
              f"({info['unique_entries']} unique / {info['total_entries']} total)")
        for k, c in sorted(info["duplicated"].items(), key=lambda x: -x[1])[:10]:
            print(f"      {k} x{c}")
        if len(info["duplicated"]) > 10:
            print(f"      ... and {len(info['duplicated']) - 10} more")

    print()
    print("=" * 70)
    print("LOCAL CROSS (list fully contained in another)")
    print("=" * 70)
    if not report_cross:
        print("  none")
    for a, supersets in report_cross.items():
        print(f"  {a}  <=  {', '.join(supersets)}")


def print_remote(res: dict) -> None:
    report = res["report"]
    print()
    print("=" * 70)
    print("REMOTE vs LOCAL")
    print("=" * 70)
    redundant = {n: i for n, i in report.items() if i["fully_covered_by_local"]}
    newish = {n: i for n, i in report.items() if i["new_vs_baseline"]}
    print(f"  remote files fully covered by a local list: {len(redundant)}")
    for n, i in sorted(redundant.items()):
        print(f"      {n} (covered by {', '.join(i['fully_covered_by_local'])})")
    print()
    print(f"  remote files with entries NEW vs baseline: {len(newish)}")
    for n, i in sorted(newish.items(), key=lambda x: -len(x[1]["new_vs_baseline"]))[:20]:
        print(f"      {n}: +{len(i['new_vs_baseline'])} new "
              f"(overlap {i['overlap_with_local_union']}/{i['remote_entries']})")
        if n in redundant:
            continue
        for e in i["new_vs_baseline"][:5]:
            print(f"          + {e}")
    if len(newish) > 20:
        print(f"      ... and {len(newish) - 20} more files with new entries")
    if res.get("skipped"):
        print()
        print(f"  skipped {len(res['skipped'])} IP/CIDR feed(s): "
              f"{', '.join(res['skipped'])}")


def main() -> int:
    global LIST_DIR  # noqa: PLW0603
    ap = argparse.ArgumentParser(description="ZDT-D host-list duplicate checker")
    ap.add_argument("--fix", action="store_true", help="rewrite local lists removing internal dups")
    ap.add_argument("--remote", action="store_true", help="fetch and compare remote lists")
    ap.add_argument("--update-baseline", action="store_true", help="refresh remote baseline snapshot")
    ap.add_argument("--list-dir", type=Path, default=LIST_DIR, help="override local list dir")
    args = ap.parse_args()

    if args.list_dir:
        LIST_DIR = args.list_dir

    if not LIST_DIR.exists():
        print(f"list dir not found: {LIST_DIR}", file=sys.stderr)
        return 2

    report_int = check_local_internal()
    # build per-file entry sets for cross + remote comparison
    local_entries: dict[str, set[str]] = {}
    for path in sorted(LIST_DIR.glob("*.txt")):
        _, entries = parse_list(path)
        local_entries[path.name] = set(entries)
    report_cross = check_local_cross()
    print_local(report_int, report_cross)

    if args.fix:
        changed = fix_local_internal()
        print()
        print("FIX: rewrote", len(changed), "file(s):", ", ".join(changed) or "-")

    if args.remote:
        try:
            res = check_remote(local_entries)
        except Exception as e:  # noqa: BLE001
            print(f"\n[!] remote fetch failed: {e}", file=sys.stderr)
            return 1
        print_remote(res)
        if args.update_baseline:
            save_baseline({k: v for k, v in res["snapshot"].items() if not remote_skip(k)})
            print(f"\nBASELINE updated: {BASELINE_PATH}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
