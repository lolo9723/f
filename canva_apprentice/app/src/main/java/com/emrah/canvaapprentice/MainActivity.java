package com.emrah.canvaapprentice;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private TextView status;
    private EditText goal;
    private CheckBox allowNew;

    @Override protected void onCreate(Bundle b){ super.onCreate(b); buildUi(); }
    @Override protected void onResume(){ super.onResume(); refresh(); }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32,32,32,32); scroll.addView(root);
        TextView title=new TextView(this); title.setText("Canva Çırak Ajan"); title.setTextSize(27); title.setTextColor(Color.BLACK); root.addView(title);
        TextView desc=new TextView(this); desc.setText("Yalnız Canva + ChatGPT. Emin değilse durur. Şifre/CAPTCHA'yı sana bırakır. Yeni tasarım varsayılan olarak kilitlidir."); desc.setTextSize(16); desc.setPadding(0,14,0,24); root.addView(desc);
        status=new TextView(this); status.setTextSize(16); status.setPadding(18,18,18,18); status.setBackgroundColor(Color.rgb(238,238,238)); root.addView(status);
        goal=new EditText(this); goal.setHint("Örn: Açık Canva tasarımındaki etkinlik afişini profesyonel hale getir; aynı tasarımda kal."); goal.setMinLines(4); goal.setGravity(Gravity.TOP); root.addView(goal,new LinearLayout.LayoutParams(-1,-2));
        allowNew=new CheckBox(this); allowNew.setText("Bu görevde yeni Canva tasarımı oluşturmasına izin ver"); allowNew.setChecked(false); root.addView(allowNew);
        Button accessibility=new Button(this); accessibility.setText("1) AJAN ERİŞİMİNİ AÇ"); accessibility.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); root.addView(accessibility);
        Button start=new Button(this); start.setText("2) GÖREVİ BAŞLAT"); start.setOnClickListener(v->{
            if(goal.getText().toString().trim().isEmpty()){toast("Önce ne istediğini yaz.");return;}
            AgentAccessibilityService s=AgentAccessibilityService.INSTANCE;
            if(s==null){toast("Önce erişilebilirlik servisinden Canva Çırak Ajan'ı aç.");return;}
            s.startTask(goal.getText().toString(),allowNew.isChecked()); refresh(); finishAndRemoveTask();
        }); root.addView(start);
        Button stop=new Button(this); stop.setText("AJANI DURDUR"); stop.setOnClickListener(v->{AgentAccessibilityService s=AgentAccessibilityService.INSTANCE;if(s!=null)s.stopTask();refresh();}); root.addView(stop);
        setContentView(scroll);
    }

    private void refresh(){
        TaskState s=new TaskStateRepository(this).load();
        boolean service=AgentAccessibilityService.INSTANCE!=null;
        int learned=0;
        try { learned=new ExperienceMemoryRepository(this).learnedTransitionCount(); } catch(Exception ignored) {}
        String anchor=s.designAnchor.isEmpty()?"Henüz bağlanmadı":s.designAnchor;
        status.setText("Servis: "+(service?"AÇIK":"KAPALI")+"\nAjan: "+s.mode+"\nAdım: "+s.step+
                "\nYeni tasarım izni: "+(s.allowNewDesign?"AÇIK":"KİLİTLİ")+
                "\nAktif tasarım: "+anchor+
                "\nÖğrenilmiş güvenli geçiş: "+learned+
                (s.humanReason.isEmpty()?"":"\nBekleyen kullanıcı işlemi: "+s.humanReason));
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
