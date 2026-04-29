package com.qurk.autosign;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String SP_NAME = "quark_autosign_prefs";
    private static final String KEY_LAST_SIGN = "last_sign_time";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 60, 40, 40);

        TextView title = new TextView(this);
        title.setText("夸克扫描王自动签到");
        title.setTextSize(22);
        layout.addView(title);

        long lastSign = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SIGN, 0);

        TextView status = new TextView(this);
        if (lastSign == 0) {
            status.setText("状态：尚未签到\n\n请确保：\n1. 模块已在Xposed中启用\n2. 夸克APP已被注入\n3. 打开夸克APP触发首次签到");
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String time = sdf.format(new Date(lastSign));
            status.setText("上次签到时间：\n" + time + "\n\n状态：已签到");
        }
        status.setTextSize(16);
        status.setPadding(0, 30, 0, 0);
        layout.addView(status);

        setContentView(layout);
    }
}
