package com.imagine.livelingo.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** sherpa-onnx Supertonic 3 bridge for multilingual local TTS. */
public final class SupertonicBridge {
    private static OfflineTts tts;
    private static String loadedDir;
    private SupertonicBridge() {}

    public static synchronized boolean ensureLoaded(String modelDir){
        if(tts!=null&&modelDir.equals(loadedDir))return true;
        File d=new File(modelDir);
        File duration=new File(d,"duration_predictor.int8.onnx");
        File textEncoder=new File(d,"text_encoder.int8.onnx");
        File vectorEstimator=new File(d,"vector_estimator.int8.onnx");
        File vocoder=new File(d,"vocoder.int8.onnx");
        File ttsJson=new File(d,"tts.json");
        File unicodeIndexer=new File(d,"unicode_indexer.bin");
        File voiceStyle=new File(d,"voice.bin");
        if(!duration.isFile()||!textEncoder.isFile()||!vectorEstimator.isFile()||!vocoder.isFile()||!ttsJson.isFile()||!unicodeIndexer.isFile()||!voiceStyle.isFile())return false;
        release();
        OfflineTtsSupertonicModelConfig sc=OfflineTtsSupertonicModelConfig.builder()
                .setDurationPredictor(duration.getAbsolutePath())
                .setTextEncoder(textEncoder.getAbsolutePath())
                .setVectorEstimator(vectorEstimator.getAbsolutePath())
                .setVocoder(vocoder.getAbsolutePath())
                .setTtsJson(ttsJson.getAbsolutePath())
                .setUnicodeIndexer(unicodeIndexer.getAbsolutePath())
                .setVoiceStyle(voiceStyle.getAbsolutePath())
                .build();
        OfflineTtsModelConfig mc=OfflineTtsModelConfig.builder().setSupertonic(sc).setNumThreads(2).setDebug(false).setProvider("cpu").build();
        tts=new OfflineTts(OfflineTtsConfig.builder().setModel(mc).build());
        loadedDir=modelDir;
        return true;
    }

    public static synchronized byte[] synthesize(String modelDir,String text,String language,int voiceProfile){
        if(text==null||text.isBlank()||!ensureLoaded(modelDir))return null;
        GenerationConfig gc=new GenerationConfig();
        gc.setSid(voiceProfile<=0?0:(voiceProfile>=2?6:3));
        gc.setSpeed(1.05f);
        gc.setNumSteps(6);
        gc.setSilenceScale(0.16f);
        Map<String,String> extra=new HashMap<>();extra.put("lang",normalizeLanguage(language));gc.setExtra(extra);
        GeneratedAudio a=tts.generateWithConfigAndCallback(text,gc,samples->{});
        if(a==null||a.getSamples()==null||a.getSamples().length==0)return null;
        return wav16(a.getSamples(),a.getSampleRate());
    }

    public static synchronized void release(){if(tts!=null){try{tts.release();}catch(Throwable ignored){}}tts=null;loadedDir=null;}
    public static synchronized boolean isLoaded(){return tts!=null;}

    private static String normalizeLanguage(String tag){
        if(tag==null||tag.isBlank())return "en";String l=tag.split("[-_]")[0].toLowerCase();
        if("deu".equals(l))return "de";if("fra".equals(l))return "fr";if("rus".equals(l))return "ru";if("spa".equals(l))return "es";if("ita".equals(l))return "it";if("por".equals(l))return "pt";if("ukr".equals(l))return "uk";if("pol".equals(l))return "pl";if("tur".equals(l))return "tr";if("jpn".equals(l))return "ja";if("kor".equals(l))return "ko";if("zho".equals(l)||"chi".equals(l))return "zh";return l;
    }

    private static byte[] wav16(float[] samples,int rate){int dataLen=samples.length*2;ByteArrayOutputStream o=new ByteArrayOutputStream(44+dataLen);writeAscii(o,"RIFF");le32(o,36+dataLen);writeAscii(o,"WAVEfmt ");le32(o,16);le16(o,1);le16(o,1);le32(o,rate);le32(o,rate*2);le16(o,2);le16(o,16);writeAscii(o,"data");le32(o,dataLen);for(float f:samples){int v=(int)(Math.max(-1f,Math.min(1f,f))*32767f);le16(o,v);}return o.toByteArray();}
    private static void writeAscii(ByteArrayOutputStream o,String s){for(int i=0;i<s.length();i++)o.write((byte)s.charAt(i));}
    private static void le16(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);}
    private static void le32(ByteArrayOutputStream o,int v){le16(o,v);le16(o,v>>>16);}
}
