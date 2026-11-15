package uq.script;

import android.os.Bundle;

/**
 * Entry contract for UQ Shell scripts. A script is a dex whose class
 * extends UqScript; the host loads it with DexClassLoader and invokes
 * main() on a dedicated thread with the app's full permission set.
 *
 * Java 7-style syntax only (no lambdas) — the on-device dx toolchain
 * does not desugar invokedynamic.
 */
public abstract class UqScript {

    /** Script body. Runs on the script's own thread. */
    public abstract void main(ScriptApi api, Bundle args) throws Exception;

    /** Called on the script thread after a stop request has ended main(). */
    public void onStop() { }

    /** Called on interval ticks for resident scripts (FR-CRON-5). */
    public void onTick(String scheduleId) { }

    /**
     * Optional parameter schema (FR-PARAM-1).
     * If overridden, StartActivity renders dynamic configuration UI for this script.
     */
    public ScriptSchema schema() { return null; }

    /**
     * Called on the script thread when configuration is updated live from StartActivity (FR-PARAM-4).
     */
    public void onConfigChanged(Bundle newArgs) { }
}
