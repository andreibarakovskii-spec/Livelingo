package com.imagine.livelingo;

import android.content.Context;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DebugTrace {
    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    public DebugTrace(Context context){ file = new File(context.getCacheDir(), "livelingo-trace.log"); }
    public synchronized void clear(){ try { new FileWriter(file, false).close(); } catch(Exception ignored){} }
    public synchronized void log(String event, String data){
        try(BufferedWriter w = new BufferedWriter(new FileWriter(file, true))){
            w.write(fmt.format(new Date()) + "\t" + event + "\t" + (data == null ? "" : data.replace("\n", " ")) + "\n");
        } catch(Exception ignored){}
    }
    public File file(){ return file; }
}
