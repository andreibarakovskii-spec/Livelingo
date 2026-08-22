package com.imagine.livelingo;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Locale;
import java.util.Queue;

public final class OfflineSpeaker implements TextToSpeech.OnInitListener {
    public interface Listener { void onStatus(String status); }
    private static final class Pending { final String text; final boolean fin; Pending(String t,boolean f){text=t;fin=f;} }
    private final TextToSpeech tts; private final Listener listener; private boolean ready; private String targetTag = "ru";
    private final Queue<Pending> pending=new ArrayDeque<>();
    public OfflineSpeaker(Context context, Listener listener) { this.listener = listener; this.tts = new TextToSpeech(context, this); }
    @Override public synchronized void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            selectOfflineVoice(targetTag); listener.onStatus("Озвучка готова");
            while(!pending.isEmpty()){Pending p=pending.poll();speakNow(p.text,p.fin);}
        } else { pending.clear(); listener.onStatus("TTS недоступен"); }
    }
    public synchronized boolean selectOfflineVoice(String tag) {
        targetTag = tag==null?"ru":tag; if (!ready) return false; Locale wanted = LanguageCatalog.localeFor(targetTag);
        Voice best = tts.getVoices()==null?null:tts.getVoices().stream().filter(v -> !v.isNetworkConnectionRequired())
                .filter(v -> v.getLocale()!=null && v.getLocale().getLanguage().equals(wanted.getLanguage()))
                .max(Comparator.comparingInt(Voice::getQuality)).orElse(null);
        boolean ok;
        if (best != null) { ok=tts.setVoice(best)==TextToSpeech.SUCCESS; }
        else { int r=tts.setLanguage(wanted);ok=r!=TextToSpeech.LANG_MISSING_DATA&&r!=TextToSpeech.LANG_NOT_SUPPORTED; }
        tts.setSpeechRate(1.06f); tts.setPitch(1.0f);
        return ok;
    }
    public synchronized void speak(String text, boolean finalChunk) {
        if (text == null || text.isBlank()) return;
        if(!ready){ if(pending.size()<8)pending.add(new Pending(text,finalChunk)); return; }
        speakNow(text,finalChunk);
    }
    private void speakNow(String text,boolean finalChunk){
        Bundle params = new Bundle(); params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, "live-" + System.nanoTime());
    }
    public synchronized boolean isReady(){return ready;}
    public synchronized void stop() { pending.clear(); if (ready) tts.stop(); }
    public synchronized void close() { pending.clear(); tts.stop(); tts.shutdown(); }
}
