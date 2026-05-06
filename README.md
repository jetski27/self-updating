# PoS Agent

self-updating desktop application by Azry.

- **Backend**: Quarkus 3.15 (JVM, fast-jar)
- **Frontend**: Vite + React + TypeScript, embedded via Quarkus Quinoa
- **Auto-update**: [update4j](https://github.com/update4j/update4j) — fetches `config.xml` from GitHub Releases, compares SHA-256 hashes, downloads only what changed (delta updates)
- **Distribution**: a plain `posagent-service-vX.Y.Z.zip` published to GitHub Releases. Client machine needs Java 21+ with system-wide `JAVA_HOME`. Extract anywhere, run `install-service.bat` as Administrator — done.
- **Windows service**: wrapped with [WinSW](https://github.com/winsw/winsw) (`PoSAgent.exe` + `PoSAgent.xml`). The install script registers `PoSAgent` with the SCM (LocalSystem, Automatic-Delayed). Manage with `services.msc`, `sc start PoSAgent`, `sc stop PoSAgent`. State (config, logs, restart marker) lives in `%PROGRAMDATA%\PoS Agent\`.

## Repository layout

```
.
├── pom.xml                     parent Maven project (modules: launcher, app)
├── launcher/                   update4j bootstrap (small, rarely changes)
├── app/                        Quarkus + Quinoa application
│   ├── frontend/               Vite + React source
│   └── src/main/java/...       REST resources, scheduler, event bus
├── installer/
│   └── service/                WinSW descriptor + install/uninstall .bat shipped in the zip
├── scripts/                    build-release.sh, build-service-zip.sh, verify-release.sh, generate-config.py
└── .github/workflows/          release.yml — GitHub Actions release pipeline
```

## Build

```bash
mvn clean package
```

Produces:
- `launcher/target/launcher.jar`
- `app/target/quarkus-app/` (Quarkus fast-jar) with the React UI embedded

## Cut a release

```bash
git tag v1.2.3
git push origin v1.2.3
```

The GitHub Actions workflow at `.github/workflows/release.yml` runs on Windows,
builds the Quarkus fast-jar + launcher, signs SHA-256 hashes into `config.xml`,
packs `posagent-service-vX.Y.Z.zip` (WinSW + launcher.jar + seed payload +
install scripts), and uploads everything to a new GitHub Release.

For a local dry-run:

```bash
scripts/build-release.sh 1.2.3
scripts/build-service-zip.sh 1.2.3
```

## Install on a client machine

Prerequisites: Windows 10/11 64-bit, Java 21+ installed, **system-wide**
`JAVA_HOME` (the service runs as LocalSystem and only sees machine-wide
env vars).

1. Download `posagent-service-vX.Y.Z.zip` from the GitHub Release.
2. Extract somewhere stable, e.g. `C:\posagent\`.
3. Right-click `install-service.bat` → **Run as administrator**.
4. Open <http://localhost:8080>.

`services.msc` shows the service as Running, Automatic (Delayed Start).

## Develop

```bash
# Quarkus dev mode (hot reload + Vite dev server proxied to /api)
mvn -pl app quarkus:dev
```

Open <http://localhost:8080>.

## How auto-update works

1. SCM starts the service. WinSW invokes `%JAVA_HOME%\bin\java.exe -jar launcher.jar --service`.
2. Launcher resolves `APP_HOME` to `%PROGRAMDATA%\PoS Agent\`. On first
   run it seeds the payload from the install dir (the extracted zip)
   into `APP_HOME` so the app boots offline.
3. Launcher hits `https://api.github.com/repos/jetski27/self-updating/releases/latest`,
   downloads `config.xml`, runs update4j which SHA-256-compares each file
   and downloads only what changed (delta updates).
4. Launcher spawns the Quarkus child JVM. Quarkus reads files from `APP_HOME`.
5. The running app polls GitHub hourly. When a newer tag exists it emits an SSE
   `restart-pending` event; the Dashboard shows a banner.
6. User clicks **Restart** → app writes `%PROGRAMDATA%/PoS Agent/.restart-pending`
   and exits → launcher's loop sees the marker, re-runs update4j (delta
   downloads new bytes), and respawns Quarkus.

Note: `launcher.jar` itself is excluded from the delta manifest because
Windows can't replace a locked file. When the launcher needs an update
(rare), users re-download the zip and re-run `install-service.bat`.
