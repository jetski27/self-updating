# PoS Agent

self-updating desktop application by Azry.

- **Backend**: Quarkus 3.15 (JVM, fast-jar)
- **Frontend**: Vite + React + TypeScript, embedded via Quarkus Quinoa
- **Auto-update**: [update4j](https://github.com/update4j/update4j) — fetches `config.xml` from GitHub Releases, compares SHA-256 hashes, downloads only what changed
- **Windows installer**: `jpackage` (bundled JRE) — `PoS Agent.exe` runs `launcher.jar` directly, no Launch4j wrapper

## Repository layout

```
.
├── pom.xml                     parent Maven project (modules: launcher, app)
├── launcher/                   update4j bootstrap (small, rarely changes)
├── app/                        Quarkus + Quinoa application
│   ├── frontend/               Vite + React source
│   └── src/main/java/...       REST resources, scheduler, event bus
├── installer/                  jpackage configuration
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

## Run as a Windows service

The Windows installer registers a `PoS Agent Service` Windows service during
install and starts it as the final step. No post-install scripts, no second
UAC prompt — when the installer wizard finishes, the service is already
running and the app is reachable at <http://localhost:8080>.

This is wired up via jpackage's native `--launcher-as-service` flag (see
`installer/launcher-service.properties` and `installer/build-windows.sh`).
jpackage emits a WiX fragment that uses an NSSM-style supervisor so our Java
code doesn't need to integrate with the Service Control Manager directly —
the launcher's existing supervisor loop keeps handling Quarkus restarts and
self-updates.

**Manage** with standard Windows tools:

```powershell
sc.exe start  "PoS Agent Service"
sc.exe stop   "PoS Agent Service"
sc.exe query  "PoS Agent Service"
# or open services.msc
```

Notes:

- The service runs under `LocalSystem` by default (jpackage's WiX template).
  Change the identity in `services.msc` → PoS Agent Service → Log On if you
  need a real user account.
- `app.home` resolves to `%ProgramData%\PoS Agent` in service mode so logs
  and downloaded jars don't end up under
  `C:\Windows\System32\config\systemprofile\AppData`.
- Service mode skips the splash window and browser auto-open (Session 0 has
  no display). Open <http://localhost:8080> yourself, or set a desktop
  shortcut to that URL.
- `PoS Agent.exe` (the interactive launcher) still ships in the install dir
  for development/debugging. If the service is already running, double-clicking
  it just opens the browser at <http://localhost:8080> and exits — no second
  JVM, no port conflict. Override with `-Dapp.home=…` to force a parallel
  instance for development.
- Logs: `%ProgramData%\PoS Agent\logs\` (`launcher.log`, `quarkus.log`).
