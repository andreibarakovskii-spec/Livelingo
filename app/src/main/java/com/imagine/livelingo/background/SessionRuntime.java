package com.imagine.livelingo.background;

import android.content.Context;
import com.imagine.livelingo.TranslationEngine;
import com.imagine.livelingo.business.MeetingInsightEngine;
import com.imagine.livelingo.business.MeetingReportBuilder;
import com.imagine.livelingo.business.MeetingSessionStore;
import com.imagine.livelingo.stt.SttEngine;
import com.imagine.livelingo.stt.SystemSttEngine;
import com.imagine.livelingo.stt.WhisperOnnxEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local runtime owned by the foreground service. Keeps STT/translation alive when Activity is recreated. */
public final class SessionRuntime implements SttEngine.Listener {
    public interface Observer {
        void onState(Snapshot snapshot);
        void onFinalizedMeeting(String encryptedId, String report);
    }

    public static final class Snapshot {
        public final boolean active;
        public final String mode;
        public final String status;
        public final String sourceText;
        public final String translatedText;
        public final String detectedLanguage;
        public final String sttEngine;
        public final boolean whisperAvailable;
        public final List<MeetingInsightEngine.Insight> insights;
        Snapshot(boolean active,String mode,String status,String sourceText,String translatedText,String detectedLanguage,String sttEngine,boolean whisperAvailable,List<MeetingInsightEngine.Insight> insights){
            this.active=active; this.mode=mode; this.status=status; this.sourceText=sourceText; this.translatedText=translatedText; this.detectedLanguage=detectedLanguage; this.sttEngine=sttEngine; this.whisperAvailable=whisperAvailable; this.insights=insights;
        }
    }

    private static SessionRuntime instance;
    public static synchronized SessionRuntime get(Context context){
        if(instance==null) instance=new SessionRuntime(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private SttEngine speech;
    private final SystemSttEngine systemSpeech;
    private final WhisperOnnxEngine whisperSpeech;
    private final TranslationEngine translator;
    private final MeetingSessionStore meetingStore=new MeetingSessionStore();
    private final MeetingInsightEngine meetingEngine=new MeetingInsightEngine();
    private final List<MeetingInsightEngine.Insight> insights=new ArrayList<>();
    private final CopyOnWriteArrayList<Observer> observers=new CopyOnWriteArrayList<>();
    private boolean active;
    private String mode="translate";
    private String inputLanguage="auto";
    private String targetLanguage="ru";
    private String status="Готов";
    private String sourceText="";
    private String translatedText="";
    private String detectedLanguage;
    private String sttEngineName="system";

    private SessionRuntime(Context context){
        this.context=context;
        meetingStore.attachVault(context);
        translator=new TranslationEngine();
        systemSpeech=new SystemSttEngine(context,this);
        whisperSpeech=new WhisperOnnxEngine(context,this);
        selectBestEngine();
    }

    private void selectBestEngine(){
        if(whisperSpeech.isAvailable()){
            speech=whisperSpeech;
            sttEngineName="whisper";
        }else{
            speech=systemSpeech;
            sttEngineName="system";
        }
        speech.setInputLanguage(inputLanguage);
    }

    public synchronized void refreshEngine(){
        if(active) return;
        selectBestEngine();
        status="whisper".equals(sttEngineName)?"LiveLingo AI готов":"Системное распознавание · AI-модель не установлена";
        notifyState();
    }

    public void addObserver(Observer o){ if(o!=null){ observers.addIfAbsent(o); o.onState(snapshot()); } }
    public void removeObserver(Observer o){ observers.remove(o); }
    public synchronized Snapshot snapshot(){ return new Snapshot(active,mode,status,sourceText,translatedText,detectedLanguage,sttEngineName,whisperSpeech.isAvailable(),Collections.unmodifiableList(new ArrayList<>(insights))); }

    public synchronized void configure(String mode,String inputLanguage,String targetLanguage){
        if(mode!=null) this.mode=mode;
        if(inputLanguage!=null) this.inputLanguage=inputLanguage;
        if(targetLanguage!=null) this.targetLanguage=targetLanguage;
        speech.setInputLanguage(this.inputLanguage);
        translator.setTarget(this.targetLanguage);
    }

    public synchronized void start(){
        if(active) return;
        selectBestEngine();
        active=true; status="whisper".equals(sttEngineName)?"LiveLingo AI запускается…":"Слушаю…"; sourceText=""; translatedText="";
        if("meeting".equals(mode)){ meetingStore.start(); meetingEngine.reset(); insights.clear(); }
        speech.start(); notifyState();
    }

    public synchronized void stop(){
        if(!active) return;
        active=false; speech.stop(); status="Остановлено"; notifyState();
        if("meeting".equals(mode)) finalizeMeeting();
    }

    private void finalizeMeeting(){
        try{
            String report=MeetingReportBuilder.build(meetingStore,insights);
            String id=meetingStore.saveEncrypted("Совещание",report);
            for(Observer o:observers) o.onFinalizedMeeting(id,report);
        }catch(Exception e){ status="Не удалось сохранить встречу"; notifyState(); }
    }

    private void handleText(String text,boolean isFinal,String lang){
        synchronized(this){ sourceText=text; detectedLanguage=lang; }
        if("meeting".equals(mode) && isFinal){ synchronized(this){ insights.addAll(meetingEngine.extract(text)); } }
        final String hint="auto".equals(inputLanguage)?lang:inputLanguage;
        translator.translateAuto(text,hint,new TranslationEngine.Callback(){
            @Override public void onTranslated(String sourceLanguage,String translated){
                synchronized(SessionRuntime.this){
                    detectedLanguage=sourceLanguage; translatedText=translated; status=isFinal?"Слушаю дальше…":"Перевожу…";
                    if("meeting".equals(mode) && isFinal) meetingStore.add("Участник",sourceLanguage,text,translated);
                }
                notifyState();
            }
            @Override public void onError(String message){ synchronized(SessionRuntime.this){ status=message; } notifyState(); }
        });
        notifyState();
    }

    @Override public void onReady(){ synchronized(this){ status="Говорите"; } notifyState(); }
    @Override public void onPartial(String text,String language){ handleText(text,false,language); }
    @Override public void onFinal(String text,String language){ handleText(text,true,language); }
    @Override public void onStatus(String s){ synchronized(this){ status=s; } notifyState(); }
    @Override public void onError(String e){ synchronized(this){ status=e; } notifyState(); }

    private void notifyState(){ Snapshot s=snapshot(); for(Observer o:observers) o.onState(s); }
    public synchronized boolean isActive(){ return active; }
    public synchronized String sttEngine(){ return sttEngineName; }
    public synchronized boolean isWhisperAvailable(){ return whisperSpeech.isAvailable(); }
}
