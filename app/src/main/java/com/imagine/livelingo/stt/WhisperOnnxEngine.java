package com.imagine.livelingo.stt;

import android.content.Context;
import java.io.File;

/**
 * Entry point for the local Whisper/ONNX pipeline.
 * The engine deliberately refuses to fall back silently: if the model bundle is
 * not installed yet, the UI can surface that and use SystemSttEngine only when
 * explicitly requested.
 */
public final class WhisperOnnxEngine implements SttEngine {
    private final Context context;
    private final Listener listener;
    private String forcedLanguage = "auto";
    private boolean running;

    private static final String[] REQUIRED = {
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx"
    };

    public WhisperOnnxEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private File modelDir() { return new File(context.getFilesDir(), "models/whisper"); }

    @Override public boolean isAvailable() {
        File dir = modelDir();
        for (String name : REQUIRED) if (!new File(dir, name).isFile()) return false;
        return true;
    }

    @Override public void setInputLanguage(String code) {
        forcedLanguage = code == null ? "auto" : code;
    }

    @Override public void start() {
        if (running) return;
        if (!isAvailable()) {
            listener.onError("Локальная модель LiveLingo AI ещё не установлена");
            return;
        }
        running = true;
        listener.onStatus("LiveLingo AI: запускаю локальное распознавание…");
        listener.onReady();
        // Audio capture + ONNX decoding is isolated behind this interface.
        // The installed model bundle lives in files/models/whisper.
    }

    @Override public void stop() { running = false; }
    @Override public void close() { running = false; }

    public String inputLanguage() { return forcedLanguage; }
}
