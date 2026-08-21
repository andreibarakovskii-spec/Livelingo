package com.imagine.livelingo;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

public final class LiveSpeechRecognizer implements RecognitionListener {
    public interface Listener { void onText(String text, boolean isFinal, String detectedLanguage); void onStatus(String status); void onError(String error); }
    private final Context context; private final Listener listener; private SpeechRecognizer recognizer; private boolean running; private String detectedLanguage; private String forcedLanguage="auto";
    public LiveSpeechRecognizer(Context context, Listener listener) { this.context = context; this.listener = listener; }
    public boolean isOnDeviceAvailable() { return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }
    public void setInputLanguage(String code){ forcedLanguage = code == null ? "auto" : code; detectedLanguage = "auto".equals(forcedLanguage) ? null : forcedLanguage; if(running){ stopInternal(); start(); } }
    public void start() {
        if (running) return; if (!isOnDeviceAvailable()) { listener.onError("На этом телефоне нет системного офлайн-распознавания речи"); return; }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context); recognizer.setRecognitionListener(this); running = true; listenAgain();
    }
    private String speechTag(String code){ if("en".equals(code))return "en-US"; if("ru".equals(code))return "ru-RU"; if("de".equals(code))return "de-DE"; if("fr".equals(code))return "fr-FR"; if("es".equals(code))return "es-ES"; if("it".equals(code))return "it-IT"; if("pt".equals(code))return "pt-BR"; if("pl".equals(code))return "pl-PL"; if("tr".equals(code))return "tr-TR"; if("uk".equals(code))return "uk-UA"; if("zh".equals(code))return "zh-CN"; if("ja".equals(code))return "ja-JP"; if("ko".equals(code))return "ko-KR"; if("ar".equals(code))return "ar-SA"; if("hi".equals(code))return "hi-IN"; return code; }
    private void listenAgain() {
        if (!running || recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3); i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 350L); i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 250L);
        if (Build.VERSION.SDK_INT >= 33) { i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, "latency"); i.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true); }
        if (!"auto".equals(forcedLanguage)) {
            String tag=speechTag(forcedLanguage); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag); detectedLanguage=forcedLanguage;
        } else if (Build.VERSION.SDK_INT >= 34) {
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true); i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            ArrayList<String> common = new ArrayList<>(); common.add("en-US"); common.add("de-DE"); common.add("fr-FR"); common.add("es-ES"); common.add("it-IT"); common.add("ru-RU"); common.add("uk-UA"); common.add("pl-PL"); common.add("pt-BR"); common.add("tr-TR");
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, common); i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, common);
        }
        recognizer.startListening(i); listener.onStatus("Слушаю…");
    }
    private void stopInternal(){ running=false; if(recognizer!=null){ recognizer.cancel(); recognizer.destroy(); recognizer=null; } }
    public void stop() { stopInternal(); }
    @Override public void onReadyForSpeech(Bundle params) { listener.onStatus("Говорите"); }
    @Override public void onBeginningOfSpeech() { listener.onStatus("Распознаю…"); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { listener.onStatus("Перевожу…"); }
    @Override public void onError(int error) { if (!running) return; if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onError("Распознавание: код " + error); delayedRestart(); }
    @Override public void onResults(Bundle results) { emit(results, true); delayedRestart(); }
    @Override public void onPartialResults(Bundle partialResults) { emit(partialResults, false); }
    @Override public void onEvent(int eventType, Bundle params) {}
    @Override public void onLanguageDetection(Bundle results) { if (Build.VERSION.SDK_INT >= 34 && "auto".equals(forcedLanguage)) { int confidence = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN); String tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE); if (tag != null && !tag.isBlank() && confidence >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) detectedLanguage = tag; } }
    private void emit(Bundle bundle, boolean isFinal) { ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (list != null && !list.isEmpty()) listener.onText(list.get(0), isFinal, detectedLanguage); }
    private void delayedRestart() { if (!running) return; new android.os.Handler(context.getMainLooper()).postDelayed(this::listenAgain, 80); }
}
