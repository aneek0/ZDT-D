#!/usr/bin/env python3
"""
strategy_dedup.py — strip user-selectable "selection" args from nfqws/nfqws2
strategy files, preserving structure exactly.

Mirrors the daemon (rust/zdtd/src/api.rs apply_selection_to_config):
  - tokenize: '\\'+newline -> nothing, newline -> space, split_whitespace
  - the file is a GLOBAL zone (tokens before the first --new) followed by one
    --new section per block. The daemon strips --hostlist*/--ipset* from EVERY
    section and re-injects the user selection into EVERY section.

This tool:
  1. Strips the daemon-owned selection args from every section:
       --hostlist=  --hostlist-exclude=  --ipset=  --ipset-exclude=
     (--hostlist-auto= is KEPT — data-driven, not user-selected.)
  2. Preserves the GLOBAL zone (no --new prefix) and every --new block, in
     order. This makes the stripped file behaviorally IDENTICAL to what the
     daemon produces for any user selection (the daemon strips + re-injects
     anyway).
  3. Optional --dedup: collapse only COMPLETELY-IDENTICAL --new blocks (by
     their full post-strip content). The global zone is never removed. This is
     pure redundancy elimination — safe.

Modes:
  * default  : DRY-RUN. Reports block/duplicate/selection-arg counts.
  * --apply  : write "<file>.new" (or --inplace) with selection stripped.
"""
import os
import sys
import argparse

# Daemon-owned selection args (stripped here, re-injected by the daemon).
STRIP_PREFIXES = (
    "--hostlist=",
    "--hostlist-exclude=",
    "--ipset=",
    "--ipset-exclude=",
)

# Data-driven auto lists: KEEP. The daemon never strips these.
KEEP_PREFIXES = ("--hostlist-auto=",)


def tokenize(text: str) -> list[str]:
    """Mirror the daemon tokenizer: '\\'+newline -> nothing, newline -> space."""
    out = []
    chars = list(text)
    i = 0
    buf = ""
    while i < len(chars):
        c = chars[i]
        if c == "\\" and i + 1 < len(chars) and chars[i + 1] == "\n":
            i += 2
            continue
        if c == "\n" or c == "\r":
            buf += " "
            i += 1
            continue
        buf += c
        i += 1
    return [t for t in buf.split() if t != "\\"]


def is_selection_arg(tok: str) -> bool:
    return any(tok.startswith(p) for p in STRIP_PREFIXES)


def strip_and_split(text: str) -> tuple[list[list[str]], int]:
    """Return (sections, selection_arg_count).

    sections[0] is the GLOBAL zone (tokens before the first --new);
    sections[1..] are the --new blocks. Selection args are removed from every
    section but token ORDER within a section is preserved.
    """
    tokens = tokenize(text)
    raw: list[list[str]] = []
    cur: list[str] = []
    for t in tokens:
        if t == "--new":
            raw.append(cur)
            cur = []
        else:
            cur.append(t)
    raw.append(cur)

    sel_count = 0
    clean: list[list[str]] = []
    for s in raw:
        kept = []
        for t in s:
            if is_selection_arg(t):
                sel_count += 1
            else:
                kept.append(t)
        clean.append(kept)
    return clean, sel_count


def dedup_sections(sections: list[list[str]]) -> list[list[str]]:
    """Collapse only COMPLETELY-IDENTICAL --new blocks. The global zone
    (sections[0]) is always kept. Among sections[1..], a block is dropped only
    if an earlier block (global or --new) is byte/order-identical in its full
    post-strip content. Safe redundancy elimination."""
    if not sections:
        return sections
    global_key = tuple(sections[0])
    seen = {global_key}
    out: list[list[str]] = [sections[0]]
    for s in sections[1:]:
        k = tuple(s)
        if k in seen:
            continue
        seen.add(k)
        out.append(s)
    return out


def render(sections: list[list[str]]) -> str:
    """Render sections back to a strategy file:
      - section[0] (global zone) is emitted WITHOUT a --new prefix
      - sections[1..] are prefixed with --new
    so the daemon parses it into the same section structure.

    Empty sections are PRESERVED (emitted as a bare "--new"). A trailing bare
    "--new" in the original denotes an empty final block; the daemon still
    parses it and injects the user selection into it, so dropping it would
    change daemon output. Preserving --new count keeps behavior identical."""
    lines = []
    for i, s in enumerate(sections):
        if i == 0:
            if s:
                lines.append(" ".join(s))
            # an empty global zone contributes nothing
        else:
            lines.append("--new" + (" " + " ".join(s) if s else ""))
    return "\n".join(lines) + "\n"


def analyze_file(path: str) -> dict:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    clean, sel_count = strip_and_split(text)
    total_blocks = sum(1 for s in clean if s)
    keys_seen: dict[tuple, int] = {}
    dup_blocks = 0
    for idx, s in enumerate(clean):
        if not s:
            continue
        k = tuple(s)
        keys_seen[k] = keys_seen.get(k, 0) + 1
        if idx > 0 and keys_seen[k] > 1:
            dup_blocks += 1
    return {
        "file": os.path.basename(path),
        "total_blocks": total_blocks,
        "dup_blocks": dup_blocks,
        "selection_args": sel_count,
        "unique_blocks": total_blocks - dup_blocks,
    }


def apply_file(path: str, inplace: bool, dedup: bool) -> dict:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    clean, sel_count = strip_and_split(text)
    total_blocks = sum(1 for s in clean if s)
    out_sections = dedup_sections(clean) if dedup else clean
    out = render(out_sections)
    dst = path if inplace else path + ".new"
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(out)
    return {
        "file": os.path.basename(path),
        "dst": os.path.basename(dst),
        "total_blocks": total_blocks,
        "selection_args": sel_count,
        "after_blocks": sum(1 for s in out_sections if s),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True,
                    help="directory of strategy files")
    ap.add_argument("--apply", action="store_true",
                    help="mutate: write <file>.new with selection args stripped")
    ap.add_argument("--dedup", action="store_true",
                    help="with --apply, also collapse identical --new blocks")
    ap.add_argument("--inplace", action="store_true",
                    help="with --apply, overwrite the original file")
    args = ap.parse_args()

    files = sorted(
        os.path.join(args.dir, f) for f in os.listdir(args.dir)
        if f.endswith(".txt") and not f.endswith(".new")
    )
    if not files:
        print(f"no .txt files in {args.dir}")
        return 1

    if args.apply:
        print(f"{'file':42} {'blocks':>7} {'sel_args':>9} {'->blocks':>9}  out")
        print("-" * 78)
        for f in files:
            r = apply_file(f, args.inplace, args.dedup)
            print(f"{r['file']:42} {r['total_blocks']:>7} {r['selection_args']:>9} "
                  f"{r['after_blocks']:>9}  {r['dst']}")
        mode = "inplace" if args.inplace else "wrote .new (review, then replace originals)"
        print("-" * 78)
        print(f"APPLY mode (dedup={'on' if args.dedup else 'off'}, {mode}).")
        return 0

    tot_blocks = tot_dup = tot_sel = 0
    print(f"{'file':42} {'blocks':>7} {'dup':>5} {'sel_args':>9}")
    print("-" * 66)
    for f in files:
        r = analyze_file(f)
        tot_blocks += r["total_blocks"]
        tot_dup += r["dup_blocks"]
        tot_sel += r["selection_args"]
        if r["dup_blocks"] or r["selection_args"]:
            print(f"{r['file']:42} {r['total_blocks']:>7} {r['dup_blocks']:>5} {r['selection_args']:>9}")
    print("-" * 66)
    print(f"{'TOTAL':42} {tot_blocks:>7} {tot_dup:>5} {tot_sel:>9}")
    print()
    print(f"After stripping selection args (daemon-owned, user-selected):")
    print(f"  selection args that move to daemon-injected config: {tot_sel}")
    if tot_dup:
        print(f"  identical --new blocks that --dedup would collapse: {tot_dup}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
