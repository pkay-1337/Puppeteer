package uq.script;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** Per-script rotating log file: NAME.log (+ one .1 generation). */
public class Logger {

    private final File file;

    public Logger(File logFile) { this.file = logFile; }

    public String path() { return file.getAbsolutePath(); }

    public synchronized void write(String line) {
        try {
            if (file.length() > 262144L) {
                File gen1 = new File(file.getParentFile(), file.getName() + ".1");
                file.renameTo(gen1);
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            w.write(line);
            w.write("\n");
            w.close();
        } catch (IOException ignored) { }
    }

    public synchronized String tail(int n) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"));
            List<String> all = new ArrayList<String>();
            String l;
            while ((l = r.readLine()) != null) all.add(l);
            r.close();
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, all.size() - n);
            for (int i = start; i < all.size(); i++) sb.append(all.get(i)).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
