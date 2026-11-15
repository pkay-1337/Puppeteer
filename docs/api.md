# ScriptApi Reference

The `uq.script.ScriptApi` facade is passed to `main(ScriptApi api, Bundle args)`. It provides safe, structured access to device features, UI overlays, usage statistics, background scheduling, files, and system utilities.

---

## Group 1: Self & Process Lifecycle

### `String id()`
Returns the unique script identifier string.

### `Bundle args()`
Returns invocation arguments passed via CLI (`--args`), cron triggers, or static configuration.

### `Context context()`
Returns the host Android application `Context`, granting direct access to system services and display metrics.

### `File scriptDir()`
Returns the script's private working directory (`/data/data/com.usq.shell/files/scripts/<id>/`). Fully readable and writable.

### `void log(String msg)`
Writes a timestamped entry to the script's rotating log (`files/logs/<id>.log`, rotated at 256 KB).

### `void toast(String text)`
Displays a system toast on screen. (Requires MIUI *Display pop-up windows in background* permission).

### `void vibrate(int ms)`
Triggers device haptic feedback for `ms` milliseconds.

### `void stop()`
Requests graceful self-termination. Sets `stopRequested()` to true and interrupts sleeping threads.

### `boolean stopRequested()`
Returns true if a stop request has been initiated by the host, CLI, or close button.

---

## Group 2: Overlay UI

### `boolean hasOverlayPermission()`
Checks if the `SYSTEM_ALERT_WINDOW` permission has been granted.

### `int overlayCard(List<String> lines)`
Creates and displays a floating, draggable translucent card over other applications.
- **Parameters**: `lines` — List of text strings displayed in monospace font.
- **Returns**: `int handle` identifier for updates and removal.
- **Features**: Drag-to-move, tap-to-close (`✕`) which terminates the script gracefully.

### `void updateOverlayCard(int handle, List<String> lines)`
Updates the text content of an active floating card. Safe to call from background loops.

### `int overlayView(View v, int x, int y, int w, int h)`
Adds any custom Android `View` as a floating window.

### `void removeOverlay(int handle)`
Removes a specific overlay window from the screen.

---

## Group 3: Usage Statistics

### `boolean hasUsagePermission()`
Checks if Android *Usage access* (`PACKAGE_USAGE_STATS`) has been granted.

### `List<Usage> usage24h()`
Returns per-package foreground usage statistics for the past 24 hours, sorted descending by total time.
- `Usage` object fields:
  - `pkg` (`String`): Android package name.
  - `totalMs` (`long`): Total foreground time in milliseconds.
  - `lastUsedMs` (`long`): Unix timestamp when last used.

### `List<Usage> usageToday()`
Returns per-package foreground usage statistics for the current day (since midnight `00:00:00` local time), sorted descending by total time.

### `List<Usage> topPackagesToday(int n)`
Returns the top `n` most-used packages for the current day.

### `List<Usage> usageDays(int days)`
Returns aggregated usage stats across the last `days` days.

### `List<Usage> usageRange(long startMs, long endMs)`
Returns per-package foreground usage statistics between arbitrary timestamps `startMs` and `endMs`.

### `List<Usage> topPackages(int n, int days)`
Returns the top `n` most-used packages over `days` days.

### `String appLabel(String pkg)`
Resolves an Android package name (e.g. `com.whatsapp`) to its human-readable application label (e.g. `WhatsApp`).

---

## Group 4: Scheduler (Cron & Intervals)

### `void cron(String expr, String scheduleId, boolean persist)`
Schedules the script using a 5-field cron expression (`minute hour dom month dow`).
- `persist=true`: Survives host restarts and device reboots (saved to registry).
- `persist=false`: Dies when the current host process ends.
- **Trigger behavior**: Spawns a fresh one-shot execution with `args = {trigger: "cron", scheduleId: scheduleId, firedAt: timestamp}`.

### `void every(long ms, String scheduleId, boolean persist)`
Schedules execution every `ms` milliseconds.
- If the script is marked `resident=true` and is currently running, triggers `onTick(scheduleId)` callbacks on the script without spawning a new process.

### `void unschedule(String scheduleId)`
Cancels an active schedule for this script.

---

## Group 5: Notifications

### `void notify(int id, String title, String text)`
Posts a notification to the system notification shade under the default `uq` channel.

### `void cancelNotify(int id)`
Dismisses an active notification.

---

## Group 6: Network & HTTP

### `Http httpGet(String url)`
Performs an HTTP GET request. Returns `Http` with `.status` (`int`) and `.body` (`String`).

### `Http httpPost(String url, String body)`
Performs an HTTP POST request with the given string body.

---

## Group 7: File I/O (Sandboxed)

### `String read(String path)`
Reads an entire file into a UTF-8 string. Path must be inside:
- App internal files (`/data/data/com.usq.shell/files/`)
- Script private directory (`files/scripts/<id>/`)
- Shared storage (`/storage/emulated/0/`)

### `void write(String path, String text)`
Overwrites a file with the given text.

### `void append(String path, String text)`
Appends text to a file.

### `List<String> list(String dir)`
Lists filenames in a directory.

---

## Group 8: Termux Bridge

### `void termux(String command)`
Dispatches a command to Termux via the `com.termux.RUN_COMMAND` intent.
> **Prerequisite**: Set `allow-external-apps = true` in `~/.termux/termux.properties` inside Termux.

---

## Group 9: Declarative Configuration & Schema (FR-PARAM)

Scripts can optionally declare parameters so that `StartActivity` dynamically renders configuration UI for them.

### `UqScript.schema()`
Override in your script to define parameters:
```java
@Override
public ScriptSchema schema() {
    return new ScriptSchema()
        .addAppMultiSelect("hidelist", "Hide in Apps", "Hide ball in these apps")
        .addBoolean("hideLandscape", "Hide in Landscape", "Hide when horizontal", true)
        .addNumber("size", "Ball Size", "Diameter in dp", 48, 32, 64)
        .addChoice("mode", "Operation Mode", "normal", new String[]{"normal", "aggressive"})
        .addText("customTitle", "Title", "Header text", "Default")
        .addAppMap(new ScriptSchema.AppMapBuilder("per_app_rules", "App Rules", "Custom per-app properties")
            .addNumber("limit", "App Limit", 30, 5, 240)
            .addChoice("policy", "Policy", "block", new String[]{"allow", "block", "notify"})
            .addBoolean("vibrate", "Vibrate on trigger", true));
}
```

### `UqScript.onConfigChanged(Bundle newArgs)`
Called on the script's thread when the user taps **Save** in `StartActivity`:
```java
@Override
public void onConfigChanged(Bundle newArgs) {
    boolean landscape = newArgs.getBoolean("hideLandscape", true);
    ArrayList<String> apps = newArgs.getStringArrayList("hidelist");
}
```
