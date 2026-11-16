#!/usr/bin/env python3
"""Import upstream zapretgui (zapret2 GUI) winws2 presets into ZDT-D.

One-shot sync of the whole nfqws2 preset set: converts preset texts, copies
preset-referenced blobs/lua/lists and re-installs the result into
module_template/strategic/. Re-runnable: a later upstream release is the same
command again.

Usage:
  python3 scripts/lists/import_zapretgui.py <dist_dir> [--dry-run]

  <dist_dir>  unpacked zapretgui distribution (the installer's install dir):
              expects <dist>/presets/winws2_builtin/*.txt, <dist>/bin,
              <dist>/lua, <dist>/lists/base. On Linux the installer
              (Zapret2Setup_*.exe, Inno Setup) extracts cleanly under wine:
                WINEPREFIX=/tmp/winepfx wine Zapret2Setup_DEV_x.exe /VERYSILENT \
                  /DIR=C:\\z2inst
              dist_dir = /tmp/winepfx/drive_c/z2inst/Zapret/Dev

Conversion rules (mirrors commit d0ded7a import + fork headers):
  - keep upstream '# Preset:', '# BuiltinVersion:', '# IconColor:' header lines
  - add fork capture-policy header (NFQWS2_*_PKT_*) after the comment block
  - drop '--wf-*' and '@windivert.filter/' lines (Windows WinDivert only;
    the ZDT-D command-builder validator rejects them)
  - rewrite '@lua/x'  -> '@/data/adb/modules/ZDT-D/strategic/lua/x'
  - rewrite '@bin/x'  -> '@/data/adb/modules/ZDT-D/strategic/bin/x'
  - rewrite 'lists/x' -> '/data/adb/modules/ZDT-D/strategic/list/x'
  - keep everything else byte-for-byte (bodies, comments, blank lines)
  - drop presets whose removal is intended by the fork (DELETE_FILES)

Fork patches applied on top of upstream (kept from c034273):
  - general ALT11 'Мои сайты' item: verified alt11 recipe on hostlist
    other.txt instead of the failing tls_max-on-ipset-all recipe.

Preserved, never overwritten (fork-owned content):
  - list/other.txt (user sites + DoH resolvers, see c034273/9e44195)
  - every file in strategic/list, lua, bin NOT referenced by the presets
    (first-ban nfqws data, hostlist-auto files, ipset/app hostlists stay).
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STRATEGIC = REPO / "module_template" / "strategic"
NFQWS2_DIR = STRATEGIC / "strategicvar" / "nfqws2"
DEVICE_STRATEGIC = "/data/adb/modules/ZDT-D/strategic"

FORK_HEADER = [
    "# NFQWS2_TCP_PKT_OUT=20",
    "# NFQWS2_TCP_PKT_IN=10",
    "# NFQWS2_UDP_PKT_OUT=20",
    "# NFQWS2_UDP_PKT_IN=10",
]

PATH_RULES = [
    (re.compile(r"@lua/"), f"@{DEVICE_STRATEGIC}/lua/"),
    (re.compile(r"@bin/"), f"@{DEVICE_STRATEGIC}/bin/"),
    (
        re.compile(
            r"(--(?:hostlist|hostlist-exclude|hostlist-domains|"
            r"hostlist-exclude-domains|ipset|ipset-exclude|ipset-ip|"
            r"ipset-exclude-ip)=)lists/"
        ),
        rf"\g<1>{DEVICE_STRATEGIC}/list/",
    ),
]

LUA_REF = re.compile(
    rf"--lua-init=@{re.escape(DEVICE_STRATEGIC)}/lua/([A-Za-z0-9_.\-]+)"
)
BIN_REF = re.compile(
    rf"--blob=[^:@]+:@{re.escape(DEVICE_STRATEGIC)}/bin/([A-Za-z0-9_.\-]+)"
)
LIST_REF = re.compile(
    rf"--(?:hostlist|hostlist-exclude|ipset|ipset-exclude)="
    rf"{re.escape(DEVICE_STRATEGIC)}/list/([A-Za-z0-9_.\-]+\.txt)"
)

# Fork lists that must never be overwritten by the import.
FORK_KEEP_LISTS = {"other.txt"}

# Fork patches: preset name -> (upstream block, fork block). The import fails
# loudly if upstream changes the block, so the patch is never silently lost.
FORK_ITEMS = {
    "general ALT11.txt": (
        "--name=Мои сайты\n"
        "--filter-tcp=80,443-65535\n"
        "--ipset=lists/ipset-all.txt\n"
        "--out-range=-d8\n"
        "--payload=tls_client_hello\n"
        "--lua-desync=fake:blob=tls_max:repeats=8:tcp_ts=-600000\n"
        "--payload=tls_client_hello,http_req\n"
        "--lua-desync=multisplit:pos=2:seqovl=664:seqovl_pattern=tls_max:repeats=8:tcp_ts=-600000\n",
        "--name=Мои сайты\n"
        "--filter-tcp=80,443\n"
        "--hostlist=lists/other.txt\n"
        "--out-range=-d10\n"
        "--lua-desync=fake:blob=tls_google:repeats=6:tcp_ack=-66000\n"
        "--lua-desync=fake:blob=tls_google:ip_autottl=-1,3-20:ip6_autottl=-1,3-20:repeats=6:tcp_ack=-66000\n"
        "--lua-desync=multisplit:pos=2:seqovl=652:seqovl_pattern=tls_google\n",
    ),
}


def convert_preset(text: str, name: str) -> str:
    if name in FORK_ITEMS:
        old, new = FORK_ITEMS[name]
        if old not in text:
            sys.exit(
                f"{name}: fork item template no longer matches upstream — "
                f"review FORK_ITEMS before importing"
            )
        text = text.replace(old, new)

    out: list[str] = []
    header_done = False
    body_started = False
    for line in text.split("\n"):
        line = line.rstrip("\r")
        # Fork capture-policy header goes right after the last upstream '# ' line.
        if not body_started and line.startswith("#"):
            out.append(line)
            continue
        if not header_done:
            out.extend(FORK_HEADER)
            out.append("")
            header_done = True
            body_started = True
            if line == "":
                continue  # upstream's own separator blank; fork header added ours
        if line.startswith("--wf-"):
            continue
        if "@windivert.filter/" in line:
            continue
        for pat, repl in PATH_RULES:
            line = pat.sub(repl, line)
        out.append(line)
    result = "\n".join(out)
    if not result.endswith("\n"):
        result += "\n"
    return result


def copy_if_changed(src: Path, dst: Path, dry: bool) -> bool:
    if dst.exists() and src.read_bytes() == dst.read_bytes():
        return False
    if not dry:
        shutil.copy(src, dst)
    return True


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dist_dir", type=Path, help="unpacked zapretgui dist (Dev dir)")
    ap.add_argument("--dry-run", action="store_true", help="report, change nothing")
    args = ap.parse_args()
    dry = args.dry_run

    presets_dir = args.dist_dir / "presets" / "winws2_builtin"
    dist_bin = args.dist_dir / "bin"
    dist_lua = args.dist_dir / "lua"
    dist_lists = args.dist_dir / "lists" / "base"
    for d, what in (
        (presets_dir, "presets/winws2_builtin"),
        (dist_bin, "bin"),
        (dist_lua, "lua"),
        (dist_lists, "lists/base"),
    ):
        if not d.is_dir():
            sys.exit(f"missing {what} in dist: {d}")

    sources = sorted(presets_dir.glob("*.txt"))
    print(f"{len(sources)} upstream presets")

    # 1. Convert presets (to memory first: refs decide what to copy).
    converted: dict[str, str] = {}
    for src in sources:
        converted[src.name] = convert_preset(src.read_text(encoding="utf-8"), src.name)

    refs_lua: set[str] = set()
    refs_bin: set[str] = set()
    refs_list: set[str] = set()
    for text in converted.values():
        refs_lua.update(LUA_REF.findall(text))
        refs_bin.update(BIN_REF.findall(text))
        refs_list.update(LIST_REF.findall(text))

    # 2. Copy referenced blobs / lua / lists from the dist.
    changed_bins = changed_lua = changed_lists = 0
    for name in sorted(refs_bin):
        src = dist_bin / name
        if not src.exists():
            sys.exit(f"preset-referenced blob missing in dist: {name}")
        changed_bins += copy_if_changed(src, STRATEGIC / "bin" / name, dry)
    for name in sorted(refs_lua):
        src = dist_lua / name
        if not src.exists():
            sys.exit(f"preset-referenced lua missing in dist: {name}")
        changed_lua += copy_if_changed(src, STRATEGIC / "lua" / name, dry)
    for name in sorted(refs_list):
        if name in FORK_KEEP_LISTS:
            continue  # fork-owned list, never overwritten
        src = dist_lists / name
        if not src.exists():
            sys.exit(f"preset-referenced list missing in dist: {name}")
        changed_lists += copy_if_changed(src, STRATEGIC / "list" / name, dry)

    # 3. Install converted presets: replace the whole shipped set.
    if not dry:
        for old in NFQWS2_DIR.glob("*.txt"):
            old.unlink()
    for name, text in converted.items():
        if not dry:
            (NFQWS2_DIR / name).write_text(text, encoding="utf-8")

    print(f"presets: {len(converted)} installed (fork patches: {len(FORK_ITEMS)})")
    print(f"bin:  {changed_bins} changed / {len(refs_bin)} referenced")
    print(f"lua:  {changed_lua} changed / {len(refs_lua)} referenced")
    print(f"list: {changed_lists} changed / {len(refs_list)} referenced")
    kept = sorted(n for n in refs_list if n in FORK_KEEP_LISTS)
    if kept:
        print(f"fork-kept lists (not overwritten): {', '.join(kept)}")
    if dry:
        print("dry-run: nothing written")


if __name__ == "__main__":
    main()
