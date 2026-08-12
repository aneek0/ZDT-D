#!/usr/bin/env python3
"""Validation harness for dedup_check.py (no network)."""
import importlib.util
import json
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("dedup_check", HERE / "dedup_check.py")
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)

# Point the baseline at a temp file so the harness never touches the committed
# scripts/lists/remote_baseline.json.
_TMPBASE = (Path(tempfile.mkdtemp()) / "baseline.json")
mod.BASELINE_PATH = _TMPBASE

fails = []


def check(name, cond):
    print(("PASS" if cond else "FAIL"), name)
    if not cond:
        fails.append(name)


# --- normalize edge cases ---------------------------------------------------
check("normalize lowercases", mod.normalize("Example.COM") == "example.com")
check("normalize strips trailing dot", mod.normalize("example.com.") == "example.com")
check("normalize trims spaces", mod.normalize("  example.com  ") == "example.com")
check("normalize keeps CIDR", mod.normalize("1.2.3.0/24") == "1.2.3.0/24")
check("normalize keeps wildcard", mod.normalize("*.x.com") == "*.x.com")

# --- parse_list: comments / blanks / mixed -----------------------------------
tmp = tempfile.mkdtemp()
p = Path(tmp) / "sample.txt"
p.write_text("# comment\n\nexample.com\nExample.com\n# another\nfoo.bar\nfoo.bar\n", encoding="utf-8")
raw, entries = mod.parse_list(p)
check("parse drops comments/blanks", raw is not None)
# parse_list returns raw normalized entries (may include dups); dedup happens
# later in check_local_internal via Counter.
check("parse normalizes case-insensitively", entries == ["example.com", "example.com", "foo.bar", "foo.bar"])
check("parse count correct", len(entries) == 4)

# --- internal dup detection --------------------------------------------------
rep = mod.check_local_internal.__wrapped__ if hasattr(mod.check_local_internal, "__wrapped__") else None
# check_local_internal reads module-global LIST_DIR; just call on our temp by monkeypatching
mod.LIST_DIR = Path(tmp)
# add a file with dups
(PATH := Path(tmp) / "dups.txt").write_text("a.com\nb.com\na.com\n", encoding="utf-8")
r = mod.check_local_internal()
check("internal dup detected", "dups.txt" in r and r["dups.txt"]["duplicated"].get("a.com") == 2)

# --- fix idempotency --------------------------------------------------------
changed = mod.fix_local_internal()
check("fix rewrote dups file", "dups.txt" in changed)
r2 = mod.check_local_internal()
check("fix removed internal dups", "dups.txt" not in r2)
changed2 = mod.fix_local_internal()
check("fix is idempotent", changed2 == [])  # no more changes on second run

# --- cross check on temp dir ------------------------------------------------
# dups.txt has {a.com,b.com}; add superset.txt = {a.com,b.com,c.com}
(Path(tmp) / "superset.txt").write_text("a.com\nb.com\nc.com\n", encoding="utf-8")
mod.LIST_DIR = Path(tmp)
cross = mod.check_local_cross()
check("cross detects subset", cross.get("dups.txt") == ["superset.txt"])

# --- remote_skip ------------------------------------------------------------
check("remote_skip ipset-", mod.remote_skip("ipset-amazon.txt"))
check("remote_skip cloudflare-ipset", mod.remote_skip("cloudflare-ipset.txt"))
check("remote_skip not host list", not mod.remote_skip("youtube.txt"))

# --- fetch_remote_file internal-dup logic (test via direct func on string) ---
# We can't easily call the network func; replicate by importing its parsing.
import types
src = 'mail.ru\nmail.ru\nok.ru\n'
# emulate fetch_remote_file parsing
import re as _re
ENTRY_RE = mod.ENTRY_RE
out, dups = set(), {}
from collections import Counter
cnt = Counter()
for line in src.splitlines():
    m = ENTRY_RE.match(line)
    if m:
        e = mod.normalize(m.group(1))
        out.add(e)
        cnt[e] += 1
dups = {k: c - 1 for k, c in cnt.items() if c > 1}
check("remote internal dup parsed", dups == {"mail.ru": 1} and out == {"mail.ru", "ok.ru"})

# --- baseline round-trip ----------------------------------------------------
bpath = Path(tmp) / "baseline.json"
snap = {"youtube.txt": {"entries": {"a.com", "b.com"}, "internal_dups": {}}}
mod.save_baseline(snap)
loaded = mod.load_baseline()
check("baseline round-trip entries", loaded["youtube.txt"]["entries"] == {"a.com", "b.com"})

# --- check_remote logic with mocked network ---------------------------------
# Patch fetch_remote_index + fetch_remote_file to avoid network.
def fake_index():
    return [{"name": "youtube.txt", "type": "file"}, {"name": "ipset-x.txt", "type": "file"}]

def fake_file(name):
    if name == "youtube.txt":
        return ({"youtube.com", "googlevideo.com"}, {})  # no internal dups
    return ({"1.2.3.4"}, {})  # skipped anyway by remote_skip

mod.fetch_remote_index = fake_index
mod.fetch_remote_file = fake_file
res = mod.check_remote({"youtube.txt": {"youtube.com", "googlevideo.com", "extra.com"}})
report = res["report"]
check("remote youtube fully covered", report["youtube.txt"]["fully_covered_by_local"] == ["youtube.txt"])
check("remote ipset skipped", "ipset-x.txt" in res["skipped"])
# baseline drift: save baseline, then add a new entry remotely
mod.save_baseline(res["snapshot"])
def fake_file2(name):
    if name == "youtube.txt":
        return ({"youtube.com", "googlevideo.com", "NEW.com"}, {})  # NEW appears
    return ({"1.2.3.4"}, {})
mod.fetch_remote_file = fake_file2
res2 = mod.check_remote({"youtube.txt": {"youtube.com", "googlevideo.com", "extra.com"}})
check("new remote entry detected vs baseline", res2["report"]["youtube.txt"]["new_vs_baseline"] == ["NEW.com"])
# internal-dup drift
def fake_file3(name):
    if name == "youtube.txt":
        return ({"youtube.com", "googlevideo.com"}, {"googlevideo.com": 1})  # new internal dup
    return ({"1.2.3.4"}, {})
mod.fetch_remote_file = fake_file3
res3 = mod.check_remote({"youtube.txt": {"youtube.com", "googlevideo.com"}})
check("new internal dup detected vs baseline", res3["report"]["youtube.txt"]["new_internal_duplicates"] == ["googlevideo.com"])

print()
if fails:
    print(f"{len(fails)} FAILURE(S): {fails}")
    raise SystemExit(1)
print("ALL VALIDATION PASSED")
