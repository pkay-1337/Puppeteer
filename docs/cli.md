# `usq` CLI Reference

`usq` is the Termux-side command-line interface used to manage, build, push, and inspect UQ Shell scripts.

---

## Command Overview

```bash
usq status | list
usq buildpush <file.java> [options...]
usq push <file.dex> [options...]
usq start <id> [--args '<json>']
usq stop <id>
usq remove <id>
usq logs <id> [tail]
usq docs [page]
```

---

## Commands

### `usq status`
Prints the overall host status, uptime, app UID, active running scripts, and all armed cron schedules with remaining seconds until next fire.

### `usq list`
Returns the full registry of installed scripts and their metadata (id, name, version, state, resident, autoStart, schedules, lastRunAt).

### `usq buildpush <file.java>`
Compiles a `.java` or `.uq.java` file using Termux's `javac` and `dx`, uploads it over loopback HTTP, and starts execution.
- **Options**:
  - `--id <string>`: Custom script ID (defaults to lowercase class name).
  - `--name <string>`: Display name in the GUI.
  - `--resident`: Marks the script as long-running (won't be marked CRASHED on loop).
  - `--autostart`: Launches automatically when UQ Shell starts or reboots.
  - `--desc <string>`: Description text.
  - `--cron <id>:<expr>`: Declares a cron schedule (e.g. `--cron tick:"*/5 * * * *"`).
  - `--args '<json>'`: Default execution arguments as a JSON string.
  - `--no-start`: Installs without immediately triggering a run.

### `usq start <id>`
Starts execution of an installed script.
- Optional: `--args '<json>'` to pass dynamic execution arguments.

### `usq stop <id>`
Requests graceful termination (`api.stop()`, interrupt, `onStop()`).

### `usq remove <id>`
Stops the script, deletes its `.dex` payload, removes persisted schedules, and deletes the registry entry.

### `usq logs <id> [tail]`
Prints recent log output for the script (defaults to last 100 lines).

### `usq docs [page]`
Fetches and displays documentation directly from the running app (`usq docs api`, `usq docs cron`, `usq docs cookbooks`).
