package com.imagine.livelingo.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;

/** sherpa-onnx Kokoro bridge. Loaded only while neural speech is requested. */
public final class KokoroBridge {
    private static OfflineTts tts;
    private static String loadedDir;
    private KokoroBridge() {}

    public static synchronized boolean ensureLoaded(String modelDir) {
        if (tts != null && modelDir.equals(loadedDir)) return true;
        File dir = new File(modelDir);
        File model = new File(dir, "model.onnx");
        File voices = new File(dir, "voices.bin");
        File tokens = new File(dir, "tokens.txt");
        File data = new File(dir, "espeak-ng-data");
        if (!model.isFile() || !voices.isFile() || !tokens.isFile() || !data.isDirectory()) return false;
        release();
        OfflineTtsKokoroModelConfig kc = OfflineTtsKokoroModelConfig.builder()
                .setModel(model.getAbsolutePath())
                .setVoices(voices.getAbsolutePath())
                .setTokens(tokens.getAbsolutePath())
                .setDataDir(data.getAbsolutePath())
                .build();
        OfflineTtsModelConfig mc = OfflineTtsModelConfig.builder()
                .setKokoro(kc).setNumThreads(2).setDebug(false).build();
        tts = new OfflineTts(OfflineTtsConfig.builder().setModel(mc).build());
        loadedDir = modelDir;
        return true;
    }

    public static synchronized byte[] synthesize(String modelDir, String text, int speakerId) {
        if (text == null || text.isBlank() || !ensureLoaded(modelDir)) return null;
        GenerationConfig gc = new GenerationConfig();
        gc.setSid(Math.max(0, Math.min(10, speakerId)));
        gc.setSpeed(1.03f);
        gc.setSilenceScale(0.18f);
        GeneratedAudio a = tts.generateWithConfigAndCallback(text, gc, samples -> {});
        if (a == null || a.getSamples() == null || a.getSamples().length == 0) return null;
        return wav16(a.getSamples(), a.getSampleRate());
    }

    public static synchronized void release() {
        if (tts != null) { try { tts.release(); } catch (Throwable ignored) {} }
        tts = null; loadedDir = null;
    }
    public static synchronized boolean isLoaded() { return tts != null; }

    private static byte[] wav16(float[] samples, int rate) {
        int dataLen = samples.length * 2;
        ByteArrayOutputStream o = new ByteArrayOutputStream(44 + dataLen);
        writeAscii(o,"RIFF"); le32(o,36+dataLen); writeAscii(o,"WAVEfmt "); le32(o,16); le16(o,1); le16(o,1);
        le32(o,rate); le32(o,rate*2); le16(o,2); le16(o,16); writeAscii(o,"data"); le32(o,dataLen);
        for(float f:samples){int v=(int)(Math.max(-1f,Math.min(1f,f))*32767f);le16(o,v);}
        return o.toByteArray();
    }
    private static void writeAscii(ByteArrayOutputStream o,String s){for(int i=0;i<s.length();i++)o.write((byte)s.charAt(i));}
    private static void le16(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);}
    private static void le32(ByteArrayOutputStream o,int v){le16(o,v);le16(o,v>>>16);}
}
