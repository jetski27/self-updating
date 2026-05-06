#!/usr/bin/env bash
#
# Build the service-zip distribution: posagent-service-vX.Y.Z.zip.
#
# A plain zip the user extracts somewhere on a Windows box that already
# has Java 21+ installed (system-wide JAVA_HOME), then runs
# install-service.bat as Administrator. Replaces the jpackage MSI path —
# WinSW + launcher.jar + the seed payload, no bundled JRE, no WiX.
#
# Updates after the first install are still delta: launcher.jar runs
# update4j, SHA-256-compares against the latest config.xml on GitHub,
# and only downloads what changed. The zip is bootstrap-only.
#
# Usage:
#   scripts/build-service-zip.sh [VERSION]
# VERSION defaults to `git describe --tags --abbrev=0` minus the leading v.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-${VERSION:-}}"
if [[ -z "${VERSION:-}" ]]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
fi
if [[ -z "${VERSION:-}" ]]; then
  echo "error: no VERSION provided and no git tag available" >&2
  exit 1
fi
VERSION="${VERSION#v}"

# WinSW v2.12.0, sha-256 pinned. Same wrapper jpackage MSI used; mature,
# MIT, requires .NET Framework 4.6.1+ which is universal on Windows 10+.
WINSW_VERSION="v2.12.0"
WINSW_URL="https://github.com/winsw/winsw/releases/download/${WINSW_VERSION}/WinSW-x64.exe"
WINSW_SHA256="05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"

if [[ ! -d dist ]]; then
  echo "error: dist/ not found. Run scripts/build-release.sh first." >&2
  exit 1
fi
if [[ ! -f dist/launcher.jar || ! -f dist/quarkus-run.jar || ! -f dist/config.xml ]]; then
  echo "error: dist/ is incomplete (missing launcher.jar / quarkus-run.jar / config.xml)." >&2
  exit 1
fi

mkdir -p installer/output installer/cache

echo "==> Fetching WinSW ${WINSW_VERSION}"
WINSW_CACHE="installer/cache/winsw-${WINSW_VERSION}.exe"
if [[ ! -f "${WINSW_CACHE}" ]]; then
  curl -fsSL "${WINSW_URL}" -o "${WINSW_CACHE}"
fi
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA="$(sha256sum "${WINSW_CACHE}" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL_SHA="$(shasum -a 256 "${WINSW_CACHE}" | awk '{print $1}')"
else
  echo "error: no sha256 tool available" >&2; exit 1
fi
if [[ "${ACTUAL_SHA}" != "${WINSW_SHA256}" ]]; then
  echo "error: WinSW checksum mismatch."        >&2
  echo "       expected ${WINSW_SHA256}"        >&2
  echo "       got      ${ACTUAL_SHA}"          >&2
  echo "Update WINSW_SHA256 if you have audited the new binary." >&2
  exit 1
fi

# Stage zip contents.
STAGE="installer/output/posagent-service-${VERSION}"
rm -rf "${STAGE}"
mkdir -p "${STAGE}"

# Service entry + descriptor + admin scripts. PoSAgent.exe is the
# renamed WinSW binary; SCM convention requires <name>.exe and <name>.xml
# to sit side by side.
cp "${WINSW_CACHE}"                          "${STAGE}/PoSAgent.exe"
cp installer/service/PoSAgent.xml            "${STAGE}/PoSAgent.xml"
cp installer/service/install-service.bat     "${STAGE}/install-service.bat"
cp installer/service/uninstall-service.bat   "${STAGE}/uninstall-service.bat"

# Seed payload — same files referenced by config.xml, so update4j sees
# everything as up-to-date on first launch and skips downloads. Without
# these the launcher would have to fetch the full payload from GitHub
# the first time, which works but adds latency to the first start.
cp -R dist/. "${STAGE}/"

# Embedded README so the user has paste-able instructions inside the zip.
cat > "${STAGE}/README.txt" <<EOF
PoS Agent ${VERSION} — Windows service distribution
====================================================

Prerequisites
-------------
  * Windows 10/11 (or Server 2016+), 64-bit
  * Java 21+ installed
  * JAVA_HOME set as a SYSTEM environment variable (not user-level —
    the service runs as LocalSystem and only sees machine-wide vars)

Install
-------
  1. Extract this zip somewhere stable, e.g. C:\\posagent\\
  2. Right-click install-service.bat → "Run as administrator"
  3. Open http://localhost:8080 in a browser

Manage the service
------------------
  services.msc                shows "PoS Agent" as Running, Auto (Delayed)
  sc query PoSAgent           query status
  sc stop  PoSAgent           stop
  sc start PoSAgent           start

Uninstall
---------
  Run uninstall-service.bat as administrator. State at
  %ProgramData%\\PoS Agent\\ is preserved across upgrades; delete it
  manually for a hard reset.

Updates
-------
  The launcher polls GitHub Releases hourly. When a newer release
  exists, the dashboard banner offers Restart. On restart, update4j
  SHA-256 compares each local file against the new config.xml and
  downloads only what changed (delta updates). The zip you ran from
  is only needed for the first install.

  launcher.jar itself is excluded from delta updates (file lock while
  running). If a release notes that launcher.jar changed, re-download
  this zip and re-run install-service.bat. In practice that's rare.

State location
--------------
  %ProgramData%\\PoS Agent\\
    config.xml                  the manifest update4j cached
    quarkus-run.jar, app/, lib/ delta-updated payload
    logs/launcher.log           supervisor log
    logs/posagent.log           Quarkus log
    logs/winsw.<svc>.log        WinSW process log
    .restart-pending            transient — restart marker
EOF

# Pack it. -y preserves perms; -q quiet.
ZIP="installer/output/posagent-service-${VERSION}.zip"
rm -f "${ZIP}"
( cd installer/output && zip -r -q "$(basename "${ZIP}")" "$(basename "${STAGE}")" )

# Sanity: print what we shipped.
echo "==> Built ${ZIP}"
unzip -l "${ZIP}" | tail -15
ls -la "${ZIP}"
