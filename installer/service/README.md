# Service installation

PoS Agent registers itself with the Windows Service Control Manager via
**WinSW** (Windows Service Wrapper) wrapped around the existing `launcher.jar`.

## Layout after install

```
C:\Program Files\PoS Agent\
├── PoS Agent.exe                jpackage's desktop launcher (still works for ad-hoc runs)
├── app\
│   ├── launcher.jar             update4j supervisor — entry point in service mode too
│   ├── PoSAgent.xml             WinSW descriptor (this dir)
│   ├── PoSAgent.exe             WinSW binary (renamed from winsw.exe, paired with the XML)
│   └── ...                      seed payload (quarkus-run.jar, lib/, app/)
└── runtime\bin\java.exe         bundled JRE
```

WinSW's convention: `<name>.exe` and `<name>.xml` must sit side by side. We
ship `winsw.exe` renamed to `PoSAgent.exe` so SCM ends up registering the
service with `ImagePath = ...\app\PoSAgent.exe`.

## What the MSI does

The WiX override at `installer/wix/main.wxs` adds a `<ServiceInstall>` and
`<ServiceControl>` element on the WinSW exe. On install:

1. Files are placed in `C:\Program Files\PoS Agent\`.
2. `ServiceInstall` registers a service named `PoSAgent` with the SCM, account
   `LocalSystem`, start type Automatic (Delayed).
3. `ServiceControl` starts the service immediately.

On uninstall:

1. `ServiceControl` stops the service, then deletes the registration.
2. Files are removed.

The `%ProgramData%\PoS Agent\` state directory is intentionally **not**
removed by the MSI — that keeps cached configs, logs and the .restart-pending
marker (if any) across upgrades. Use `Remove-Item -Recurse` manually if you
want a hard reset.

## Service mode behavior

The launcher detects service mode through any of:

- `--service` CLI flag (what WinSW passes)
- `POSAGENT_SERVICE=1` environment variable
- `-Dposagent.service=true` system property

In service mode the launcher:

- Resolves `APP_HOME` to `%ProgramData%\PoS Agent\` instead of `%APPDATA%`.
- One-time migrates state from a pre-existing `%APPDATA%\PoS Agent\` so users
  who installed the desktop build don't lose their cached config on upgrade.
- Suppresses the splash window and the browser-open helper (Session 0 has no
  desktop, so Swing and `Desktop.browse` would either no-op or throw).
- Installs a JVM shutdown hook so SCM's stop signal cleanly drains the
  Quarkus child instead of being mistaken for a crash.

The dashboard's `Restart` button keeps the same semantics it had in desktop
mode: write `.restart-pending`, exit Quarkus, supervisor loop catches it,
re-runs update4j, boots the new fast-jar.

## Manual installation (for development / debugging)

```powershell
# As Administrator, in C:\Program Files\PoS Agent\app\
.\PoSAgent.exe install
.\PoSAgent.exe start

# Uninstall
.\PoSAgent.exe stop
.\PoSAgent.exe uninstall
```

These are useful when iterating on the WinSW XML without rebuilding the
whole MSI.
