#!/usr/bin/env bash
#
# Build the Windows installer (.exe) using jpackage.
#
# launcher.jar is the entry point: jpackage's MyApp.exe boots launcher.jar,
# which checks for updates via update4j and then loads the Quarkus fast-jar
# from the dynamic classpath. quarkus-run.jar + lib/ + app/ are still copied
# into the install dir as the seed payload, so first launch can work offline
# (the launcher's local-fallback path).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-1.0.0}"
JAVA_HOME_DIR="${JAVA_HOME:?JAVA_HOME is required}"

mkdir -p installer/output

jpackage \
  --type exe \
  --name "MyApp" \
  --app-version "${VERSION}" \
  --vendor "MyApp" \
  --description "MyApp self-updating desktop application" \
  --input dist/ \
  --main-jar launcher.jar \
  --main-class com.example.myapp.launcher.Launcher \
  --java-options "-Dgithub.owner=jetski27" \
  --java-options "-Dgithub.repo=self-updating" \
  --win-dir-chooser \
  --win-menu \
  --win-menu-group "MyApp" \
  --win-shortcut \
  --icon installer/app.ico \
  --runtime-image "${JAVA_HOME_DIR}" \
  --dest installer/output/

echo "==> Done. Installers at installer/output/"
ls -la installer/output/
