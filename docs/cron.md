# Scheduler & Cron Guide

UQ Shell includes a built-in scheduler that supports standard **5-field cron expressions** and **fixed intervals**, backed by Android's `AlarmManager.setExactAndAllowWhileIdle` for battery-aware wakeup and persistence across reboots.

---

## 5-Field Cron Grammar

```text
┌───────────── minute (0 - 59)
│ ┌───────────── hour (0 - 23)
│ │ ┌───────────── day of the month (1 - 31)
│ │ │ ┌───────────── month (1 - 12)
│ │ │ │ ┌───────────── day of the week (0 - 6) (Sunday = 0 or 7)
│ │ │ │ │
* * * * *
```

### Supported Syntax

- `*` : Matches any value in the field.
- `,` : Value list divider (e.g. `0,15,30,45`).
- `-` : Range of values (e.g. `1-5` for Monday through Friday).
- `/` : Step values (e.g. `*/5` for every 5 minutes, `10-30/2`).

---

## Cron Recipes

| Schedule | Expression | Description |
|---|---|---|
| Every minute | `* * * * *` | Fires at the start of every minute (:00) |
| Every 2 minutes | `*/2 * * * *` | Fires at even minutes (:00, :02, :04, ...) |
| Every 15 minutes | `*/15 * * * *` | Fires at :00, :15, :30, :45 |
| Hourly | `0 * * * *` | Fires at the beginning of every hour |
| Daily at 9:00 AM | `0 9 * * *` | Fires every day at 09:00:00 |
| Weekdays at 18:30 | `30 18 * * 1-5` | Fires Monday to Friday at 6:30 PM |
| Midnight Sunday | `0 0 * * 0` | Fires once a week on Sunday at midnight |

---

## Registering Schedules

### 1. Via CLI (`usq buildpush`)
Pass `--cron <scheduleId>:<expr>`:
```bash
usq buildpush Backup.java --cron daily:"0 2 * * *"
```

### 2. At Runtime via `ScriptApi`
```java
// Schedule a persistent daily backup
api.cron("0 2 * * *", "nightly_backup", true);

// Schedule a 30-second interval
api.every(30000, "fast_tick", false);
```

### 3. Inspecting Active Schedules
Run `usq status` to see all armed schedules and their upcoming fire countdowns:
```bash
usq status
```
```json
"schedules": [
  {
    "scriptId": "backup",
    "scheduleId": "nightly_backup",
    "cron": "0 2 * * *",
    "persist": true,
    "nextFireInSec": 32140
  }
]
```
