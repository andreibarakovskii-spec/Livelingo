package com.imagine.livelingo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.imagine.livelingo.core.SpokenDiff;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements LiveSpeechRecognizer.Listener {
    private static final int REQ=7;
    private TextView status,sourceText,translatedText,detected,headset,modeTitle,modeSubtitle,meetingInsights;
    private Button mainButton,debugButton,modeTranslate,modeConversation,modeMeeting;
    private Spinner inputSpinner,targetSpinner;
    private LiveSpeechRecognizer speech; private TranslationEngine translator; private OfflineSpeaker speaker;
    private final SpokenDiff spokenDiff=new SpokenDiff(2); private boolean active;
    private final Handler debounce=new Handler(Looper.getMainLooper()); private Runnable pendingTranslation;
    private long translationSeq=0; private String manualInput="auto"; private DebugTrace trace; private String currentMode="translate";

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); trace=new DebugTrace(this); getWindow().setStatusBarColor(0xFFF7F8FB); buildUi();
        translator=new TranslationEngine(); speaker=new OfflineSpeaker(this,s->{status.setText(s);trace.log("TTS_STATUS",s);}); speech=new LiveSpeechRecognizer(this,this);
        mainButton.setOnClickListener(v->toggle()); debugButton.setOnClickListener(v->shareTrace());
        modeTranslate.setOnClickListener(v->selectMode("translate")); modeConversation.setOnClickListener(v->selectMode("conversation")); modeMeeting.setOnClickListener(v->selectMode("meeting"));
        inputSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){manualInput=LanguageCatalog.inputCodeForDisplay((String)inputSpinner.getSelectedItem());trace.log("UI_INPUT_LANGUAGE",manualInput);speech.setInputLanguage(manualInput);detected.setText("auto".equals(manualInput)?"Автоопределение языка":"Говорит: "+LanguageCatalog.displayForCode(manualInput));spokenDiff.reset();translationSeq++;}public void onNothingSelected(android.widget.AdapterView<?> p){}});
        targetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){String c=LanguageCatalog.codeForDisplay((String)targetSpinner.getSelectedItem());trace.log("UI_TARGET_LANGUAGE",c);translator.setTarget(c);speaker.selectOfflineVoice(c);spokenDiff.reset();translationSeq++;}public void onNothingSelected(android.widget.AdapterView<?> p){}});
        requestPermissionsIfNeeded(); headset.setText(BluetoothRouter.routeToHeadset(this)); status.setText(speech.isOnDeviceAvailable()?"Готов к переводу":"Нужно установить офлайн-языки речи в Android"); selectMode("translate");
    }

    private void selectMode(String mode){
        currentMode=mode; trace.log("UI_MODE",mode);
        modeTranslate.setAlpha("translate".equals(mode)?1f:.45f); modeConversation.setAlpha("conversation".equals(mode)?1f:.45f); modeMeeting.setAlpha("meeting".equals(mode)?1f:.45f);
        if("translate".equals(mode)){modeTitle.setText("Перевод в реальном времени");modeSubtitle.setText("Слушает собеседника и сразу говорит перевод");meetingInsights.setVisibility(View.GONE);mainButton.setText(active?"ОСТАНОВИТЬ":"НАЧАТЬ ПЕРЕВОД");}
        else if("conversation".equals(mode)){modeTitle.setText("Разговор вдвоём");modeSubtitle.setText("Поочерёдный перевод двух собеседников");meetingInsights.setVisibility(View.GONE);mainButton.setText(active?"ОСТАНОВИТЬ":"НАЧАТЬ РАЗГОВОР");}
        else {modeTitle.setText("Совещание");modeSubtitle.setText("Стенограмма, перевод и важные решения встречи");meetingInsights.setVisibility(View.VISIBLE);mainButton.setText(active?"ЗАВЕРШИТЬ ВСТРЕЧУ":"НАЧАТЬ СОВЕЩАНИЕ");}
    }

    private void requestPermissionsIfNeeded(){List<String> p=new ArrayList<>();if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.RECORD_AUDIO);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);headset.setText(BluetoothRouter.routeToHeadset(this));trace.log("PERMISSION_RESULT",java.util.Arrays.toString(g));}

    private void toggle(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissionsIfNeeded();return;}
        active=!active; trace.log("UI_TOGGLE",active?"start":"stop");
        if(active){spokenDiff.reset();sourceText.setText("Слушаю…");translatedText.setText("Перевод появится здесь");headset.setText(BluetoothRouter.routeToHeadset(this));speech.start();status.setText("Слушаю…");}
        else{speech.stop();speaker.stop();if(pendingTranslation!=null)debounce.removeCallbacks(pendingTranslation);translationSeq++;status.setText("Остановлено");}
        selectMode(currentMode);
    }

    @Override public void onText(String text,boolean isFinal,String lang){
        trace.log(isFinal?"UI_FINAL_TEXT":"UI_PARTIAL_TEXT",text+" | lang="+lang); sourceText.setText(text);
        String sourceHint="auto".equals(manualInput)?lang:manualInput;
        if("auto".equals(manualInput)&&lang!=null)detected.setText("Определён язык: "+LanguageCatalog.displayForCode(lang));
        final long seq=++translationSeq; final String textSnapshot=text; final String hintSnapshot=sourceHint; final boolean finalSnapshot=isFinal;
        if(finalSnapshot){if(pendingTranslation!=null)debounce.removeCallbacks(pendingTranslation);runTranslation(seq,textSnapshot,hintSnapshot,true);} else {if(pendingTranslation!=null)debounce.removeCallbacks(pendingTranslation);pendingTranslation=()->runTranslation(seq,textSnapshot,hintSnapshot,false);debounce.postDelayed(pendingTranslation,120);}
        if("meeting".equals(currentMode) && isFinal) appendMeetingInsight(text);
    }

    private void appendMeetingInsight(String text){
        String l=text.toLowerCase(); String prefix=null;
        if(l.contains("решили")||l.contains("договорились")||l.contains("agreed")||l.contains("decided")) prefix="✓ Решение: ";
        else if(l.contains("нужно")||l.contains("надо")||l.contains("send")||l.contains("prepare")) prefix="☐ Задача: ";
        else if(l.contains("риск")||l.contains("задерж")||l.contains("risk")||l.contains("delay")) prefix="⚠ Риск: ";
        else if(text.contains("?")) prefix="? Вопрос: ";
        if(prefix!=null){String old=meetingInsights.getText().toString(); if(old.contains("Пока нет важных моментов")) old=""; meetingInsights.setText((old+"\n"+prefix+text).trim());}
    }

    private void runTranslation(long seq,String textSnapshot,String hintSnapshot,boolean finalSnapshot){
        trace.log("TRANSLATE_REQUEST","seq="+seq+" hint="+hintSnapshot+" final="+finalSnapshot+" text="+textSnapshot);
        translator.translateAuto(textSnapshot,hintSnapshot,new TranslationEngine.Callback(){public void onTranslated(String src,String tr){trace.log("TRANSLATE_OK","seq="+seq+" src="+src+" out="+tr);runOnUiThread(()->{if("auto".equals(manualInput))detected.setText("Определён язык: "+LanguageCatalog.displayForCode(src));translatedText.setText(tr);String delta=spokenDiff.acceptPartial(tr,finalSnapshot);trace.log("TTS_DELTA",delta);if(!delta.isBlank()&&!"meeting".equals(currentMode))speaker.speak(delta, finalSnapshot);if(finalSnapshot){String tail=spokenDiff.flushFinal(tr);trace.log("TTS_TAIL",tail);if(!tail.isBlank()&&!"meeting".equals(currentMode))speaker.speak(tail,true);spokenDiff.reset();}status.setText(finalSnapshot?"Слушаю дальше…":"Перевожу по ходу речи…");});}public void onError(String m){trace.log("TRANSLATE_ERROR","seq="+seq+" "+m);runOnUiThread(()->status.setText(m));}});
    }

    @Override public void onStatus(String s){trace.log("SPEECH_STATUS",s);runOnUiThread(()->status.setText(s));}
    @Override public void onError(String e){trace.log("SPEECH_ERROR",e);runOnUiThread(()->status.setText(e));}
    private void shareTrace(){try{File f=trace.file();Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Отправить журнал LiveLingo"));}catch(Exception e){Toast.makeText(this,"Не удалось открыть журнал: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    @Override protected void onDestroy(){if(speech!=null)speech.stop();if(speaker!=null)speaker.close();if(translator!=null)translator.close();super.onDestroy();}

    private void buildUi(){
        int pad=dp(18); ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(0xFFF7F8FB); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad,dp(18),pad,dp(36)); scroll.addView(root);
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); TextView logo=tv("LiveLingo",30,true,0xFF101217); top.addView(logo,new LinearLayout.LayoutParams(0,-2,1)); TextView badge=chip("BETA BUSINESS",0xFFE9EEFF,0xFF3157D5); top.addView(badge); root.addView(top); root.addView(tv("AI-переводчик и ассистент встреч",14,false,0xFF747B88),lp(-1,-2,0,2,0,18));
        LinearLayout modes=new LinearLayout(this); modes.setOrientation(LinearLayout.HORIZONTAL); modeTranslate=modeButton("🌐\nПеревод"); modeConversation=modeButton("⇄\nРазговор"); modeMeeting=modeButton("●\nСовещание"); modes.addView(modeTranslate,new LinearLayout.LayoutParams(0,dp(72),1)); modes.addView(modeConversation,new LinearLayout.LayoutParams(0,dp(72),1)); modes.addView(modeMeeting,new LinearLayout.LayoutParams(0,dp(72),1)); root.addView(modes,lp(-1,-2,0,0,0,16));
        LinearLayout hero=card(0xFF111318); modeTitle=tv("",23,true,Color.WHITE); modeSubtitle=tv("",14,false,0xFFB8BEC8); hero.addView(modeTitle); hero.addView(modeSubtitle,lp(-1,-2,0,6,0,16)); LinearLayout statusRow=new LinearLayout(this); statusRow.setGravity(Gravity.CENTER_VERTICAL); TextView dot=tv("●",12,true,0xFF61D095); statusRow.addView(dot); status=tv("Готов",13,true,0xFFE9EDF4); statusRow.addView(status,lp(-2,-2,6,0,0,0)); hero.addView(statusRow); root.addView(hero,lp(-1,-2,0,0,0,14));
        LinearLayout lang=card(Color.WHITE); lang.addView(tv("ЯЗЫК СОБЕСЕДНИКА",11,true,0xFF8B929F)); inputSpinner=new Spinner(this); inputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>(LanguageCatalog.INPUTS.keySet()))); lang.addView(inputSpinner,lp(-1,dp(48),0,4,0,10)); lang.addView(tv("ПЕРЕВОДИТЬ НА",11,true,0xFF8B929F)); targetSpinner=new Spinner(this); targetSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>(LanguageCatalog.TARGETS.keySet()))); lang.addView(targetSpinner,lp(-1,dp(48),0,4,0,8)); detected=tv("Автоопределение языка",13,true,0xFF3157D5); lang.addView(detected); root.addView(lang,lp(-1,-2,0,0,0,12));
        mainButton=new Button(this); mainButton.setAllCaps(false); mainButton.setTextSize(16); mainButton.setTextColor(Color.WHITE); mainButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD); mainButton.setBackground(roundRect(0xFF3157D5,24)); root.addView(mainButton,lp(-1,dp(60),0,0,0,10)); headset=tv("",12,false,0xFF7A818D); headset.setGravity(Gravity.CENTER); root.addView(headset,lp(-1,-2,0,0,0,14));
        LinearLayout live=card(Color.WHITE); LinearLayout liveHead=new LinearLayout(this); liveHead.setGravity(Gravity.CENTER_VERTICAL); liveHead.addView(tv("LIVE",11,true,0xFFE55353)); TextView privacy=tv("  локально на устройстве",11,false,0xFF8D94A0); liveHead.addView(privacy); live.addView(liveHead); live.addView(tv("ОРИГИНАЛ",10,true,0xFF9AA1AD),lp(-1,-2,0,14,0,0)); sourceText=tv("Здесь появится речь собеседника",17,false,0xFF30343B); live.addView(sourceText,lp(-1,-2,0,6,0,15)); View divider=new View(this); divider.setBackgroundColor(0xFFECEEF2); live.addView(divider,new LinearLayout.LayoutParams(-1,dp(1))); live.addView(tv("ПЕРЕВОД",10,true,0xFF9AA1AD),lp(-1,-2,0,15,0,0)); translatedText=tv("Здесь появится перевод",22,true,0xFF111318); live.addView(translatedText,lp(-1,-2,0,6,0,0)); root.addView(live);
        meetingInsights=tv("Пока нет важных моментов. Во время совещания здесь появятся решения, задачи, риски и вопросы.",14,false,0xFF444A55); meetingInsights.setBackground(roundRect(0xFFFFFBEB,18)); meetingInsights.setPadding(dp(16),dp(14),dp(16),dp(14)); root.addView(meetingInsights,lp(-1,-2,0,12,0,0));
        debugButton=new Button(this); debugButton.setText("Отправить журнал теста"); debugButton.setAllCaps(false); root.addView(debugButton,lp(-1,dp(48),0,14,0,0)); setContentView(scroll);
    }

    private Button modeButton(String t){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(0xFF20242B);b.setGravity(Gravity.CENTER);b.setBackground(roundRect(Color.WHITE,18));return b;}
    private TextView chip(String t,int bg,int fg){TextView v=tv(t,10,true,fg);v.setPadding(dp(9),dp(5),dp(9),dp(5));v.setBackground(roundRect(bg,14));return v;}
    private LinearLayout card(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(17),dp(17),dp(17),dp(17));l.setBackground(roundRect(color,22));l.setElevation(dp(1));return l;}
    private TextView tv(String t,int sp,boolean bold,int c){TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);v.setTextColor(c);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable roundRect(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
