package com.usq.shell;

import android.content.Context;
import android.os.Bundle;

import dalvik.system.DexClassLoader;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import uq.script.Logger;
import uq.script.ScriptApi;
import uq.script.UqScript;

/**
 * Loads and runs scripts: one thread per run, DexClassLoader per run,
 * Throwable boundary around main(), interrupt+join(5s) stop with STUCK
 * fallback. Scripts see uq.script.* from the app classloader (parent).
 */
class ScriptRunner implements ScriptApi.SchedulerFacade {

    private final Context ctx;
    private final Registry reg;
    private final File dexDir;
    private final File scriptsDir;
    private final File logsDir;

    private final Map<String, Run> running = new HashMap<String, Run>();

    private static class Run {
        final String id;
        final UqScript script;
        final ScriptApi api;
        final ClassLoader loader;
        Thread thread;
        volatile boolean stopRequested;

        Run(String id, UqScript script, ScriptApi api, ClassLoader loader) {
            this.id = id;
            this.script = script;
            this.api = api;
            this.loader = loader;
        }
    }

    ScriptRunner(Context ctx, Registry reg, File dexDir, File scriptsDir, File logsDir) {
        this.ctx = ctx;
        this.reg = reg;
        this.dexDir = dexDir;
        this.scriptsDir = scriptsDir;
        this.logsDir = logsDir;
    }

    boolean isRunning(String id) {
        synchronized (running) { return running.containsKey(id); }
    }

    /** Start a run; throws IllegalStateException/IllegalArgumentException with a human message. */
    void start(String id, JSONObject extraArgs) {
        synchronized (running) {
            if (running.containsKey(id)) throw new IllegalStateException(id + " already running");
        }
        JSONObject e = reg.get(id);
        if (e == null) throw new IllegalArgumentException("unknown script: " + id);
        String entry = e.optString("entry", "");
        File dex = new File(dexDir, id + ".dex");
        if (!dex.exists()) throw new IllegalStateException("dex missing for " + id);

        DexClassLoader cl = new DexClassLoader(dex.getAbsolutePath(),
                ctx.getCodeCacheDir().getAbsolutePath(), null, getClass().getClassLoader());
        UqScript script;
        try {
            Class<?> c = cl.loadClass(entry);
            script = (UqScript) c.newInstance();
        } catch (Throwable t) {
            throw new IllegalStateException("cannot load " + entry + ": " + chain(t));
        }

        File sdir = new File(scriptsDir, id);
        sdir.mkdirs();
        Logger log = new Logger(new File(logsDir, id + ".log"));
        Bundle args = mergeArgs(e.optJSONObject("args"), extraArgs);

        final Run r = new Run(id, script, new ScriptApi(ctx, id, args, sdir, log, this), cl);
        synchronized (running) { running.put(id, r); }
        try {
            e.put("state", "RUNNING").put("lastRunAt", System.currentTimeMillis());
        } catch (Throwable ignored) { }
        reg.put(id, e);

        Thread t = new Thread(new Runnable() { public void run() { runBody(r); } }, "uq-" + id);
        r.thread = t;
        t.start();
    }

    private void runBody(final Run r) {
        Thread.currentThread().setContextClassLoader(r.loader);
        String state = "STOPPED";
        try {
            r.script.main(r.api, r.api.args());
        } catch (Throwable t) {
            state = r.stopRequested ? "STOPPED" : "CRASHED";
            r.api.log("[boundary] " + chain(t));
        } finally {
            if (r.stopRequested) {
                try { r.script.onStop(); } catch (Throwable ignored) { }
            }
            try { r.api.cleanup(); } catch (Throwable ignored) { }
            synchronized (running) { running.remove(r.id); }
            JSONObject e = reg.get(r.id);
            if (e != null) {
                try { e.put("state", state); } catch (Throwable ignored) { }
                reg.put(r.id, e);
            }
        }
    }

    /** Deliver periodic onTick callback to resident script (FR-CRON-5). */
    void deliverTick(String id, final String scheduleId) {
        final Run r;
        synchronized (running) { r = running.get(id); }
        if (r != null && r.script != null) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        r.script.onTick(scheduleId);
                    } catch (Throwable t) {
                        r.api.log("[onTick boundary] " + chain(t));
                    }
                }
            }, "uq-tick-" + id).start();
        }
    }

    /** Request stop: interrupt + join 5 s; unresponsive runs are marked STUCK. */
    String stop(String id) {
        Run r;
        synchronized (running) { r = running.get(id); }
        if (r == null) return "NOT_RUNNING";
        r.stopRequested = true;
        r.api.stop();
        r.thread.interrupt();
        try { r.thread.join(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        try { r.api.cleanup(); } catch (Throwable ignored) { }
        if (r.thread.isAlive()) {
            synchronized (running) { running.remove(id); }
            JSONObject e = reg.get(id);
            if (e != null) {
                try { e.put("state", "STUCK"); } catch (Throwable ignored) { }
                reg.put(id, e);
            }
            return "STUCK";
        }
        synchronized (running) { running.remove(id); }
        JSONObject e = reg.get(id);
        return e != null ? e.optString("state") : "STOPPED";
    }

    uq.script.ScriptSchema getSchema(String id) {
        synchronized (running) {
            Run r = running.get(id);
            if (r != null && r.script != null) {
                try { return r.script.schema(); } catch (Throwable ignored) {}
            }
        }
        JSONObject e = reg.get(id);
        if (e == null) return null;
        String entry = e.optString("entry", "");
        File dex = new File(dexDir, id + ".dex");
        if (!dex.exists()) return null;
        try {
            DexClassLoader cl = new DexClassLoader(dex.getAbsolutePath(),
                    ctx.getCodeCacheDir().getAbsolutePath(), null, getClass().getClassLoader());
            Class<?> c = cl.loadClass(entry);
            UqScript s = (UqScript) c.newInstance();
            return s.schema();
        } catch (Throwable t) {
            return null;
        }
    }

    void deliverConfigChanged(String id, final Bundle newArgs) {
        final Run r;
        synchronized (running) { r = running.get(id); }
        if (r != null && r.script != null) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        r.script.onConfigChanged(newArgs);
                    } catch (Throwable t) {
                        r.api.log("[onConfigChanged boundary] " + chain(t));
                    }
                }
            }, "uq-config-" + id).start();
        }
    }

    // ---- SchedulerFacade implementation ----

    public void cron(String scriptId, String schedId, String expr, boolean persist) {
        Scheduler s = ScriptService.getScheduler();
        if (s != null) {
            s.schedule(scriptId, schedId, expr, 0, persist, null);
        }
    }

    public void every(String scriptId, String schedId, long ms, boolean persist) {
        Scheduler s = ScriptService.getScheduler();
        if (s != null) {
            s.schedule(scriptId, schedId, null, ms, persist, null);
        }
    }

    public void unschedule(String scriptId, String schedId) {
        Scheduler s = ScriptService.getScheduler();
        if (s != null) {
            s.unschedule(scriptId, schedId);
        }
    }

    Bundle jsonToBundle(JSONObject o) {
        Bundle b = new Bundle();
        apply(b, o);
        return b;
    }

    private Bundle mergeArgs(JSONObject base, JSONObject extra) {
        Bundle b = new Bundle();
        apply(b, base);
        apply(b, extra);
        return b;
    }

    private void apply(Bundle b, JSONObject o) {
        if (o == null) return;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof String) b.putString(k, (String) v);
            else if (v instanceof Integer) b.putInt(k, ((Integer) v).intValue());
            else if (v instanceof Long) b.putLong(k, ((Long) v).longValue());
            else if (v instanceof Boolean) b.putBoolean(k, ((Boolean) v).booleanValue());
            else if (v instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) v;
                java.util.ArrayList<String> list = new java.util.ArrayList<String>();
                for (int i = 0; i < arr.length(); i++) {
                    list.add(arr.optString(i));
                }
                b.putStringArrayList(k, list);
            }
            else if (v != null) b.putString(k, v.toString());
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
}
