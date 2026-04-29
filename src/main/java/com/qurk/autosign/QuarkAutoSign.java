package com.qurk.autosign;

import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class QuarkAutoSign implements IXposedHookLoadPackage {

    private static final String TAG = "QuarkAutoSign";
    private static final String TARGET_PKG = "com.quark.scanking";
    private static final String TARGET_PKG_LEGACY = "com.quark.scank";
    private static final String SP_NAME = "quark_autosign_prefs";
    private static final String KEY_LAST_SIGN = "last_sign_time";
    private static final String KEY_LAST_LOTTERY = "last_lottery_time";
    private static final String KEY_SIGN_HISTORY = "sign_history";
    private static final String KEY_STATUS_LOG = "status_log";
    private static final String KEY_ENABLE_SIGN = "enable_auto_sign";
    private static final String KEY_ENABLE_LOTTERY = "enable_auto_lottery";
    private static final String KEY_ENABLE_TOAST = "enable_toast";
    private static final int MAX_HISTORY = 30;
    private static final int MAX_LOG = 50;
    private static final int DAILY_LOTTERY_TIMES = 3;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)
            && !TARGET_PKG_LEGACY.equals(lpparam.packageName)) {
            return;
        }

        log(lpparam, "模块已加载，注入进程: " + lpparam.packageName);
        hookApplication(lpparam);
        hookSettingsActivity(lpparam);
    }

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application.class.getName(),
                lpparam.classLoader,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.thisObject;
                        Context ctx = app.getApplicationContext();
                        log(lpparam, "Hook成功：夸克扫描王启动");
                        showToast(ctx, "Hook成功，模块已激活");
                        performAutoTasks(ctx, lpparam);
                    }
                }
            );
        } catch (Exception e) {
            Log.e(TAG, "Hook Application failed: " + e.getMessage());
        }
    }

    private void hookSettingsActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook Activity.onResume，在夸克APP的设置相关页面添加模块入口
        try {
            XposedHelpers.findAndHookMethod(
                Activity.class.getName(),
                lpparam.classLoader,
                "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        String actName = activity.getClass().getName();
                        // 在包含"setting"或"about"的页面添加入口
                        String lower = actName.toLowerCase();
                        if (lower.contains("setting") || lower.contains("about") || lower.contains("mine") || lower.contains("profile") || lower.contains("personal")) {
                            injectModuleEntry(activity);
                        }
                    }
                }
            );
            log(lpparam, "Hook Activity.onResume 成功，将在设置页注入模块入口");
        } catch (Exception e) {
            Log.e(TAG, "Hook settings failed: " + e.getMessage());
        }
    }

    private boolean entryInjected = false;
    private void injectModuleEntry(Activity activity) {
        if (entryInjected) return;
        try {
            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decorView;
            // Try to find a LinearLayout or FrameLayout content area
            ViewGroup content = findContentView(root);
            if (content == null) return;

            TextView entryView = new TextView(activity);
            entryView.setText("✅ 夸克自动签到模块（已注入）");
            entryView.setTextSize(14);
            entryView.setPadding(40, 24, 40, 24);
            entryView.setBackgroundColor(0x1A4CAF50);
            entryView.setOnClickListener(v -> showModuleStatusDialog(activity));

            content.addView(entryView);
            entryInjected = true;
            Log.i(TAG, "Module entry injected into: " + activity.getClass().getName());
        } catch (Exception e) {
            Log.e(TAG, "injectModuleEntry error: " + e.getMessage());
        }
    }

    private ViewGroup findContentView(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) child;
                String name = vg.getClass().getName();
                if (name.contains("FrameLayout") || name.contains("LinearLayout") || name.contains("CoordinatorLayout")) {
                    if (vg.getChildCount() > 0) return vg;
                }
                ViewGroup deeper = findContentView(vg);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    private void showModuleStatusDialog(Activity activity) {
        try {
            Context ctx = activity.getApplicationContext();
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);

            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(48, 40, 48, 20);

            // Status
            long lastSign = sp.getLong(KEY_LAST_SIGN, 0);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            TextView status = new TextView(ctx);
            if (lastSign == 0) {
                status.setText("状态：尚未签到");
            } else {
                status.setText("上次签到：" + sdf.format(new Date(lastSign)) + "\n状态：已签到");
            }
            long lastLottery = sp.getLong(KEY_LAST_LOTTERY, 0);
            if (lastLottery > 0) {
                status.append("\n上次抽奖：" + sdf.format(new Date(lastLottery)));
            }
            status.setTextSize(16);
            status.setPadding(0, 0, 0, 24);
            layout.addView(status);

            // History
            String historyRaw = sp.getString(KEY_SIGN_HISTORY, "");
            if (!historyRaw.isEmpty()) {
                TextView header = new TextView(ctx);
                header.setText("最近签到记录：");
                header.setTextSize(14);
                header.setPadding(0, 0, 0, 8);
                layout.addView(header);

                List<String> entries = new ArrayList<>(Arrays.asList(historyRaw.split(",")));
                Collections.reverse(entries);
                int count = Math.min(entries.size(), 10);
                for (int i = 0; i < count; i++) {
                    String[] parts = entries.get(i).split("\\|", 2);
                    TextView item = new TextView(ctx);
                    if (parts.length == 2) {
                        try {
                            String time = sdf.format(new Date(Long.parseLong(parts[0])));
                            item.setText((i+1) + ". " + time + " [" + parts[1] + "]");
                        } catch (Exception e) {
                            item.setText((i+1) + ". " + entries.get(i));
                        }
                    } else {
                        item.setText((i+1) + ". " + entries.get(i));
                    }
                    item.setTextSize(13);
                    item.setPadding(0, 4, 0, 4);
                    layout.addView(item);
                }
            }

            TextView switches = new TextView(ctx);
            switches.setText("\n功能开关：\n自动签到=" + readSwitch(KEY_ENABLE_SIGN, true)
                + "\n自动抽奖=" + readSwitch(KEY_ENABLE_LOTTERY, true)
                + "\nToast提示=" + readSwitch(KEY_ENABLE_TOAST, true));
            switches.setTextSize(13);
            switches.setPadding(0, 12, 0, 0);
            layout.addView(switches);

            // Status log
            String logRaw = sp.getString(KEY_STATUS_LOG, "");
            if (!logRaw.isEmpty()) {
                TextView logHeader = new TextView(ctx);
                logHeader.setText("\n运行日志：");
                logHeader.setTextSize(14);
                logHeader.setPadding(0, 12, 0, 8);
                layout.addView(logHeader);

                String[] logLines = logRaw.split("\n");
                int start = Math.max(0, logLines.length - 15);
                for (int i = start; i < logLines.length; i++) {
                    TextView logItem = new TextView(ctx);
                    logItem.setText(logLines[i]);
                    logItem.setTextSize(12);
                    logItem.setPadding(0, 2, 0, 2);
                    layout.addView(logItem);
                }
            }

            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(layout);

            new AlertDialog.Builder(activity)
                .setTitle("夸克自动签到模块")
                .setView(scroll)
                .setPositiveButton("确定", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "showModuleStatusDialog error: " + e.getMessage());
        }
    }

    private volatile boolean tasksStarted = false;
    private XSharedPreferences modulePrefs;
    
    private void initModulePrefs() {
        if (modulePrefs == null) {
            modulePrefs = new XSharedPreferences("com.qurk.autosign", SP_NAME);
            modulePrefs.makeWorldReadable();
        }
    }
    
    private boolean readSwitch(String key, boolean def) {
        try {
            initModulePrefs();
            modulePrefs.reload();
            return modulePrefs.getBoolean(key, def);
        } catch (Exception e) {
            Log.w(TAG, "readSwitch failed: " + e.getMessage());
            return def;
        }
    }
    
    private void performAutoTasks(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        if (tasksStarted) {
            log(lpparam, "任务已在执行中，跳过重复触发");
            return;
        }
        tasksStarted = true;
        
        // 延迟10秒执行，确保APP完全初始化
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                boolean signEnabled = readSwitch(KEY_ENABLE_SIGN, true);
                boolean lotteryEnabled = readSwitch(KEY_ENABLE_LOTTERY, true);
                boolean toastEnabled = readSwitch(KEY_ENABLE_TOAST, true);
                
                log(lpparam, "开关状态: 签到=" + signEnabled + ", 抽奖=" + lotteryEnabled + ", Toast=" + toastEnabled);
                
                // 使用夸克APP的SharedPreferences读取状态
                android.content.SharedPreferences quarkSp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                
                if (signEnabled) {
                    long lastSign = quarkSp.getLong(KEY_LAST_SIGN, 0);
                    if (shouldDoToday(lastSign)) {
                        log(lpparam, "今日未签到，执行签到");
                        doSignInSafe(ctx, lpparam);
                    } else {
                        log(lpparam, "今日已签到，跳过");
                        if (toastEnabled) showToast(ctx, "今日已签到");
                    }
                } else {
                    log(lpparam, "自动签到开关关闭，跳过");
                }

                if (lotteryEnabled) {
                    long lastLottery = quarkSp.getLong(KEY_LAST_LOTTERY, 0);
                    if (shouldDoToday(lastLottery)) {
                        log(lpparam, "今日未抽奖，执行抽奖");
                        doDailyLotterySafe(ctx, lpparam);
                    } else {
                        log(lpparam, "今日已抽奖，跳过");
                    }
                } else {
                    log(lpparam, "自动抽奖开关关闭，跳过");
                }
            } catch (Throwable e) {
                Log.e(TAG, "performAutoTasks error: " + e.getMessage());
            }
        }, 10000);
    }

    private boolean shouldDoToday(long ts) {
        if (ts == 0) return true;
        try {
            Calendar last = Calendar.getInstance();
            last.setTimeInMillis(ts);
            Calendar now = Calendar.getInstance();
            return last.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)
                || last.get(Calendar.YEAR) != now.get(Calendar.YEAR);
        } catch (Exception e) {
            return true;
        }
    }

    private void doSignInSafe(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 再延迟2秒，确保网络模块初始化
                log(lpparam, "[签到] 正在获取 CameraCheckInManager...");
                
                Class<?> managerClass = XposedHelpers.findClass(
                    "com.ucpro.feature.study.userop.CameraCheckInManager",
                    ctx.getClassLoader()
                );

                Object manager = XposedHelpers.callStaticMethod(managerClass, "m63467i", (Object) null);
                if (manager == null) {
                    log(lpparam, "[签到] CameraCheckInManager 为 null");
                    showToast(ctx, "签到失败：Manager为null");
                    return;
                }

                log(lpparam, "[签到] 调用接口 m63476q...");
                try {
                    XposedHelpers.callMethod(manager, "m63476q", null, "xposed_auto_sign");
                } catch (Throwable e1) {
                    // 尝试无参调用
                    try {
                        XposedHelpers.callMethod(manager, "m63476q", new Object[]{null, "xposed_auto_sign"});
                    } catch (Throwable e2) {
                        log(lpparam, "[签到] 调用失败: " + e2.getMessage());
                        showToast(ctx, "签到调用失败");
                        return;
                    }
                }
                
                recordSignTime(ctx, "auto");
                log(lpparam, "[签到] 触发成功 ✓");
                showToast(ctx, "签到成功 ✓");
            } catch (Throwable e) {
                String msg = "[签到] 异常: " + e.getMessage();
                Log.e(TAG, msg);
                log(lpparam, msg);
                showToast(ctx, "签到异常");
            }
        }).start();
    }
    
    private void doDailyLotterySafe(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        new Thread(() -> {
            try {
                Thread.sleep(3000); // 抽奖再延迟3秒
                log(lpparam, "[抽奖] 开始每日抽奖，共" + DAILY_LOTTERY_TIMES + "次");
                showToast(ctx, "开始自动抽奖");
                
                for (int i = 1; i <= DAILY_LOTTERY_TIMES; i++) {
                    final int index = i;
                    Thread.sleep(2000); // 每次抽奖间隔2秒
                    
                    try {
                        Class<?> managerClass = XposedHelpers.findClass(
                            "com.ucpro.feature.study.userop.CameraCheckInManager",
                            ctx.getClassLoader()
                        );
                        Object manager = XposedHelpers.callStaticMethod(managerClass, "m63467i", (Object) null);
                        if (manager == null) {
                            log(lpparam, "[抽奖] 第" + index + "次失败：Manager为null");
                            continue;
                        }
                        
                        // 尝试调用抽奖方法
                        boolean triggered = false;
                        String[] candidates = new String[]{"m63475p", "m63474o", "m63473n", "lottery", "drawLottery"};
                        for (String method : candidates) {
                            try {
                                XposedHelpers.callMethod(manager, method);
                                triggered = true;
                                break;
                            } catch (Throwable ignored) {
                            }
                        }
                        
                        if (triggered) {
                            recordSignTime(ctx, "lottery#" + index);
                            log(lpparam, "[抽奖] 第" + index + "次成功");
                            showToast(ctx, "抽奖" + index + "成功");
                        } else {
                            log(lpparam, "[抽奖] 第" + index + "次未找到方法");
                        }
                    } catch (Throwable e) {
                        log(lpparam, "[抽奖] 第" + index + "次异常: " + e.getMessage());
                    }
                }
                
                // 记录抽奖完成时间
                ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_LOTTERY, System.currentTimeMillis()).apply();
                showToast(ctx, "抽奖执行完成");
                
            } catch (Throwable e) {
                log(lpparam, "[抽奖] 整体异常: " + e.getMessage());
            }
        }).start();
    }

    private void recordSignTime(Context ctx, String source) {
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);

            sp.edit().putLong(KEY_LAST_SIGN, now).apply();

            String history = sp.getString(KEY_SIGN_HISTORY, "");
            String entry = now + "|" + source;
            List<String> list = new ArrayList<>();
            if (!history.isEmpty()) {
                list.addAll(Arrays.asList(history.split(",")));
            }
            list.add(entry);
            while (list.size() > MAX_HISTORY) {
                list.remove(0);
            }
            sp.edit().putString(KEY_SIGN_HISTORY, String.join(",", list)).apply();
        } catch (Exception e) {
            Log.e(TAG, "recordSignTime error: " + e.getMessage());
        }
    }

    private void log(XC_LoadPackage.LoadPackageParam lpparam, String msg) {
        Log.i(TAG, msg);
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentApplication"
            );
            if (ctx == null) return;
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
            String line = sdf.format(new Date()) + " " + msg;
            String existing = sp.getString(KEY_STATUS_LOG, "");
            List<String> list = new ArrayList<>();
            if (!existing.isEmpty()) {
                list.addAll(Arrays.asList(existing.split("\n")));
            }
            list.add(line);
            while (list.size() > MAX_LOG) {
                list.remove(0);
            }
            sp.edit().putString(KEY_STATUS_LOG, String.join("\n", list)).apply();
        } catch (Exception e) {
            Log.w(TAG, "append status log failed: " + e.getMessage());
        }
    }

    private void showToast(Context ctx, String msg) {
        try {
            boolean enabled = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLE_TOAST, true);
            if (!enabled) return;
        } catch (Exception ignore) {
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "[夸克签到] " + msg, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                // ignore
            }
        });
    }

}
