#!/usr/bin/env python3
"""
Generate an update4j config.xml from a built distribution directory.

Usage:
  generate-config.py --version 1.2.3 \
                     --dist-dir ./dist \
                     --base-url https://github.com/OWNER/REPO/releases/download/v1.2.3/ \
                     --output config.xml

Walks every file under --dist-dir, computes SHA-256 + byte size, and writes a
valid update4j configuration. Files inside `app/` or `lib/` are marked as
classpath="true" so update4j adds them to the dynamic classpath at launch.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from datetime import datetime, timezone
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

CLASSPATH_PREFIXES = ("app/", "lib/")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--version", required=True)
    p.add_argument("--dist-dir", required=True, type=Path)
    p.add_argument("--base-url", required=True, help="Base URI; trailing slash recommended")
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()

    dist: Path = args.dist_dir.resolve()
    if not dist.is_dir():
        print(f"error: dist dir not found: {dist}", file=sys.stderr)
        return 1

    base = args.base_url
    if not base.endswith("/"):
        base = base + "/"

    files: list[tuple[Path, int, str, bool]] = []
    total_bytes = 0

    for f in sorted(dist.rglob("*")):
        if not f.is_file():
            continue
        rel = f.relative_to(dist).as_posix()
        # Don't ship the config itself as a payload entry.
        if rel == "config.xml":
            continue
        size = f.stat().st_size
        digest = sha256_of(f)
        on_classpath = any(rel.startswith(pfx) for pfx in CLASSPATH_PREFIXES)
        files.append((Path(rel), size, digest, on_classpath))
        total_bytes += size

    timestamp = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    out_lines = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        "<configuration>",
        f"    <timestamp>{timestamp}</timestamp>",
        f"    <base uri={quoteattr(base)}/>",
        f"    <properties>",
        f'        <property key="app.version" value={quoteattr(args.version)}/>',
        f"    </properties>",
        "    <files>",
    ]
    for rel, size, digest, on_cp in files:
        rel_posix = rel.as_posix()
        attrs = [
            f"path={quoteattr(rel_posix)}",
            f'size="{size}"',
            f'checksum="{digest}"',
            f"uri={quoteattr(base + rel_posix)}",
        ]
        if on_cp:
            attrs.append('classpath="true"')
        out_lines.append("        <file " + " ".join(attrs) + "/>")
    out_lines.append("    </files>")
    out_lines.append("</configuration>")
    out_lines.append("")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(out_lines), encoding="utf-8")

    print(f"Wrote {args.output}: {len(files)} files, {total_bytes} bytes total")
    # silence "escape" unused import warnings on linters
    _ = escape
    return 0


if __name__ == "__main__":
    sys.exit(main())
