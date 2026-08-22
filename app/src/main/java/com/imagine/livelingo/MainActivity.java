package com.imagine.livelingo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.imagine.livelingo.background.LiveLingoForegroundService;
import com.imagine.livelingo.background.SessionRuntime;
import com.imagine.livelingo.business.Insight;
import com.imagine.livelingo.business.MeetingContentTools;
import com.imagine.livelingo.security.EncryptedMeetingVault;
import com.imagine.livelingo.security.MeetingHistoryController;
import com.imagine.livelingo.security.MeetingHistoryUi;
import com.imagine.livelingo.security.SecureScreen;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SessionRuntime.Observer {
    private static final int REQ=7;
    private static final int REQ_UNLOCK_MEETING=41;
    private TextView status,sourceText,translatedText,detected,headset,modeTitle,modeSubtitle,meetingInsights,meetingResult,importantResults,presentationResult;
    private EditText importantSearch;
    private Button mainButton,debugButton,modeTranslate,modeConversation,modeMeeting,navLive,navMeetings,navPresentation,navLibrary,navProfile,shareMeetingButton,presentationButton,searchImportantButton,generatePresentationButton,sharePresentationButton;
    private Spinner inputSpinner,targetSpinner;
    private LinearLayout livePage,meetingsPage,presentationPage,libraryPage,profilePage,meetingResultCard,meetingHistoryHost;
    private boolean active;
    private String manualInput="auto";
    private DebugTrace trace;
    private String currentMode="translate";
    private String openedMeetingId;
    private String openedMeetingPayload="";
    private MeetingHistoryController historyController;
    private MeetingHistoryUi historyUi;
    private SessionRuntime runtime;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        trace=new DebugTrace(this);
        getWindow().setStatusBarColor(0xFFF7F8FB);
        buildUi();
        runtime=SessionRuntime.get(this);
        historyController=new MeetingHistoryController(this,new MeetingHistoryController.Listener(){
            public void onList(List<com.imagine.livelingo.security.MeetingVaultRepository.Item> items){historyUi.render(items);}
            public void onMeeting(EncryptedMeetingVault.StoredMeeting meeting){
                openedMeetingId=meeting.id;openedMeetingPayload=meeting.payload;
                meetingResult.setText(meeting.payload);meetingResultCard.setVisibility(View.VISIBLE);
                importantResults.setText(MeetingContentTools.searchImportant(meeting.payload,""));
                SecureScreen.protect(MainActivity.this);
            }
            public void onError(String message){Toast.makeText(MainActivity.this,message,Toast.LENGTH_LONG).show();}
            public void onDeleted(){openedMeetingId=null;openedMeetingPayload="";meetingResult.setText("Выберите сохранённую встречу");meetingResultCard.setVisibility(View.GONE);importantResults.setText("Откройте встречу, чтобы искать по важным моментам");SecureScreen.unprotect(MainActivity.this);}
        });
        historyUi=new MeetingHistoryUi(this,meetingHistoryHost,new MeetingHistoryUi.Actions(){
            public void open(String id){if(!historyController.requestOpen(id,REQ_UNLOCK_MEETING)) Toast.makeText(MainActivity.this,"На устройстве не настроена защита экрана",Toast.LENGTH_LONG).show();}
            public void delete(String id){historyController.delete(id);}
            public void wipeAll(){historyController.wipeAll();}
        });
        mainButton.setOnClickListener(v->toggle());
        debugButton.setOnClickListener(v->shareTrace());
        modeTranslate.setOnClickListener(v->selectMode("translate"));
        modeConversation.setOnClickListener(v->selectMode("conversation"));
        modeMeeting.setOnClickListener(v->selectMode("meeting"));
        navLive.setOnClickListener(v->showPage("live"));
        navMeetings.setOnClickListener(v->{showPage("meetings");historyController.refresh();});
        navPresentation.setOnClickListener(v->showPage("presentation"));
        navLibrary.setOnClickListener(v->showPage("library"));
        navProfile.setOnClickListener(v->showPage("profile"));
        shareMeetingButton.setOnClickListener(v->shareMeeting());
        presentationButton.setOnClickListener(v->{generatePresentation();showPage("presentation");});
        generatePresentationButton.setOnClickListener(v->generatePresentation());
        sharePresentationButton.setOnClickListener(v->shareText(presentationResult.getText().toString(),"Поделиться презентацией"));
        searchImportantButton.setOnClickListener(v->runImportantSearch());
        importantSearch.setOnEditorActionListener((v,action,event)->{runImportantSearch();return true;});
        inputSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                manualInput=LanguageCatalog.inputCodeForDisplay((String)inputSpinner.getSelectedItem());
                detected.setText("auto".equals(manualInput)?"Автоопределение языка":"Говорит: "+LanguageCatalog.displayForCode(manualInput));
                if(active) runtime.configure(currentMode,manualInput,LanguageCatalog.codeForDisplay((String)targetSpinner.getSelectedItem()));
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        targetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(active) runtime.configure(currentMode,manualInput,LanguageCatalog.codeForDisplay((String)targetSpinner.getSelectedItem()));}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        requestPermissionsIfNeeded();
        headset.setText(BluetoothRouter.routeToHeadset(this));
        selectMode("translate");showPage("live");
    }

    @Override protected void onStart(){super.onStart();runtime.addObserver(this);}
    @Override protected void onStop(){runtime.removeObserver(this);super.onStop();}

    @Override public void onState(SessionRuntime.Snapshot s){runOnUiThread(()->{active=s.active;currentMode=s.mode;status.setText(s.status);if(s.sourceText!=null&&!s.sourceText.isBlank())sourceText.setText(s.sourceText);if(s.translatedText!=null&&!s.translatedText.isBlank())translatedText.setText(s.translatedText);if(s.detectedLanguage!=null)detected.setText("Определён язык: "+LanguageCatalog.displayForCode(s.detectedLanguage));renderInsights(s.insights);selectMode(currentMode);});}

    @Override public void onFinalizedMeeting(String encryptedId,String report){runOnUiThread(()->{openedMeetingId=encryptedId;openedMeetingPayload=report;meetingResult.setText(report);meetingResultCard.setVisibility(View.VISIBLE);importantResults.setText(MeetingContentTools.searchImportant(report,""));historyController.refresh();showPage("meetings");Toast.makeText(this,"Встреча сохранена в защищённое хранилище",Toast.LENGTH_SHORT).show();});}

    private void renderInsights(List<Insight> list){
        if(list==null||list.isEmpty()){meetingInsights.setText("Пока нет важных моментов. Во время совещания здесь появятся решения, задачи, риски и вопросы.");return;}
        StringBuilder sb=new StringBuilder();
        for(Insight i:list){String p="• ";if(i.type==Insight.Type.DECISION)p="✓ Решение: ";else if(i.type==Insight.Type.ACTION)p="☐ Задача: ";else if(i.type==Insight.Type.RISK)p="⚠ Риск: ";else if(i.type==Insight.Type.QUESTION)p="? Вопрос: ";else if(i.type==Insight.Type.FOLLOW_UP)p="→ Следующий шаг: ";if(sb.length()>0)sb.append('\n');sb.append(p).append(i.text);}meetingInsights.setText(sb.toString());
    }

    private void runImportantSearch(){
        if(openedMeetingPayload.isBlank()){importantResults.setText("Сначала откройте сохранённую встречу");return;}
        importantResults.setText(MeetingContentTools.searchImportant(openedMeetingPayload,importantSearch.getText().toString()));
    }

    private void generatePresentation(){
        if(openedMeetingPayload.isBlank()){presentationResult.setText("Сначала откройте встречу во вкладке Meetings");return;}
        presentationResult.setText(MeetingContentTools.buildPresentationDraft(openedMeetingPayload));
        SecureScreen.protect(this);
    }

    private void showPage(String page){
        livePage.setVisibility("live".equals(page)?View.VISIBLE:View.GONE);meetingsPage.setVisibility("meetings".equals(page)?View.VISIBLE:View.GONE);presentationPage.setVisibility("presentation".equals(page)?View.VISIBLE:View.GONE);libraryPage.setVisibility("library".equals(page)?View.VISIBLE:View.GONE);profilePage.setVisibility("profile".equals(page)?View.VISIBLE:View.GONE);
        navLive.setAlpha("live".equals(page)?1f:.42f);navMeetings.setAlpha("meetings".equals(page)?1f:.42f);navPresentation.setAlpha("presentation".equals(page)?1f:.42f);navLibrary.setAlpha("library".equals(page)?1f:.42f);navProfile.setAlpha("profile".equals(page)?1f:.42f);
        boolean confidential="meetings".equals(page)||"presentation".equals(page);
        if(confidential && !openedMeetingPayload.isBlank()) SecureScreen.protect(this); else if(!confidential) SecureScreen.unprotect(this);
    }

    private void selectMode(String mode){currentMode=mode;modeTranslate.setAlpha("translate".equals(mode)?1f:.45f);modeConversation.setAlpha("conversation".equals(mode)?1f:.45f);modeMeeting.setAlpha("meeting".equals(mode)?1f:.45f);if("translate".equals(mode)){modeTitle.setText("Перевод в реальном времени");modeSubtitle.setText("Слушает собеседника и сразу говорит перевод");meetingInsights.setVisibility(View.GONE);mainButton.setText(active?"ОСТАНОВИТЬ":"НАЧАТЬ ПЕРЕВОД");}else if("conversation".equals(mode)){modeTitle.setText("Разговор вдвоём");modeSubtitle.setText("Поочерёдный перевод двух собеседников");meetingInsights.setVisibility(View.GONE);mainButton.setText(active?"ОСТАНОВИТЬ":"НАЧАТЬ РАЗГОВОР");}else{modeTitle.setText("Совещание");modeSubtitle.setText("Стенограмма, перевод и важные решения встречи");meetingInsights.setVisibility(View.VISIBLE);mainButton.setText(active?"ЗАВЕРШИТЬ ВСТРЕЧУ":"НАЧАТЬ СОВЕЩАНИЕ");}}

    private void requestPermissionsIfNeeded(){List<String> p=new ArrayList<>();if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.RECORD_AUDIO);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);headset.setText(BluetoothRouter.routeToHeadset(this));}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_UNLOCK_MEETING){if(resultCode==RESULT_OK)historyController.onAuthenticationSucceeded();else Toast.makeText(this,"Доступ к встрече отменён",Toast.LENGTH_SHORT).show();}}

    private void toggle(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissionsIfNeeded();return;}if(active){LiveLingoForegroundService.stop(this);}else{String target=LanguageCatalog.codeForDisplay((String)targetSpinner.getSelectedItem());LiveLingoForegroundService.start(this,currentMode,manualInput,target);}}
    private void shareMeeting(){if(openedMeetingPayload.isBlank()||openedMeetingId==null){Toast.makeText(this,"Сначала откройте защищённую встречу",Toast.LENGTH_SHORT).show();return;}shareText(openedMeetingPayload,"Поделиться итогами встречи");}
    private void shareText(String text,String title){if(text==null||text.isBlank()){Toast.makeText(this,"Нет данных для отправки",Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,title));}
    private void shareTrace(){try{File f=trace.file();Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Отправить журнал LiveLingo"));}catch(Exception e){Toast.makeText(this,"Не удалось открыть журнал: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    @Override protected void onDestroy(){SecureScreen.unprotect(this);super.onDestroy();}

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

        meetingsPage=new LinearLayout(this);meetingsPage.setOrientation(LinearLayout.VERTICAL);pages.addView(meetingsPage);meetingsPage.addView(tv("Встречи",27,true,0xFF111318));meetingsPage.addView(tv("Защищённая история, поиск и AI-итоги",14,false,0xFF747B88),lp(-1,-2,0,3,0,14));meetingHistoryHost=new LinearLayout(this);meetingHistoryHost.setOrientation(LinearLayout.VERTICAL);meetingsPage.addView(meetingHistoryHost);
        LinearLayout searchCard=card(Color.WHITE);searchCard.addView(tv("ПОИСК ПО ВАЖНЫМ МОМЕНТАМ",11,true,0xFF8B929F));importantSearch=new EditText(this);importantSearch.setHint("Например: цена, срок, клиент, риск…");importantSearch.setSingleLine(true);searchCard.addView(importantSearch,lp(-1,dp(52),0,6,0,6));searchImportantButton=new Button(this);searchImportantButton.setText("Найти");searchImportantButton.setAllCaps(false);searchCard.addView(searchImportantButton);importantResults=tv("Откройте встречу, чтобы искать решения, задачи, риски и фразы",14,false,0xFF343942);searchCard.addView(importantResults,lp(-1,-2,0,10,0,0));meetingsPage.addView(searchCard,lp(-1,-2,0,0,0,12));
        meetingResultCard=card(Color.WHITE);meetingResult=tv("Выберите сохранённую встречу",14,false,0xFF333841);meetingResultCard.addView(meetingResult);LinearLayout actions=new LinearLayout(this);shareMeetingButton=new Button(this);shareMeetingButton.setText("Поделиться");presentationButton=new Button(this);presentationButton.setText("Презентация");actions.addView(shareMeetingButton,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(presentationButton,new LinearLayout.LayoutParams(0,dp(48),1));meetingResultCard.addView(actions,lp(-1,-2,0,12,0,0));meetingResultCard.setVisibility(View.GONE);meetingsPage.addView(meetingResultCard);

        presentationPage=new LinearLayout(this);presentationPage.setOrientation(LinearLayout.VERTICAL);pages.addView(presentationPage);presentationPage.addView(tv("Презентация",27,true,0xFF111318));presentationPage.addView(tv("Автоматическая структура слайдов из решений встречи",14,false,0xFF747B88),lp(-1,-2,0,3,0,14));LinearLayout pCard=card(0xFF111318);pCard.addView(tv("MEETING → SLIDES",11,true,0xFF8DA6FF));pCard.addView(tv("LiveLingo выделит решения, задачи, риски и следующие шаги и соберёт из них 5-слайдовую структуру.",15,false,Color.WHITE),lp(-1,-2,0,8,0,12));generatePresentationButton=new Button(this);generatePresentationButton.setText("СОБРАТЬ ПРЕЗЕНТАЦИЮ");generatePresentationButton.setAllCaps(false);pCard.addView(generatePresentationButton);presentationPage.addView(pCard,lp(-1,-2,0,0,0,12));LinearLayout pResult=card(Color.WHITE);presentationResult=tv("Сначала откройте встречу во вкладке Meetings",15,false,0xFF252A32);pResult.addView(presentationResult);sharePresentationButton=new Button(this);sharePresentationButton.setText("Поделиться структурой");sharePresentationButton.setAllCaps(false);pResult.addView(sharePresentationButton,lp(-1,dp(48),0,12,0,0));presentationPage.addView(pResult);

        libraryPage=new LinearLayout(this);libraryPage.setOrientation(LinearLayout.VERTICAL);pages.addView(libraryPage);libraryPage.addView(tv("Библиотека",27,true,0xFF111318));libraryPage.addView(tv("Транскрипты, переводы и материалы встреч",14,false,0xFF747B88));
        profilePage=new LinearLayout(this);profilePage.setOrientation(LinearLayout.VERTICAL);pages.addView(profilePage);profilePage.addView(tv("Профиль и безопасность",27,true,0xFF111318));profilePage.addView(tv("Локальное хранение · зашифрованные встречи · AI-модели",14,false,0xFF747B88));

        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(4),dp(6),dp(4),dp(8));navLive=navButton("Live");navMeetings=navButton("Meet");navPresentation=navButton("Slides");navLibrary=navButton("Library");navProfile=navButton("Profile");nav.addView(navLive,new LinearLayout.LayoutParams(0,dp(48),1));nav.addView(navMeetings,new LinearLayout.LayoutParams(0,dp(48),1));nav.addView(navPresentation,new LinearLayout.LayoutParams(0,dp(48),1));nav.addView(navLibrary,new LinearLayout.LayoutParams(0,dp(48),1));nav.addView(navProfile,new LinearLayout.LayoutParams(0,dp(48),1));shell.addView(nav);setContentView(shell);
    }

    private Button navButton(String t){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(11);return b;}
    private Button modeButton(String t){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(0xFF20242B);b.setGravity(Gravity.CENTER);b.setBackground(roundRect(Color.WHITE,18));return b;}
    private TextView chip(String t,int bg,int fg){TextView v=tv(t,10,true,fg);v.setPadding(dp(9),dp(5),dp(9),dp(5));v.setBackground(roundRect(bg,14));return v;}
    private LinearLayout card(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(17),dp(17),dp(17),dp(17));l.setBackground(roundRect(color,22));l.setElevation(dp(1));return l;}
    private TextView tv(String t,int sp,boolean bold,int c){TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);v.setTextColor(c);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable roundRect(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
