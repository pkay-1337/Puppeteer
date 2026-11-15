package com.usq.shell;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;

import uq.script.Logger;
import uq.script.UqScript;

/**
 * Control plane: minimal HTTP/1.1 on 127.0.0.1:7823. Sequential request
 * handling (single control user). Endpoints per SRS §3.1.1. Script
 * metadata arrives as X-Uq-* headers (repeatable X-Uq-Cron supported).
 */
class HttpControl implements Runnable {

    private final Context ctx;
    private final Registry reg;
    private final ScriptRunner runner;
    private final File dexDir;
    private final File logsDir;
    private final Scheduler scheduler;
    private final long startAt = System.currentTimeMillis();
    private volatile boolean running = true;
    private ServerSocket ss;

    HttpControl(Context ctx, Registry reg, ScriptRunner runner, File dexDir, File logsDir, Scheduler scheduler) {
        this.ctx = ctx;
        this.reg = reg;
        this.runner = runner;
        this.dexDir = dexDir;
        this.logsDir = logsDir;
        this.scheduler = scheduler;
    }

    void stop() {
        running = false;
        try {
            if (ss != null) ss.close();
        } catch (Throwable ignored) { }
    }

    @Override public void run() {
        try {
            ss = new ServerSocket();
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7823), 16);
            while (running) {
                final Socket s = ss.accept();
                try {
                    s.setSoTimeout(15000);
                    handle(s);
                } catch (Throwable t) {
                    try {
                        respond(s, 500, "application/json", json("ok", false, "error", chain(t)));
                    } catch (Throwable ignored) { }
                } finally {
                    try { s.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            if (running) android.util.Log.w("uq-http", "server died: " + t);
        }
    }

    // ---- request handling ----

    private void handle(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        String line = readLine(in);
        if (line == null || line.length() == 0) {
            respond(s, 400, "application/json", json("ok", false, "error", "empty request"));
            return;
        }
        String[] parts = line.split(" ");
        String method = parts[0];
        String pathQ = parts[1];

        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        int contentLength = 0;
        String h;
        while ((h = readLine(in)) != null && h.length() > 0) {
            int c = h.indexOf(':');
            if (c > 0) {
                String k = h.substring(0, c).trim();
                String v = h.substring(c + 1).trim();
                List<String> l = headers.get(k);
                if (l == null) { l = new ArrayList<String>(); headers.put(k, l); }
                l.add(v);
                if ("Content-Length".equalsIgnoreCase(k)) contentLength = parseInt(v);
            }
        }
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n < 0) break;
            off += n;
        }

        String path = pathQ;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

        if ("GET".equals(method) && "/status".equals(path)) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("app", "puppeteer");
            o.put("version", versionName());
            o.put("uptimeMs", System.currentTimeMillis() - startAt);
            o.put("uid", android.os.Process.myUid());
            o.put("scripts", reg.list());
            JSONArray run = new JSONArray();
            for (String id : runnerIds()) run.put(id);
            o.put("running", run);
            o.put("schedules", scheduler != null ? scheduler.listStatus() : new JSONArray());
            respond(s, 200, "application/json", o.toString().getBytes("UTF-8"));

        } else if ("GET".equals(method) && "/list".equals(path)) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("scripts", reg.list());
            respond(s, 200, "application/json", o.toString().getBytes("UTF-8"));

        } else if ("POST".equals(method) && "/load".equals(path)) {
            doLoad(s, headers, body);

        } else if ("POST".equals(method) && "/start".equals(path)) {
            String id = param(query, "id");
            JSONObject args = argsFrom(query);
            runner.start(id, args);
            respond(s, 200, "application/json", json("ok", true, "id", id));

        } else if ("POST".equals(method) && "/stop".equals(path)) {
            String id = param(query, "id");
            String state = runner.stop(id);
            respond(s, 200, "application/json", json("ok", true, "id", id, "state", state));

        } else if ("POST".equals(method) && "/remove".equals(path)) {
            String id = param(query, "id");
            runner.stop(id);
            if (scheduler != null) {
                scheduler.unscheduleAllForScript(id, false);
            }
            new File(dexDir, id + ".dex").delete();
            reg.remove(id);
            respond(s, 200, "application/json", json("ok", true, "id", id));

        } else if ("GET".equals(method) && "/logs".equals(path)) {
            String id = param(query, "id");
            int tail = parseIntOr(param(query, "tail"), 100);
            Logger l = new Logger(new File(logsDir, id + ".log"));
            respond(s, 200, "text/plain; charset=utf-8", l.tail(tail).getBytes("UTF-8"));

        } else if ("GET".equals(method) && "/schema".equals(path)) {
            String id = param(query, "id");
            uq.script.ScriptSchema schema = runner.getSchema(id);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            o.put("schema", schema != null ? schema.toJson() : new JSONArray());
            JSONObject e = reg.get(id);
            o.put("values", (e != null && e.has("args")) ? e.optJSONObject("args") : new JSONObject());
            respond(s, 200, "application/json", o.toString().getBytes("UTF-8"));

        } else if ("POST".equals(method) && "/config".equals(path)) {
            String id = param(query, "id");
            JSONObject newArgs = new JSONObject(new String(body, "UTF-8"));
            JSONObject e = reg.get(id);
            if (e != null) {
                e.put("args", newArgs);
                reg.put(id, e);
            }
            File sdir = new File(new File(ctx.getFilesDir(), "scripts"), id);
            sdir.mkdirs();
            File argsFile = new File(sdir, "args.json");
            FileOutputStream fos = new FileOutputStream(argsFile);
            fos.write(newArgs.toString(2).getBytes("UTF-8"));
            fos.close();

            Bundle b = runner.jsonToBundle(newArgs);
            runner.deliverConfigChanged(id, b);
            respond(s, 200, "application/json", json("ok", true, "id", id));

        } else if ("GET".equals(method) && "/api.jar".equals(path)) {
            InputStream is = ctx.getAssets().open("script-api.jar");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            respond(s, 200, "application/java-archive", bo.toByteArray());

        } else if ("GET".equals(method) && path.startsWith("/docs")) {
            String page = path.length() > 5 ? path.substring(5) : "index";
            while (page.startsWith("/")) page = page.substring(1);
            if (page.isEmpty()) page = "index";
            if (!page.endsWith(".md") && !page.endsWith(".txt")) page = page + ".md";

            InputStream is = null;
            try {
                is = ctx.getAssets().open("docs/" + page);
            } catch (Throwable ignored) { }

            if (is == null) {
                File f = new File(new File(ctx.getFilesDir(), "docs"), page);
                if (f.exists()) {
                    try { is = new FileInputStream(f); } catch (Throwable ignored) { }
                }
            }

            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                respond(s, 200, "text/markdown; charset=utf-8", bo.toByteArray());
            } else {
                respond(s, 404, "text/plain; charset=utf-8",
                        ("Page not found: " + page + "\nTry: usq docs index\nAvailable pages: index, quickstart, api, cron, cookbooks, cli, troubleshooting\n").getBytes("UTF-8"));
            }

        } else {
            respond(s, 404, "application/json",
                    json("ok", false, "error", "no route: " + method + " " + path));
        }
    }

    /** POST /load: validate dex, stop old run, swap file, update registry. */
    private void doLoad(Socket s, Map<String, List<String>> headers, byte[] body) throws Exception {
        String id = header(headers, "X-Uq-Id");
        String entry = header(headers, "X-Uq-Entry");
        if (id == null || entry == null) {
            respond(s, 400, "application/json",
                    json("ok", false, "error", "need X-Uq-Id and X-Uq-Entry headers"));
            return;
        }
        File tmp = new File(dexDir, id + ".dex.tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        fo.write(body);
        fo.close();

        // load-test the dex and entry class before installing
        try {
            DexClassLoader test = new DexClassLoader(tmp.getAbsolutePath(),
                    ctx.getCodeCacheDir().getAbsolutePath(), null, getClass().getClassLoader());
            Class<?> c = test.loadClass(entry);
            if (!UqScript.class.isAssignableFrom(c)) {
                throw new IllegalArgumentException(entry + " does not extend UqScript");
            }
            c.newInstance();
        } catch (Throwable t) {
            tmp.delete();
            respond(s, 400, "application/json",
                    json("ok", false, "error", "load-test failed: " + chain(t)));
            return;
        }

        runner.stop(id); // no-op if not running

        File dst = new File(dexDir, id + ".dex");
        dst.delete();
        if (!tmp.renameTo(dst)) {
            respond(s, 500, "application/json", json("ok", false, "error", "dex swap failed"));
            return;
        }

        JSONObject e = new JSONObject();
        e.put("id", id);
        e.put("entry", entry);
        e.put("name", headerOr(headers, "X-Uq-Name", id));
        e.put("version", headerOr(headers, "X-Uq-Version", "0"));
        e.put("resident", "1".equals(headerOr(headers, "X-Uq-Resident", "0")));
        e.put("autoStart", "1".equals(headerOr(headers, "X-Uq-AutoStart", "0")));
        e.put("description", headerOr(headers, "X-Uq-Description", ""));
        e.put("state", "INSTALLED");
        e.put("installedAt", System.currentTimeMillis());

        JSONArray scheds = new JSONArray();
        List<String> crons = headers.get("X-Uq-Cron");
        if (crons != null) {
            for (String cr : crons) {
                int c = cr.indexOf(':');
                if (c <= 0) continue;
                JSONObject sc = new JSONObject();
                sc.put("id", cr.substring(0, c).trim());
                String v = cr.substring(c + 1).trim();
                if (v.matches("[0-9]+")) sc.put("everyMs", Long.parseLong(v));
                else sc.put("cron", v);
                scheds.put(sc);
            }
        }
        e.put("schedules", scheds);

        String ab = header(headers, "X-Uq-Args");
        if (ab != null) {
            e.put("args", new JSONObject(new String(
                    Base64.decode(ab, Base64.DEFAULT), "UTF-8")));
        } else {
            File argsFile = new File(new File(ctx.getFilesDir(), "scripts"), id + "/args.json");
            if (argsFile.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(argsFile);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = fis.read(b)) > 0) bo.write(b, 0, n);
                    fis.close();
                    e.put("args", new JSONObject(new String(bo.toByteArray(), "UTF-8")));
                } catch (Throwable ignored) {}
            }
        }

        uq.script.ScriptSchema schema = runner.getSchema(id);
        if (schema != null && !schema.getParams().isEmpty()) {
            e.put("hasSchema", true);
        }

        reg.put(id, e);
        if (scheduler != null) {
            scheduler.loadFromRegistry();
            scheduler.rescheduleAlarm();
        }
        respond(s, 200, "application/json", json("ok", true, "id", id));
    }

    // ---- helpers ----

    private List<String> runnerIds() {
        List<String> ids = new ArrayList<String>();
        JSONArray a = reg.list();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && "RUNNING".equals(e.optString("state"))) ids.add(e.optString("id"));
        }
        return ids;
    }

    private JSONObject argsFrom(String query) throws Exception {
        String ab = param(query, "args");
        if (ab == null) return null;
        return new JSONObject(new String(Base64.decode(ab, Base64.DEFAULT), "UTF-8"));
    }

    private static String param(String query, String key) {
        if (query == null || query.length() == 0) return null;
        for (String p : query.split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0 && key.equals(p.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(p.substring(eq + 1), "UTF-8");
                } catch (Throwable t) {
                    return p.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String header(Map<String, List<String>> headers, String k) {
        List<String> l = headers.get(k);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    private static String headerOr(Map<String, List<String>> headers, String k, String dflt) {
        String v = header(headers, k);
        return v == null ? dflt : v;
    }

    private static int parseInt(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Throwable t) { return 0; }
    }

    private static int parseIntOr(String v, int dflt) {
        try { return Integer.parseInt(v.trim()); } catch (Throwable t) { return dflt; }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    private static void respond(Socket s, int status, String ct, byte[] bytes) throws Exception {
        String head = "HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: " + ct + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes("ISO-8859-1"));
        os.write(bytes);
        os.flush();
    }

    private static byte[] json(Object... kv) {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]);
            return o.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return "{\"ok\":false}".getBytes();
        }
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 6) {
            sb.append(depth == 0 ? "" : " <- ").append(cur.toString());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
