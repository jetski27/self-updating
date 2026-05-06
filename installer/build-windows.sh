#!/usr/bin/env bash
#
# Build the Windows installer (.exe) using jpackage.
#
# launcher.jar is the entry point: jpackage's "PoS Agent.exe" boots
# launcher.jar, which checks for updates via update4j and then loads the
# Quarkus fast-jar from the dynamic classpath. quarkus-run.jar + lib/ + app/
# are still copied into the install dir as the seed payload, so first launch
# can work offline (the launcher's local-fallback path).
#
# Windows-service mode: alongside the seed payload we also stage WinSW
# (Windows Service Wrapper) and our PoSAgent.xml descriptor. WiX overrides
# in installer/wix/overrides.wxi register the resulting MSI service via
# <ServiceInstall> + <ServiceControl>. After install the service starts
# automatically; SCM owns start/stop/restart from then on.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-1.0.0}"
JAVA_HOME_DIR="${JAVA_HOME:?JAVA_HOME is required}"

# WinSW v2.12.0 — stable, widely used (Jenkins ships it). Requires
# .NET Framework 4.6.1+, which is universal on Windows 10+. Pinned by
# version + sha256 so a malicious upstream can't slip a binary into our MSI.
WINSW_VERSION="v2.12.0"
WINSW_URL="https://github.com/winsw/winsw/releases/download/${WINSW_VERSION}/WinSW-x64.exe"
WINSW_SHA256="05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"

INPUT_DIR="installer/jpackage-input"
mkdir -p installer/output "${INPUT_DIR}" installer/cache

# 1) Stage the update4j-managed payload (everything in dist/) as the
#    base of jpackage's --input. This is what gets seeded into APP_HOME on
#    first launch; subsequent launches let update4j manage these files
#    based on dist/config.xml's manifest.
echo "==> Staging update4j payload"
rm -rf "${INPUT_DIR:?}"/*
cp -R dist/. "${INPUT_DIR}/"

# 2) Drop WinSW + descriptor + helper scripts alongside the payload.
#    These live in the install dir permanently and are NOT in config.xml,
#    so update4j won't try to manage them. They're MSI-installed once.
echo "==> Fetching WinSW ${WINSW_VERSION}"
WINSW_CACHE="installer/cache/winsw-${WINSW_VERSION}.exe"
if [[ ! -f "${WINSW_CACHE}" ]]; then
  curl -fsSL "${WINSW_URL}" -o "${WINSW_CACHE}"
fi
# Verify checksum — fail loudly if upstream binary has changed.
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
  echo "       file     ${WINSW_CACHE}"         >&2
  echo "Update WINSW_SHA256 in this script if you have audited the new binary." >&2
  exit 1
fi

# WinSW convention: <name>.exe and <name>.xml side by side. Renaming the
# binary to PoSAgent.exe means SCM registers ImagePath as ...\PoSAgent.exe
# which makes services.msc / sc query output more readable.
cp "${WINSW_CACHE}"                    "${INPUT_DIR}/PoSAgent.exe"
cp installer/service/PoSAgent.xml      "${INPUT_DIR}/PoSAgent.xml"
cp installer/service/register-service.bat   "${INPUT_DIR}/register-service.bat"
cp installer/service/unregister-service.bat "${INPUT_DIR}/unregister-service.bat"

# 3) jpackage. --resource-dir points at our WiX overrides directory, which
#    contains overrides.wxi adding the ServiceInstall / ServiceControl
#    elements. jpackage's main.wxs <?include?>s overrides.wxi automatically.
echo "==> Running jpackage"
jpackage \
  --verbose \
  --type exe \
  --name "PoS Agent" \
  --app-version "${VERSION}" \
  --vendor "Azry" \
  --description "PoS Agent — self-updating desktop application by Azry" \
  --input "${INPUT_DIR}/" \
  --main-jar launcher.jar \
  --main-class com.example.myapp.launcher.Launcher \
  --java-options "-Dgithub.owner=jetski27" \
  --java-options "-Dgithub.repo=self-updating" \
  --resource-dir installer/wix \
  --win-dir-chooser \
  --win-menu \
  --win-menu-group "Azry" \
  --win-shortcut \
  --win-upgrade-uuid "8B2E9C4F-3D7A-4F1B-9A6E-5C3D2E1F4A0B" \
  --icon installer/app.ico \
  --runtime-image "${JAVA_HOME_DIR}" \
  --dest installer/output/

echo "==> Done. Installers at installer/output/"
ls -la installer/output/
