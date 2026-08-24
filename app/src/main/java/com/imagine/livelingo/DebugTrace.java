package com.imagine.livelingo;

import android.content.Context;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Lightweight diagnostic recorder. Text trace is always written; PCM audio is kept only for
 * the current diagnostic session and exported together with the trace as one ZIP. */
public final class DebugTrace {
    private final File file;
    private final File zipFile;
    private static volatile File globalFile;
    private static volatile File audioPcmFile;
    private static volatile OutputStream audioOut;
    private static long audioSamples;
    private static final int AUDIO_RATE=16000;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public DebugTrace(Context context){
        File cache=context.getCacheDir();
        file = new File(cache, "livelingo-trace.log");
        zipFile = new File(cache,"LiveLingo-diagnostic.zip");
        globalFile = file;
        audioPcmFile=new File(cache,"livelingo-session.pcm");
    }

    public synchronized void clear(){
        try { new FileWriter(file, false).close(); } catch(Exception ignored){}
        synchronized(DebugTrace.class){
            closeAudioLocked(); audioSamples=0;
            try{ if(audioPcmFile!=null)new FileOutputStream(audioPcmFile,false).close(); }catch(Exception ignored){}
        }
    }

    public void log(String event, String data){ logTo(file,event,data); }
    public static void logGlobal(String event,String data){File f=globalFile;if(f!=null)logTo(f,event,data);}

    /** Append the exact mono 16 kHz PCM stream used by STT. This lets diagnostics be compared
     * against the original audio without opening a second microphone. */
    public static synchronized void appendAudio(float[] samples){
        if(samples==null||samples.length==0||audioPcmFile==null)return;
        try{
            if(audioOut==null)audioOut=new BufferedOutputStream(new FileOutputStream(audioPcmFile,true),64*1024);
            byte[] b=new byte[samples.length*2];int p=0;
            for(float f:samples){int v=Math.max(-32768,Math.min(32767,Math.round(f*32767f)));b[p++]=(byte)(v&255);b[p++]=(byte)((v>>>8)&255);}
            audioOut.write(b);audioSamples+=samples.length;
        }catch(Exception e){logGlobal("DIAG_AUDIO_ERROR",e.toString());}
    }

    public static synchronized void markAudioSessionStart(){logGlobal("DIAG_AUDIO_START","rate=16000 mono pcm16");}
    public static synchronized void markAudioSessionStop(){flushAudioLocked();logGlobal("DIAG_AUDIO_STOP","samples="+audioSamples+" ms="+(audioSamples*1000/AUDIO_RATE));}

    private static void flushAudioLocked(){try{if(audioOut!=null)audioOut.flush();}catch(Exception ignored){}}
    private static void closeAudioLocked(){try{if(audioOut!=null)audioOut.close();}catch(Exception ignored){}audioOut=null;}

    private static synchronized void logTo(File file,String event,String data){
        if(file==null)return;
        try(BufferedWriter w = new BufferedWriter(new FileWriter(file, true))){w.write(FMT.format(new Date()) + "\t" + event + "\t" + sanitize(data) + "\n");}catch(Exception ignored){}
    }
    private static String sanitize(String s){if(s==null)return "";s=s.replace("\n"," ").replace("\r"," ").replace("\t"," ");return s.length()>500?s.substring(0,500)+"…":s;}

    /** Existing UI calls file(); return a self-contained ZIP instead of a lone log. */
    public File file(){
        synchronized(DebugTrace.class){flushAudioLocked();}
        try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))){
            addFile(z,file,"trace.log");
            if(audioPcmFile!=null&&audioPcmFile.exists()&&audioPcmFile.length()>0){
                z.putNextEntry(new ZipEntry("session.wav"));writeWavHeader(z,(int)Math.min(Integer.MAX_VALUE,audioPcmFile.length()),AUDIO_RATE);
                try(InputStream in=new BufferedInputStream(new FileInputStream(audioPcmFile))){byte[] b=new byte[64*1024];int n;while((n=in.read(b))>0)z.write(b,0,n);}z.closeEntry();
            }
            String meta="LiveLingo diagnostic package\nrate=16000\nchannels=1\nformat=PCM16 WAV\naudio_samples="+audioSamples+"\n";
            z.putNextEntry(new ZipEntry("README.txt"));z.write(meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));z.closeEntry();
        }catch(Exception e){log("DIAG_ZIP_ERROR",e.toString());return file;}
        return zipFile;
    }
    private static void addFile(ZipOutputStream z,File f,String name)throws IOException{if(f==null||!f.exists())return;z.putNextEntry(new ZipEntry(name));try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[32*1024];int n;while((n=in.read(b))>0)z.write(b,0,n);}z.closeEntry();}
    private static void writeWavHeader(OutputStream o,int data,int rate)throws IOException{writeAscii(o,"RIFF");le32(o,36+data);writeAscii(o,"WAVEfmt ");le32(o,16);le16(o,1);le16(o,1);le32(o,rate);le32(o,rate*2);le16(o,2);le16(o,16);writeAscii(o,"data");le32(o,data);}
    private static void writeAscii(OutputStream o,String s)throws IOException{o.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    private static void le16(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);}
    private static void le32(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
}
