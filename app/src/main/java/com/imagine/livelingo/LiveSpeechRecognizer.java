package com.imagine.livelingo;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

public final class LiveSpeechRecognizer implements RecognitionListener {
    public interface Listener { void onText(String text, boolean isFinal, String detectedLanguage); void onStatus(String status); void onError(String error); }
    private final Context context; private final Listener listener; private final Handler handler = new Handler(Looper.getMainLooper()); private final DebugTrace trace;
    private SpeechRecognizer recognizer; private boolean running; private boolean listening; private boolean restarting; private String detectedLanguage; private String forcedLanguage="auto";
    public LiveSpeechRecognizer(Context context, Listener listener) { this.context = context; this.listener = listener; this.trace = new DebugTrace(context); }
    public boolean isOnDeviceAvailable() { return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }
    public void setInputLanguage(String code){
        forcedLanguage = code == null ? "auto" : code;
        detectedLanguage = "auto".equals(forcedLanguage) ? null : forcedLanguage;
        trace.log("LANGUAGE_SET", forcedLanguage);
        if(running) scheduleFullRestart("language-change", 80);
    }
    public void start() {
        if (running) return;
        if (!isOnDeviceAvailable()) { listener.onError("На этом телефоне нет системного офлайн-распознавания речи"); return; }
        running = true; trace.clear(); trace.log("START", "forced="+forcedLanguage+" sdk="+Build.VERSION.SDK_INT);
        createRecognizer(); startSession(40);
    }
    private void createRecognizer(){
        listening=false;
        if(recognizer!=null){ try{ recognizer.destroy(); }catch(Exception ignored){} }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context); recognizer.setRecognitionListener(this); trace.log("RECOGNIZER_CREATE", "ok");
    }
    private void scheduleFullRestart(String why, long delay){
        if(!running || restarting) return; restarting=true; listening=false; trace.log("RESTART_SCHEDULE", why);
        handler.postDelayed(() -> {
            try { if(recognizer!=null) recognizer.cancel(); } catch(Exception ignored) {}
            createRecognizer(); restarting=false; startSession(20);
        }, delay);
    }
    private String speechTag(String code){ if("en".equals(code))return "en-US"; if("ru".equals(code))return "ru-RU"; if("de".equals(code))return "de-DE"; if("fr".equals(code))return "fr-FR"; if("es".equals(code))return "es-ES"; if("it".equals(code))return "it-IT"; if("pt".equals(code))return "pt-BR"; if("pl".equals(code))return "pl-PL"; if("tr".equals(code))return "tr-TR"; if("uk".equals(code))return "uk-UA"; if("zh".equals(code))return "zh-CN"; if("ja".equals(code))return "ja-JP"; if("ko".equals(code))return "ko-KR"; if("ar".equals(code))return "ar-SA"; if("hi".equals(code))return "hi-IN"; return code; }
    private Intent buildIntent(){
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3); i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        // Keep one recognition session alive through normal conversational pauses. This greatly
        // reduces endpoint/restart tones and avoids losing the first words of the next clause.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L);
        if (Build.VERSION.SDK_INT >= 33) { i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, "latency"); i.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true); }
        if (!"auto".equals(forcedLanguage)) {
            String tag=speechTag(forcedLanguage); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag); detectedLanguage=forcedLanguage;
        } else if (Build.VERSION.SDK_INT >= 34) {
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true); i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            ArrayList<String> common = new ArrayList<>(); common.add("en-US"); common.add("de-DE"); common.add("fr-FR"); common.add("es-ES"); common.add("it-IT"); common.add("ru-RU"); common.add("uk-UA"); common.add("pl-PL"); common.add("pt-BR"); common.add("tr-TR");
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, common); i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, common);
        }
        return i;
    }
    private void startSession(long delay) {
        if (!running || recognizer == null || restarting || listening) return;
        handler.postDelayed(() -> {
            if (!running || recognizer == null || restarting || listening) return;
            try { recognizer.startListening(buildIntent()); listening=true; trace.log("SESSION_START", "forced="+forcedLanguage); listener.onStatus("Слушаю…"); }
            catch(Exception e){ trace.log("SESSION_START_EXCEPTION", e.toString()); scheduleFullRestart("start-exception",120); }
        }, Math.max(0,delay));
    }
    public void stop() { running=false; listening=false; restarting=false; trace.log("STOP", "user"); handler.removeCallbacksAndMessages(null); if(recognizer!=null){ try{recognizer.cancel();}catch(Exception ignored){} try{recognizer.destroy();}catch(Exception ignored){} recognizer=null; } }
    @Override public void onReadyForSpeech(Bundle params) { trace.log("READY", ""); listener.onStatus("Говорите"); }
    @Override public void onBeginningOfSpeech() { trace.log("BEGIN", ""); listener.onStatus("Распознаю…"); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { trace.log("END", ""); listener.onStatus("Уточняю фразу…"); }
    @Override public void onError(int error) {
        listening=false; trace.log("ERROR", String.valueOf(error)); if (!running) return;
        if(error==SpeechRecognizer.ERROR_CLIENT || error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY){ scheduleFullRestart("error-"+error,100); return; }
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onError("Распознавание: код " + error);
        startSession(20);
    }
    @Override public void onResults(Bundle results) { listening=false; trace.log("FINAL", safeFirst(results)); emit(results, true); startSession(0); }
    @Override public void onPartialResults(Bundle partialResults) { trace.log("PARTIAL", safeFirst(partialResults)); emit(partialResults, false); }
    @Override public void onEvent(int eventType, Bundle params) { trace.log("EVENT", String.valueOf(eventType)); }
    @Override public void onLanguageDetection(Bundle results) { if (Build.VERSION.SDK_INT >= 34 && "auto".equals(forcedLanguage)) { int confidence = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN); String tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE); trace.log("LANGUAGE_DETECT", tag+" conf="+confidence); if (tag != null && !tag.isBlank() && confidence >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) detectedLanguage = tag; } }
    private String safeFirst(Bundle b){ ArrayList<String> list=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); return list==null||list.isEmpty()?"":list.get(0); }
    private void emit(Bundle bundle, boolean isFinal) { ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (list != null && !list.isEmpty()) listener.onText(list.get(0), isFinal, detectedLanguage); }
}
