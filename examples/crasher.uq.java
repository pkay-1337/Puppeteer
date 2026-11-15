import uq.script.ScriptApi;
import uq.script.UqScript;

/** crasher.uq.java — crash-isolation test (SRS acceptance A2).
 *  The host and all other scripts must survive this. */
public class Crasher extends UqScript {

    @Override public void main(ScriptApi api, android.os.Bundle args) throws Exception {
        api.log("about to crash on purpose");
        throw new RuntimeException("intentional crash (acceptance A2)");
    }
}
