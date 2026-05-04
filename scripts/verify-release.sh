#!/usr/bin/env bash
#
# Sanity-check the dist/ tree before uploading to GitHub releases.
# Verifies that:
#   - dist/config.xml exists and is well-formed XML
#   - every <file> declared in config.xml is present in dist/ with the expected sha256
#   - dist/quarkus-run.jar exists
#   - dist/launcher.jar exists
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${1:-$ROOT/dist}"

fail() { echo "verify-release: $*" >&2; exit 1; }

[[ -d "$DIST" ]]                || fail "dist dir not found: $DIST"
[[ -f "$DIST/config.xml" ]]     || fail "missing $DIST/config.xml"
[[ -f "$DIST/launcher.jar" ]]   || fail "missing $DIST/launcher.jar"
[[ -f "$DIST/quarkus-run.jar" ]]|| fail "missing $DIST/quarkus-run.jar"

echo "==> XML well-formedness"
python3 -c "import sys, xml.etree.ElementTree as ET; ET.parse(sys.argv[1])" "$DIST/config.xml" \
  || fail "config.xml is not valid XML"

echo "==> Adler-32 cross-check of every <file> (must match update4j's expected format)"
python3 - "$DIST" <<'PY'
import os, sys, zlib
import xml.etree.ElementTree as ET

dist = sys.argv[1]
tree = ET.parse(os.path.join(dist, "config.xml"))
root = tree.getroot()

errors = 0
checked = 0
for f in root.iter("file"):
    rel = f.attrib.get("path")
    expected = f.attrib.get("checksum")
    expected_size = int(f.attrib.get("size", "-1"))
    if not rel or not expected:
        print(f"  missing path/checksum on entry: {f.attrib}", file=sys.stderr)
        errors += 1
        continue
    p = os.path.join(dist, rel)
    if not os.path.isfile(p):
        print(f"  missing file declared in config.xml: {rel}", file=sys.stderr)
        errors += 1
        continue
    a = 1
    with open(p, "rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            a = zlib.adler32(chunk, a)
    actual = f"{a & 0xFFFFFFFF:x}"
    actual_size = os.path.getsize(p)
    if actual != expected:
        print(f"  adler-32 mismatch on {rel}: expected {expected}, got {actual}", file=sys.stderr)
        errors += 1
    if expected_size != -1 and actual_size != expected_size:
        print(f"  size mismatch on {rel}: expected {expected_size}, got {actual_size}", file=sys.stderr)
        errors += 1
    checked += 1

if errors:
    print(f"verify-release: {errors} problem(s)", file=sys.stderr)
    sys.exit(1)
print(f"verified {checked} files OK")
PY

echo "verify-release: OK"
