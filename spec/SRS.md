# UQ Shell — Software Requirements Specification

**Document ID:** UQS-SRS-001 · **Status:** Draft for review · **Date:** 2026-11-15
**Standard:** ISO/IEC/IEEE 29148:2018 (requirements engineering) — successor of IEEE 830-1998
**Design doc standard (next phase):** IEEE 1016-2009 (SDD)
**Note on IEEE 1058-1998:** withdrawn and project-management-scoped (plans/schedules/risk) — not applicable to requirements; see §1.5.

---

## 1. Introduction

### 1.1 Purpose
This document specifies the requirements of **UQ Shell**: a lightweight, self-built Android application for one personal device (Xiaomi Redmi Note 10 Pro, MIUI 14 / Android 13) that:

1. holds the device permission set agreed by the owner ("every access a sideloaded app can have", §2.4),
2. **hosts multiple dynamically loaded scripts** (dex modules) in its own process, running with the app's full permission set,
3. provides **scheduling (cron) capability** — both declared inside script metadata and issued by scripts at runtime,
4. is driven from **Termux over SSH** through a push pipeline (`usq` CLI → localhost HTTP receiver),
5. ships with **complete script-writer documentation** (Script Writer's Manual) served both as files and from the app itself.

The intended audience is the owner-developer (single user). The document is the basis for design (IEEE 1016 SDD, next), implementation, and acceptance testing.

### 1.2 Scope
**In scope:** the UQ Shell APK (host), the on-device script runtime, the localhost script-push API, the `usq` Termux CLI, script metadata format, scheduler, documentation deliverables, and one example module set (usage card, cron logger, usage report).

**Out of scope:** Play distribution, multi-device support, security hardening against third-party apps (owner explicitly accepted personal-device risk), root/privilege escalation, text-interpretation engine (deferred, §3.2.7 FR-TXT).

### 1.3 Product overview
UQ Shell is a **script host**: a foreground-service app whose process is a real Android app (therefore holds the window-manager binder at bind time, unlike the bare-process toolkit this project grew out of). Scripts are compiled Java/dex modules produced by the existing Termux toolchain (javac 17 → dx) and hot-loaded via `DexClassLoader`. Each script runs on its own thread, isolated by a Throwable boundary, and reaches Android through a small `ScriptApi` facade plus the real framework classes of its own dex.

The development pipeline reuses the established SSH bridge: the developer edits a script in the proot/Termux environment, `usq buildpush Foo.java` compiles it in Termux and pushes it to the app, which loads and starts it within seconds — including on-screen UI modules such as the floating usage card.

### 1.4 Definitions
| Term | Definition |
|---|---|
| **script / module** | a dex file containing an entry class implementing `uq.script.UqScript` plus optional `uq.json` metadata |
| **resident script** | a long-running script (e.g., overlay card) that stays alive until stopped |
| **one-shot run** | a script invocation that terminates by itself (typical for cron-triggered runs) |
| **registry** | persisted JSON table of installed scripts and their state |
| **schedule** | a time-based trigger (cron expression or interval) owned by the host scheduler |
| **SWM** | Script Writer's Manual — the primary documentation deliverable (§3.2.6) |
| **push** | transfer + install of a script into the host (HTTP or shared-storage watcher) |
| **buildpush** | compile Java → dex → push → (auto)start, in one CLI command |

### 1.5 References
1. ISO/IEC/IEEE 29148:2018, *Systems and software engineering — Life cycle processes — Requirements engineering* (replaces IEEE 830-1998, 1233, 1362).
2. IEEE 1016-2009, *Standard for Information Technology — Software Design Descriptions* (for the follow-on SDD).
3. ISO/IEC/IEEE 42010 (architecture description concepts) — applied informally in §2.1 and future ADRs.
4. IEEE Std 1058-1998 — considered and rejected: it defines Software Project Management Plans (withdrawn status); no PM-plan content is required for this single-developer product.
5. Project history documents: `USAGE_STATS_FROM_TERMUX.txt` Parts 1–12 (direct-binder toolkit, Termux compile chain, MIUI quirks).
6. Android 13 (API 33) SDK references; MIUI 14 device realities (battery manager, autostart).

---

## 2. Overall description

### 2.1 Product perspective
UQ Shell sits between the Termux environment (development + control plane) and the Android UI (presentation plane):

```
┌────────────────────────── proot / developer ──────────────────────────┐
│  editor · usagestats CLI · usq CLI (push/list/logs/docs)              │
└───────────────SSH──────────────▶ Termux ──────────────────────────────┘
                                  │  javac 17 → dx → dex
                                  │  curl http://127.0.0.1:7823 (loopback)
┌────────────────────────── Android 13 / MIUI 14 device ────────────────┐
│  UQ Shell app (uid self, real app process)                            │
│   ├─ ScriptService (foreground) ── registry.json                      │
│   ├─ ScriptLoader (DexClassLoader, codeCacheDir)                      │
│   ├─ Scheduler (AlarmManager exact + thread ticks)                    │
│   ├─ ScriptApi facade (overlay, usage, notify, http, file, sched…)    │
│   └─ scripts/ (installed dex modules, private dir)                    │
└───────────────────────────────────────────────────────────────────────┘
```

- **Complementary to Termux:** the app does what a bare `app_process` cannot (real windows, services); Termux remains the development and control environment. A `ScriptApi.termux()` bridge (RUN_COMMAND intent) lets scripts reach back into the Termux environment.
- **Replaces nothing:** the existing `usagestats` CLI keeps working; the first UQ Shell module reproduces its data as an on-screen card.

### 2.2 Product functions (summary)
1. Install/update/list/start/stop/remove **multiple scripts** concurrently in one process.
2. Hot **push** pipeline from Termux (build + push + run in one command).
3. **Scheduler**: cron expressions and fixed intervals, declared in script metadata or issued at runtime by scripts; persisted; survives reboot.
4. **ScriptApi facade**: overlay UI, usage statistics, notifications, HTTP, files, logging, Termux bridge, self-scheduling.
5. **Boot persistence**: autostart scripts and schedules after reboot.
6. **Documentation**: SWM + runnable examples, served as files and over the app's HTTP interface.
7. **Management screen**: the launcher activity lists installed scripts with start/stop/remove controls and live status.
8. (Phase 2) Text-script tier (Rhino/BeanShell) reusing the same host.

### 2.3 User characteristics
Single owner-developer, technically expert, operates the device over SSH from a proot shell; comfortable with Java, expects "prove it" empirical behavior; values small artifacts and honest limitations. No other users exist.

### 2.4 Constraints
- **C1 (lite):** pure Java + framework APIs only. No NDK, no Gradle, no Android SDK download, no third-party runtime dependencies in v1. Build = aapt + javac + dx + apksigner inside Termux. APK size budget ≤ 1 MB (text tier, if added, may add ≤ 1.2 MB).
- **C2 (platform):** minSdk 26, targetSdk 28, compiled against the Robolectric `android-all-13` jar (device = Android 13, API 33). Rationale: legacy storage access, exact alarms without the SCHEDULE_EXACT_ALARM toggle, fewer runtime dialogs; matches the established toolkit.
- **C3 (device):** Xiaomi Redmi Note 10 Pro (`sweetin`), MIUI 14 (`V14.0.1.0.TKFINXM`), Android 13 (SDK 33), security patch 2023-03-01, end-of-life ROM. MIUI battery manager aggressiveness is a design driver: the host runs as a foreground service; battery-optimization exemption is requested.
- **C4 (identity):** scripts execute as the app's uid with the app's permission set only. The ceiling is "everything a fully-toggled normal app can do" (no signature permissions without adb, no root, no other apps' private data).
- **C5 (security posture):** personal device, owner accepts the risk of script injection (e.g., via shared storage). Loopback-only HTTP binding is retained as a convenience default, not as a security boundary.
- **C6 (toolchain):** script compilation happens in Termux (javac 17 with `-source 8 -target 8`, dx via dalvikvm). On-device compilation inside the app is out of scope.

### 2.5 Assumptions and dependencies
- Owner will sideload the APK, then grant **Display over other apps** and **Usage access** (two Settings toggles), and optionally request battery-optimization exemption and enable MIUI **Autostart** for the app.
- Termux SSH bridge and compile chain (javac, dx, android.jar) remain available.
- Exact alarms are available at targetSdk 28 on Android 13; if the system denies them, the scheduler shall degrade to inexact alarms (graceful, documented).

---

## 3. Specific requirements

Notation: requirement IDs are stable; priorities use MoSCoW (**M** = must, **S** = should, **C** = could). Each requirement is verifiable via §4.

### 3.1 External interfaces

#### 3.1.1 Push/control HTTP API (EI-1)
The host shall expose a minimal HTTP/1.1 server bound to **127.0.0.1:7823 only**, implemented with a raw `ServerSocket` and a hand-rolled parser (no dependencies). Metadata for script load is carried in request headers (no multipart parsing).

| Endpoint | Method | Semantics |
|---|---|---|
| `/status` | GET | JSON: host uptime, app uid, script list with states, active schedules |
| `/list` | GET | JSON: registry (id, name, version, entry, resident, autoStart, state, dexFile, installedAt, lastRunAt, lastError) |
| `/load` | POST | body = dex bytes; headers: `X-Uq-Id`, `X-Uq-Entry`, `X-Uq-Name`, `X-Uq-Version`, `X-Uq-Resident`, `X-Uq-AutoStart`, `X-Uq-Cron` (repeatable: `id:cron` or `id:everyMs`), `X-Uq-Args` (base64 JSON), `X-Uq-Description`. Installs/updates the script; replaces existing id atomically |
| `/start` | POST | `?id=` ; optional `?args=` (base64 JSON) — starts a run (resident or one-shot) |
| `/stop` | POST | `?id=` — requests stop (interrupt + `onStop()` hook) |
| `/remove` | POST | `?id=` — stops if running, unregisters, deletes dex + registry entry |
| `/logs` | GET | `?id=&tail=n` — per-script log text (also `?file=1` to download full log) |
| `/docs/…` | GET | serves SWM pages (markdown) from the app's assets/files |
| `/reload` | POST | re-reads shared-storage drop dir (see EI-2) |

Responses are JSON `{"ok":true,...}` or `{"ok":false,"error":"..."}` with 200/400/404.

#### 3.1.2 Shared-storage drop dir (EI-2)
The app shall also accept scripts placed in `/sdcard/uq/in/`: any `.dex` file appearing there is imported (metadata from a sidecar `.json` of the same basename, if present, else from the dex's embedded conventions), then moved to `/sdcard/uq/done/`. A `usq drop` CLI path shall use this when HTTP is unavailable. Owner accepted the injection risk (C5).

#### 3.1.3 Script metadata (`uq.json`, EI-3)
Each script may ship `uq.json`; header fields of EI-1 override it. Schema in Appendix A.

#### 3.1.4 Termux CLI (`usq`, EI-4)
Installed at `$PREFIX/bin/usq`. Subcommands:

```
usq status | list
usq push FILE.dex [--id X] [--entry CLS] [--name N] [--resident] [--autostart] [--cron id:expr]…
usq buildpush Foo.java [--id X] …    # javac → dx → push → auto-start if autoStart/resident
usq start ID [--args JSON] | stop ID | remove ID
usq logs ID [-f] [--tail N]
usq docs [PAGE]
```
`buildpush` compiles against `android.jar` + `script-api.jar` (extracted from the host APK at first use), dexes the result, and pushes. Exit codes and JSON output (`--json`) for toolability.

#### 3.1.5 Script programming interface (EI-5)
Entry convention (Appendix C defines the full facade):

```java
public class Hello extends UqScript {          // uq.script.UqScript (abstract class)
    @Override public void main(ScriptApi api, Bundle args) throws Exception {
        api.toast("hello from a pushed script");
    }
    @Override public void onStop() { /* request to stop; release resources */ }
}
```
- `UqScript` is an **abstract class** (`main` abstract, `onStop()` empty) — avoids Java-8 default-method issues in dx.
- The script's own dex classloader is the TCCL during `main`, so custom `View` subclasses inflate correctly.
- `ScriptApi` v1 groups (methods + signatures in Appendix C): `ui` (overlay card/view, toast), `data` (usage 24h / N days / top-N), `notify` (post/cancel), `net` (httpGet/httpPost), `file` (read/write/list under sandboxed roots), `sys` (termux RUN_COMMAND, vibrate), `sched` (every/cron/unschedule), `self` (id, args, scriptDir, stop, log).

### 3.2 Functional requirements

#### 3.2.1 Script hosting (FR-HOST)
- **FR-HOST-1 (M):** The host shall install, replace, and remove multiple scripts concurrently; each script is identified by `id` (unique; re-install replaces atomically: write new dex to temp, load test, swap, delete old).
- **FR-HOST-2 (M):** Each running script shall execute on its own thread within the host process, with `UqScript` loaded from its dex via `DexClassLoader` (optimized dir = `getCodeCacheDir()`).
- **FR-HOST-3 (M):** A `Throwable` thrown by a script that escapes `main` shall be caught at the boundary: the run is marked **CRASHED** with the error logged; **all other scripts and the host process continue unaffected**.
- **FR-HOST-4 (M):** `stop` shall interrupt the script thread and invoke `onStop()`; unresponsive scripts (no exit within 5 s) shall be marked STUCK and left dead-threaded (documented).
- **FR-HOST-5 (M):** The registry (§EI-1 `/list`) shall persist across host restarts and reboots; states: INSTALLED, RUNNING, STOPPED, CRASHED, STUCK.
- **FR-HOST-6 (S):** Concurrency cap: at most 8 simultaneously RUNNING runs; further starts queue with a logged notice.
- **FR-HOST-7 (M):** The host shall run as a **foreground service** with a persistent notification (channel `uq`, low importance) showing count of running scripts.
- **FR-HOST-8 (M):** Launcher management screen: the launcher activity lists all installed scripts (name, id, entry, version, state, resident/autostart flags) with per-script **Start**, **Stop**, **Remove** buttons plus a refresh action; the list re-renders after every action; data is sourced from the same control plane (EI-1); behaves sanely with zero scripts (setup hint shown); host-unreachable state is surfaced instead of crashing.

#### 3.2.2 Push pipeline (FR-PUSH)
- **FR-PUSH-1 (M):** `usq buildpush Foo.java` shall produce a running script end-to-end without touching the app UI: compile (Termux), dex, `POST /load`, `POST /start` when `--autostart`/resident — total ≤ 2 s warm (NFR-2).
- **FR-PUSH-2 (M):** The pipeline shall fail loudly and early: compile errors return the javac output verbatim; HTTP errors return the server message; exit code ≠ 0 on any failure.
- **FR-PUSH-3 (S):** `usq logs ID -f` shall stream new log lines (poll loop, 1 s).
- **FR-PUSH-4 (C):** `usq watch Foo.java` re-pushes on file modification (1 s debounce).

#### 3.2.3 ScriptApi facade (FR-API)
- **FR-API-1 (M):** Overlay UI: `overlayView(View, x, y, w, h)` and `overlayCard(text lines)` — custom-drawn draggable card over other apps (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`); returns handle; `removeOverlay(handle)` removes. Requires the owner-granted overlay toggle; a clean `IllegalStateException` with setup instructions otherwise.
- **FR-API-2 (M):** Usage data: `usage24h()`, `usageDays(n)` (aggregated per package: pkg, total, last), `topPackages(n, days)` — implemented via `UsageStatsManager` in-app (Usage access toggle), NOT via the bare-process binder tricks.
- **FR-API-3 (M):** `notify(id, title, text[, channel])` and `cancelNotify(id)`; default channel `uq`.
- **FR-API-4 (M):** `httpGet(url[, headers])`, `httpPost(url, body[, headers])` returning `(status, body)`; 15 s timeout; runs on the script thread.
- **FR-API-5 (M):** File I/O sandboxed to: app files dir, script dir (`files/scripts/ID/`), and absolute paths under shared storage (legacy access at targetSdk 28); `read/write/append/list`.
- **FR-API-6 (M):** `termux(command)` fires the Termux RUN_COMMAND intent (requires owner's one-time allow-external-apps setting in Termux); failures logged, never fatal.
- **FR-API-7 (M):** Logging: `log(msg)` writes to per-script log file (rotated at 256 KB, keeps 2) and echoes to host log; `api.logPath()` exposes it for `usq logs`.
- **FR-API-8 (M):** `api.stop()` requests termination of the current run (graceful: returns control, host then calls `onStop()`).

#### 3.2.4 Scheduler (FR-CRON)
- **FR-CRON-1 (M):** The host shall maintain a scheduler supporting (a) **cron expressions** (5-field, Appendix B) and (b) **fixed intervals** (`everyMs`).
- **FR-CRON-2 (M):** Schedules may be **declared statically** in `uq.json` (`schedules[]`) — registered at script install, persisted, re-registered at boot.
- **FR-CRON-3 (M):** Scripts may create schedules **at runtime** via `api.every(ms, id, persist)` / `api.cron(expr, id, persist)`; `persist=false` schedules die with the script run; `persist=true` survive reboot (bound to the script id; removed by `api.unschedule(id)` or script removal).
- **FR-CRON-4 (M):** **Fire semantics = cron semantics:** each fire **spawns a fresh one-shot run** of the script entry with `args = {trigger:"cron", scheduleId, firedAt}` — crash-isolated by construction. (Long-running resident scripts that need periodic callbacks instead use an interval callback, FR-CRON-5.)
- **FR-CRON-5 (S):** `api.every(ms, id, false)` on a resident script shall deliver periodic `onTick(id)` callbacks on the script's handler thread (no new process/run spawned).
- **FR-CRON-6 (M):** Scheduling mechanism: `AlarmManager.setExactAndAllowWhileIdle` (available at targetSdk 28; on denial degrade to `setAndAllowWhileIdle` and log once). While the screen is on and the host is alive, the scheduler thread shall also tick from a `Handler` to keep jitter ≤ 2 s without alarm spam (alarm batching for ≥ 5 min ahead).
- **FR-CRON-7 (M):** Missed fires during doze shall be skipped (cron: run at most once per minute boundary catch-up; no stampede).
- **FR-CRON-8 (S):** `/status` and `usq status` shall show upcoming fire times for the next hour per schedule.

#### 3.2.5 Persistence and boot (FR-BOOT)
- **FR-BOOT-1 (M):** On device boot (`BOOT_COMPLETED`), the host shall start as a foreground service, load the registry, and auto-start `autoStart` scripts and re-register persisted schedules.
- **FR-BOOT-2 (M):** All host state (registry, dex files, logs, persisted schedules) lives in the app's private dir; nothing required for operation lives outside it (shared storage is only a drop-dir convenience).

#### 3.2.6 Documentation (FR-DOC)
- **FR-DOC-1 (M):** A **Script Writer's Manual (SWM)** shall exist covering: quickstart (working hello in ≤ 10 lines), script lifecycle, `uq.json` reference, **complete ScriptApi reference — every method with signature, behavior, error cases, and a runnable example**, cron guide (grammar + recipes), overlay/card cookbook, usage-stats cookbook, HTTP/file cookbook, Termux bridge, `usq` CLI reference, push pipeline internals, troubleshooting (MIUI battery, autostart, toggles), FAQ.
- **FR-DOC-2 (M):** Runnable example scripts in `examples/` (`hello.uq.java`, `card.uq.java`, `cron_logger.uq.java`, `usage_report.uq.java`), each verified by `usq buildpush` on the target device before each release.
- **FR-DOC-3 (S):** The host shall serve SWM pages at `GET /docs/…` (markdown); `usq docs [PAGE]` fetches and prints them; page list at `/docs/index`.
- **FR-DOC-4 (M):** Sync rule: no ScriptApi change merges without a corresponding SWM + examples update (checklist item in release process).

#### 3.2.7 Text-script tier (FR-TXT, phase 2, optional)
- **FR-TXT-1 (C):** A pure-Java interpreter (Rhino or BeanShell) may run text scripts through the same ScriptApi and registry, with a `provider` field in metadata; size budget + ≤ 1.2 MB (C1).

#### 3.2.8 Declarative Parameter Schema & Dynamic Configuration (FR-PARAM)
- **FR-PARAM-1 (M):** Scripts may declare an interactive parameter schema by overriding `ScriptSchema schema()` on `UqScript`. Schema supports strongly typed parameters:
  - `app_multiselect`: Searchable list of installed launchable apps (`Intent.CATEGORY_LAUNCHER`) with multi-select checkboxes.
  - `app_singleselect`: Single app selection from launchable applications.
  - `boolean`: Toggle switch for feature flags and behavior toggles.
  - `number`: Numeric slider (`SeekBar`) or input with configurable min, max, and default value.
  - `text`: Single-line text input for custom messages, headers, or URLs.
  - `choice`: Single-select radio choice from predefined string options.
- **FR-PARAM-2 (M):** The host shall expose the script's schema via HTTP (`GET /schema?id=...`) and persist user configuration per script in `files/scripts/<id>/args.json`.
- **FR-PARAM-3 (M):** `StartActivity` shall render a "⚙ Config" button for scripts defining a schema, dynamically generating native dark-themed configuration dialogs.
- **FR-PARAM-4 (M):** Saving configuration shall persist values to `args.json`. If the target script is currently running, the host shall invoke `onConfigChanged(Bundle newArgs)` on the script thread for real-time live updates without requiring a manual script restart.
- **FR-PARAM-5 (M):** Saved arguments shall automatically be supplied as `Bundle args` on script start (via GUI, CLI, or Boot).
- **FR-PARAM-6 (M):** The `VolumeBall` script shall implement this schema with parameters:
  - `hidelist` (`app_multiselect`): list of packages where the ball automatically hides.
  - `hideLandscape` (`boolean`): automatically hide the ball when the device is in landscape orientation.

### 3.3 Performance requirements
- **NFR-1 (M):** APK ≤ 1 MB.
- **NFR-2 (M):** Warm `buildpush` → script running or on-screen ≤ 2 s (p95); cold app start ≤ 5 s.
- **NFR-3 (M):** Cron jitter ≤ 2 s while device awake and host alive; ≤ 5 min worst-case during doze (documented).
- **NFR-4 (M):** Host steady-state RSS ≤ 60 MB with one resident card + 3 installed scripts.
- **NFR-5 (S):** Overlay card interaction latency (drag response) ≤ 1 frame.

### 3.4 Design constraints (decision records, summary)
- **ADR-1:** dex modules first (Tier B), text interpreter later — full Android API fidelity with zero new dependencies; the existing Termux javac/dx chain is the compiler.
- **ADR-2:** localhost HTTP push with header metadata (EI-1) as primary pipeline; shared-storage drop dir as fallback — both simple, both owner-risk-accepted (C5).
- **ADR-3:** targetSdk 28 / minSdk 26 (C2) — legacy storage, free exact alarms, fewer dialogs; android-all-13 compile target matches the device exactly.
- **ADR-4:** cron = spawn semantics (FR-CRON-4) + interval callbacks for resident scripts (FR-CRON-5) — crash isolation by construction.
- **ADR-5:** `UqScript` abstract class entry (EI-5) — dx-safe, no default methods.
- **ADR-6:** hand-rolled HTTP/1.1 on `ServerSocket`, header-based metadata — no dependencies (C1).
- **ADR-7:** host = foreground service from boot; MIUI autostart toggle documented as setup step (MIUI kills plain services).

### 3.5 Software system attributes
- **Reliability:** script crashes never take down the host (FR-HOST-3); host crash/restart self-heals via registry (FR-HOST-5, FR-BOOT-1).
- **Maintainability:** single-developer codebase; docs kept in the same repo; ScriptApi is the only facade — framework access outside it is allowed (script's own dex can call Android directly) but documented as "advanced".
- **Portability:** v1 targets exactly one device/build (C3); the spec still forbids APIs above API 33 compile surface.
- **Observability:** per-script logs + `/status` + `usq status/logs` (FR-API-7, FR-CRON-8).

---

## 4. Verification and acceptance criteria

| # | Criterion (testable on the device) | Traces to |
|---|---|---|
| A1 | `usq buildpush examples/hello.uq.java` compiles, pushes, and the toast appears ≤ 2 s (warm) | FR-PUSH-1, NFR-2 |
| A2 | Push a script that throws; host + other scripts keep running; `usq list` shows CRASHED with error | FR-HOST-3 |
| A3 | Push `card.uq.java` with overlay toggle on: draggable custom card over other apps; X button removes it | FR-API-1 |
| A4 | Reboot with card autoStart: card returns without user interaction (after MIUI autostart enabled) | FR-BOOT-1 |
| A5 | `card.uq.java` shows top-5 usage for 24 h matching the `usagestats` CLI output (± 1 s) | FR-API-2 |
| A6 | Schedule `*/2 * * * *` logger: fires at minute boundaries ± 2 s while awake; survives reboot | FR-CRON-1..3, NFR-3, FR-BOOT-1 |
| A7 | `api.cron("…", "x", true)` from a script: schedule visible in `usq status`; removed by `usq remove` | FR-CRON-3 |
| A8 | Stop a `Thread.sleep`-looping script: thread ends ≤ 5 s or marked STUCK; host healthy | FR-HOST-4 |
| A9 | `usq remove ID` deletes dex + registry entry; `/list` no longer contains it | EI-1 |
| A10 | Every ScriptApi method has a SWM entry with a runnable example; all `examples/` pass buildpush | FR-DOC-1, FR-DOC-2 |
| A11 | `usq docs card-cookbook` prints the page | FR-DOC-3 |
| A12 | APK ≤ 1 MB; host RSS ≤ 60 MB with card running | NFR-1, NFR-4 |
| A13 | Launcher screen lists `hello`; Start/Stop/Remove buttons work and the list reflects state after each action | FR-HOST-8 |
| A14 | Configure `VolumeBall` via `StartActivity`: select apps in `hidelist` and toggle `hideLandscape`; live update without restart (`onConfigChanged`); ball disappears in selected apps and in landscape orientation | FR-PARAM-1..6 |

---

## 5. Milestones

| Phase | Deliverable | Exit |
|---|---|---|
| **M0** | This SRS approved | owner sign-off (Passed) |
| **M1** | Shell APK + registry + HTTP + `usq` push/buildpush + hello example | A1, A2, A9 (Passed) |
| **M1.5** | Management screen (list + start/stop/remove, modern UI) | A13 (Passed) |
| **M2** | Overlay API + usage card module (residents, drag, X-stop) | A3, A5, A12 (Passed) |
| **M3** | Scheduler (cron + interval, persistence, boot) | A6, A7, A8 (Passed) |
| **M4** | SWM + examples complete; in-app docs serving | A10, A11 (Passed) |
| **M4.5** | Declarative parameter schema + dynamic GUI config + live updates | A14 |
| **M5** (optional) | Text tier (Rhino/BeanShell) | — |

---

## Appendix A — `uq.json` schema

```json
{
  "id": "card",                      // required, unique, [a-z0-9_.-]{1,32}
  "name": "Usage Card",              // display name
  "version": "0.1.0",
  "entry": "uq.mods.Card",           // required; class extends uq.script.UqScript
  "resident": true,                  // long-running (vs one-shot)
  "autoStart": true,                 // start at host boot
  "description": "24h usage card",
  "args": {"lines": 5},              // default args (JSON object)
  "schedules": [                     // static schedules (FR-CRON-2)
    {"id": "refresh", "cron": "*/5 * * * *", "args": {"reason":"cron"}},
    {"id": "tick",    "everyMs": 300000}
  ]
}
```
Header overrides on `POST /load`: `X-Uq-Id, X-Uq-Entry, X-Uq-Name, X-Uq-Version, X-Uq-Resident (1/0), X-Uq-AutoStart (1/0), X-Uq-Cron (repeatable "schedId:cron" or "schedId:everyMs"), X-Uq-Args (base64 JSON), X-Uq-Description`.

## Appendix B — Cron grammar (5-field)

```
expr      = minute SP hour SP day-of-month SP month SP day-of-week
field     = "*" | step | range | list | number
step      = term "/" n
range     = term "-" term
list      = field ("," field)*
term      = number | range
number    = 0-9{1,2}
minutes   0-59 · hours 0-23 · dom 1-31 · month 1-12 · dow 0-6 (0=Sunday)
```
- Standard semantics: dom/dow OR when both restricted; `*/n` steps; ranges and lists.
- **Second precision not supported** (v1). `@daily`-style macros not supported (v1).
- Fires are minute-granularity; catch-up skips missed minutes (FR-CRON-7).

## Appendix C — ScriptApi v1 (method table)

| Group | Method | Behavior |
|---|---|---|
| ui | `int overlayView(View v, int x, int y, int w, int h)` | adds overlay window (draggable card helper below); returns handle |
| ui | `int overlayCard(List<String> lines)` | custom-drawn draggable card (Canvas, MIUI-safe); X = self-remove |
| ui | `void removeOverlay(int handle)` | removes an overlay |
| ui | `void toast(String text)` | system-rendered toast |
| data | `List<Usage> usage24h()` | per-package aggregate, last 24 h |
| data | `List<Usage> usageDays(int days)` | N-day aggregate |
| data | `List<Usage> topPackages(int n, int days)` | top-n by total time |
| notify | `void notify(int id, String title, String text)` / `notify(id, title, text, channel)` / `void cancelNotify(int id)` | posts to channel `uq` (default, IMPORTANCE_LOW) |
| net | `Http httpGet(String url, Map<String,String> headers?)` | status + body; 15 s timeout |
| net | `Http httpPost(String url, String body, Map<String,String> headers?)` | as above, POST |
| file | `String read(String path)` / `void write(String path, String text)` / `void append(String path, String text)` / `List<String> list(String dir)` | sandboxed roots: app files, script dir, shared storage |
| sys | `void termux(String command)` | RUN_COMMAND intent to Termux |
| sys | `void vibrate(int ms)` | vibrator service |
| sched | `void every(long ms, String id, boolean persist)` | interval schedule or resident-callback (FR-CRON-5) |
| sched | `void cron(String expr, String id, boolean persist)` | cron schedule (FR-CRON-3) |
| sched | `void unschedule(String id)` | removes one of this script's schedules |
| self | `String id()` / `Bundle args()` / `File scriptDir()` | identity + invocation context |
| self | `void log(String msg)` / `String logPath()` | per-script log (FR-API-7) |
| self | `void stop()` | graceful self-termination |

`Usage` = `{String pkg; long totalMs; long lastUsedMs;}` · `Http` = `{int status; String body;}`

## Appendix D — Risk register (summary)

| Risk | Impact | Mitigation |
|---|---|---|
| MIUI kills the service despite foreground | card disappears | battery-optimization exemption + Autostart setup (documented); registry auto-restart on host relaunch |
| DexClassLoader pitfalls (wrong parent CL) | script classes not found | TCCL set to script loader during main; parent = app CL (framework classes shared) |
| Cron in doze drifts | late fires | exact alarms at targetSdk 28; documented ≤ 5 min doze bound (NFR-3) |
| Overlay permission revoked by user | clean failure | `IllegalStateException` with setup instructions (FR-API-1) |
| Log growth | disk creep | rotation at 256 KB × 2 per script (FR-API-7) |

## Appendix E — Standards compliance mapping (29148:2018)

| SRS element (this doc) | 29148 concept |
|---|---|
| §1.1–1.2 | Stakeholder needs, purpose, scope |
| §2 | Product/context description, constraints, assumptions |
| §3.1 | System/environment interfaces (external interface requirements) |
| §3.2 | Functional requirements (unique IDs, verifiable statements) |
| §3.3/3.5 | Quality/performance requirements (NFR) |
| §3.4 | Design constraints + decision records (ADRs, cf. ISO/IEC/IEEE 42010) |
| §4 | Verification criteria (requirements ↔ acceptance traceability) |
| Appendix D | Risk register (informal, project-scoped) |

*End of SRS. Next artifact: `design/SDD.md` per IEEE 1016-2009, created after M0 sign-off.*
