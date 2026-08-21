package com.imagine.livelingo;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.HashMap;
import java.util.Map;

public final class TranslationEngine {
    public interface Callback { void onTranslated(String sourceLanguage, String translated); void onError(String message); }
    private final LanguageIdentifier identifier = LanguageIdentification.getClient();
    private final Map<String, Translator> translators = new HashMap<>(); private String target = "ru";
    public void setTarget(String targetLanguage) { this.target = targetLanguage; }
    public void translateAuto(String text, String detectedHint, Callback cb) {
        if (text == null || text.trim().isEmpty()) return; String hint = normalizeTag(detectedHint);
        if (hint != null) { translate(text, hint, cb); return; }
        identifier.identifyLanguage(text).addOnSuccessListener(code -> {
            if (code == null || "und".equals(code)) cb.onError("Язык пока не определён");
            else translate(text, normalizeTag(code), cb);
        }).addOnFailureListener(e -> cb.onError("Не удалось определить язык: " + e.getMessage()));
    }
    private void translate(String text, String source, Callback cb) {
        if (source == null) { cb.onError("Этот язык пока не поддерживается переводчиком"); return; }
        if (source.equals(target)) { cb.onTranslated(source, text); return; }
        String src = TranslateLanguage.fromLanguageTag(source), dst = TranslateLanguage.fromLanguageTag(target);
        if (src == null || dst == null) { cb.onError("Перевод этой языковой пары пока не поддерживается"); return; }
        String key = src + ">" + dst; Translator translator = translators.get(key);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build());
            translators.put(key, translator);
        }
        Translator t = translator;
        t.downloadModelIfNeeded(new DownloadConditions.Builder().build()).addOnSuccessListener(v -> t.translate(text)
                .addOnSuccessListener(out -> cb.onTranslated(source, out))
                .addOnFailureListener(e -> cb.onError("Ошибка перевода: " + e.getMessage())))
                .addOnFailureListener(e -> cb.onError("Нужна сеть один раз, чтобы скачать языковую модель"));
    }
    private static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) return null; String lang = tag.split("[-_]")[0].toLowerCase();
        return TranslateLanguage.fromLanguageTag(lang) == null ? null : lang;
    }
    public void close() { identifier.close(); for (Translator t : translators.values()) t.close(); translators.clear(); }
}
