# Puppeteer System Developer & Engineering Guide

Puppeteer is a split-plane Android automation and mindful focus runtime. It consists of:
1. **Host Application (`apk/`)**: A persistent Android foreground service (`com.usq.shell`) exposing privileged system capabilities, floating overlays, an on-device VPN blackhole, hardware grayscale, and an embedded HTTP REST control server at `127.0.0.1:7823`.
2. **Dynamic Script Engine (`examples/*.uq.java`)**: Independent Java scripts compiled on-device into Dalvik bytecode (`.dex`), loaded dynamically into sandboxed `DexClassLoader` instances, and controlled via the `puppet` CLI in Termux.

---

## 1. Script Development Guide (`*.uq.java`)

All custom automations, focus daemons, and system interceptors are written as single-file Java scripts located in [`examples/`](file:///home/pk/uq-shell/examples/).

### Crucial Language Constraints
> [!CAUTION]
> **No Lambdas or Method References!**
> The on-device compilation toolchain uses Dalvik `dx.jar`, which does **not** desugar `invokedynamic`. 
> - **Do NOT use**: `() -> { ... }`, `list.forEach(...)`, `Class::method`.
> - **Always use**: Standard anonymous inner classes (e.g. `new Runnable() { public void run() { ... } }`, `new Comparator<T>() { public int compare(...) { ... } }`).
> - Keep source target compatibility to Java 8 (`-source 8 -target 8`).

### Script Architecture & Skeleton
Every script must contain a class extending `uq.script.UqScript`:

```java
package uq.script;

import android.os.Bundle;
import java.util.Map;
import java.util.List;

public class MyDaemon extends UqScript {

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        api.log("MyDaemon started with ID: " + api.id());

        // Background loop pattern
        while (!api.stopRequested()) {
            // Do periodic work...

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break; // Graceful exit on interrupt
            }
        }
        api.log("MyDaemon exiting cleanly.");
    }

    @Override
    public void onStop() {
        // Called when a stop request has been initiated
    }

    @Override
    public void onTick(String scheduleId) {
        // Called when an interval or cron tick fires for a resident script
    }

    @Override
    public ScriptSchema schema() {
        // Optional: Expose dynamic schema for StartActivity UI
        return null;
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        // Called live when parameters are adjusted in StartActivity
    }
}
```

### Core `ScriptApi` Capabilities
The `api` facade provides access to device systems:

| Capability Group | Key Methods | Description |
| :--- | :--- | :--- |
| **Lifecycle & Logging** | `api.id()`, `api.args()`, `api.log(msg)`, `api.toast(txt)`, `api.vibrate(ms)`, `api.stop()`, `api.stopRequested()` | Process identity, rotating log storage (`files/logs/<id>.log`), haptics, and cooperative termination. |
| **State Bus (Inter-Script)** | `api.busSet(key, val)`, `api.busGet(key)`, `api.busGetLong(key, def)`, `api.busGetBool(key, def)` | Shared in-memory event bus. Used by scripts (e.g. `blocker` publishes remaining daily budget for `intent_session` to read). |
| **App Usage Telemetry** | `api.hasUsagePermission()`, `api.usageToday()`, `api.usage24h()`, `api.topPackagesToday(n)`, `api.appLabel(pkg)` | Queries `UsageStatsManager` for foreground duration and last active package. |
| **System Overlays & HUDs** | `api.hasOverlayPermission()`, `api.overlayCard(lines)`, `api.updateOverlayCard(handle, lines)`, `api.overlayView(view, x, y, w, h)`, `api.removeOverlay(handle)`, `api.cleanup()` | Spawns draggable floating translucent cards, countdown timers, or interactive widgets (`SYSTEM_ALERT_WINDOW`). |
| **Network Firewall** | `api.isFirewallPrepared()`, `api.blockInternet(seconds)`, `api.blockAppsInternet(packages, seconds)`, `api.restoreInternet()` | Activates `UqVpnService` to blackhole routing per package or system-wide without touching Termux or port 8022. |
| **Hardware Grayscale** | `api.hasWriteSecureSettings()`, `api.setGrayscale(true/false)`, `api.isGrayscale()` | Flips hardware display daltonizer between full color and monochrome mode. |
| **Scheduler** | `api.cron(expr, id, persist)`, `api.every(ms, id, persist)`, `api.unschedule(id)` | Schedules background cron jobs or interval heartbeats. |
| **File I/O & Networking** | `api.read(path)`, `api.write(path, txt)`, `api.httpGet(url)`, `api.httpPost(url, body)` | Sandboxed file system operations and HTTP client. |

### Compiling and Deploying Scripts
From Termux on the phone:
```bash
# Push and compile .java into Dalvik bytecode, then launch immediately:
puppet buildpush examples/my_script.uq.java --name "My Script" --resident --autostart

# Pass runtime JSON arguments:
puppet buildpush examples/my_script.uq.java --args '{"threshold_mins":15}'

# Inspect runtime logs:
puppet logs my_script 50
```

---

## 2. Modifying the Host APK (`apk/`)

When adding new hardware integrations, Android system listeners, or updating the UI, modifications belong in [`apk/`](file:///home/pk/uq-shell/apk/).

### Source Code Map
```
apk/
├── AndroidManifest.xml        ← Permissions, services, broadcast receivers, and app identity
├── build_all.sh               ← 3-stage automated build pipeline (Termux + proot)
├── build.sh                   ← Stage 1: javac + script-api.jar + dx.jar
├── assemble.sh                ← Stage 2: aapt resource packaging inside proot
└── src/
    ├── com/usq/shell/
    │   ├── StartActivity.java ← Management UI: permission cards, active scripts, config schema editor, log viewer
    │   ├── ScriptService.java ← Android Foreground Service managing resident scripts, wakelocks, and state
    │   ├── ScriptRunner.java  ← Dynamic DexClassLoader manager, thread pools, and script lifecycle runner
    │   ├── HttpControl.java   ← NanoHTTPD REST control plane listening on 127.0.0.1:7823
    │   ├── UqVpnService.java  ← Local TUN blackhole firewall with bypass rules for Termux (SSH :8022)
    │   ├── Scheduler.java     ← AlarmManager cron and interval dispatcher
    │   ├── BootReceiver.java  ← Auto-starts resident daemons upon device reboot
    │   └── AlarmReceiver.java ← Receives AlarmManager triggers
    └── uq/script/
        ├── ScriptApi.java     ← Main API facade exposed to scripts (exported to script-api.jar)
        ├── ScriptSchema.java  ← Declarative parameter types (String, Int, Boolean, AppMap)
        ├── UqScript.java      ← Base class for dynamic scripts
        └── Logger.java        ← File logger with 256 KB rotation per script
```

### Guide: Adding a New System API
To expose a new Android system capability to dynamic scripts:
1. **Declare Permissions**: Add required permissions to [`apk/AndroidManifest.xml`](file:///home/pk/uq-shell/apk/AndroidManifest.xml) and add the grant command to [`release/setup_permissions.sh`](file:///home/pk/uq-shell/release/setup_permissions.sh).
2. **Implement in Host Service**: If it requires application context or background services, implement the logic in [`apk/src/com/usq/shell/ScriptService.java`](file:///home/pk/uq-shell/apk/src/com/usq/shell/ScriptService.java) or [`apk/src/uq/script/ScriptApi.java`](file:///home/pk/uq-shell/apk/src/uq/script/ScriptApi.java).
3. **Expose on `ScriptApi`**: Add public methods to [`apk/src/uq/script/ScriptApi.java`](file:///home/pk/uq-shell/apk/src/uq/script/ScriptApi.java).
4. **Regenerate `script-api.jar`**:
   The scripts compile against `script-api.jar`. When `ScriptApi` is modified, recompile the jar or rebuild the APK so `~/.uq/api.jar` stays updated.

---

## 3. Building & Installing the Host APK

### Method A: Build from Source on Device (Termux + Proot)
Puppeteer has an on-device build system that runs without a PC:
```bash
cd ~/Puppeteer/apk
bash build_all.sh
```
* **Stage 1 (`build.sh`)**: Runs `javac` (targeting Java 8) and `dx` in Termux, creating `classes.dex` and `script-api.jar`.
* **Stage 2 (`assemble.sh`)**: Uses `proot` (Ubuntu 22.04 environment) with `aapt` to package the APK against the device's native `framework-res.apk`.
* **Stage 3 (`apksigner`)**: Signs the APK with `~/uq-shell.keystore` and outputs `uq-shell.apk` to `/storage/emulated/0/Download/`.
* Install the resulting APK:
  ```bash
  adb install -r /storage/emulated/0/Download/UQShell.apk
  ```

### Method B: Install Pre-Built Binary via ADB
A signed production APK is pre-compiled and maintained in [`release/Puppeteer-v0.7.0.apk`](file:///home/pk/uq-shell/release/Puppeteer-v0.7.0.apk).

1. **Install APK**:
   ```bash
   adb install -r release/Puppeteer-v0.7.0.apk
   ```
2. **Grant System Permissions**:
   ```bash
   bash release/setup_permissions.sh
   ```
   This grants:
   - Battery Doze whitelist (`dumpsys deviceidle whitelist +com.usq.shell`)
   - `WRITE_SECURE_SETTINGS` (Grayscale control)
   - `READ_LOGS`
   - AppOps: `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`, `WRITE_SETTINGS`, `MANAGE_EXTERNAL_STORAGE`
   - DND Policy: `cmd notification allow_dnd com.usq.shell`
   - Location, Sensors, Media, and Notification permissions
3. **Launch the Application**:
   ```bash
   adb shell am start -n com.usq.shell/.StartActivity
   ```

---

## 4. Git & Sync Workflow

The repository is hosted at `git@github.com:pkay-1337/Puppeteer.git`.

### Termux on Phone
- Working directory: `~/Puppeteer` (symlinked from `~/uq-shell`).
- Authenticated via SSH key at `~/.ssh/id_ed25519`.
- Commit and push changes directly from phone:
  ```bash
  cd ~/Puppeteer
  git add -A
  git commit -m "feat: Add new focus automation script"
  git push origin main
  ```

### Workstation / PC
- Working directory: `/home/pk/Puppeteer` (or `/home/pk/uq-shell`).
- Keep in sync with phone changes:
  ```bash
  cd /home/pk/Puppeteer
  git pull origin main
  ```

---

## 5. Control CLI (`puppet`) Reference

The `puppet` CLI script ([`tools/puppet`](file:///home/pk/uq-shell/tools/puppet)) is installed into Termux's `$PREFIX/bin/puppet`.

```
puppet status                       # Show system status, uptime, running scripts & daemons
puppet list                         # Detailed JSON dump of installed scripts
puppet buildpush <FILE.java> [args] # Compile in Termux (javac+dx), push bytecode, and start
puppet push <FILE.dex> [args]       # Push raw compiled .dex bytecode
puppet start <ID>                   # Start installed script
puppet stop <ID>                    # Stop running script gracefully
puppet remove <ID>                  # Unregister and delete script from registry
puppet logs <ID> [lines]            # Tail script log file
puppet api                          # Pull fresh script-api.jar from host app to ~/.uq/api.jar
puppet docs [PAGE]                  # Read embedded documentation
```
