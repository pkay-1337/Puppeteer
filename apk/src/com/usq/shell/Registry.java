package com.usq.shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.Iterator;

/**
 * Persisted script table: files/registry.json — id → {entry, name, version,
 * resident, autoStart, description, args, schedules, state, installedAt,
 * lastRunAt, lastError}. RUNNING is normalized to STOPPED at load (stale).
 */
class Registry {

    private JSONObject data = new JSONObject();
    private final File file;

    Registry(File file) { this.file = file; }

    synchronized void load() {
        data = new JSONObject();
        try {
            if (!file.exists()) return;
            InputStream in = new FileInputStream(file);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject all = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject e = all.optJSONObject(id);
                if (e != null) {
                    if ("RUNNING".equals(e.optString("state"))) e.put("state", "STOPPED");
                    data.put(id, e);
                }
            }
        } catch (Throwable ignored) { /* corrupt registry -> start empty */ }
    }

    synchronized void save() {
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write(data.toString(2));
            w.close();
            file.delete();
            tmp.renameTo(file);
        } catch (Throwable ignored) { }
    }

    synchronized JSONArray list() {
        JSONArray a = new JSONArray();
        Iterator<String> it = data.keys();
        while (it.hasNext()) a.put(data.optJSONObject(it.next()));
        return a;
    }

    synchronized JSONObject get(String id) { return data.optJSONObject(id); }

    synchronized void put(String id, JSONObject e) {
        try {
            data.put(id, e);
            save();
        } catch (Throwable ignored) { }
    }

    synchronized void remove(String id) {
        data.remove(id);
        save();
    }

    synchronized int size() { return data.length(); }
}
