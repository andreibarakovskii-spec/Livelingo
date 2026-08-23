package com.imagine.livelingo.background;

import android.content.Context;
import android.content.SharedPreferences;
import com.imagine.livelingo.OfflineSpeaker;
import com.imagine.livelingo.TranslationEngine;
import com.imagine.livelingo.audio.SpeakerRouter;
import com.imagine.livelingo.audio.VoiceProfile;
import com.imagine.livelingo.business.Insight;
import com.imagine.livelingo.business.MeetingInsightEngine;
import com.imagine.livelingo.business.MeetingReportBuilder;
import com.imagine.livelingo.business.MeetingSessionStore;
import com.imagine.livelingo.core.SpokenDiff;
import com.imagine.livelingo.stt.SttEngine;
import com.imagine.livelingo.stt.SystemSttEngine;
import com.imagine.livelingo.stt.WhisperOnnxEngine;
import com.imagine.livelingo.tts.NeuralVoiceManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local runtime owned by the foreground service. Keeps STT/translation alive when Activity is recreated. */
public final class SessionRuntime implements SttEngine.Listener {
    public static final String VOICE_OFF="off",VOICE_AI="ai",VOICE_SYSTEM="system";
    private static final String PREFS="livelingo_runtime",KEY_VOICE_MODE="voice_mode";

    public interface Observer{void onState(Snapshot snapshot);void onFinalizedMeeting(String encryptedId,String report);}
    public static final class Snapshot{
        public final boolean active;
        public final String mode,status,sourceText,translatedText,detectedLanguage,sttEngine,speakerLabel,voiceMode;
        public final boolean whisperAvailable,neuralVoiceInstalled,multilingualVoiceInstalled;
        public final List<Insight> insights;
        Snapshot(boolean active,String mode,String status,String sourceText,String translatedText,String detectedLanguage,String sttEngine,String speakerLabel,String voiceMode,boolean whisperAvailable,boolean neuralVoiceInstalled,boolean multilingualVoiceInstalled,List<Insight> insights){
            this.active=active;this.mode=mode;this.status=status;this.sourceText=sourceText;this.translatedText=translatedText;this.detectedLanguage=detectedLanguage;this.sttEngine=sttEngine;this.speakerLabel=speakerLabel;this.voiceMode=voiceMode;this.whisperAvailable=whisperAvailable;this.neuralVoiceInstalled=neuralVoiceInstalled;this.multilingualVoiceInstalled=multilingualVoiceInstalled;this.insights=insights;
        }
    }
    private static SessionRuntime instance;
    public static synchronized SessionRuntime get(Context context){if(instance==null)instance=new SessionRuntime(context.getApplicationContext());return instance;}

    private final Context context;private final SharedPreferences prefs;private SttEngine speech;private final SystemSttEngine systemSpeech;private final WhisperOnnxEngine whisperSpeech;
    private final TranslationEngine translator;private final OfflineSpeaker systemSpeaker;private final NeuralVoiceManager neuralSpeaker;private final SpokenDiff spokenDiff=new SpokenDiff(2);
    private final SpeakerRouter speakerRouter=new SpeakerRouter();
    private final MeetingSessionStore meetingStore=new MeetingSessionStore();private final MeetingInsightEngine meetingEngine=new MeetingInsightEngine();
    private final List<Insight> insights=new ArrayList<>();private final CopyOnWriteArrayList<Observer> observers=new CopyOnWriteArrayList<>();
    private boolean active;private String mode="translate";private String inputLanguage="auto";private String targetLanguage="ru";private String voiceMode=VOICE_OFF;
    private String status="Готов",sourceText="",translatedText="",detectedLanguage,sttEngineName="system",speakerLabel="";private long utteranceGeneration=0;
    private int currentSpeaker=1;private VoiceProfile currentVoice=new VoiceProfile(0,0,0,VoiceProfile.Band.NEUTRAL);
    private String learnedConversationLang1,learnedConversationLang2;

    private SessionRuntime(Context context){
        this.context=context;this.prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);voiceMode=normalizeVoiceMode(prefs.getString(KEY_VOICE_MODE,VOICE_OFF));meetingStore.attachVault(context);translator=new TranslationEngine();
        systemSpeaker=new OfflineSpeaker(context,s->{synchronized(SessionRuntime.this){if(active&&!"meeting".equals(mode)&&!s.startsWith("Озвучка готова"))status=s;}notifyState();});
        neuralSpeaker=new NeuralVoiceManager(context,new NeuralVoiceManager.Listener(){
            @Override public void onStatus(String s){synchronized(SessionRuntime.this){if(!active||!"meeting".equals(mode))status=s;}notifyState();}
            @Override public void onDownloadProgress(int p){synchronized(SessionRuntime.this){status="AI Voice · "+p+"%";}notifyState();}
        },(text,language,finalChunk,profile)->systemSpeaker.speak(text,finalChunk,language,bandFromInt(profile)));
        systemSpeech=new SystemSttEngine(context,this);whisperSpeech=new WhisperOnnxEngine(context,this);selectBestEngine();
    }

    /** Meeting and Conversation prefer the persistent PCM/Whisper path. The system recognizer is only a compatibility fallback. */
    private void selectBestEngine(){
        if(whisperSpeech.isAvailable()){speech=whisperSpeech;sttEngineName="whisper";}else{speech=systemSpeech;sttEngineName="system";}
        boolean continuousMode="conversation".equals(mode)||"meeting".equals(mode);
        speech.setInputLanguage(continuousMode?"auto":inputLanguage);
    }
    public synchronized void refreshEngine(){if(active)return;selectBestEngine();status="whisper".equals(sttEngineName)?"LiveLingo AI готов":"Системное распознавание · установите Whisper для бесшумной непрерывной записи";notifyState();}
    public void addObserver(Observer o){if(o!=null){observers.addIfAbsent(o);o.onState(snapshot());}}public void removeObserver(Observer o){observers.remove(o);}
    public synchronized Snapshot snapshot(){return new Snapshot(active,mode,status,sourceText,translatedText,detectedLanguage,sttEngineName,speakerLabel,voiceMode,whisperSpeech.isAvailable(),neuralSpeaker.isInstalled(),neuralSpeaker.isMultilingualInstalled(),Collections.unmodifiableList(new ArrayList<>(insights)));}

    public void downloadNeuralVoice(){neuralSpeaker.downloadModel();}
    public void downloadMultilingualVoice(){neuralSpeaker.downloadMultilingualModel();}
    public boolean isNeuralVoiceInstalled(){return neuralSpeaker.isInstalled();}
    public boolean isMultilingualVoiceInstalled(){return neuralSpeaker.isMultilingualInstalled();}
    public long neuralVoiceBytes(){return neuralSpeaker.modelSizeBytes();}
    public synchronized String voiceMode(){return voiceMode;}
    public synchronized void setVoiceMode(String requested){
        String next=normalizeVoiceMode(requested);if(next.equals(voiceMode))return;
        voiceMode=next;prefs.edit().putString(KEY_VOICE_MODE,next).apply();spokenDiff.reset();systemSpeaker.stop();neuralSpeaker.stop();
        if(VOICE_OFF.equals(next))status=active?"Слушаю · озвучка выключена":"Озвучка выключена";
        else if(VOICE_AI.equals(next))status=(neuralSpeaker.isInstalled()||neuralSpeaker.isMultilingualInstalled())?"AI-озвучка включена":"AI-голос включён · пока используется резервный голос";
        else status="Системная озвучка включена";
        notifyState();
    }

    /** In conversation mode inputLanguage=participant 1, targetLanguage=participant 2. Both may be auto. */
    public synchronized void configure(String mode,String inputLanguage,String targetLanguage){
        String nextMode=mode==null?this.mode:mode;
        String nextInput=inputLanguage==null?this.inputLanguage:inputLanguage;
        String nextTarget=targetLanguage==null?this.targetLanguage:targetLanguage;
        boolean pairChanged=!nextMode.equals(this.mode)||!nextInput.equals(this.inputLanguage)||!nextTarget.equals(this.targetLanguage);
        if(!pairChanged)return;
        this.mode=nextMode;this.inputLanguage=nextInput;this.targetLanguage=nextTarget;
        boolean continuousMode="conversation".equals(this.mode)||"meeting".equals(this.mode);
        speech.setInputLanguage(continuousMode?"auto":this.inputLanguage);translator.setTarget(this.targetLanguage);systemSpeaker.selectOfflineVoice("auto".equals(this.targetLanguage)?"ru":this.targetLanguage);spokenDiff.reset();utteranceGeneration++;
        learnedConversationLang1=null;learnedConversationLang2=null;speakerRouter.reset();currentSpeaker=1;speakerLabel="";
    }
    public synchronized void start(){
        if(active)return;selectBestEngine();active=true;
        if("meeting".equals(mode))status="whisper".equals(sttEngineName)?"Непрерывная запись встречи · микрофон не закрывается":"Режим совместимости · установите Whisper, чтобы убрать системные сигналы";
        else status="whisper".equals(sttEngineName)?"LiveLingo AI запускается…":"Слушаю…";
        sourceText="";translatedText="";spokenDiff.reset();utteranceGeneration++;speakerRouter.reset();learnedConversationLang1=null;learnedConversationLang2=null;currentSpeaker=1;speakerLabel="";
        if(!"auto".equals(targetLanguage))systemSpeaker.selectOfflineVoice(targetLanguage);
        if("meeting".equals(mode)){meetingStore.start();meetingEngine.reset();insights.clear();}
        speech.start();notifyState();
    }
    public synchronized void stop(){if(!active)return;active=false;speech.stop();systemSpeaker.stop();neuralSpeaker.stop();spokenDiff.reset();speakerRouter.reset();utteranceGeneration++;status="Остановлено";notifyState();if("meeting".equals(mode))finalizeMeeting();}
    private void finalizeMeeting(){try{String report=MeetingReportBuilder.build(meetingStore,insights);String id=meetingStore.saveEncrypted("Совещание",report);for(Observer o:observers)o.onFinalizedMeeting(id,report);}catch(Exception e){status="Не удалось сохранить встречу";notifyState();}}

    private static final class Direction{final String source,target,label;final int speaker;Direction(String source,String target,String label,int speaker){this.source=source;this.target=target;this.label=label;this.speaker=speaker;}}
    private synchronized Direction conversationDirection(String lang){
        String src=shortLang(lang),a=shortLang(inputLanguage),b=shortLang(targetLanguage);int sp=currentSpeaker;
        if(src!=null){if(a!=null&&src.equals(a))sp=1;else if(b!=null&&src.equals(b))sp=2;if(sp==1&&learnedConversationLang1==null)learnedConversationLang1=src;if(sp==2&&learnedConversationLang2==null)learnedConversationLang2=src;}
        String l1=a!=null?a:learnedConversationLang1;String l2=b!=null?b:learnedConversationLang2;
        if(sp==1&&l1==null&&src!=null){l1=src;learnedConversationLang1=src;}if(sp==2&&l2==null&&src!=null){l2=src;learnedConversationLang2=src;}
        String dst=sp==1?l2:l1;String hint=src!=null?src:(sp==1?l1:l2);return new Direction(hint,dst,"Собеседник "+sp,sp);
    }

    private void handleText(String text,boolean isFinal,String lang){
        final long generation;final String chosenTarget;final String hint;final String lineSpeaker;final VoiceProfile.Band voiceBand;
        synchronized(this){
            if(!"meeting".equals(mode))sourceText=text;
            detectedLanguage=lang;generation=utteranceGeneration;
            if("conversation".equals(mode)){Direction d=conversationDirection(lang);chosenTarget=d.target;hint=d.source;lineSpeaker=d.label;speakerLabel=d.label;}
            else{chosenTarget=targetLanguage;hint="auto".equals(inputLanguage)?lang:inputLanguage;lineSpeaker="meeting".equals(mode)?("Спикер "+currentSpeaker):"Собеседник";speakerLabel=lineSpeaker;}
            voiceBand=currentVoice==null?VoiceProfile.Band.NEUTRAL:currentVoice.band;
        }
        if("meeting".equals(mode)&&isFinal){synchronized(this){insights.addAll(meetingEngine.analyze(text,meetingStore.durationMs()));}}
        if(chosenTarget==null||"auto".equals(chosenTarget)){
            synchronized(this){translatedText="";status="conversation".equals(mode)?"Определяю язык второго собеседника…":"Язык перевода не выбран";
                if("meeting".equals(mode)&&isFinal){meetingStore.add(lineSpeaker,lang,text,"");sourceText=buildMeetingChat();utteranceGeneration++;}}
            notifyState();return;
        }
        translator.translateAutoTo(text,hint,chosenTarget,new TranslationEngine.Callback(){
            @Override public void onTranslated(String sourceLanguage,String translated){
                String toSpeak="";String outputMode;boolean foreignToTarget;
                synchronized(SessionRuntime.this){
                    if(!active||generation!=utteranceGeneration)return;
                    detectedLanguage=sourceLanguage;foreignToTarget=!sameLanguage(sourceLanguage,chosenTarget);
                    translatedText=foreignToTarget?translated:"";
                    status=isFinal?("meeting".equals(mode)?"Запись продолжается…":"Слушаю дальше…"):"Перевожу…";speakerLabel=lineSpeaker;outputMode=voiceMode;
                    if("meeting".equals(mode)&&isFinal){
                        meetingStore.add(lineSpeaker,sourceLanguage,text,foreignToTarget?translated:"");
                        sourceText=buildMeetingChat();
                    }
                    boolean voiceAllowed=!VOICE_OFF.equals(outputMode)&&foreignToTarget;
                    if(voiceAllowed){
                        toSpeak=isFinal?spokenDiff.flushFinal(translated):spokenDiff.acceptPartial(translated,false);
                        if(isFinal){if(toSpeak.isBlank()&&spokenDiff.spokenWordCount()==0)toSpeak=translated;spokenDiff.reset();utteranceGeneration++;}
                    }else if(isFinal){spokenDiff.reset();utteranceGeneration++;}
                }
                if(!toSpeak.isBlank()){
                    if(VOICE_SYSTEM.equals(outputMode))systemSpeaker.speak(toSpeak,isFinal,chosenTarget,voiceBand);
                    else if(VOICE_AI.equals(outputMode))neuralSpeaker.speak(toSpeak,chosenTarget,isFinal,bandToInt(voiceBand));
                }
                notifyState();
            }
            @Override public void onError(String message){
                synchronized(SessionRuntime.this){
                    if(generation!=utteranceGeneration)return;status=message;
                    if("meeting".equals(mode)&&isFinal){meetingStore.add(lineSpeaker,lang,text,"");sourceText=buildMeetingChat();utteranceGeneration++;}
                }
                notifyState();
            }
        });notifyState();
    }

    private synchronized String buildMeetingChat(){
        StringBuilder sb=new StringBuilder();
        for(MeetingSessionStore.Entry e:meetingStore.entries()){
            if(sb.length()>0)sb.append("\n\n");
            long total=e.elapsedMs/1000;long mm=total/60,ss=total%60;
            sb.append(String.format(java.util.Locale.US,"[%02d:%02d] ",mm,ss)).append(e.speaker==null?"Спикер":e.speaker).append("\n");
            sb.append(e.original==null?"":e.original);
            if(e.translated!=null&&!e.translated.isBlank())sb.append("\n↳ ").append(e.translated);
        }
        return sb.toString();
    }

    @Override public void onSpeechStart(){
        boolean interrupted=false;
        synchronized(this){
            if(!active||VOICE_OFF.equals(voiceMode))return;
            interrupted=systemSpeaker.isSpeaking()||neuralSpeaker.isSpeaking();
            if(interrupted){status="Слушаю вас · озвучка прервана";spokenDiff.reset();}
        }
        if(interrupted){systemSpeaker.stop();neuralSpeaker.stop();notifyState();}
    }

    private static boolean sameLanguage(String a,String b){String x=shortLang(a),y=shortLang(b);return x!=null&&y!=null&&x.equals(y);}
    private static String normalizeVoiceMode(String s){return VOICE_AI.equals(s)?VOICE_AI:(VOICE_SYSTEM.equals(s)?VOICE_SYSTEM:VOICE_OFF);}
    private static int bandToInt(VoiceProfile.Band b){return b==VoiceProfile.Band.LOW?0:(b==VoiceProfile.Band.HIGH?2:1);}private static VoiceProfile.Band bandFromInt(int p){return p<=0?VoiceProfile.Band.LOW:(p>=2?VoiceProfile.Band.HIGH:VoiceProfile.Band.NEUTRAL);}
    private static String shortLang(String tag){if(tag==null||tag.isBlank()||"auto".equalsIgnoreCase(tag))return null;return tag.split("[-_]")[0].toLowerCase();}
    @Override public void onVoiceProfile(VoiceProfile profile){synchronized(this){SpeakerRouter.Match m=speakerRouter.assign(profile);currentSpeaker=m.speaker;currentVoice=m.profile;speakerLabel=("meeting".equals(mode)?"Спикер ":"Собеседник ")+currentSpeaker;}notifyState();}
    @Override public void onReady(){synchronized(this){status="meeting".equals(mode)?("whisper".equals(sttEngineName)?"Запись идёт непрерывно":"Готов · системный режим совместимости"):"Говорите";}notifyState();}
    @Override public void onPartial(String text,String language){handleText(text,false,language);}
    @Override public void onFinal(String text,String language){handleText(text,true,language);}
    @Override public void onStatus(String s){synchronized(this){if(!"meeting".equals(mode)||!"whisper".equals(sttEngineName))status=s;}notifyState();}
    @Override public void onError(String e){synchronized(this){status=e;}notifyState();}
    private void notifyState(){Snapshot s=snapshot();for(Observer o:observers)o.onState(s);}public synchronized boolean isActive(){return active;}public synchronized String sttEngine(){return sttEngineName;}public synchronized boolean isWhisperAvailable(){return whisperSpeech.isAvailable();}
}
