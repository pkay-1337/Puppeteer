# 🎭 Puppeteer — Personal Android Automation Engine

VIBECODED

A lightweight, high-performance Android automation system and mindful focus engine designed for Android 13 / MIUI 14 (Redmi Note 10 Pro), driven via the `puppet` CLI tool in Termux and SSH.

**Core Philosophy:** The host app (`Puppeteer`) holds all system permissions and hardware hooks; standalone hot-reloadable Java scripts execute all workflows, UI overlays, and daemons without recompiling the host app.

---

## Architecture Overview

```
 ┌────────────────────────────────────────────────────────┐
 │                      ANDROID OS                        │
 ├────────────────────────────┬───────────────────────────┤
 │     Termux (u0_a291)       │   Puppeteer Host (u0_a332)│
 │  ┌──────────────────────┐  │  ┌──────────────────────┐ │
 │  │     puppet CLI       │──┼──│ Loopback HTTP Server │ │
 │  │ (javac + dx wrapper) │  │  │   (127.0.0.1:7823)   │ │
 │  └──────────────────────┘  │  └──────────┬───────────┘ │
 │                            │             ▼             │
 │                            │  ┌──────────────────────┐ │
 │                            │  │    ScriptRunner      │ │
 │                            │  │   (DexClassLoader)   │ │
 │                            │  └──────────┬───────────┘ │
 │                            │             ▼             │
 │                            │  ┌──────────────────────┐ │
 │                            │  │   ScriptApi.BUS      │ │
 │                            │  │ (Inter-Script Bridge)│ │
 │                            │  └──────────┬───────────┘ │
 │                            │             ▼             │
 │                            │  ┌──────────────────────┐ │
 │                            │  │  Active Daemons (6)  │ │
 │                            │  └──────────────────────┘ │
 └────────────────────────────┴───────────────────────────┘
```

---

## Active Daemons Suite

| Daemon ID | Script Name | Key Capabilities |
| :--- | :--- | :--- |
| `blocker` | [App Blocker v3](file:///home/pk/Apps/Puppeteer/examples/blocker.uq.java) | Daily cumulative limits (per-app), periodic usage nudges (5m/10m), 2-tap intentional emergency 5-min unlock passes, shared budget bus telemetry. |
| `intent_session` | [Intent Sessions](file:///home/pk/Apps/Puppeteer/examples/intent_session.uq.java) | Mindful app launch interceptor: asks for goal and duration. Pinned live floating countdown HUD (`🎯 Goal · ⏳ MM:SS`). Capped at daily remaining budget with extensions up to daily balance. |
| `grayscale` | [Dynamic Grayscale](file:///home/pk/Apps/Puppeteer/examples/grayscale.uq.java) | Hardware daltonizer switcher: instantly flips display to 100% monochrome when monitored apps are open; immediately restores color on exit. |
| `firewall_ball` | [Firewall Ball](file:///home/pk/Apps/Puppeteer/examples/firewall_ball.uq.java) | Draggable 44dp floating ball on monitored apps (🔴 Red = blocked, 🟢 Green = allowed). Single tap toggles internet access in ~5ms via local VPN blackhole. Red apps stay firewalled in background. |
| `bedtime` | [Bedtime Firewall](file:///home/pk/Apps/Puppeteer/examples/bedtime_firewall.uq.java) | 10:00 PM to 8:00 AM full-device internet blackhole with a 15-minute advance popup overlay warning at 9:45 PM. Preserves SSH access to Termux. |
| `volnotif` | [Volume Notif](file:///home/pk/Apps/Puppeteer/examples/volume_notif.uq.java) | Persistent/floating volume slider and ringer mode dialog. |

---

## Project Structure

```
Puppeteer/
├── README.md                      ← Master documentation
├── REQUIREMENTS.md                ← Prerequisites, environment & permissions guide
├── apk/                           ← Host application source code
│   ├── AndroidManifest.xml        ← Permissions, services, receivers
│   ├── build_all.sh               ← Termux automated build pipeline (javac + dx + aapt + apksigner)
│   └── src/
│       ├── com/usq/shell/
│       │   ├── StartActivity.java ← Management UI with permission health & logs viewer
│       │   ├── UqVpnService.java  ← On-device network blackhole TUN interface
│       │   ├── HttpControl.java   ← 127.0.0.1:7823 REST IPC server
│       │   ├── ScriptService.java ← Foreground resident execution engine
│       │   └── ScriptRunner.java  ← DexClassLoader isolation & lifecycle
│       └── uq/script/
│           ├── ScriptApi.java     ← Script facade: overlays, usage stats, firewall, grayscale, BUS
│           └── ScriptSchema.java  ← Declarative parameters schema builder
├── examples/                      ← Production Java scripts
│   ├── blocker.uq.java            ← App Blocker v3
│   ├── intent_session.uq.java     ← Integrated Intent Sessions
│   ├── grayscale.uq.java          ← Dynamic Grayscale daemon
│   ├── firewall_ball.uq.java      ← Floating interactive firewall ball
│   ├── bedtime_firewall.uq.java   ← Bedtime internet blocker
│   ├── volume_notif.uq.java       ← Volume dialog
│   └── usage_report.uq.java       ← 24-hour analytics report
├── release/                       ← Distributable assets
│   ├── Puppeteer-v0.7.0.apk       ← Production signed APK
│   ├── setup_termux.sh            ← Automated Termux toolchain setup
│   ├── setup_permissions.sh       ← One-click ADB permission grant script
│   └── dex/                       ← Pre-compiled .dex binaries for all scripts
│       ├── blocker.dex
│       ├── intent_session.dex
│       ├── grayscale.dex
│       ├── firewall_ball.dex
│       ├── bedtime.dex
│       └── volnotif.dex
└── tools/                         ← Control scripts
    ├── puppet                     ← Termux CLI control tool
    └── setup_termux.sh            ← Automated setup script
```

---

## One-Click Setup & Installation

### 1. Install APK
Connect device via USB/Wireless Debugging:
```bash
adb install -r release/Puppeteer-v0.7.0.apk
```

### 2. Grant Privileges
Run the automated script to grant Doze whitelist, Secure Settings, and special permissions:
```bash
cd release && ./setup_permissions.sh
```

### 3. Deploy Daemons
From Termux on your phone:
```bash
# Push precompiled .dex or build directly from .java source:
puppet buildpush examples/blocker.uq.java --resident --id blocker --name "App Blocker"
puppet buildpush examples/intent_session.uq.java --resident --autostart --id intent_session --name "Intent Sessions"
puppet buildpush examples/grayscale.uq.java --resident --autostart --id grayscale --name "Dynamic Grayscale"
puppet buildpush examples/firewall_ball.uq.java --resident --autostart --id firewall_ball --name "Firewall Ball"
puppet buildpush examples/bedtime_firewall.uq.java --resident --autostart --id bedtime --name "Bedtime Firewall"
```

---

## Management CLI (`puppet`)

```bash
puppet status                 # View uptime and all running daemons
puppet logs <id> [tail]       # Stream script logs (e.g. puppet logs intent_session 30)
puppet stop <id>              # Stop a running script
puppet start <id> [--args]    # Start script with custom parameters
puppet remove <id>            # Unload and remove script
```
