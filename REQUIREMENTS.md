# 🎭 Puppeteer — System Requirements & Specifications

This document outlines the technical requirements, architecture constraints, and prerequisites for running and developing on **Puppeteer** (formerly UQ Shell).

---

## 1. Target Hardware & Platform Specification

| Attribute | Specification |
| :--- | :--- |
| **Primary Device** | Xiaomi Redmi Note 10 Pro (`M2101K6P`, codename `sweetin`) |
| **Operating System** | MIUI 14 Global (`V14.0.1.0.TKFINXM`) based on **Android 13** (API level 33) |
| **CPU Architecture** | ARM64 (`aarch64`, Qualcomm Snapdragon 732G) |
| **Privilege Model** | **Non-root**. Termux operates under UID `10291`; Puppeteer operates under UID `10332`. |
| **Host Connectivity** | SSH server (`sshd`) running inside Termux on port `8022`. USB / Wireless Debugging on port `5555`. |

---

## 2. Host Application Architecture Requirements

### 2.1 SDK Levels & Compatibility
- **`minSdkVersion: 26`** (Android 8.0 Oreo): Required for notification channels and background execution limits.
- **`targetSdkVersion: 28`** (Android 9.0 Pie):
  - Retains legacy storage model permissions without scoped-storage restrictions.
  - Allows exact alarm scheduling without restrictive user prompts.
  - Permits background foreground-service starts from broadcast receivers.

### 2.2 Resident Execution & Process Lifecycle
- **Foreground Service**: `ScriptService` runs as an Android foreground service with a persistent low-priority ongoing notification. This prevents Android's LMK (Low Memory Killer) and MIUI's aggressive battery manager from terminating daemons.
- **Process Boundaries**: All hot-loaded scripts execute within the single host process (`com.usq.shell`), running on isolated daemon threads managed by `ScriptRunner`.

### 2.3 Local Control Plane (IPC)
- **Localhost HTTP Server**: `HttpControl` binds strictly to `127.0.0.1:7823`.
- **Sequential Requests**: Single-client sequential socket handling for CLI commands (`/status`, `/load`, `/start`, `/stop`, `/logs`, `/schema`, `/config`, `/api.jar`).
- **No External Network Exposure**: The socket never binds to `0.0.0.0` or public interfaces; external access is blocked by kernel loopback routing.

### 2.4 Network Firewall (Kernel Blackhole)
- **`VpnService` Integration**: Uses Android's native `VpnService` to construct a dummy TUN interface (`10.222.0.1`) that drops outbound IP packets without cycling the physical Wi-Fi or mobile data chips.
- **Rule Separation**:
  - *Per-App Mode*: Uses `builder.addAllowedApplication(pkg)` exclusively. Unmonitored apps continue using regular network and DNS.
  - *Full-Device Mode*: Uses `builder.addDisallowedApplication("com.termux")` and `builder.addDisallowedApplication("com.usq.shell")` to ensure SSH control and ADB remain immune to the blackhole.

---

## 3. System Permissions & ADB Authorizations

Puppeteer requires elevated Android permissions that standard user apps cannot grant through regular Google Play prompts. These are granted once via ADB:

### 3.1 Background Immunity & Power Management
- **Doze Whitelist**: `dumpsys deviceidle whitelist +com.usq.shell` (prevents network drops and CPU throttling when screen is turned off).
- **Notification Policy (DND)**: `cmd notification allow_dnd com.usq.shell` (allows scripts to toggle Priority / Total Silence DND).
- **System Settings**: `appops set com.usq.shell WRITE_SETTINGS allow` (allows scripts to adjust screen brightness and screen timeout).

### 3.2 Display & Telemetry Control
- **Hardware Daltonizer (Grayscale)**: `pm grant com.usq.shell android.permission.WRITE_SECURE_SETTINGS` (allows scripts to toggle `accessibility_display_daltonizer_enabled` and `accessibility_display_daltonizer = 0`).
- **System Logcat**: `pm grant com.usq.shell android.permission.READ_LOGS` (allows reading low-latency system events).
- **All-Files Access**: `appops set com.usq.shell MANAGE_EXTERNAL_STORAGE allow` (unrestricted read/write across `/storage/emulated/0/`).
- **Usage Access**: `appops set com.usq.shell GET_USAGE_STATS allow` (allows `UsageStatsManager` foreground queries).
- **Display Over Other Apps**: `appops set com.usq.shell SYSTEM_ALERT_WINDOW allow` (allows `TYPE_APPLICATION_OVERLAY` windows).

### 3.3 Hardware Sensors & Media
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
- `ACTIVITY_RECOGNITION`, `BODY_SENSORS`
- `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `ACCESS_MEDIA_LOCATION`
- `POST_NOTIFICATIONS`, `RECORD_AUDIO`, `CAMERA`, `READ_PHONE_STATE`

---

## 4. Termux Development & Compilation Requirements

The on-device script toolchain requires the following software packages installed inside Termux:

| Package / Binary | Path | Purpose |
| :--- | :--- | :--- |
| **`openjdk-17`** | `/data/data/com.termux/files/usr/bin/javac` | Compiles `.java` script source into Java bytecode. |
| **`dx` / `dalvikvm`** | `/data/data/com.termux/files/usr/bin/dalvikvm` | Converts Java `.class` files into Android Dalvik bytecode (`.dex`). |
| **`android.jar`** | `$HOME/usq/android.jar` or `$UQD/android.jar` | Android API class stubs (Android SDK 28+). |
| **`script-api.jar`** | `$HOME/.uq/api.jar` | Puppeteer script API facade (downloaded via `http://127.0.0.1:7823/api.jar`). |
| **`curl`** | `/data/data/com.termux/files/usr/bin/curl` | HTTP client used by the `puppet` CLI to communicate with the host app. |
| **`base64`** | `/data/data/com.termux/files/usr/bin/base64` | Encodes JSON metadata headers (must use `-w 0` or newline stripping). |

---

## 5. Java Scripting Conventions & Constraints

When authoring scripts (`*.uq.java`) for Puppeteer:

1. **Java 7/8 Language Level (No Lambdas)**:
   - On-device `dx` does not support desugaring `invokedynamic`.
   - **Do NOT use Java 8 lambdas (`v -> ...`) or method references (`this::func`)**.
   - Always use standard anonymous inner classes (`new View.OnClickListener() { public void onClick(View v) { ... } }`).

2. **Non-Blocking Main Loop**:
   - Scripts run on a dedicated thread in `main(ScriptApi api, Bundle args)`.
   - Loops must periodically check `!api.stopRequested()` and include a sleep delay (`Thread.sleep(400)` to `1000`).

3. **Safe View Cleanups (`onStop`)**:
   - Scripts that attach overlays via `WindowManager.addView()` must remove all views in `onStop()` or on script exit.
   - Grayscale scripts must restore full color (`api.setGrayscale(false)`) in `onStop()` so the display never gets stuck in monochrome.

4. **Inter-Script Bus Protocol (`ScriptApi.BUS`)**:
   - Daemons share in-memory state through `ScriptApi.BUS`:
     - `blocker:limit_ms:<pkg>`: Configured daily limit in ms.
     - `blocker:used_ms:<pkg>`: Current daily usage in ms.
     - `blocker:rem_ms:<pkg>`: Remaining daily budget in ms.
     - `blocker:is_exceeded:<pkg>`: Boolean flag when daily budget is exhausted.
     - `intent:overlay_active:<pkg>`: Boolean flag when Intent dialog is visible (prevents overlay collisions).
     - `intent:start_emergency_session:<pkg>`: Timestamp token to start emergency session without prompts.
