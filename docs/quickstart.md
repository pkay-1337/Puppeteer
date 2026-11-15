# Quickstart Guide

Get your first UQ Shell script running on your device in under 2 minutes.

---

## 1. Anatomy of a Script

Every UQ Shell script is a standard Java class extending `uq.script.UqScript`:

```java
import android.os.Bundle;
import uq.script.ScriptApi;
import uq.script.UqScript;

public class MyScript extends UqScript {

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        // Runs on its own dedicated worker thread with the app's full permissions
        api.log("MyScript executed!");
        api.toast("Hello from UQ Shell!");
    }

    @Override
    public void onStop() {
        // Optional: invoked when stop is requested
    }

    @Override
    public void onTick(String scheduleId) {
        // Optional: invoked on periodic intervals for resident scripts
    }
}
```

> **Rules**:
> - Use standard Java 7 / 8 syntax. Avoid `invokedynamic` (lambdas) as Android's `dx` does not desugar them on-device.
> - The class name should match the filename (e.g., `class MyScript` in `MyScript.java` or `MyScript.uq.java`).

---

## 2. One-Command Build and Push

From Termux on your phone, run:

```bash
usq buildpush MyScript.java
```

`usq buildpush` automatically:
1. Compiles your Java file against `android.jar` and `api.jar` using Termux's `javac`.
2. Translates bytecode into Dalvik/ART executable format (`dex`) via `dx`.
3. Pushes the `.dex` payload over loopback HTTP to `http://127.0.0.1:7823/load`.
4. Hot-starts execution inside the host app process.

---

## 3. Checking Results

View live script logs:
```bash
usq logs myscript
```

Check host status:
```bash
usq status
```

Or open the **UQ Shell** management screen on your phone to view status chips, live logs, and start/stop buttons.
