# Troubleshooting & MIUI 14 Quirks

Xiaomi / MIUI 14 devices enforce strict non-standard background restrictions. This guide explains how to configure them for reliable operation.

---

## 1. Background Pop-Up Windows (Toasts & Overlays)

By default, MIUI silently drops `Toast.show()` calls from background services.
- **Symptom**: `api.toast(...)` does not appear on screen even though the script runs.
- **Fix**:
  1. Open **Settings → Apps → Manage apps → UQ Shell**.
  2. Tap **Other permissions**.
  3. Set **"Display pop-up windows while running in the background"** (后台弹出界面) to **Allow**.
  4. Ensure **"Display over other apps"** (悬浮窗) is also set to **Allow**.

---

## 2. MIUI Autostart & Battery Optimization

MIUI kills background services when the screen is off unless autostart is granted.
- **Fix**:
  1. In **Settings → Apps → Manage apps → UQ Shell**:
  2. Enable **Autostart** (toggle on).
  3. Under **Battery saver**, select **No restrictions**.

---

## 3. Usage Access (24h Stats)

- **Symptom**: `hasUsagePermission()` returns `false` or `usage24h()` throws `IllegalStateException`.
- **Fix**:
  1. Open the UQ Shell app screen.
  2. Tap the **Grant** button on the purple Usage Access banner, or go to:
     **Settings → Privacy → Special app access → Usage access → UQ Shell → Permit**.

---

## 4. Resetting Staged Builds

If a build cache in Termux becomes corrupted:
```bash
rm -rf ~/.uq/build
```
And re-run `usq buildpush <file>.java`.
