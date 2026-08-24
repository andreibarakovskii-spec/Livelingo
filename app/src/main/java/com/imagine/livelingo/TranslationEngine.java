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
        final long started=System.currentTimeMillis();
        final String hint = normalizeTag(detectedHint);
        final String dst = normalizeTag(requestedTarget);
        DebugTrace.logGlobal("TRANSLATE_REQUEST","hint="+hint+" target="+dst+" chars="+text.length()+" text="+preview(text));
        if(dst==null){DebugTrace.logGlobal("TRANSLATE_ERROR","target unresolved");cb.onError("Язык перевода пока не определён");return;}
        identifier.identifyLanguage(text).addOnSuccessListener(code -> {
            String identified = normalizeTag(code);
            String chosen = chooseSource(text, hint, identified);
            DebugTrace.logGlobal("LANGUAGE_ID","hint="+hint+" mlkit="+identified+" chosen="+chosen+" target="+dst);
            if (chosen == null) {DebugTrace.logGlobal("TRANSLATE_ERROR","source unresolved");cb.onError("Язык пока не определён");}
            else translate(text, chosen, dst, started, cb);
        }).addOnFailureListener(e -> {
            DebugTrace.logGlobal("LANGUAGE_ID_ERROR",safe(e.getMessage()));
            if (hint != null) translate(text, hint, dst, started, cb);
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
    private void translate(String text, String source,String requestedTarget,long started, Callback cb) {
        if (source == null) { DebugTrace.logGlobal("TRANSLATE_ERROR","unsupported source"); cb.onError("Этот язык пока не поддерживается переводчиком"); return; }
        if (source.equals(requestedTarget)) { DebugTrace.logGlobal("TRANSLATE_BYPASS","same language="+source); cb.onTranslated(source, text); return; }
        String src = TranslateLanguage.fromLanguageTag(source), dst = TranslateLanguage.fromLanguageTag(requestedTarget);
        if (src == null || dst == null) { DebugTrace.logGlobal("TRANSLATE_ERROR","unsupported pair "+source+">"+requestedTarget); cb.onError("Перевод этой языковой пары пока не поддерживается"); return; }
        String key = src + ">" + dst; Translator translator = translators.get(key);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build());
            translators.put(key, translator);
            DebugTrace.logGlobal("TRANSLATE_MODEL","created pair="+key);
        }
        Translator t = translator;
        t.downloadModelIfNeeded(new DownloadConditions.Builder().build()).addOnSuccessListener(v -> {
            DebugTrace.logGlobal("TRANSLATE_MODEL_READY","pair="+key);
            t.translate(text).addOnSuccessListener(out -> {
                DebugTrace.logGlobal("TRANSLATE_OK","pair="+key+" ms="+(System.currentTimeMillis()-started)+" chars="+out.length()+" text="+preview(out));
                cb.onTranslated(source, out);
            }).addOnFailureListener(e -> {DebugTrace.logGlobal("TRANSLATE_ERROR","pair="+key+" "+safe(e.getMessage()));cb.onError("Ошибка перевода: " + e.getMessage());});
        }).addOnFailureListener(e -> {DebugTrace.logGlobal("TRANSLATE_MODEL_ERROR","pair="+key+" "+safe(e.getMessage()));cb.onError("Нужна сеть один раз, чтобы скачать языковую модель");});
    }
    private static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank() || "und".equalsIgnoreCase(tag) || "auto".equalsIgnoreCase(tag)) return null; String lang = tag.split("[-_]")[0].toLowerCase();
        return TranslateLanguage.fromLanguageTag(lang) == null ? null : lang;
    }
    private static String preview(String s){if(s==null)return "";String x=s.replace('\n',' ').trim();return x.length()>100?x.substring(0,100)+"…":x;}
    private static String safe(String s){return s==null?"":s.replace('\n',' ');}
    public void close() { identifier.close(); for (Translator t : translators.values()) t.close(); translators.clear(); }
}
