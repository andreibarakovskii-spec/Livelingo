package com.imagine.livelingo;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

public final class LiveSpeechRecognizer implements RecognitionListener {
    public interface Listener {
        void onText(String text, boolean isFinal, String detectedLanguage);
        void onStatus(String status);
        void onError(String error);
        default void onSpeechStart() {}
    }

    private static final long READY_WATCHDOG_MS = 6000L;
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DebugTrace trace;
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean listening;
    private boolean restarting;
    private boolean compatibilityMode;
    private boolean sessionHadAudioEvent;
    private int sessionToken;
    private String detectedLanguage;
    private String forcedLanguage = "auto";

    public LiveSpeechRecognizer(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.trace = new DebugTrace(context);
    }

    public boolean isOnDeviceAvailable() {
        return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
    }

    /** Re-applying the same language is deliberately a no-op. */
    public void setInputLanguage(String code) {
        String next = (code == null || code.isBlank()) ? "auto" : code;
        if (next.equals(forcedLanguage)) {
            trace.log("LANGUAGE_UNCHANGED", next);
            return;
        }
        forcedLanguage = next;
        detectedLanguage = "auto".equals(forcedLanguage) ? null : forcedLanguage;
        trace.log("LANGUAGE_SET", forcedLanguage);
        if (running) scheduleFullRestart("language-change", 180);
    }

    public void start() {
        if (running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("На этом телефоне недоступен системный распознаватель речи");
            return;
        }
        running = true;
        compatibilityMode = !isOnDeviceAvailable();
        trace.clear();
        trace.log("START", "forced=" + forcedLanguage + " sdk=" + Build.VERSION.SDK_INT + " mode=" + (compatibilityMode ? "compat" : "on-device"));
        createRecognizer();
        startSession(40);
    }

    private void createRecognizer() {
        listening = false;
        sessionHadAudioEvent = false;
        sessionToken++;
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
        }
        try {
            recognizer = compatibilityMode
                    ? SpeechRecognizer.createSpeechRecognizer(context)
                    : SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            recognizer.setRecognitionListener(this);
            trace.log("RECOGNIZER_CREATE", compatibilityMode ? "compat" : "on-device");
        } catch (Throwable e) {
            trace.log("RECOGNIZER_CREATE_FAIL", e.toString());
            if (!compatibilityMode) {
                compatibilityMode = true;
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
                trace.log("RECOGNIZER_CREATE", "compat-fallback");
            } else {
                recognizer = null;
                listener.onError("Не удалось открыть распознаватель речи");
            }
        }
    }

    private void scheduleFullRestart(String why, long delay) {
        if (!running || restarting) return;
        restarting = true;
        listening = false;
        sessionToken++;
        trace.log("RESTART_SCHEDULE", why);
        handler.postDelayed(() -> {
            if (!running) { restarting = false; return; }
            try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
            if (!running) { restarting = false; return; }
            createRecognizer();
            restarting = false;
            startSession(20);
        }, delay);
    }

    private String speechTag(String code) {
        if ("en".equals(code)) return "en-US";
        if ("ru".equals(code)) return "ru-RU";
        if ("de".equals(code)) return "de-DE";
        if ("fr".equals(code)) return "fr-FR";
        if ("es".equals(code)) return "es-ES";
        if ("it".equals(code)) return "it-IT";
        if ("pt".equals(code)) return "pt-BR";
        if ("pl".equals(code)) return "pl-PL";
        if ("tr".equals(code)) return "tr-TR";
        if ("uk".equals(code)) return "uk-UA";
        if ("zh".equals(code)) return "zh-CN";
        if ("ja".equals(code)) return "ja-JP";
        if ("ko".equals(code)) return "ko-KR";
        if ("ar".equals(code)) return "ar-SA";
        if ("hi".equals(code)) return "hi-IN";
        return code;
    }

    private Intent buildIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !compatibilityMode);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L);
        if (Build.VERSION.SDK_INT >= 33) {
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, "latency");
            i.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true);
        }
        if (!"auto".equals(forcedLanguage)) {
            String tag = speechTag(forcedLanguage);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
            detectedLanguage = forcedLanguage;
        } else if (!compatibilityMode && Build.VERSION.SDK_INT >= 34) {
            // Language switching is only enabled on the on-device recognizer. Several OEM
            // recognition services become stuck in READY when these extras are supplied.
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            ArrayList<String> common = new ArrayList<>();
            common.add("en-US"); common.add("de-DE"); common.add("fr-FR"); common.add("es-ES");
            common.add("it-IT"); common.add("ru-RU"); common.add("uk-UA"); common.add("pl-PL");
            common.add("pt-BR"); common.add("tr-TR");
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, common);
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, common);
        }
        return i;
    }

    private void startSession(long delay) {
        if (!running || recognizer == null || restarting || listening) return;
        handler.postDelayed(() -> {
            if (!running || recognizer == null || restarting || listening) return;
            try {
                sessionHadAudioEvent = false;
                recognizer.startListening(buildIntent());
                listening = true;
                trace.log("SESSION_START", "forced=" + forcedLanguage + " mode=" + (compatibilityMode ? "compat" : "on-device"));
                listener.onStatus("Слушаю…");
            } catch (Exception e) {
                trace.log("SESSION_START_EXCEPTION", e.toString());
                scheduleFullRestart("start-exception", 120);
            }
        }, Math.max(0, delay));
    }

    private void armReadyWatchdog() {
        final int token = sessionToken;
        handler.postDelayed(() -> {
            if (!running || token != sessionToken || !listening || sessionHadAudioEvent) return;
            trace.log("WATCHDOG_TIMEOUT", "no audio callbacks; mode=" + (compatibilityMode ? "compat" : "on-device"));
            if (!compatibilityMode) {
                compatibilityMode = true;
                listener.onStatus("Переключаю распознавание в режим совместимости…");
                trace.log("FALLBACK_TRIGGERED", "system-compatibility");
                scheduleFullRestart("ready-no-audio", 80);
            } else {
                listener.onStatus("Проверяю микрофон…");
                probeMicrophoneAsync();
            }
        }, READY_WATCHDOG_MS);
    }

    private void markAudioEvent(String kind) {
        if (!sessionHadAudioEvent) trace.log("AUDIO_EVENT", kind);
        sessionHadAudioEvent = true;
    }

    private void probeMicrophoneAsync() {
        final int token = sessionToken;
        listening = false;
        sessionToken++;
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
        new Thread(() -> {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                trace.log("MIC_PROBE", "permission-denied");
                handler.post(() -> listener.onError("Нет доступа к микрофону"));
                return;
            }
            final int rate = 16000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                trace.log("MIC_PROBE", "buffer-error=" + min);
                handler.post(() -> listener.onError("Android не открыл аудиовход"));
                return;
            }
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate * 2));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("not-initialized");
                short[] buf = new short[Math.max(1024, min / 2)];
                rec.startRecording();
                long until = System.currentTimeMillis() + 1400L;
                long samples = 0; double sum = 0; int peak = 0;
                while (System.currentTimeMillis() < until && running) {
                    int n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) continue;
                    samples += n;
                    for (int x = 0; x < n; x++) { int v = Math.abs((int) buf[x]); if (v > peak) peak = v; sum += (double) v * v; }
                }
                double rms = samples == 0 ? 0 : Math.sqrt(sum / samples);
                trace.log("MIC_PROBE", "samples=" + samples + " rms=" + String.format(Locale.US, "%.0f", rms) + " peak=" + peak);
                final boolean alive = samples > 4000 && peak > 20;
                handler.post(() -> {
                    if (!running) return;
                    if (alive) listener.onStatus("Микрофон работает · перезапускаю распознавание");
                    else listener.onError("Микрофон не отдаёт аудиосигнал. Проверьте доступ к микрофону в Android");
                    if (alive) scheduleFullRestart("mic-probe-ok", 250);
                });
            } catch (Throwable e) {
                trace.log("MIC_PROBE_FAIL", e.toString());
                handler.post(() -> { if (running) listener.onError("Не удалось проверить микрофон: " + e.getMessage()); });
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Exception ignored) {}
                    try { rec.release(); } catch (Exception ignored) {}
                }
            }
        }, "livelingo-mic-probe").start();
    }

    public void stop() {
        running = false;
        listening = false;
        restarting = false;
        sessionToken++;
        trace.log("STOP", "user");
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {
        trace.log("READY", compatibilityMode ? "compat" : "on-device");
        listener.onStatus("Говорите");
        armReadyWatchdog();
    }

    @Override public void onBeginningOfSpeech() {
        markAudioEvent("begin");
        trace.log("BEGIN", "");
        listener.onSpeechStart();
        listener.onStatus("Распознаю…");
    }

    @Override public void onRmsChanged(float rmsdB) {
        markAudioEvent("rms");
        if (rmsdB > 2f) trace.log("AUDIO_LEVEL", String.format(Locale.US, "%.1f", rmsdB));
    }

    @Override public void onBufferReceived(byte[] buffer) {
        markAudioEvent("buffer");
    }

    @Override public void onEndOfSpeech() {
        markAudioEvent("end");
        trace.log("END", "");
        listener.onStatus("Уточняю фразу…");
    }

    @Override public void onError(int error) {
        listening = false;
        sessionToken++;
        trace.log("ERROR", String.valueOf(error));
        if (!running) return;
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            scheduleFullRestart("error-" + error, 100);
            return;
        }
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            listener.onError("Распознавание: код " + error);
        startSession(20);
    }

    @Override public void onResults(Bundle results) {
        listening = false;
        sessionToken++;
        markAudioEvent("final");
        trace.log("FINAL", safeFirst(results));
        emit(results, true);
        startSession(0);
    }

    @Override public void onPartialResults(Bundle partialResults) {
        markAudioEvent("partial");
        trace.log("PARTIAL", safeFirst(partialResults));
        emit(partialResults, false);
    }

    @Override public void onEvent(int eventType, Bundle params) {
        trace.log("EVENT", String.valueOf(eventType));
    }

    @Override public void onLanguageDetection(Bundle results) {
        if (Build.VERSION.SDK_INT >= 34 && "auto".equals(forcedLanguage)) {
            int confidence = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN);
            String tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
            trace.log("LANGUAGE_DETECT", tag + " conf=" + confidence);
            if (tag != null && !tag.isBlank() && confidence >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT)
                detectedLanguage = tag;
        }
    }

    private String safeFirst(Bundle b) {
        ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private void emit(Bundle bundle, boolean isFinal) {
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) listener.onText(list.get(0), isFinal, detectedLanguage);
    }
}
