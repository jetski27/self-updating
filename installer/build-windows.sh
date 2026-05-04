#!/usr/bin/env bash
#
# Build the Windows installer (.exe) using jpackage, with launcher.jar wrapped
# as MyApp.exe via Launch4j.
#
# Why two-step: jpackage's --main-jar would make quarkus-run.jar the entry point.
# We want our update4j Launcher to run first instead — it checks for updates,
# then loads Quarkus through update4j's dynamic classpath. So we wrap launcher.jar
# into MyApp.exe (Launch4j) and use --add-launcher to make jpackage register the
# wrapped EXE as the application's primary launcher.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-1.0.0}"
JAVA_HOME_DIR="${JAVA_HOME:?JAVA_HOME is required}"

echo "==> Step 1: wrap launcher.jar -> MyApp.exe (Launch4j)"
launch4j launcher/launch4j-config.xml

if [[ ! -f installer/MyApp.exe ]]; then
  echo "Launch4j did not produce installer/MyApp.exe" >&2
  exit 1
fi

mkdir -p installer/output

# We use the wrapped MyApp.exe as the primary launcher. quarkus-run.jar is still
# in --input dist/ so it ships, but it's not the entry point at runtime — the
# launcher loads it dynamically via update4j.
echo "==> Step 2: jpackage"
jpackage \
  --type exe \
  --name "MyApp" \
  --app-version "${VERSION}" \
  --input dist/ \
  --main-jar quarkus-run.jar \
  --main-class io.quarkus.bootstrap.runner.QuarkusEntryPoint \
  --java-options "-Dapp.home=%APPDATA%/MyApp" \
  --win-dir-chooser \
  --win-menu \
  --win-menu-group "MyApp" \
  --win-shortcut \
  --icon installer/app.ico \
  --runtime-image "${JAVA_HOME_DIR}" \
  --add-launcher MyAppLauncher=installer/launcher.properties \
  --dest installer/output/

echo "==> Done. Installers at installer/output/"
ls -la installer/output/
