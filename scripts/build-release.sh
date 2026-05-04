#!/usr/bin/env bash
#
# Build a release distribution at dist/ ready to be uploaded to a GitHub release.
#
# Usage: scripts/build-release.sh [VERSION]
#   VERSION defaults to `git describe --tags --abbrev=0`.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
fi
if [[ -z "$VERSION" ]]; then
  echo "error: no VERSION argument and no git tag available" >&2
  exit 1
fi
# Strip leading 'v' if present.
VERSION="${VERSION#v}"

OWNER="${GITHUB_OWNER:-jetski27}"
REPO="${GITHUB_REPO:-self-updating}"
BASE_URL="https://github.com/${OWNER}/${REPO}/releases/download/v${VERSION}/"

echo "==> Building MyApp ${VERSION}"

echo "==> mvn clean package (revision=${VERSION})"
mvn -B -q clean package \
    -Drevision="${VERSION}" \
    -Dquarkus.package.jar.type=fast-jar \
    -DskipTests

echo "==> Assembling dist/"
rm -rf dist
mkdir -p dist
cp -R app/target/quarkus-app/. dist/
cp launcher/target/launcher.jar dist/launcher.jar

echo "==> Generating config.xml (base=${BASE_URL})"
python3 scripts/generate-config.py \
  --version "$VERSION" \
  --dist-dir dist \
  --base-url "$BASE_URL" \
  --output dist/config.xml

echo "==> Verifying release"
"$ROOT/scripts/verify-release.sh"

echo "Build complete. dist/ is ready to upload."
