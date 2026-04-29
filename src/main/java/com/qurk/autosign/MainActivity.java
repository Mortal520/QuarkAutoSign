package com.qurk.autosign;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String SP_NAME = "quark_autosign_prefs";
    private static final String KEY_LAST_SIGN = "last_sign_time";
    private static final String KEY_SIGN_HISTORY = "sign_history";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);

        // Title
        TextView title = new TextView(this);
        title.setText("夸克扫描王自动签到");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        // Summary
        long lastSign = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SIGN, 0);

        TextView summary = new TextView(this);
        if (lastSign == 0) {
            summary.setText("状态：尚未签到\n\n请确保：\n1. 模块已在Xposed中启用\n2. 夸克APP已被注入\n3. 打开夸克APP触发首次签到");
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String time = sdf.format(new Date(lastSign));
            summary.setText("上次签到时间：" + time + "\n状态：已签到\n");
        }
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        summary.setPadding(0, 20, 0, 20);
        root.addView(summary);

        // History header
        TextView historyHeader = new TextView(this);
        historyHeader.setText("签到历史记录（最近30次）");
        historyHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        historyHeader.setTextColor(Color.BLACK);
        root.addView(historyHeader);

        // History list
        String historyRaw = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SIGN_HISTORY, "");

        if (historyRaw.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无记录");
            empty.setPadding(0, 16, 0, 16);
            root.addView(empty);
        } else {
            List<String> entries = new ArrayList<>(Arrays.asList(historyRaw.split(",")));
            Collections.reverse(entries); // newest first

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            int index = 1;
            for (String entry : entries) {
                TextView item = new TextView(this);
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    try {
                        long ts = Long.parseLong(parts[0]);
                        String source = parts[1];
                        String time = sdf.format(new Date(ts));
                        item.setText(index + ". " + time + "  [" + source + "]");
                    } catch (NumberFormatException e) {
                        item.setText(index + ". " + entry);
                    }
                } else {
                    item.setText(index + ". " + entry);
                }
                item.setPadding(0, 8, 0, 8);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                root.addView(item);
                index++;
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }
}
