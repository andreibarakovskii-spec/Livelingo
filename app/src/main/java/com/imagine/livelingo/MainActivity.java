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
import com.imagine.livelingo.business.MeetingInsightEngine;
import com.imagine.livelingo.business.MeetingReportBuilder;
import com.imagine.livelingo.business.MeetingSessionStore;
import com.imagine.livelingo.core.SpokenDiff;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements LiveSpeechRecognizer.Listener {
    private static final int REQ=7;
    private TextView status,sourceText,translatedText,detected,headset,modeTitle,modeSubtitle,meetingInsights,meetingResult;
    private Button mainButton,debugButton,modeTranslate,modeConversation,modeMeeting,navLive,navMeetings,navLibrary,navProfile,shareMeetingButton,presentationButton;
    private Spinner inputSpinner,targetSpinner;
    private LinearLayout livePage,meetingsPage,libraryPage,profilePage,meetingResultCard;
    private LiveSpeechRecognizer speech; private TranslationEngine translator; private OfflineSpeaker speaker;
    private final SpokenDiff spokenDiff=new SpokenDiff(2); private boolean active;
    private final Handler debounce=new Handler(Looper.getMainLooper()); private Runnable pendingTranslation;
    private long translationSeq=0; private String manualInput="auto"; private DebugTrace trace; private String currentMode="translate";
    private final MeetingSessionStore meetingStore=new MeetingSessionStore();
    private final MeetingInsightEngine meetingEngine=new MeetingInsightEngine();
    private final List<MeetingInsightEngine.Insight> meetingInsightList=new ArrayList<>();

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); trace=new DebugTrace(this); getWindow().setStatusBarColor(0xFFF7F8FB); buildUi();
        translator=new TranslationEngine(); speaker=new OfflineSpeaker(this,s->{status.setText(s);trace.log("TTS_STATUS",s);}); speech=new LiveSpeechRecognizer(this,this);
        mainButton.setOnClickListener(v->toggle()); debugButton.setOnClickListener(v->shareTrace());
        modeTranslate.setOnClickListener(v->selectMode("translate")); modeConversation.setOnClickListener(v->selectMode("conversation")); modeMeeting.setOnClickListener(v->selectMode("meeting"));
        navLive.setOnClickListener(v->showPage("live")); navMeetings.setOnClickListener(v->showPage("meetings")); navLibrary.setOnClickListener(v->showPage("library")); navProfile.setOnClickListener(v->showPage("profile"));
        shareMeetingButton.setOnClickListener(v->shareMeeting()); presentationButton.setOnClickListener(v->Toast.makeText(this,"Генератор презентации подготовлен как следующий модуль",Toast.LENGTH_SHORT).show());
        inputSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){manualInput=LanguageCatalog.inputCodeForDisplay((String)inputSpinner.getSelectedItem());trace.log("UI_INPUT_LANGUAGE",manualInput);speech.setInputLanguage(manualInput);detected.setText("auto".equals(manualInput)?"Автоопределение языка":"Говорит: "+LanguageCatalog.displayForCode(manualInput));spokenDiff.reset();translationSeq++;}public void onNothingSelected(android.widget.AdapterView<?> p){}});
        targetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){String c=LanguageCatalog.codeForDisplay((String)targetSpinner.getSelectedItem());trace.log("UI_TARGET_LANGUAGE",c);translator.setTarget(c);speaker.selectOfflineVoice(c);spokenDiff.reset();translationSeq++;}public void onNothingSelected(android.widget.AdapterView<?> p){}});
        requestPermissionsIfNeeded(); headset.setText(BluetoothRouter.routeToHeadset(this)); status.setText(speech.isOnDeviceAvailable()?"Готов к переводу":"Нужно установить офлайн-языки речи в Android"); selectMode("translate"); showPage("live");
    }

    private void showPage(String page){
        livePage.setVisibility("live".equals(page)?View.VISIBLE:View.GONE); meetingsPage.setVisibility("meetings".equals(page)?View.VISIBLE:View.GONE); libraryPage.setVisibility("library".equals(page)?View.VISIBLE:View.GONE); profilePage.setVisibility("profile".equals(page)?View.VISIBLE:View.GONE);
        navLive.setAlpha("live".equals(page)?1f:.45f); navMeetings.setAlpha("meetings".equals(page)?1f:.45f); navLibrary.setAlpha("library".equals(page)?1f:.45f); navProfile.setAlpha("profile".equals(page)?1f:.45f);
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
        if(active){
            spokenDiff.reset(); sourceText.setText("Слушаю…"); translatedText.setText("Перевод появится здесь"); headset.setText(BluetoothRouter.routeToHeadset(this));
            if("meeting".equals(currentMode)){meetingStore.start();meetingEngine.reset();meetingInsightList.clear();meetingInsights.setText("Пока нет важных моментов. Во время совещания здесь появятся решения, задачи, риски и вопросы.");meetingResultCard.setVisibility(View.GONE);}
            speech.start();status.setText("Слушаю…");
        } else {
            speech.stop();speaker.stop();if(pendingTranslation!=null)debounce.removeCallbacks(pendingTranslation);translationSeq++;status.setText("Остановлено");
            if("meeting".equals(currentMode)){renderMeetingReport();showPage("meetings");}
        }
        selectMode(currentMode);
    }

    @Override public void onText(String text,boolean isFinal,String lang){
        trace.log(isFinal?"UI_FINAL_TEXT":"UI_PARTIAL_TEXT",text+" | lang="+lang); sourceText.setText(text);
        String sourceHint="auto".equals(manualInput)?lang:manualInput;
        if("auto".equals(manualInput)&&lang!=null)detected.setText("Определён язык: "+LanguageCatalog.displayForCode(lang));
        final long seq=++translationSeq; final String textSnapshot=text; final String hintSnapshot=sourceHint; final boolean finalSnapshot=isFinal;
        if(finalSnapshot){if(pendingTranslation!=null)debounce.removeCallbacks(pendingTranslation);runTranslation(seq,textSnapshot,hintSnapshot,true);} else {if(pendingTranslation!=null)debounce.removeCallbacks(pendingTranslation);pendingTranslation=()->runTranslation(seq,textSnapshot,hintSnapshot,false);debounce.postDelayed(pendingTranslation,120);}
        if("meeting".equals(currentMode) && isFinal){List<MeetingInsightEngine.Insight> fresh=meetingEngine.extract(text);meetingInsightList.addAll(fresh);appendMeetingInsights(fresh);}
    }

    private void appendMeetingInsights(List<MeetingInsightEngine.Insight> fresh){
        if(fresh.isEmpty())return; String old=meetingInsights.getText().toString(); if(old.contains("Пока нет важных моментов"))old=""; StringBuilder sb=new StringBuilder(old);
        for(MeetingInsightEngine.Insight i:fresh){String p="• ";if("decision".equals(i.type))p="✓ Решение: ";else if("action".equals(i.type))p="☐ Задача: ";else if("risk".equals(i.type))p="⚠ Риск: ";else if("question".equals(i.type))p="? Вопрос: ";else if("followup".equals(i.type))p="→ Следующий шаг: ";if(sb.length()>0)sb.append("\n");sb.append(p).append(i.text);}
        meetingInsights.setText(sb.toString());
    }

    private void runTranslation(long seq,String textSnapshot,String hintSnapshot,boolean finalSnapshot){
        trace.log("TRANSLATE_REQUEST","seq="+seq+" hint="+hintSnapshot+" final="+finalSnapshot+" text="+textSnapshot);
        translator.translateAuto(textSnapshot,hintSnapshot,new TranslationEngine.Callback(){public void onTranslated(String src,String tr){trace.log("TRANSLATE_OK","seq="+seq+" src="+src+" out="+tr);runOnUiThread(()->{if("auto".equals(manualInput))detected.setText("Определён язык: "+LanguageCatalog.displayForCode(src));translatedText.setText(tr);if("meeting".equals(currentMode)&&finalSnapshot){String target=LanguageCatalog.codeForDisplay((String)targetSpinner.getSelectedItem());meetingStore.add("Участник",src,textSnapshot,tr);}String delta=spokenDiff.acceptPartial(tr,finalSnapshot);trace.log("TTS_DELTA",delta);if(!delta.isBlank()&&!"meeting".equals(currentMode))speaker.speak(delta, finalSnapshot);if(finalSnapshot){String tail=spokenDiff.flushFinal(tr);trace.log("TTS_TAIL",tail);if(!tail.isBlank()&&!"meeting".equals(currentMode))speaker.speak(tail,true);spokenDiff.reset();}status.setText(finalSnapshot?"Слушаю дальше…":"Перевожу по ходу речи…");});}public void onError(String m){trace.log("TRANSLATE_ERROR","seq="+seq+" "+m);runOnUiThread(()->status.setText(m));}});
    }

    private void renderMeetingReport(){String report=MeetingReportBuilder.build(meetingStore,meetingInsightList);meetingResult.setText(report);meetingResultCard.setVisibility(View.VISIBLE);}
    private void shareMeeting(){String report=meetingResult.getText().toString();if(report.isBlank()){Toast.makeText(this,"Сначала завершите встречу",Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,report);startActivity(Intent.createChooser(i,"Поделиться итогами встречи"));}

    @Override public void onStatus(String s){trace.log("SPEECH_STATUS",s);runOnUiThread(()->status.setText(s));}
    @Override public void onError(String e){trace.log("SPEECH_ERROR",e);runOnUiThread(()->status.setText(e));}
    private void shareTrace(){try{File f=trace.file();Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Отправить журнал LiveLingo"));}catch(Exception e){Toast.makeText(this,"Не удалось открыть журнал: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    @Override protected void onDestroy(){if(speech!=null)speech.stop();if(speaker!=null)speaker.close();if(translator!=null)translator.close();super.onDestroy();}

    private void buildUi(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(0xFFF7F8FB);
        ScrollView scroll=new ScrollView(this);LinearLayout pages=new LinearLayout(this);pages.setOrientation(LinearLayout.VERTICAL);pages.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(pages);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);TextView logo=tv("LiveLingo",30,true,0xFF101217);top.addView(logo,new LinearLayout.LayoutParams(0,-2,1));top.addView(chip("BUSINESS",0xFFE9EEFF,0xFF3157D5));pages.addView(top);pages.addView(tv("AI-переводчик и ассистент встреч",14,false,0xFF747B88),lp(-1,-2,0,2,0,18));

        livePage=new LinearLayout(this);livePage.setOrientation(LinearLayout.VERTICAL);pages.addView(livePage);
        LinearLayout modes=new LinearLayout(this);modes.setOrientation(LinearLayout.HORIZONTAL);modeTranslate=modeButton("🌐\nПеревод");modeConversation=modeButton("⇄\nРазговор");modeMeeting=modeButton("●\nСовещание");modes.addView(modeTranslate,new LinearLayout.LayoutParams(0,dp(72),1));modes.addView(modeConversation,new LinearLayout.LayoutParams(0,dp(72),1));modes.addView(modeMeeting,new LinearLayout.LayoutParams(0,dp(72),1));livePage.addView(modes,lp(-1,-2,0,0,0,16));
        LinearLayout hero=card(0xFF111318);modeTitle=tv("",23,true,Color.WHITE);modeSubtitle=tv("",14,false,0xFFB8BEC8);hero.addView(modeTitle);hero.addView(modeSubtitle,lp(-1,-2,0,6,0,16));LinearLayout statusRow=new LinearLayout(this);statusRow.setGravity(Gravity.CENTER_VERTICAL);statusRow.addView(tv("●",12,true,0xFF61D095));status=tv("Готов",13,true,0xFFE9EDF4);statusRow.addView(status,lp(-2,-2,6,0,0,0));hero.addView(statusRow);livePage.addView(hero,lp(-1,-2,0,0,0,14));
        LinearLayout lang=card(Color.WHITE);lang.addView(tv("ЯЗЫК СОБЕСЕДНИКА",11,true,0xFF8B929F));inputSpinner=new Spinner(this);inputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>(LanguageCatalog.INPUTS.keySet())));lang.addView(inputSpinner,lp(-1,dp(48),0,4,0,10));lang.addView(tv("ПЕРЕВОДИТЬ НА",11,true,0xFF8B929F));targetSpinner=new Spinner(this);targetSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>(LanguageCatalog.TARGETS.keySet())));lang.addView(targetSpinner,lp(-1,dp(48),0,4,0,8));detected=tv("Автоопределение языка",13,true,0xFF3157D5);lang.addView(detected);livePage.addView(lang,lp(-1,-2,0,0,0,12));
        mainButton=new Button(this);mainButton.setAllCaps(false);mainButton.setTextSize(16);mainButton.setTextColor(Color.WHITE);mainButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);mainButton.setBackground(roundRect(0xFF3157D5,24));livePage.addView(mainButton,lp(-1,dp(60),0,0,0,10));headset=tv("",12,false,0xFF7A818D);headset.setGravity(Gravity.CENTER);livePage.addView(headset,lp(-1,-2,0,0,0,14));
        LinearLayout live=card(Color.WHITE);LinearLayout liveHead=new LinearLayout(this);liveHead.setGravity(Gravity.CENTER_VERTICAL);liveHead.addView(tv("LIVE",11,true,0xFFE55353));liveHead.addView(tv("  локально на устройстве",11,false,0xFF8D94A0));live.addView(liveHead);live.addView(tv("ОРИГИНАЛ",10,true,0xFF9AA1AD),lp(-1,-2,0,14,0,0));sourceText=tv("Здесь появится речь собеседника",17,false,0xFF30343B);live.addView(sourceText,lp(-1,-2,0,6,0,15));View divider=new View(this);divider.setBackgroundColor(0xFFECEEF2);live.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));live.addView(tv("ПЕРЕВОД",10,true,0xFF9AA1AD),lp(-1,-2,0,15,0,0));translatedText=tv("Здесь появится перевод",22,true,0xFF111318);live.addView(translatedText,lp(-1,-2,0,6,0,0));livePage.addView(live);
        meetingInsights=tv("Пока нет важных моментов. Во время совещания здесь появятся решения, задачи, риски и вопросы.",14,false,0xFF444A55);meetingInsights.setBackground(roundRect(0xFFFFFBEB,18));meetingInsights.setPadding(dp(16),dp(14),dp(16),dp(14));livePage.addView(meetingInsights,lp(-1,-2,0,12,0,0));debugButton=new Button(this);debugButton.setText("Отправить журнал теста");debugButton.setAllCaps(false);livePage.addView(debugButton,lp(-1,dp(48),0,14,0,0));

        meetingsPage=new LinearLayout(this);meetingsPage.setOrientation(LinearLayout.VERTICAL);pages.addView(meetingsPage);meetingsPage.addView(tv("Встречи",27,true,0xFF111318));meetingsPage.addView(tv("Итоги, решения и задачи из ваших разговоров",14,false,0xFF747B88),lp(-1,-2,0,4,0,16));meetingResultCard=card(Color.WHITE);meetingResult=tv("Завершённые встречи появятся здесь.",15,false,0xFF343942);meetingResultCard.addView(meetingResult);LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);shareMeetingButton=smallButton("Поделиться");presentationButton=smallButton("Презентация");actions.addView(shareMeetingButton,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(presentationButton,new LinearLayout.LayoutParams(0,dp(48),1));meetingResultCard.addView(actions,lp(-1,-2,0,14,0,0));meetingsPage.addView(meetingResultCard);

        libraryPage=new LinearLayout(this);libraryPage.setOrientation(LinearLayout.VERTICAL);pages.addView(libraryPage);libraryPage.addView(tv("Библиотека",27,true,0xFF111318));libraryPage.addView(tv("Сохранённые расшифровки, переводы и презентации",14,false,0xFF747B88),lp(-1,-2,0,4,0,16));LinearLayout lib=card(Color.WHITE);lib.addView(tv("Пока пусто",18,true,0xFF20242B));lib.addView(tv("Здесь будут храниться встречи и экспортированные материалы.",14,false,0xFF7A818D),lp(-1,-2,0,6,0,0));libraryPage.addView(lib);

        profilePage=new LinearLayout(this);profilePage.setOrientation(LinearLayout.VERTICAL);pages.addView(profilePage);profilePage.addView(tv("Профиль",27,true,0xFF111318));profilePage.addView(tv("Настройки языков, приватности и бизнес-режима",14,false,0xFF747B88),lp(-1,-2,0,4,0,16));LinearLayout prof=card(Color.WHITE);prof.addView(tv("Локальная обработка",17,true,0xFF20242B));prof.addView(tv("Аудио и журнал теста остаются на устройстве, пока вы сами ими не поделитесь.",14,false,0xFF7A818D),lp(-1,-2,0,6,0,0));profilePage.addView(prof);

        LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(8),dp(8),dp(8),dp(10));nav.setBackgroundColor(Color.WHITE);navLive=navButton("●\nLive");navMeetings=navButton("▣\nMeetings");navLibrary=navButton("□\nLibrary");navProfile=navButton("○\nProfile");nav.addView(navLive,new LinearLayout.LayoutParams(0,dp(58),1));nav.addView(navMeetings,new LinearLayout.LayoutParams(0,dp(58),1));nav.addView(navLibrary,new LinearLayout.LayoutParams(0,dp(58),1));nav.addView(navProfile,new LinearLayout.LayoutParams(0,dp(58),1));shell.addView(nav);
        setContentView(shell);
    }

    private Button modeButton(String t){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(0xFF20242B);b.setGravity(Gravity.CENTER);b.setBackground(roundRect(Color.WHITE,18));return b;}
    private Button navButton(String t){Button b=modeButton(t);b.setBackgroundColor(Color.TRANSPARENT);return b;}
    private Button smallButton(String t){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(13);b.setBackground(roundRect(0xFFF0F3FA,16));return b;}
    private TextView chip(String t,int bg,int fg){TextView v=tv(t,10,true,fg);v.setPadding(dp(9),dp(5),dp(9),dp(5));v.setBackground(roundRect(bg,14));return v;}
    private LinearLayout card(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(17),dp(17),dp(17),dp(17));l.setBackground(roundRect(color,22));l.setElevation(dp(1));return l;}
    private TextView tv(String t,int sp,boolean bold,int c){TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);v.setTextColor(c);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable roundRect(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
