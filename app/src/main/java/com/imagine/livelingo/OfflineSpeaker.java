package com.imagine.livelingo;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import com.imagine.livelingo.audio.VoiceProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class OfflineSpeaker implements TextToSpeech.OnInitListener {
    public interface Listener { void onStatus(String status); }
    private final TextToSpeech tts; private final Listener listener; private boolean ready; private String targetTag="ru";
    private final AtomicInteger activeUtterances=new AtomicInteger();
    public OfflineSpeaker(Context context,Listener listener){
        this.listener=listener;this.tts=new TextToSpeech(context,this);
        this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){activeUtterances.incrementAndGet();}
            @Override public void onDone(String id){decrement();}
            @Override public void onError(String id){decrement();}
            @Override public void onStop(String id,boolean interrupted){decrement();}
            private void decrement(){activeUtterances.updateAndGet(v->Math.max(0,v-1));}
        });
    }
    @Override public void onInit(int status){ready=status==TextToSpeech.SUCCESS;if(ready){selectOfflineVoice(targetTag);listener.onStatus("Озвучка готова");}else listener.onStatus("TTS недоступен");}
    public boolean selectOfflineVoice(String tag){targetTag=tag;if(!ready)return false;Locale wanted=LanguageCatalog.localeFor(tag);Voice best=tts.getVoices().stream().filter(v->!v.isNetworkConnectionRequired()).filter(v->v.getLocale().getLanguage().equals(wanted.getLanguage())).max(Comparator.comparingInt(Voice::getQuality)).orElse(null);if(best!=null){tts.setVoice(best);tts.setSpeechRate(1.08f);return true;}return tts.setLanguage(wanted)>=TextToSpeech.LANG_AVAILABLE;}
    private void selectProfileVoice(String tag,VoiceProfile.Band band){if(!ready)return;Locale wanted=LanguageCatalog.localeFor(tag);List<Voice> voices=new ArrayList<>();for(Voice v:tts.getVoices())if(!v.isNetworkConnectionRequired()&&v.getLocale().getLanguage().equals(wanted.getLanguage()))voices.add(v);voices.sort(Comparator.comparing(Voice::getName));if(!voices.isEmpty()){int idx=band==VoiceProfile.Band.LOW?0:(band==VoiceProfile.Band.HIGH?voices.size()-1:voices.size()/2);tts.setVoice(voices.get(Math.max(0,Math.min(idx,voices.size()-1))));}else tts.setLanguage(wanted);tts.setPitch(band==VoiceProfile.Band.LOW?0.88f:(band==VoiceProfile.Band.HIGH?1.12f:1.0f));tts.setSpeechRate(1.08f);}
    public void speak(String text,boolean finalChunk){speak(text,finalChunk,targetTag,VoiceProfile.Band.NEUTRAL);}
    public synchronized void speak(String text,boolean finalChunk,String language,VoiceProfile.Band band){if(!ready||text==null||text.isBlank())return;if(language!=null&&!language.isBlank())targetTag=language;selectProfileVoice(targetTag,band==null?VoiceProfile.Band.NEUTRAL:band);Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f);tts.speak(text,TextToSpeech.QUEUE_ADD,params,"live-"+System.nanoTime());}
    public boolean isSpeaking(){return ready&&(activeUtterances.get()>0||tts.isSpeaking());}
    public void stop(){activeUtterances.set(0);if(ready)tts.stop();}
    public void close(){activeUtterances.set(0);tts.stop();tts.shutdown();}
}
