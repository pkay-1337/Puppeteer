import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * one_piece.uq.java — Smart Weekly One Piece Manga Release Monitor & Native Notifier.
 *
 * Rules:
 *   - Active Window: Thursday 00:00 through Saturday 23:59.
 *   - Weekly Release Logic:
 *     - Once Thursday begins, checks TCB Scans every 30 minutes until the new chapter drops.
 *     - As soon as the chapter is released and notified, checking STOPS for the rest of
 *       the week and sleeps until next Thursday.
 *     - If Saturday ends (Sunday 00:00) with no release (e.g. Oda break week or holiday),
 *       checking pauses and sleeps until next Thursday.
 *   - Dormant Window: Sunday, Monday, Tuesday, Wednesday (zero battery / network polling).
 *   - Native Android Notification:
 *     - High priority heads-up notification with chapter title and click-to-read PendingIntent.
 *     - Custom celebratory haptic vibration pattern and toast.
 *   - Persistent State: Tracks target chapter, released week, and year across device reboots.
 */
public class OnePiece extends UqScript {

    private static final String TCB_BASE_URL = "https://tcbonepiecechapters.com";
    private static final String MANGA_URL = "https://tcbonepiecechapters.com/mangas/5/one-piece";
    private static final String NOTIF_CHANNEL_ID = "one_piece_releases";
    private static final int NOTIF_ID = 1199;
    private static final String STATE_FILE = "/storage/emulated/0/Documents/Puppeteer/one_piece_tracker.txt";

    // Configurable parameters
    private int targetChapter = 1199;
    private int checkIntervalMins = 30;
    private boolean vibrateOnRelease = true;
    private String windowMode = "thu_to_sat"; // "thu_to_sat" or "everyday"

    // Runtime state
    private int lastReleasedWeek = -1;
    private int lastReleasedYear = -1;
    private long lastCheckTime = 0L;
    private String lastCheckedStatus = "Initialized";
    private long lastDormantLogTime = 0L;

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addNumber("target_chapter", "Target Chapter to Monitor",
                        "The chapter number to monitor for release", 1199, 1000, 2500)
                .addNumber("check_interval_mins", "Check Interval (Minutes)",
                        "Polling frequency in minutes during active window (default 30 mins)", 30, 5, 240)
                .addChoice("window_mode", "Schedule Window",
                        "When to monitor for releases (Thu-Sat recommended)", "thu_to_sat",
                        new String[]{"thu_to_sat", "everyday"})
                .addBoolean("vibrate_on_release", "Vibrate Phone on Release",
                        "Strong haptic pattern alert when a new chapter drops", true);
    }

    @Override
    public void onConfigChanged(Bundle newArgs) {
        applyConfig(newArgs);
    }

    private void applyConfig(Bundle b) {
        if (b == null) return;
        targetChapter = b.getInt("target_chapter", targetChapter);
        checkIntervalMins = b.getInt("check_interval_mins", checkIntervalMins);
        vibrateOnRelease = b.getBoolean("vibrate_on_release", vibrateOnRelease);
        String wm = b.getString("window_mode");
        if (wm != null && wm.length() > 0) {
            windowMode = wm.toLowerCase();
        }
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        api.log("one_piece: starting Smart Weekly One Piece Chapter Monitor daemon");
        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("one_piece error: Context unavailable");
            return;
        }

        // Initialize tracked chapter and weekly state from persistent storage
        initTrackerState(api);

        api.log("one_piece: initialized target Chapter " + targetChapter + " [Window: " + windowMode + ", Interval: " + checkIntervalMins + "m]");
        api.toast("🏴‍☠️ One Piece Monitor active (Target: " + targetChapter + ")");

        while (!api.stopRequested()) {
            Calendar nowCal = Calendar.getInstance();
            boolean isWindowActive = isMonitoringWindowActive(nowCal);
            boolean alreadyReleasedThisWeek = isAlreadyReleasedThisWeek(nowCal);

            if (isWindowActive && !alreadyReleasedThisWeek) {
                // ACTIVE POLLING: Thursday through Saturday, target not yet released
                try {
                    checkForNewChapter(ctx, api, nowCal);
                } catch (Throwable t) {
                    api.log("one_piece: check failed: " + t.getMessage());
                    lastCheckedStatus = "Error: " + t.getMessage();
                }

                // If just released, we won't poll again this week
                if (isAlreadyReleasedThisWeek(nowCal)) {
                    long untilThu = getMillisUntilNextThursday(nowCal);
                    api.log("one_piece: 🎉 Chapter released! Checks paused until next Thursday (" + formatDuration(untilThu) + ").");
                    sleepInterruptible(api, Math.min(3600000L, untilThu));
                    continue;
                }

                // Normal interval sleep between checks
                sleepInterruptible(api, checkIntervalMins * 60 * 1000L);

            } else {
                // DORMANT: Sunday–Wednesday OR already released this week
                long untilThu = getMillisUntilNextThursday(nowCal);
                long nowMs = System.currentTimeMillis();

                // Periodic status log every 30 minutes when dormant
                if (nowMs - lastDormantLogTime > 1800000L) {
                    lastDormantLogTime = nowMs;
                    String reason = alreadyReleasedThisWeek
                            ? "This week's chapter already released."
                            : "Outside release window (Sun-Wed or Break Week).";
                    api.log("one_piece: [Dormant] " + reason + " Next active scan on Thursday 12:00 AM (in " + formatDuration(untilThu) + ").");

                    ScriptApi.BUS.put("one_piece:status", "dormant");
                    ScriptApi.BUS.put("one_piece:next_window_ms", Long.valueOf(nowMs + untilThu));
                }

                // Sleep in safe 10-minute slices to stay responsive and clock-synced
                long sliceMs = Math.min(600000L, untilThu);
                sleepInterruptible(api, sliceMs);
            }
        }

        api.log("one_piece: monitor daemon stopped");
    }

    private boolean isMonitoringWindowActive(Calendar cal) {
        if ("everyday".equals(windowMode)) return true;
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        // Active: Thursday (5), Friday (6), Saturday (7)
        return (dow == Calendar.THURSDAY || dow == Calendar.FRIDAY || dow == Calendar.SATURDAY);
    }

    private boolean isAlreadyReleasedThisWeek(Calendar cal) {
        if ("everyday".equals(windowMode)) return false;
        int currentWeek = cal.get(Calendar.WEEK_OF_YEAR);
        int currentYear = cal.get(Calendar.YEAR);
        return (lastReleasedWeek == currentWeek && lastReleasedYear == currentYear);
    }

    private long getMillisUntilNextThursday(Calendar now) {
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, 0);
        target.set(Calendar.MINUTE, 0);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        // Advance day by day until it's Thursday and strictly in the future
        while (target.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY || !target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return Math.max(60000L, target.getTimeInMillis() - now.getTimeInMillis());
    }

    private static String formatDuration(long ms) {
        long hours = ms / 3600000L;
        long mins = (ms % 3600000L) / 60000L;
        long days = hours / 24L;
        long remHours = hours % 24L;
        if (days > 0) {
            return days + "d " + remHours + "h";
        }
        return hours + "h " + mins + "m";
    }

    private void sleepInterruptible(ScriptApi api, long waitMs) {
        long start = System.currentTimeMillis();
        while (!api.stopRequested() && (System.currentTimeMillis() - start < waitMs)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private void initTrackerState(ScriptApi api) {
        try {
            File sf = new File(STATE_FILE);
            if (sf.exists()) {
                String saved = api.read(STATE_FILE);
                if (saved != null && !saved.trim().isEmpty()) {
                    saved = saved.trim();
                    if (saved.startsWith("{")) {
                        JSONObject json = new JSONObject(saved);
                        int last = json.optInt("last_chapter", 1198);
                        targetChapter = last + 1;
                        lastReleasedWeek = json.optInt("released_week", -1);
                        lastReleasedYear = json.optInt("released_year", -1);
                        api.log("one_piece: restored JSON state target=" + targetChapter +
                                " (lastReleasedWeek=" + lastReleasedWeek + ", year=" + lastReleasedYear + ")");
                        return;
                    } else {
                        int last = Integer.parseInt(saved);
                        if (last >= 1000) {
                            targetChapter = last + 1;
                            api.log("one_piece: restored legacy state target=" + targetChapter);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: check legacy ubuntu22-fs root .last_chapter
        try {
            File legacy = new File("/data/data/com.termux/files/home/ubuntu22-fs/root/.last_chapter");
            if (legacy.exists() && legacy.canRead()) {
                byte[] b = new byte[64];
                java.io.FileInputStream fis = new java.io.FileInputStream(legacy);
                int n = fis.read(b);
                fis.close();
                if (n > 0) {
                    String str = new String(b, 0, n).trim();
                    int last = Integer.parseInt(str);
                    if (last >= 1000) {
                        targetChapter = last + 1;
                        api.log("one_piece: imported legacy state from ubuntufs .last_chapter: " + targetChapter);
                        saveTrackerState(api, last, -1, -1);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        api.log("one_piece: initialized default target chapter " + targetChapter);
    }

    private void saveTrackerState(ScriptApi api, int lastChapter, int week, int year) {
        try {
            File parent = new File(STATE_FILE).getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            JSONObject json = new JSONObject();
            json.put("last_chapter", lastChapter);
            json.put("released_week", week);
            json.put("released_year", year);
            json.put("updated_at", System.currentTimeMillis());
            api.write(STATE_FILE, json.toString());
        } catch (Throwable t) {
            api.log("one_piece: saveTrackerState failed: " + t);
        }
    }

    private void checkForNewChapter(Context ctx, ScriptApi api, Calendar nowCal) throws Exception {
        lastCheckTime = System.currentTimeMillis();
        String timeStr = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date(lastCheckTime));
        api.log("one_piece: [" + timeStr + "] Fetching TCB Scans to check Chapter " + targetChapter + "...");

        String html = fetchHtml(MANGA_URL);
        if (html == null || html.isEmpty()) {
            lastCheckedStatus = "Empty response";
            return;
        }

        // Search for chapter presence: "chapter-XXXX" or "Chapter XXXX"
        String chapterKey = "chapter-" + targetChapter;
        boolean isReleased = html.contains(chapterKey) || html.contains("Chapter " + targetChapter);

        if (isReleased) {
            api.log("one_piece: 🚨 CHAPTER " + targetChapter + " IS OUT! Extracting details...");

            // Extract direct chapter link and title
            String chapterUrl = extractChapterUrl(html, targetChapter);
            String chapterTitle = extractChapterTitle(html, targetChapter);

            // Post native Android heads-up notification with browser PendingIntent
            postChapterNotification(ctx, api, targetChapter, chapterTitle, chapterUrl);

            // Record release week and year so checks pause until next Thursday
            int currentWeek = nowCal.get(Calendar.WEEK_OF_YEAR);
            int currentYear = nowCal.get(Calendar.YEAR);
            lastReleasedWeek = currentWeek;
            lastReleasedYear = currentYear;

            // Save state & advance to next chapter
            saveTrackerState(api, targetChapter, lastReleasedWeek, lastReleasedYear);
            targetChapter++;
            lastCheckedStatus = "Chapter " + (targetChapter - 1) + " released!";

            ScriptApi.BUS.put("one_piece:last_released", Integer.valueOf(targetChapter - 1));
            ScriptApi.BUS.put("one_piece:target_chapter", Integer.valueOf(targetChapter));
            ScriptApi.BUS.put("one_piece:status", "released_for_week");
        } else {
            lastCheckedStatus = "Not yet released (checked at " + timeStr + ")";
            api.log("one_piece: Chapter " + targetChapter + " not yet out. Next check in " + checkIntervalMins + "m.");

            ScriptApi.BUS.put("one_piece:target_chapter", Integer.valueOf(targetChapter));
            ScriptApi.BUS.put("one_piece:last_checked", Long.valueOf(lastCheckTime));
            ScriptApi.BUS.put("one_piece:status", "monitoring");
        }
    }

    private String extractChapterUrl(String html, int chapter) {
        try {
            Pattern p = Pattern.compile("href=\"(/chapters/[0-9]+/one-piece-chapter-" + chapter + "[^\"]*)\"");
            Matcher m = p.matcher(html);
            if (m.find()) {
                String relPath = m.group(1);
                if (relPath.startsWith("/")) {
                    return TCB_BASE_URL + relPath;
                }
                return TCB_BASE_URL + "/" + relPath;
            }
        } catch (Throwable ignored) {}
        return MANGA_URL;
    }

    private String extractChapterTitle(String html, int chapter) {
        try {
            String needle = "chapter-" + chapter;
            int idx = html.indexOf(needle);
            if (idx != -1) {
                int searchEnd = Math.min(html.length(), idx + 500);
                String sub = html.substring(idx, searchEnd);
                Pattern p = Pattern.compile("<div class=\"text-gray-500\">([^<]+)</div>");
                Matcher m = p.matcher(sub);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Throwable ignored) {}
        return "New Chapter Available";
    }

    private void postChapterNotification(Context ctx, ScriptApi api, int chapter,
                                         String chapterTitle, String chapterUrl) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "One Piece Chapter Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("High-priority alerts when new One Piece chapters drop on TCB Scans");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 300, 150, 300, 150, 500});
                nm.createNotificationChannel(channel);
            }

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(chapterUrl));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(ctx, chapter, browserIntent, flags);

            String title = "🏴‍☠️ One Piece Chapter " + chapter + " is OUT!";
            String content = chapterTitle + " • Tap to read on TCB Scans";

            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nb = new Notification.Builder(ctx, NOTIF_CHANNEL_ID);
            } else {
                nb = new Notification.Builder(ctx);
            }

            nb.setSmallIcon(android.R.drawable.ic_dialog_info)
              .setContentTitle(title)
              .setContentText(content)
              .setContentIntent(pendingIntent)
              .setAutoCancel(true)
              .setPriority(Notification.PRIORITY_HIGH)
              .setDefaults(Notification.DEFAULT_ALL);

            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(title);
            bigStyle.bigText(content + "\n\nDirect Link: " + chapterUrl);
            nb.setStyle(bigStyle);

            nm.notify(NOTIF_ID, nb.build());
            api.log("one_piece: posted native system notification for Chapter " + chapter);

            if (vibrateOnRelease) {
                api.vibrate(1000);
            }
            api.toast("🏴‍☠️ One Piece Chapter " + chapter + " is OUT!");
        } catch (Throwable t) {
            api.log("one_piece: native notification failed: " + t);
            api.notify(NOTIF_ID, "🏴‍☠️ One Piece Chapter " + chapter + " is OUT!",
                    chapterTitle + " • Tap to read on TCB Scans");
        }
    }

    private String fetchHtml(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; sweetin) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }

        InputStream is = conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        is.close();
        return new String(baos.toByteArray(), "UTF-8");
    }

    private static Context getContext(ScriptApi api) {
        try {
            Method m = api.getClass().getMethod("context");
            return (Context) m.invoke(api);
        } catch (Throwable ignored) {}
        try {
            Field f = api.getClass().getDeclaredField("ctx");
            f.setAccessible(true);
            return (Context) f.get(api);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onStop() {
        // Cleanup if needed
    }
}
