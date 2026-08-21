package com.imagine.livelingo.stt;

public interface SttEngine {
    interface Listener {
        void onReady();
        void onPartial(String text, String language);
        void onFinal(String text, String language);
        void onError(String message);
        void onStatus(String status);
    }

    boolean isAvailable();
    void setInputLanguage(String code);
    void start();
    void stop();
    void close();
}
