package com.imagine.livelingo.stt;

import com.imagine.livelingo.audio.VoiceProfile;

public interface SttEngine {
    interface Listener {
        void onReady();
        void onPartial(String text, String language);
        void onFinal(String text, String language);
        void onError(String message);
        void onStatus(String status);
        default void onVoiceProfile(VoiceProfile profile) {}
        /** Fired when a new acoustic speech segment begins, before transcription is ready. */
        default void onSpeechStart() {}
    }

    boolean isAvailable();
    void setInputLanguage(String code);
    void start();
    void stop();
    void close();
}
