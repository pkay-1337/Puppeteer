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
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uq.script.ScriptApi;
import uq.script.ScriptSchema;
import uq.script.UqScript;

/**
 * one_piece.uq.java — Automated One Piece Manga Release Monitor & Native Notifier.
 *
 * Replaces external ntfy.sh dependency with direct on-device Android notifications:
 *   - Polls TCB Scans (https://tcbonepiecechapters.com/mangas/5/one-piece).
 *   - Detects the exact moment a new chapter releases.
 *   - Parses the chapter title and direct reading URL.
 *   - Issues a high-priority heads-up system notification with a PendingIntent:
 *     tapping the notification opens the chapter directly in your browser.
 *   - Celebratory haptic vibration and toast on release.
 *   - Persists state across device reboots and updates.
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

    // Runtime state
    private long lastCheckTime = 0L;
    private String lastCheckedStatus = "Initialized";

    @Override
    public ScriptSchema schema() {
        return new ScriptSchema()
                .addNumber("target_chapter", "Target Chapter to Monitor",
                        "The chapter number to monitor for release", 1199, 1000, 2500)
                .addNumber("check_interval_mins", "Check Interval (Minutes)",
                        "Polling frequency in minutes (default 30 mins)", 30, 5, 240)
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
    }

    @Override
    public void main(ScriptApi api, Bundle args) throws Exception {
        api.log("one_piece: starting One Piece Chapter Monitor daemon");
        applyConfig(args);

        Context ctx = getContext(api);
        if (ctx == null) {
            api.log("one_piece error: Context unavailable");
            return;
        }

        // Initialize tracked chapter from persistent storage if available
        initTargetChapter(api);

        api.log("one_piece: actively monitoring for Chapter " + targetChapter + " (every " + checkIntervalMins + "m)");
        api.toast("🏴‍☠️ Monitoring for One Piece Chapter " + targetChapter);

        while (!api.stopRequested()) {
            try {
                checkForNewChapter(ctx, api);
            } catch (Throwable t) {
                api.log("one_piece: check failed: " + t.getMessage());
                lastCheckedStatus = "Error: " + t.getMessage();
            }

            // Interruptible countdown sleep
            long waitMs = checkIntervalMins * 60 * 1000L;
            long startSleep = System.currentTimeMillis();
            while (!api.stopRequested() && (System.currentTimeMillis() - startSleep < waitMs)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }

        api.log("one_piece: monitor daemon stopped");
    }

    private void initTargetChapter(ScriptApi api) {
        try {
            // First check persistent script state
            File sf = new File(STATE_FILE);
            if (sf.exists()) {
                String saved = api.read(STATE_FILE);
                if (saved != null && !saved.trim().isEmpty()) {
                    int lastSaved = Integer.parseInt(saved.trim());
                    if (lastSaved >= 1000) {
                        targetChapter = lastSaved + 1;
                        api.log("one_piece: restored state from " + STATE_FILE + ", next target=" + targetChapter);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: check ubuntu22-fs root .last_chapter if readable
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
                        saveLastChapter(api, last);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Default: 1199
        api.log("one_piece: using default target chapter " + targetChapter);
    }

    private void saveLastChapter(ScriptApi api, int lastChapter) {
        try {
            api.write(STATE_FILE, String.valueOf(lastChapter));
        } catch (Throwable t) {
            api.log("one_piece: saveLastChapter failed: " + t);
        }
    }

    private void checkForNewChapter(Context ctx, ScriptApi api) throws Exception {
        lastCheckTime = System.currentTimeMillis();
        String timeStr = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date(lastCheckTime));
        api.log("one_piece: [" + timeStr + "] Fetching TCB Scans page to check Chapter " + targetChapter + "...");

        String html = fetchHtml(MANGA_URL);
        if (html == null || html.isEmpty()) {
            lastCheckedStatus = "Empty response";
            return;
        }

        // Search for chapter presence: "chapter-XXXX" or "Chapter XXXX"
        String chapterKey = "chapter-" + targetChapter;
        boolean isReleased = html.contains(chapterKey) || html.contains("Chapter " + targetChapter);

        if (isReleased) {
            api.log("one_piece: 🚨 CHAPTER " + targetChapter + " IS OUT! Parsing details...");

            // Extract direct chapter link and title
            String chapterUrl = extractChapterUrl(html, targetChapter);
            String chapterTitle = extractChapterTitle(html, targetChapter);

            // Post native Android heads-up notification with browser PendingIntent
            postChapterNotification(ctx, api, targetChapter, chapterTitle, chapterUrl);

            // Save state & advance to next chapter
            saveLastChapter(api, targetChapter);
            targetChapter++;
            lastCheckedStatus = "Chapter " + (targetChapter - 1) + " released!";

            ScriptApi.BUS.put("one_piece:last_released", Integer.valueOf(targetChapter - 1));
            ScriptApi.BUS.put("one_piece:target_chapter", Integer.valueOf(targetChapter));
            ScriptApi.BUS.put("one_piece:status", "released");
        } else {
            lastCheckedStatus = "Not yet released (checked at " + timeStr + ")";
            api.log("one_piece: Chapter " + targetChapter + " not yet released. Sleeping for " + checkIntervalMins + "m.");

            ScriptApi.BUS.put("one_piece:target_chapter", Integer.valueOf(targetChapter));
            ScriptApi.BUS.put("one_piece:last_checked", Long.valueOf(lastCheckTime));
            ScriptApi.BUS.put("one_piece:status", "waiting");
        }
    }

    private String extractChapterUrl(String html, int chapter) {
        try {
            // Pattern: href="(/chapters/[0-9]+/one-piece-chapter-XXXX)"
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
            // Look for card block containing chapter-XXXX and text-gray-500 title
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

            // Notification Channel for Android 8.0+
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

            // PendingIntent to launch the browser directly to the chapter URL
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
            // Fallback to built-in ScriptApi notify
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
