# UQ Shell — Script Writer's Manual (SWM)

Welcome to **UQ Shell**: the personal on-device script host and executor for Android 13 / MIUI 14.

UQ Shell turns an Android application into an extensible script platform:
- The **App** holds the broad Android permissions (Overlays, Usage Stats, Notifications, Network, Boot, Alarms).
- **Scripts** are pure Java classes loaded dynamically as dex modules, executing with the host app's permissions.
- The **Control Plane** (`usq` CLI in Termux) compiles Java via `javac + dx`, pushes over loopback HTTP (`127.0.0.1:7823`), and hot-executes scripts in seconds.

---

## Documentation Index

| Guide | Description |
|---|---|
| [`quickstart`](quickstart.md) | Write, compile, and run your first script in under 10 lines |
| [`api`](api.md) | Complete `ScriptApi` reference with signatures, behavior, and examples |
| [`cron`](cron.md) | 5-field cron grammar, intervals, persistence across reboots, and recipes |
| [`cookbooks`](cookbooks.md) | Practical recipes: floating cards, usage analytics, HTTP, and Termux bridge |
| [`cli`](cli.md) | `usq` Termux CLI reference (`buildpush`, `status`, `logs`, `docs`, etc.) |
| [`troubleshooting`](troubleshooting.md) | MIUI 14 background popups, battery restrictions, and permission fixes |

---

## Quick Example

```java
import uq.script.ScriptApi;
import uq.script.UqScript;

public class Hello extends UqScript {
    @Override
    public void main(ScriptApi api, android.os.Bundle args) throws Exception {
        api.log("Hello from UQ Shell!");
        api.toast("Script running inside host process!");
    }
}
```

Push and run from Termux:
```bash
usq buildpush Hello.java
```
