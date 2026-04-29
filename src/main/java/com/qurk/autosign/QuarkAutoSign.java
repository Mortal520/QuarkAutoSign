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
    
    // 静态变量防止跨实例重复
    private static volatile boolean hookToastShown = false;
    private static volatile long lastToastTime = 0;
    private static volatile String lastToastMsg = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)
            && !TARGET_PKG_LEGACY.equals(lpparam.packageName)) {
            return;
        }

        log(lpparam, "模块已加载，注入进程: " + lpparam.packageName);
        // Hook Application 用于初始化
        hookApplication(lpparam);
        // Hook 主页 Activity 用于实际触发签到（APP完全初始化后）
        hookMainActivity(lpparam);
        // Hook 设置页面显示模块入口
        hookSettingsActivity(lpparam);
        // Hook 抽奖结果
        hookLotteryResult(lpparam);
    }
    
    private void hookMainActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook 夸克首页 Activity，在页面显示时触发签到（此时APP已完全初始化）
        String[] mainActivities = new String[]{
            "com.scanking.homepage.view.main.SKMainActivity",
            "com.scanking.homepage.SKMainActivity",
            "com.quark.scanking.MainActivity",
            "com.ucpro.MainActivity",
            "com.quark.scanking.homepage.MainActivity"
        };
        
        for (String actName : mainActivities) {
            try {
                Class<?> actClass = XposedHelpers.findClass(actName, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(actClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        Context ctx = activity.getApplicationContext();
                        
                        // 只显示一次Hook成功的Toast
                        if (!hookToastShown) {
                            hookToastShown = true;
                            showToastOnce(ctx, "模块已激活");
                            log(lpparam, "Hook主页成功: " + actName);
                        }
                        
                        // 执行签到/抽奖任务
                        performAutoTasks(ctx, lpparam);
                    }
                });
                log(lpparam, "已Hook主页: " + actName);
                return; // 成功Hook一个就返回
            } catch (Throwable e) {
                // 尝试下一个
            }
        }
        log(lpparam, "未找到主页Activity，回退到Application Hook");
    }
    
    private void hookLotteryResult(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook CameraCheckInManager 的可能回调方法
            Class<?> managerClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager",
                lpparam.classLoader
            );
            
            // Hook 可能的结果回调方法
            String[] resultCallbacks = new String[]{
                "onLotteryResult", "onPrizeResult", "onReward", 
                "m63472m", "m63471l", "m63470k", "handleResult"
            };
            
            for (String method : resultCallbacks) {
                try {
                    XposedHelpers.findAndHookMethod(managerClass, method, new Object[]{
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                String prize = extractPrizeInfo(param.getResult());
                                if (prize != null && !prize.isEmpty()) {
                                    Context ctx = (Context) XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                                        "currentApplication"
                                    );
                                    if (ctx != null) {
                                        log(lpparam, "[抽奖结果] 获得奖品: " + prize);
                                        showToastOnce(ctx, "🎁 抽到: " + prize);
                                    }
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            
            // Hook 签到结果回调
            try {
                XposedHelpers.findAndHookMethod(managerClass, "m63476q", new Object[]{
                    XposedHelpers.findClass("com.uc.webview.export.extension.AbsWindow", lpparam.classLoader),
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            Context ctx = (Context) XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                                "currentApplication"
                            );
                            if (ctx != null) {
                                log(lpparam, "[签到结果] " + (result != null ? result.toString() : "null"));
                                if (result != null) {
                                    String prize = extractPrizeInfo(result);
                                    if (prize != null && !prize.isEmpty()) {
                                        showToastOnce(ctx, "🎁 签到奖励: " + prize);
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
            
            log(lpparam, "抽奖结果Hook已设置");
        } catch (Throwable e) {
            Log.w(TAG, "Hook lottery result failed: " + e.getMessage());
        }
    }
    
    private String extractPrizeInfo(Object result) {
        if (result == null) return null;
        try {
            String str = result.toString();
            // 尝试提取奖品名称
            if (str.contains("prize") || str.contains("reward") || str.contains("name") || 
                str.contains("奖品") || str.contains("奖励") || str.contains("获得")) {
                return str;
            }
            // 检查是否有getName/getPrize等方法
            try {
                Object name = XposedHelpers.callMethod(result, "getName");
                if (name != null) return name.toString();
            } catch (Throwable ignored) {}
            try {
                Object prize = XposedHelpers.callMethod(result, "getPrize");
                if (prize != null) return prize.toString();
            } catch (Throwable ignored) {}
            try {
                Object title = XposedHelpers.callMethod(result, "getTitle");
                if (title != null) return title.toString();
            } catch (Throwable ignored) {}
            return str.length() > 50 ? str.substring(0, 50) + "..." : str;
        } catch (Throwable e) {
            return null;
        }
    }

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        // 只用于初始化，不触发任务（任务在Activity onResume触发）
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
                        log(lpparam, "Application.onCreate Hook成功");
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
            // 使用 WORLD_READABLE 模式让模块可以读取夸克的日志
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_WORLD_READABLE);

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

    // 进程级别的任务执行锁（防止Activity重建导致重复）
    private static volatile boolean tasksExecutedToday = false;
    private static volatile long tasksExecutedDay = 0;
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
        // 检查今天是否已经执行过（进程级别）
        Calendar now = Calendar.getInstance();
        long today = now.get(Calendar.DAY_OF_YEAR) + now.get(Calendar.YEAR) * 1000L;
        if (tasksExecutedToday && tasksExecutedDay == today) {
            log(lpparam, "今日任务已在本进程中执行过，跳过");
            return;
        }
        
        // 延迟3秒执行（主页已显示，APP已初始化）
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // 再次检查（可能其他Activity已经触发了）
                if (tasksExecutedToday && tasksExecutedDay == today) {
                    return;
                }
                
                boolean signEnabled = readSwitch(KEY_ENABLE_SIGN, true);
                boolean lotteryEnabled = readSwitch(KEY_ENABLE_LOTTERY, true);
                
                log(lpparam, "开关: 签到=" + signEnabled + ", 抽奖=" + lotteryEnabled);
                
                android.content.SharedPreferences quarkSp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                boolean anyTaskExecuted = false;
                
                // 签到
                if (signEnabled) {
                    long lastSign = quarkSp.getLong(KEY_LAST_SIGN, 0);
                    if (shouldDoToday(lastSign)) {
                        log(lpparam, "执行签到...");
                        if (doSignInSimple(ctx, lpparam)) {
                            anyTaskExecuted = true;
                        }
                    } else {
                        log(lpparam, "今日已签到，跳过");
                    }
                }

                // 抽奖
                if (lotteryEnabled) {
                    long lastLottery = quarkSp.getLong(KEY_LAST_LOTTERY, 0);
                    if (shouldDoToday(lastLottery)) {
                        log(lpparam, "执行抽奖...");
                        if (doLotterySimple(ctx, lpparam)) {
                            anyTaskExecuted = true;
                        }
                    } else {
                        log(lpparam, "今日已抽奖，跳过");
                    }
                }
                
                if (anyTaskExecuted) {
                    tasksExecutedToday = true;
                    tasksExecutedDay = today;
                }
            } catch (Throwable e) {
                Log.e(TAG, "performAutoTasks error: " + e.getMessage());
            }
        }, 3000);
    }
    
    // 简化版签到 - 直接反射调用，更稳定
    private boolean doSignInSimple(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager", 
                ctx.getClassLoader()
            );
            
            // 获取实例
            Object mgr = XposedHelpers.callStaticMethod(mgrClass, "m63467i", new Class<?>[]{Class.forName("com.uc.webview.export.extension.AbsWindow", false, ctx.getClassLoader())}, (Object) null);
            if (mgr == null) {
                // 尝试无参获取
                mgr = XposedHelpers.callStaticMethod(mgrClass, "m63467i");
            }
            if (mgr == null) {
                log(lpparam, "签到失败：无法获取Manager");
                return false;
            }
            
            // 调用签到
            XposedHelpers.callMethod(mgr, "m63476q", null, "xposed_hook");
            recordSignTime(ctx, "auto");
            showToastOnce(ctx, "签到已触发 ✓");
            return true;
        } catch (Throwable e) {
            log(lpparam, "签到失败: " + e.getMessage());
            return false;
        }
    }
    
    // 简化版抽奖
    private boolean doLotterySimple(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        int successCount = 0;
        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager", 
                ctx.getClassLoader()
            );
            
            for (int i = 1; i <= DAILY_LOTTERY_TIMES; i++) {
                try {
                    Thread.sleep(1500);
                    
                    Object mgr = XposedHelpers.callStaticMethod(mgrClass, "m63467i", new Class<?>[]{Class.forName("com.uc.webview.export.extension.AbsWindow", false, ctx.getClassLoader())}, (Object) null);
                    if (mgr == null) {
                        mgr = XposedHelpers.callStaticMethod(mgrClass, "m63467i");
                    }
                    if (mgr == null) continue;
                    
                    // 尝试抽奖方法
                    String[] methods = {"m63475p", "m63474o", "m63473n"};
                    for (String method : methods) {
                        try {
                            XposedHelpers.callMethod(mgr, method);
                            recordSignTime(ctx, "lottery#" + i);
                            successCount++;
                            showToastOnce(ctx, "抽奖" + i + "已触发");
                            break;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable e) {
                    log(lpparam, "抽奖" + i + "失败: " + e.getMessage());
                }
            }
            
            if (successCount > 0) {
                ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_LOTTERY, System.currentTimeMillis()).apply();
                showToastOnce(ctx, "抽奖完成 (" + successCount + "/" + DAILY_LOTTERY_TIMES + ")");
                return true;
            }
        } catch (Throwable e) {
            log(lpparam, "抽奖整体失败: " + e.getMessage());
        }
        return false;
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

    private void showToastOnce(Context ctx, String msg) {
        // 防止相同消息在5秒内重复显示
        long now = System.currentTimeMillis();
        if (msg.equals(lastToastMsg) && (now - lastToastTime) < 5000) {
            return;
        }
        lastToastMsg = msg;
        lastToastTime = now;
        showToast(ctx, msg);
    }
    
    private void showToast(Context ctx, String msg) {
        try {
            boolean enabled = readSwitch(KEY_ENABLE_TOAST, true);
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
