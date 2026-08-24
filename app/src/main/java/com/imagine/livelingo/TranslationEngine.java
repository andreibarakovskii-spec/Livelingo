package com.imagine.livelingo;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TranslationEngine {
    public interface Callback { void onTranslated(String sourceLanguage, String translated); void onError(String message); }

    private static final class Pending {
        final String text,source; final long started; final Callback cb;
        Pending(String text,String source,long started,Callback cb){this.text=text;this.source=source;this.started=started;this.cb=cb;}
    }
    private static final class PairState {
        final Translator translator; final List<Pending> pending=new ArrayList<>();
        boolean loading,ready;
        PairState(Translator translator){this.translator=translator;}
    }

    private final LanguageIdentifier identifier = LanguageIdentification.getClient();
    private final Map<String, PairState> pairs = new HashMap<>();
    private String target = "ru";

    public void setTarget(String targetLanguage) { this.target = targetLanguage; }
    public void translateAuto(String text, String detectedHint, Callback cb) { translateAutoTo(text,detectedHint,target,cb); }

    /** Starts the one-time ML Kit model download before the first spoken phrase. */
    public void preparePair(String sourceLanguage,String targetLanguage){
        String src=normalizeTag(sourceLanguage),dst=normalizeTag(targetLanguage);
        if(src==null||dst==null||src.equals(dst))return;
        ensurePairReady(src,dst,null);
    }

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
        ensurePairReady(src,dst,new Pending(text,source,started,cb));
    }

    private void ensurePairReady(String src,String dst,Pending item){
        final String key=src+">"+dst; final PairState state;
        synchronized(this){
            PairState existing=pairs.get(key);
            if(existing==null){
                Translator translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build());
                existing=new PairState(translator);pairs.put(key,existing);
                DebugTrace.logGlobal("TRANSLATE_MODEL","created pair="+key);
            }
            state=existing;
            if(item!=null){
                if(state.ready){runTranslation(key,state,item);return;}
                // During initial model download retain only useful work; exact duplicates are collapsed.
                for(Pending p:state.pending){
                    if(p.text.equals(item.text)&&p.source.equals(item.source)){
                        DebugTrace.logGlobal("TRANSLATE_QUEUE_DROP","pair="+key+" duplicate="+preview(item.text));return;
                    }
                }
                state.pending.add(item);
                if(state.pending.size()>8){Pending dropped=state.pending.remove(0);DebugTrace.logGlobal("TRANSLATE_QUEUE_DROP","pair="+key+" stale="+preview(dropped.text));}
                DebugTrace.logGlobal("TRANSLATE_QUEUE","pair="+key+" size="+state.pending.size());
            }
            if(state.ready||state.loading)return;
            state.loading=true;
        }
        long modelStarted=System.currentTimeMillis();
        state.translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()).addOnSuccessListener(v -> {
            List<Pending> queued;
            synchronized(TranslationEngine.this){state.loading=false;state.ready=true;queued=new ArrayList<>(state.pending);state.pending.clear();}
            DebugTrace.logGlobal("TRANSLATE_MODEL_READY","pair="+key+" ms="+(System.currentTimeMillis()-modelStarted)+" queued="+queued.size());
            for(Pending p:queued)runTranslation(key,state,p);
        }).addOnFailureListener(e -> {
            List<Pending> queued;
            synchronized(TranslationEngine.this){state.loading=false;queued=new ArrayList<>(state.pending);state.pending.clear();}
            DebugTrace.logGlobal("TRANSLATE_MODEL_ERROR","pair="+key+" "+safe(e.getMessage()));
            for(Pending p:queued)p.cb.onError("Нужна сеть один раз, чтобы скачать языковую модель");
        });
    }

    private void runTranslation(String key,PairState state,Pending p){
        state.translator.translate(p.text).addOnSuccessListener(out -> {
            DebugTrace.logGlobal("TRANSLATE_OK","pair="+key+" ms="+(System.currentTimeMillis()-p.started)+" chars="+out.length()+" text="+preview(out));
            p.cb.onTranslated(p.source,out);
        }).addOnFailureListener(e -> {DebugTrace.logGlobal("TRANSLATE_ERROR","pair="+key+" "+safe(e.getMessage()));p.cb.onError("Ошибка перевода: " + e.getMessage());});
    }

    private static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank() || "und".equalsIgnoreCase(tag) || "auto".equalsIgnoreCase(tag)) return null;
        String lang = tag.split("[-_]")[0].toLowerCase();
        return TranslateLanguage.fromLanguageTag(lang) == null ? null : lang;
    }
    private static String preview(String s){if(s==null)return "";String x=s.replace('\n',' ').trim();return x.length()>100?x.substring(0,100)+"…":x;}
    private static String safe(String s){return s==null?"":s.replace('\n',' ');}
    public synchronized void close() { identifier.close(); for (PairState s : pairs.values()) s.translator.close(); pairs.clear(); }
}
