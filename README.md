# PoS Agent

self-updating desktop application by Azry.

- **Backend**: Quarkus 3.15 (JVM, fast-jar)
- **Frontend**: Vite + React + TypeScript, embedded via Quarkus Quinoa
- **Auto-update**: [update4j](https://github.com/update4j/update4j) — fetches `config.xml` from GitHub Releases, compares SHA-256 hashes, downloads only what changed
- **Windows installer**: `jpackage` (bundled JRE) — `PoS Agent.exe` runs `launcher.jar` directly, no Launch4j wrapper
- **Windows service**: registered automatically via the MSI's WiX `ServiceInstall`. Wrapped with [WinSW](https://github.com/winsw/winsw) so the launcher process is owned by the SCM. Manage with `services.msc`, `sc start "PoSAgent"`, `sc stop "PoSAgent"`. State (config, logs, restart marker) lives in `%PROGRAMDATA%\PoS Agent\` instead of `%APPDATA%\PoS Agent\` when running as a service.

## Repository layout

```
.
├── pom.xml                     parent Maven project (modules: launcher, app)
├── launcher/                   update4j bootstrap (small, rarely changes)
├── app/                        Quarkus + Quinoa application
│   ├── frontend/               Vite + React source
│   └── src/main/java/...       REST resources, scheduler, event bus
├── installer/                  jpackage configuration
│   ├── service/                WinSW descriptor + register/unregister .bat helpers
│   └── wix/overrides.wxi       jpackage WiX <ServiceInstall> override
├── scripts/                    build-release.sh, verify-release.sh, generate-config.py
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
builds everything, signs the SHA-256 hashes into `config.xml`, builds the
installer with `jpackage`, and uploads the artifacts to a new GitHub Release.

For a local dry-run:

```bash
scripts/build-release.sh 1.2.3
```

## Develop

```bash
# Quarkus dev mode (hot reload + Vite dev server proxied to /api)
mvn -pl app quarkus:dev
```

Open <http://localhost:8080>.

## How auto-update works

1. User runs `PoS Agent.exe` (jpackage native launcher → `launcher.jar` on the bundled JRE).
2. Launcher hits `https://api.github.com/repos/jetski27/self-updating/releases/latest`,
   downloads `config.xml`, runs update4j which sha-256-compares each file and
   downloads only what changed.
3. Launcher caches the new `config.xml` to `%APPDATA%/PoS Agent/config.xml` and
   `config.launch()` boots the Quarkus app.
4. The running app polls GitHub hourly. When a newer tag exists it emits an SSE
   `restart-pending` event; the Dashboard shows a banner.
5. User clicks **Restart** → app writes `%APPDATA%/PoS Agent/.restart-pending` and
   exits → launcher's loop sees the marker and re-runs the update.
