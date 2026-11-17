# Cookbooks & Practical Recipes

---

## 1. Floating Draggable Usage Card

Create an overlay that floats on screen and refreshes its content periodically:

```java
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import uq.script.ScriptApi;
import uq.script.UqScript;

public class MyCard extends UqScript {

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        List<String> lines = getLines(api);
        int handle = api.overlayCard(lines);

        while (!api.stopRequested()) {
            Thread.sleep(3000);
            if (api.stopRequested()) break;
            api.updateOverlayCard(handle, getLines(api));
        }
    }

    private List<String> getLines(ScriptApi api) {
        List<String> lines = new ArrayList<String>();
        lines.add("Top Apps Today:");
        for (ScriptApi.Usage u : api.topPackages(3, 1)) {
            lines.add("• " + api.appLabel(u.pkg) + ": " + (u.totalMs / 60000) + "m");
        }
        return lines;
    }
}
```

Push as a resident script:
```bash
puppet buildpush MyCard.java --resident --id mycard
```

---

## 2. Triggering Termux Commands

Scripts can execute shell commands inside the Termux environment:

```java
public class BackupToTermux extends UqScript {
    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        // Runs inside Termux bash!
        api.termux("tar -czf ~/backup_$(date +%s).tar.gz ~/study");
        api.toast("Termux backup dispatched!");
    }
}
```

---

## 3. Sandboxed File Operations

Write logs, reports, or intermediate data safely:

```java
// Write to script's private directory
File reportFile = new File(api.scriptDir(), "summary.json");
api.write(reportFile.getAbsolutePath(), "{\"status\": \"ok\"}");

// Append to private run log
api.append(reportFile.getAbsolutePath(), "\n{\"event\": \"complete\"}");

// Read back
String content = api.read(reportFile.getAbsolutePath());
```

---

## 4. Digital Wellbeing & Brutal App Blocker (v2)

Monitor foreground app usage in real time, enforce daily quotas with declarative schema and in-app configuration, deliver active usage nudge alerts (toasts + vibration), and lock down the screen with an unclosable full-screen overlay when the quota is exceeded.

Key features:
- **Declarative Schema**: Configurable directly from UQ Shell's `⚙ Config` UI modal.
- **App Whitelist & Blocklist**: Pick monitored or exempt apps via the interactive launcher app picker.
- **Custom Quotas**: Adjust default, Brave, and Lichess daily limits via sliders (5 to 240 mins).
- **Periodic Nudges**: Periodic toast and 5-second vibration alerts while in active use.
- **Strictness Modes**:
  - `brutal`: Impenetrable obsidian blocker with zero bypass until midnight.
  - `friction`: Allows a one-time 5-minute emergency grace pass per app per day by typing a strict commitment phrase (*"I will stop wasting time and get to work"*).
- **Live Countdown**: Displays hours & minutes remaining until daily quota resets at midnight.

See [`examples/blocker.uq.java`](file:///home/pk/Apps/Puppeteer/examples/blocker.uq.java).

```bash
# Push and run resident
puppet buildpush examples/blocker.uq.java --resident --id blocker --name "App Blocker"

# Check live logs
puppet logs blocker

# Stop or remove
puppet stop blocker
```

---

## 5. Notification Shade Volume Controller

A clean, non-intrusive ongoing notification in the Android shade that controls device volume without any screen overlays. Tapping the notification or action buttons collapses the shade and triggers the native volume slider panel:

See [`examples/volume_notif.uq.java`](file:///home/pk/Apps/Puppeteer/examples/volume_notif.uq.java).

```bash
# Push and run resident
puppet buildpush examples/volume_notif.uq.java --resident --id volnotif --name "Volume Control"

# Check live logs
puppet logs volnotif --tail 10

# Stop or remove
puppet stop volnotif
```
