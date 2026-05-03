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
import android.widget.Toast;
import android.database.Cursor;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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
    private static final String KEY_STATUS_LOG = "status_log";
    private static final String KEY_LAST_LOTTERY = "last_lottery_time";
    private static final String KEY_LOTTERY_COUNT = "lottery_count_today";
    private static final String KEY_ENABLE_SIGN = "enable_auto_sign";
    private static final String KEY_ENABLE_LOTTERY = "enable_auto_lottery";
    private static final String KEY_ENABLE_TOAST = "enable_toast";
    private static final String LOG_FILE_NAME = "autosign_log.txt";

    // 尝试通过反射使用 XSharedPreferences，避免直接引用导致崩溃
    private Object quarkPrefs;
    private boolean useXposedPrefs = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 安全初始化跨进程读取
        initXposedPrefs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);

        // Title
        TextView title = new TextView(this);
        title.setText("夸克扫描王自动签到");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        // Summary - 优先从夸克APP读取，如果没有则从模块读取
        android.content.SharedPreferences sp = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        
        // 从夸克APP读取数据（如果可用）
        long quarkLastSign = getQuarkLong(KEY_LAST_SIGN, 0);
        
        // 优先使用夸克的时间（更新的）
        long lastSign = quarkLastSign > sp.getLong(KEY_LAST_SIGN, 0) ? quarkLastSign : sp.getLong(KEY_LAST_SIGN, 0);

        TextView summary = new TextView(this);
        if (lastSign == 0) {
            summary.setText("状态：尚未签到\n\n请确保：\n1. 模块已在Xposed中启用\n2. 夸克APP已被注入\n3. 打开夸克APP触发首次签到");
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String time = sdf.format(new Date(lastSign));
            summary.setText("上次签到时间：" + time + "\n状态：已签到\n");
        }
        // 抽奖状态
        long lastLottery = Math.max(getQuarkLong(KEY_LAST_LOTTERY, 0), sp.getLong(KEY_LAST_LOTTERY, 0));
        if (lastLottery > 0) {
            SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            summary.append("\n上次抽奖：" + sdf2.format(new Date(lastLottery)));
            int lotteryCount = sp.getInt(KEY_LOTTERY_COUNT, 0);
            if (lotteryCount > 0) summary.append("（" + lotteryCount + "/3次）");
        } else {
            summary.append("\n抽奖：尚未执行");
        }
        summary.append("\n\n每日3次免费抽奖，模块自动执行");
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
        lotterySwitch.setText("自动抽奖（每日3次免费）");
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
        logHeader.setText("运行日志（最近20条）- 来自夸克APP");
        logHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        logHeader.setTextColor(Color.BLACK);
        logHeader.setPadding(0, 16, 0, 8);
        root.addView(logHeader);

        // 优先通过root读取夸克应用目录的日志文件
        String logRaw = readLogViaSu();
        if (logRaw.isEmpty()) {
            logRaw = readLogFromProvider();
        }
        if (logRaw.isEmpty()) {
            logRaw = readLogFromFile();
        }
        if (logRaw.isEmpty()) {
            reloadQuarkPrefs();
            logRaw = getQuarkString(KEY_STATUS_LOG, "");
        }
        if (logRaw.isEmpty()) {
            logRaw = sp.getString(KEY_STATUS_LOG, "");
        }
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

        // 诊断提示
        TextView diagHeader = new TextView(this);
        diagHeader.setText("模块诊断");
        diagHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        diagHeader.setTextColor(Color.BLACK);
        diagHeader.setPadding(0, 20, 0, 8);
        root.addView(diagHeader);

        TextView diagInfo = new TextView(this);
        diagInfo.setText(
            "如无日志显示：\n" +
            "1. 打开 LSPosed 管理器 → 日志\n" +
            "2. 搜索 \"QuarkAutoSign\"\n" +
            "3. 确认模块已勾选夸克扫描王\n" +
            "4. 打开夸克扫描王等待15秒\n" +
            "5. 如看到 \"模块已激活\" Toast即正常\n\n" +
            "模块包名: com.qurk.autosign\n" +
            "目标包名: com.quark.scanking");
        diagInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        diagInfo.setTextColor(Color.DKGRAY);
        diagInfo.setPadding(0, 4, 0, 20);
        root.addView(diagInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }
    
    // 安全初始化 XposedPreferences
    private void initXposedPrefs() {
        try {
            Class<?> xspClass = Class.forName("de.robv.android.xposed.XSharedPreferences");
            // 尝试 com.quark.scanking
            quarkPrefs = xspClass.getConstructor(String.class, String.class)
                .newInstance("com.quark.scanking", SP_NAME);
            xspClass.getMethod("makeWorldReadable").invoke(quarkPrefs);
            xspClass.getMethod("reload").invoke(quarkPrefs);
            
            // 检查是否有数据
            Object keys = xspClass.getMethod("getAll").invoke(quarkPrefs);
            if (keys instanceof java.util.Map && ((java.util.Map<?, ?>) keys).isEmpty()) {
                // 尝试旧包名
                quarkPrefs = xspClass.getConstructor(String.class, String.class)
                    .newInstance("com.quark.scank", SP_NAME);
                xspClass.getMethod("makeWorldReadable").invoke(quarkPrefs);
            }
            useXposedPrefs = true;
        } catch (Throwable e) {
            useXposedPrefs = false;
            quarkPrefs = null;
        }
    }
    
    private void reloadQuarkPrefs() {
        if (useXposedPrefs && quarkPrefs != null) {
            try {
                quarkPrefs.getClass().getMethod("reload").invoke(quarkPrefs);
            } catch (Throwable ignored) {}
        }
    }
    
    private long getQuarkLong(String key, long def) {
        if (!useXposedPrefs || quarkPrefs == null) return def;
        try {
            return (Long) quarkPrefs.getClass().getMethod("getLong", String.class, long.class)
                .invoke(quarkPrefs, key, def);
        } catch (Throwable e) {
            return def;
        }
    }
    
    private String getQuarkString(String key, String def) {
        if (!useXposedPrefs || quarkPrefs == null) return def;
        try {
            return (String) quarkPrefs.getClass().getMethod("getString", String.class, String.class)
                .invoke(quarkPrefs, key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    private String readLogViaSu() {
        String[] paths = {
            "/data/data/com.quark.scanking/files/" + LOG_FILE_NAME,
            "/data/user/0/com.quark.scanking/files/" + LOG_FILE_NAME,
            "/data/data/com.quark.scank/files/" + LOG_FILE_NAME,
        };
        for (String path : paths) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                br.close();
                p.waitFor();
                if (sb.length() > 0) return sb.toString();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private String readLogFromProvider() {
        try {
            Cursor cursor = getContentResolver().query(
                Uri.parse("content://com.qurk.autosign.logprovider/log"),
                null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        String log = cursor.getString(0);
                        if (log != null && !log.isEmpty()) return log;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private String readLogFromFile() {
        String[] paths = {
            "/data/data/com.quark.scanking/files/" + LOG_FILE_NAME,
            "/data/user/0/com.quark.scanking/files/" + LOG_FILE_NAME,
            "/data/data/com.quark.scank/files/" + LOG_FILE_NAME,
            "/data/user/0/com.quark.scank/files/" + LOG_FILE_NAME,
        };
        for (String path : paths) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists() && f.canRead()) {
                    StringBuilder sb = new StringBuilder();
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(line);
                    }
                    br.close();
                    if (sb.length() > 0) return sb.toString();
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }
}
