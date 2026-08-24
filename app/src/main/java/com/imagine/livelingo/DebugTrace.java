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
    private static volatile File globalFile;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public DebugTrace(Context context){
        file = new File(context.getCacheDir(), "livelingo-trace.log");
        globalFile = file;
    }

    public synchronized void clear(){ try { new FileWriter(file, false).close(); } catch(Exception ignored){} }

    public void log(String event, String data){ logTo(file,event,data); }

    /** Allows low-level audio/translation classes to add diagnostics without holding Activity context. */
    public static void logGlobal(String event,String data){
        File f=globalFile;
        if(f!=null)logTo(f,event,data);
    }

    private static synchronized void logTo(File file,String event,String data){
        if(file==null)return;
        try(BufferedWriter w = new BufferedWriter(new FileWriter(file, true))){
            w.write(FMT.format(new Date()) + "\t" + event + "\t" + sanitize(data) + "\n");
        } catch(Exception ignored){}
    }

    private static String sanitize(String s){
        if(s==null)return "";
        s=s.replace("\n"," ").replace("\r"," ").replace("\t"," ");
        return s.length()>500?s.substring(0,500)+"…":s;
    }

    public File file(){ return file; }
}
