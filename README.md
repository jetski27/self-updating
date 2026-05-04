# self-updating

Production-ready, self-updating desktop application.

- **Backend**: Quarkus 3.15 (JVM, fast-jar)
- **Frontend**: Vite + React + TypeScript, embedded via Quarkus Quinoa
- **Auto-update**: [update4j](https://github.com/update4j/update4j) — fetches `config.xml` from GitHub Releases, compares SHA-256 hashes, downloads only what changed
- **Windows installer**: `jpackage` (bundled JRE) + Launch4j to wrap the launcher into `MyApp.exe`

## Repository layout

```
.
├── pom.xml                     parent Maven project (modules: launcher, app)
├── launcher/                   update4j bootstrap (small, rarely changes)
├── app/                        Quarkus + Quinoa application
│   ├── frontend/               Vite + React source
│   └── src/main/java/...       REST resources, scheduler, event bus
├── installer/                  jpackage + Launch4j configuration
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

1. User runs `MyApp.exe` (Launch4j-wrapped launcher.jar with bundled JRE).
2. Launcher hits `https://api.github.com/repos/jetski27/self-updating/releases/latest`,
   downloads `config.xml`, runs update4j which sha-256-compares each file and
   downloads only what changed.
3. Launcher caches the new `config.xml` to `%APPDATA%/MyApp/config.xml` and
   `config.launch()` boots the Quarkus app.
4. The running app polls GitHub hourly. When a newer tag exists it emits an SSE
   `restart-pending` event; the Dashboard shows a banner.
5. User clicks **Restart** → app writes `%APPDATA%/MyApp/.restart-pending` and
   exits → launcher's loop sees the marker and re-runs the update.
