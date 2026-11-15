import uq.script.ScriptApi;
import uq.script.UqScript;

/** hello.uq.java — the canonical first script (SRS acceptance A1).
 *  Usage: usq buildpush hello.uq.java */
public class Hello extends UqScript {

    @Override public void main(ScriptApi api, android.os.Bundle args) throws Exception {
        api.log("hello: run at " + new java.util.Date());
        api.toast("hello from a pushed script (id=" + api.id() + ")");
    }
}
