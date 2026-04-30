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

        Log.i(TAG, "模块已加载，注入进程: " + lpparam.packageName);
        // Hook Application 仅记录日志（不显示Toast）
        hookApplication(lpparam);
        // Hook 签到方法 - 拦截APP自身的签到调用
        hookSignInMethod(lpparam);
        // Hook 设置页面显示模块入口
        hookSettingsActivity(lpparam);
    }
    
    private Context getAppContext(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentApplication"
            );
        } catch (Throwable e) {
            return null;
        }
    }
    
    private static volatile boolean signSucceededToday = false;
    
    private void hookSignInMethod(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager",
                lpparam.classLoader
            );
            
            // Hook m63476q - APP首页加载时自动调用: m63476q(null, "main_page")
            // 拦截结果，如果失败则补强重试
            java.lang.reflect.Method[] methods = mgrClass.getDeclaredMethods();
            java.lang.reflect.Method signMethod = null;
            for (java.lang.reflect.Method m : methods) {
                if (m.getName().equals("m63476q") && m.getParameterCount() == 2) {
                    signMethod = m;
                    break;
                }
            }
            
            if (signMethod != null) {
                XposedHelpers.findAndHookMethod(mgrClass, "m63476q", 
                    signMethod.getParameterTypes()[0], signMethod.getParameterTypes()[1],
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Context ctx = getAppContext(lpparam);
                            if (ctx == null) return;
                            
                            String source = param.args[1] != null ? param.args[1].toString() : "unknown";
                            log(lpparam, "签到方法被调用 来源=" + source);
                            recordSignTime(ctx, source);
                            signSucceededToday = true;
                            
                            // 显示Toast（只显示一次）
                            if (!hookToastShown) {
                                hookToastShown = true;
                                showToastOnce(ctx, "签到已触发 ✓");
                            }
                            
                            // 签到成功后自动触发抽奖
                            if (readSwitch(KEY_ENABLE_LOTTERY, true)) {
                                android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                                if (shouldDoToday(sp.getLong(KEY_LAST_LOTTERY, 0))) {
                                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                        doLotterySimple(ctx, lpparam);
                                    }, 5000);
                                }
                            }
                        }
                    });
                log(lpparam, "已Hook签到方法 m63476q");
            } else {
                log(lpparam, "未找到m63476q方法");
            }
            
            log(lpparam, "CameraCheckInManager Hook完成");
        } catch (Throwable e) {
            Log.w(TAG, "hookSignInMethod failed: " + e.getMessage());
            log(lpparam, "签到方法Hook失败: " + e.getMessage());
        }
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
                        final Context ctx = app.getApplicationContext();
                        
                        log(lpparam, "Application.onCreate 包名: " + lpparam.packageName);
                        
                        // 仅显示一次模块加载Toast（使用SP防止跨进程重复）
                        android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                        long lastToast = sp.getLong("last_module_toast", 0);
                        long now = System.currentTimeMillis();
                        // 同一天内只显示一次
                        if (shouldDoToday(lastToast)) {
                            sp.edit().putLong("last_module_toast", now).apply();
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                showToastOnce(ctx, "模块已激活");
                            }, 2000);
                        }
                        
                        // 延迟触发签到/抽奖
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            performAutoTasks(ctx, lpparam);
                        }, 8000);
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
            return;
        }
        tasksExecutedToday = true;
        tasksExecutedDay = today;
        
        // 这是补强逻辑：APP自己会调用签到，但如果失败或未触发，我们在这里补强
        // 等待15秒 - 给APP自己足够时间完成签到
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                boolean signEnabled = readSwitch(KEY_ENABLE_SIGN, true);
                boolean lotteryEnabled = readSwitch(KEY_ENABLE_LOTTERY, true);
                
                android.content.SharedPreferences quarkSp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                
                // 补强签到：检查Hook是否已经拦截到签到
                if (signEnabled && !signSucceededToday) {
                    long lastSign = quarkSp.getLong(KEY_LAST_SIGN, 0);
                    if (shouldDoToday(lastSign)) {
                        log(lpparam, "补强签到: APP未自动完成，主动触发");
                        doSignInSimple(ctx, lpparam);
                    }
                }

                // 补强抽奖
                if (lotteryEnabled) {
                    long lastLottery = quarkSp.getLong(KEY_LAST_LOTTERY, 0);
                    if (shouldDoToday(lastLottery)) {
                        log(lpparam, "补强抽奖: 主动触发");
                        doLotterySimple(ctx, lpparam);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "performAutoTasks error: " + e.getMessage());
            }
        }, 15000); // 15秒后补强
    }
    
    // 补强签到 - 在APP自己的签到失败时主动触发
    private boolean doSignInSimple(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager", 
                ctx.getClassLoader()
            );
            
            // 获取单例实例 - 通过反射直接读取静态字段
            Object mgr = getManagerInstance(mgrClass, ctx);
            if (mgr == null) {
                log(lpparam, "补强签到: Manager未初始化，等待APP自动签到");
                return false;
            }
            
            // 调用签到方法 m63476q(null, "module_retry")
            try {
                java.lang.reflect.Method signMethod = null;
                for (java.lang.reflect.Method m : mgrClass.getDeclaredMethods()) {
                    if (m.getName().equals("m63476q") && m.getParameterCount() == 2) {
                        signMethod = m;
                        break;
                    }
                }
                if (signMethod != null) {
                    signMethod.setAccessible(true);
                    signMethod.invoke(mgr, null, "module_retry");
                    log(lpparam, "补强签到成功");
                    showToastOnce(ctx, "补强签到已触发 ✓");
                    return true;
                }
            } catch (Throwable e) {
                log(lpparam, "补强签到失败: " + e.getMessage());
            }
            return false;
        } catch (Throwable e) {
            log(lpparam, "补强签到异常: " + e.getMessage());
            return false;
        }
    }
    
    // 获取CameraCheckInManager单例
    private Object getManagerInstance(Class<?> mgrClass, Context ctx) {
        // 方式1: 直接读取静态字段（单例模式）
        try {
            java.lang.reflect.Field[] fields = mgrClass.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) 
                    && f.getType() == mgrClass) {
                    f.setAccessible(true);
                    Object instance = f.get(null);
                    if (instance != null) return instance;
                }
            }
        } catch (Throwable ignored) {}
        
        // 方式2: 调用工厂方法
        try {
            return XposedHelpers.callStaticMethod(mgrClass, "m63467i", (Object) null);
        } catch (Throwable e1) {
            try {
                return XposedHelpers.callStaticMethod(mgrClass, "m63467i");
            } catch (Throwable e2) {
                return null;
            }
        }
    }
    
    // 自动抽奖 - 只调用已知的抽奖方法
    private boolean doLotterySimple(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        int successCount = 0;
        try {
            log(lpparam, "开始抽奖...");
            
            Class<?> mgrClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager", 
                ctx.getClassLoader()
            );
            
            Object mgr = getManagerInstance(mgrClass, ctx);
            if (mgr == null) {
                log(lpparam, "抽奖失败: Manager未初始化");
                return false;
            }
            
            // 只调用已知的抽奖方法
            String[] lotteryMethodNames = {"m63475p", "m63474o", "m63473n", "m63472m"};
            java.lang.reflect.Method lotteryMethod = null;
            for (String name : lotteryMethodNames) {
                for (java.lang.reflect.Method m : mgrClass.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) {
                        lotteryMethod = m;
                        log(lpparam, "找到抽奖方法: " + name);
                        break;
                    }
                }
                if (lotteryMethod != null) break;
            }
            
            if (lotteryMethod == null) {
                log(lpparam, "抽奖失败: 未找到抽奖方法");
                return false;
            }
            
            lotteryMethod.setAccessible(true);
            
            for (int i = 1; i <= DAILY_LOTTERY_TIMES; i++) {
                try {
                    if (i > 1) Thread.sleep(3000); // 每次间隔3秒
                    
                    lotteryMethod.invoke(mgr);
                    successCount++;
                    log(lpparam, "抽奖" + i + "/" + DAILY_LOTTERY_TIMES + " 已触发");
                    recordSignTime(ctx, "lottery#" + i);
                } catch (Throwable e) {
                    log(lpparam, "抽奖" + i + "失败: " + e.getMessage());
                }
            }
            
            if (successCount > 0) {
                ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_LOTTERY, System.currentTimeMillis()).apply();
                showToastOnce(ctx, "抽奖完成 (" + successCount + "/" + DAILY_LOTTERY_TIMES + ")");
                log(lpparam, "抽奖完成: " + successCount + "/" + DAILY_LOTTERY_TIMES);
                return true;
            }
        } catch (Throwable e) {
            log(lpparam, "抽奖异常: " + e.getMessage());
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
