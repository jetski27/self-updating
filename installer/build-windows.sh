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

# 2) WinSW (renamed PoSAgent.exe) and PoSAgent.xml are NOT staged into the
#    jpackage input dir. We deliberately keep them out so jpackage's
#    auto-harvest doesn't put them in its own Files ComponentGroup —
#    instead our overrides.wxi declares its own Component for them, with
#    ServiceInstall + ServiceControl, and pulls it into MainFeature via
#    FeatureRef. ImagePath comes from the Component's KeyPath File.
#
#    The override's <File Source="$(env.POSAGENT_WINSW_PATH)"/> reads the
#    absolute path at candle compile time — that's why we export the env
#    vars below before invoking jpackage.
echo "==> Fetching WinSW ${WINSW_VERSION}"
WINSW_CACHE="installer/cache/winsw-${WINSW_VERSION}.exe"
if [[ ! -f "${WINSW_CACHE}" ]]; then
  curl -fsSL "${WINSW_URL}" -o "${WINSW_CACHE}"
fi
# Verify checksum so a malicious upstream binary can't slip in.
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

# Compute Windows-style absolute paths for candle. On the GitHub Actions
# Windows runner Git Bash provides cygpath; on macOS/Linux we just pass
# the Unix path through (jpackage MSI builds don't run there anyway).
if command -v cygpath >/dev/null 2>&1; then
  POSAGENT_WINSW_PATH="$(cygpath -w "$(cd "$(dirname "${WINSW_CACHE}")" && pwd)\\$(basename "${WINSW_CACHE}")" 2>/dev/null \
                         || cygpath -w "${WINSW_CACHE}")"
  POSAGENT_XML_PATH="$(cygpath -w "${ROOT}/installer/service/PoSAgent.xml")"
else
  POSAGENT_WINSW_PATH="${ROOT}/installer/cache/winsw-${WINSW_VERSION}.exe"
  POSAGENT_XML_PATH="${ROOT}/installer/service/PoSAgent.xml"
fi
export POSAGENT_WINSW_PATH POSAGENT_XML_PATH
echo "    POSAGENT_WINSW_PATH=${POSAGENT_WINSW_PATH}"
echo "    POSAGENT_XML_PATH=${POSAGENT_XML_PATH}"

# 3) jpackage. --resource-dir contains overrides.wxi which Fragment-defines
#    a service Component (PoSAgent.exe + PoSAgent.xml + ServiceInstall +
#    ServiceControl) and FeatureRef-pulls it into MainFeature. candle
#    resolves $(env.POSAGENT_WINSW_PATH) and $(env.POSAGENT_XML_PATH) at
#    compile time using the env vars we exported above, so the .exe and
#    .xml don't need to be in --input.
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
