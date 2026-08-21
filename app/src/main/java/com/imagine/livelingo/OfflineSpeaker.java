package com.imagine.livelingo;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Comparator;
import java.util.Locale;

public final class OfflineSpeaker implements TextToSpeech.OnInitListener {
    public interface Listener { void onStatus(String status); }
    private final TextToSpeech tts; private final Listener listener; private boolean ready; private String targetTag = "ru";
    public OfflineSpeaker(Context context, Listener listener) { this.listener = listener; this.tts = new TextToSpeech(context, this); }
    @Override public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) { selectOfflineVoice(targetTag); listener.onStatus("Озвучка готова"); }
        else listener.onStatus("TTS недоступен");
    }
    public boolean selectOfflineVoice(String tag) {
        targetTag = tag; if (!ready) return false; Locale wanted = LanguageCatalog.localeFor(tag);
        Voice best = tts.getVoices().stream().filter(v -> !v.isNetworkConnectionRequired())
                .filter(v -> v.getLocale().getLanguage().equals(wanted.getLanguage()))
                .max(Comparator.comparingInt(Voice::getQuality)).orElse(null);
        if (best != null) { tts.setVoice(best); tts.setSpeechRate(1.06f); return true; }
        return tts.setLanguage(wanted) >= TextToSpeech.LANG_AVAILABLE;
    }
    public void speak(String text) {
        if (!ready || text == null || text.isBlank()) return;
        Bundle params = new Bundle(); params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, "live-" + System.nanoTime());
    }
    public void stop() { if (ready) tts.stop(); }
    public void close() { tts.stop(); tts.shutdown(); }
}
