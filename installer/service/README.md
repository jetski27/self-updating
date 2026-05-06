# Service installation

PoS Agent ships as a plain zip (`posagent-service-vX.Y.Z.zip`) that the
client extracts and registers as a Windows service via **WinSW** (Windows
Service Wrapper) wrapped around the existing `launcher.jar`. No installer,
no bundled JRE — the client supplies Java 21+.

## Layout after extracting the zip

```
C:\posagent\                              (or wherever the user extracted)
├── PoSAgent.exe                          WinSW binary (renamed from winsw.exe)
├── PoSAgent.xml                          WinSW descriptor (paired with the .exe)
├── install-service.bat                   register + start the service (run as admin)
├── uninstall-service.bat                 stop + remove the service
├── README.txt                            quick instructions
├── launcher.jar                          update4j supervisor (service entry point)
├── config.xml                            update4j manifest (delta-updated later)
├── quarkus-run.jar                       Quarkus fast-jar
├── app/                                  Quarkus app classes + frontend bundle
├── lib/                                  Quarkus dependencies
└── quarkus/                              Quarkus internals
```

WinSW's convention: `<name>.exe` and `<name>.xml` must sit side by side. We
ship `winsw.exe` renamed to `PoSAgent.exe` so SCM registers the service with
`ImagePath = ...\PoSAgent.exe` and `services.msc` shows it as "PoS Agent".

## What install-service.bat does

1. Verifies it was launched with admin privileges (`net session`).
2. Verifies system-wide `JAVA_HOME` is set and points at a real JDK
   (the service runs as LocalSystem and only sees machine-wide env vars
   — a user-only `JAVA_HOME` won't reach it).
3. If the service already exists from a prior install, stops + removes
   it cleanly so the re-register starts from a known state.
4. `PoSAgent.exe install` registers the service with the SCM
   (LocalSystem, Automatic-Delayed, depends on Tcpip).
5. `PoSAgent.exe start` starts the service immediately.

After that, SCM owns the lifecycle. `launcher.jar`'s in-process
supervisor handles update4j checks, dashboard restart-pending, etc.,
without involving the SCM.

## State

`%ProgramData%\PoS Agent\` (machine-wide, since the service runs as
LocalSystem):

- `config.xml` — the manifest update4j cached
- `quarkus-run.jar`, `app/`, `lib/` — delta-updated payload
- `logs/launcher.log` — supervisor log
- `logs/posagent.log` — Quarkus log
- `logs/winsw.<svc>.log` — WinSW process log
- `.restart-pending` — transient, drives in-process restarts

`uninstall-service.bat` only removes the service registration; it leaves
`%ProgramData%\PoS Agent\` intact so the next install reuses the cached
payload. Delete the directory manually for a hard reset.

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
# As Administrator, in the extracted zip directory:
.\PoSAgent.exe install
.\PoSAgent.exe start

# Uninstall
.\PoSAgent.exe stop
.\PoSAgent.exe uninstall
```

These do exactly what install-service.bat / uninstall-service.bat do,
just without the env-var sanity checks. Handy when iterating on the
WinSW XML.
