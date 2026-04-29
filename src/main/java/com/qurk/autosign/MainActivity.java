package com.qurk.autosign;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
    private static final String KEY_LAST_LOTTERY = "last_lottery_time";
    private static final String KEY_SIGN_HISTORY = "sign_history";
    private static final String KEY_STATUS_LOG = "status_log";
    private static final String KEY_ENABLE_SIGN = "enable_auto_sign";
    private static final String KEY_ENABLE_LOTTERY = "enable_auto_lottery";
    private static final String KEY_ENABLE_TOAST = "enable_toast";

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
        android.content.SharedPreferences sp = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        long lastSign = sp.getLong(KEY_LAST_SIGN, 0);
        long lastLottery = sp.getLong(KEY_LAST_LOTTERY, 0);

        TextView summary = new TextView(this);
        if (lastSign == 0) {
            summary.setText("状态：尚未签到\n\n请确保：\n1. 模块已在Xposed中启用\n2. 夸克APP已被注入\n3. 打开夸克APP触发首次签到");
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String time = sdf.format(new Date(lastSign));
            summary.setText("上次签到时间：" + time + "\n状态：已签到\n");
        }
        if (lastLottery > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            summary.append("上次抽奖时间：" + sdf.format(new Date(lastLottery)) + "\n");
        }
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        summary.setPadding(0, 20, 0, 20);
        root.addView(summary);

        TextView switchHeader = new TextView(this);
        switchHeader.setText("功能开关");
        switchHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        switchHeader.setTextColor(Color.BLACK);
        switchHeader.setPadding(0, 0, 0, 12);
        root.addView(switchHeader);

        Switch signSwitch = new Switch(this);
        signSwitch.setText("自动签到");
        signSwitch.setChecked(sp.getBoolean(KEY_ENABLE_SIGN, true));
        signSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
            sp.edit().putBoolean(KEY_ENABLE_SIGN, isChecked).apply());
        root.addView(signSwitch);

        Switch lotterySwitch = new Switch(this);
        lotterySwitch.setText("每日自动抽奖（3次）");
        lotterySwitch.setChecked(sp.getBoolean(KEY_ENABLE_LOTTERY, true));
        lotterySwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
            sp.edit().putBoolean(KEY_ENABLE_LOTTERY, isChecked).apply());
        root.addView(lotterySwitch);

        Switch toastSwitch = new Switch(this);
        toastSwitch.setText("Toast 提示");
        toastSwitch.setChecked(sp.getBoolean(KEY_ENABLE_TOAST, true));
        toastSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
            sp.edit().putBoolean(KEY_ENABLE_TOAST, isChecked).apply());
        root.addView(toastSwitch);

        // History header
        TextView historyHeader = new TextView(this);
        historyHeader.setText("签到历史记录（最近30次）");
        historyHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        historyHeader.setTextColor(Color.BLACK);
        root.addView(historyHeader);

        // History list
        String historyRaw = sp.getString(KEY_SIGN_HISTORY, "");

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

        TextView logHeader = new TextView(this);
        logHeader.setText("运行日志（最近20条）");
        logHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        logHeader.setTextColor(Color.BLACK);
        logHeader.setPadding(0, 16, 0, 8);
        root.addView(logHeader);

        String logRaw = sp.getString(KEY_STATUS_LOG, "");
        if (logRaw.isEmpty()) {
            TextView emptyLog = new TextView(this);
            emptyLog.setText("暂无日志");
            root.addView(emptyLog);
        } else {
            String[] lines = logRaw.split("\\n");
            int start = Math.max(0, lines.length - 20);
            for (int i = start; i < lines.length; i++) {
                TextView lineView = new TextView(this);
                lineView.setText(lines[i]);
                lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                lineView.setPadding(0, 2, 0, 2);
                root.addView(lineView);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }
}
