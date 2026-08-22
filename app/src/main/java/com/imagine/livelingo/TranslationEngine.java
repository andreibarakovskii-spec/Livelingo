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
    public void translateAuto(String text, String detectedHint, Callback cb) { translateAutoTo(text,detectedHint,target,cb); }
    public void translateAutoTo(String text,String detectedHint,String requestedTarget,Callback cb) {
        if (text == null || text.trim().isEmpty()) return;
        final String hint = normalizeTag(detectedHint);
        final String dst = normalizeTag(requestedTarget);
        if(dst==null){cb.onError("Язык перевода пока не определён");return;}
        identifier.identifyLanguage(text).addOnSuccessListener(code -> {
            String identified = normalizeTag(code);
            String chosen = chooseSource(text, hint, identified);
            if (chosen == null) cb.onError("Язык пока не определён");
            else translate(text, chosen, dst, cb);
        }).addOnFailureListener(e -> {
            if (hint != null) translate(text, hint, dst, cb);
            else cb.onError("Не удалось определить язык: " + e.getMessage());
        });
    }
    private String chooseSource(String text, String hint, String identified) {
        String t = text == null ? "" : text.trim();
        boolean latin = t.matches(".*[A-Za-z].*");
        boolean cyrillic = t.matches(".*[А-Яа-яЁёІіЇїЄєҐґ].*");
        if (latin && "ru".equals(hint)) return identified != null ? identified : "en";
        if (latin && ("uk".equals(hint) || "bg".equals(hint) || "sr".equals(hint))) return identified != null ? identified : "en";
        if (cyrillic && "en".equals(hint)) return identified != null ? identified : "ru";
        if (identified != null && hint != null && !identified.equals(hint)) {
            if (latin && ("en".equals(identified) || "de".equals(identified) || "fr".equals(identified) || "es".equals(identified) || "it".equals(identified) || "pt".equals(identified))) return identified;
            if (cyrillic && ("ru".equals(identified) || "uk".equals(identified))) return identified;
        }
        return hint != null ? hint : identified;
    }
    private void translate(String text, String source,String requestedTarget, Callback cb) {
        if (source == null) { cb.onError("Этот язык пока не поддерживается переводчиком"); return; }
        if (source.equals(requestedTarget)) { cb.onTranslated(source, text); return; }
        String src = TranslateLanguage.fromLanguageTag(source), dst = TranslateLanguage.fromLanguageTag(requestedTarget);
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
        if (tag == null || tag.isBlank() || "und".equalsIgnoreCase(tag) || "auto".equalsIgnoreCase(tag)) return null; String lang = tag.split("[-_]")[0].toLowerCase();
        return TranslateLanguage.fromLanguageTag(lang) == null ? null : lang;
    }
    public void close() { identifier.close(); for (Translator t : translators.values()) t.close(); translators.clear(); }
}
