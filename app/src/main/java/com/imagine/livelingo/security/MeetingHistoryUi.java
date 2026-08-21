package com.imagine.livelingo.security;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** Native Android renderer for encrypted meeting history. */
public final class MeetingHistoryUi {
    public interface Actions {
        void open(String id);
        void delete(String id);
        void wipeAll();
    }

    private final Activity activity;
    private final LinearLayout host;
    private final Actions actions;

    public MeetingHistoryUi(Activity activity, LinearLayout host, Actions actions) {
        this.activity=activity; this.host=host; this.actions=actions;
    }

    public void render(List<MeetingVaultRepository.Item> items) {
        host.removeAllViews();
        if(items==null || items.isEmpty()){
            host.addView(text("Пока нет сохранённых встреч",18,true,0xFF1D2128));
            host.addView(text("После завершения совещания оно появится здесь в зашифрованном виде.",14,false,0xFF747B88),lp(-1,-2,0,8,0,0));
            return;
        }
        LinearLayout head=new LinearLayout(activity); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Защищённые встречи",22,true,0xFF111318),new LinearLayout.LayoutParams(0,-2,1));
        Button wipe=new Button(activity); wipe.setText("Удалить все"); wipe.setAllCaps(false); wipe.setTextColor(0xFFB42318); wipe.setBackground(round(0xFFFFEEEE,16));
        wipe.setOnClickListener(v->confirmWipe()); head.addView(wipe);
        host.addView(head,lp(-1,-2,0,0,0,14));
        for(MeetingVaultRepository.Item item:items) host.addView(card(item),lp(-1,-2,0,0,0,10));
    }

    private View card(MeetingVaultRepository.Item item){
        LinearLayout box=new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(15),dp(16),dp(15)); box.setBackground(round(Color.WHITE,20)); box.setElevation(dp(1));
        LinearLayout row=new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(item.title==null||item.title.isBlank()?"Совещание":item.title,17,true,0xFF15181E); row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView lock=text("🔒",16,false,0xFF3157D5); row.addView(lock); box.addView(row);
        box.addView(text(item.displayDate(),12,false,0xFF858C98),lp(-1,-2,0,5,0,9));
        if(item.preview!=null&&!item.preview.isBlank()) box.addView(text(item.preview,13,false,0xFF4C525D),lp(-1,-2,0,0,0,12));
        LinearLayout actionsRow=new LinearLayout(activity);
        Button open=new Button(activity); open.setText("Открыть"); open.setAllCaps(false); open.setTextColor(Color.WHITE); open.setBackground(round(0xFF3157D5,16)); open.setOnClickListener(v->actions.open(item.id));
        Button del=new Button(activity); del.setText("Удалить"); del.setAllCaps(false); del.setTextColor(0xFFB42318); del.setBackground(round(0xFFFFEEEE,16)); del.setOnClickListener(v->confirmDelete(item));
        actionsRow.addView(open,new LinearLayout.LayoutParams(0,dp(44),1)); LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,dp(44),1);dp.setMargins(dp(8),0,0,0); actionsRow.addView(del,dp); box.addView(actionsRow);
        return box;
    }

    private void confirmDelete(MeetingVaultRepository.Item item){
        new AlertDialog.Builder(activity).setTitle("Удалить встречу?").setMessage("Зашифрованная запись будет удалена с устройства без возможности восстановления.")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->actions.delete(item.id)).show();
    }
    private void confirmWipe(){
        new AlertDialog.Builder(activity).setTitle("Удалить все встречи?").setMessage("Все зашифрованные записи LiveLingo будут удалены с этого устройства.")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить все",(d,w)->actions.wipeAll()).show();
    }

    private TextView text(String s,int sp,boolean bold,int color){TextView v=new TextView(activity);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*activity.getResources().getDisplayMetrics().density);}
}
