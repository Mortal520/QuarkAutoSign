package com.qurk.autosign;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 根目录副本 - 与 src/main/java 版本保持一致
 * 详细注释见 src 版本
 */
public class QuarkAutoSign implements IXposedHookLoadPackage {

    private static final String TAG = "QuarkAutoSign";
    private static final String TARGET_PKG = "com.quark.scanking";
    private static final String TARGET_PKG_LEGACY = "com.quark.scank";
    private static final String SP_NAME = "quark_autosign_prefs";
    private static final String KEY_LAST_SIGN = "last_sign_time";
    private static final String KEY_SIGN_HISTORY = "sign_history";
    private static final String KEY_STATUS_LOG = "status_log";
    private static final String KEY_ENABLE_SIGN = "enable_auto_sign";
    private static final String KEY_ENABLE_TOAST = "enable_toast";
    private static final int MAX_HISTORY = 30;
    private static final int MAX_LOG = 50;
    private static final int SIGN_RETRY_TIMES = 3;
    private static final long SIGN_RETRY_INTERVAL_MS = 10000L;

    private static final String MGR_CLASS = "com.ucpro.feature.study.userop.CameraCheckInManager";
    private static final String MGR_GET_INSTANCE = "i";
    private static final String MGR_SIGN_IN = "q";
    private static final String MGR_FIELD_INSTANCE = "f";
    private static final String SIGN_REQUEST_CLASS = "iq0.m";
    private static final String SIGN_REQUEST_START = "k";

    private static volatile boolean hookToastShown = false;
    private static volatile boolean signDetectedToday = false;
    private static volatile boolean tasksScheduled = false;
    private static volatile long lastToastTime = 0;
    private static volatile String lastToastMsg = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Log.i(TAG, "handleLoadPackage: " + lpparam.packageName);
        if (!TARGET_PKG.equals(lpparam.packageName)
            && !TARGET_PKG_LEGACY.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + " 模块注入: " + lpparam.packageName);
        hookApplication(lpparam);
        hookSignInMethod(lpparam);
    }

    private Context getAppContext(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                "currentApplication");
        } catch (Throwable e) { return null; }
    }

    private void hookSignInMethod(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(MGR_CLASS, lpparam.classLoader);
            log(lpparam, "找到 CameraCheckInManager 类");

            java.lang.reflect.Method signMethod = findMethodBySignature(mgrClass, MGR_SIGN_IN, 2);
            if (signMethod == null) {
                signMethod = findMethodByParamTypes(mgrClass, ValueCallback.class, String.class);
            }
            if (signMethod != null) {
                final String methodName = signMethod.getName();
                XposedHelpers.findAndHookMethod(mgrClass, methodName,
                    signMethod.getParameterTypes()[0], signMethod.getParameterTypes()[1],
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Context ctx = getAppContext(lpparam);
                            if (ctx == null) return;
                            String source = param.args[1] != null ? param.args[1].toString() : "unknown";
                            log(lpparam, "签到方法被调用: " + methodName + " 来源=" + source);
                            signDetectedToday = true;
                            recordSignTime(ctx, source);
                            if (!hookToastShown) {
                                hookToastShown = true;
                                showToastOnce(ctx, "签到已触发 ✓ (" + source + ")");
                            }
                        }
                    });
                log(lpparam, "已Hook签到方法: " + methodName);
            } else {
                log(lpparam, "未找到签到方法(q)，将仅依赖主动触发");
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " hookSignInMethod 失败: " + e.getMessage());
            log(lpparam, "Hook签到方法失败: " + e.getMessage());
        }
    }

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.thisObject;
                        final Context ctx = app.getApplicationContext();
                        if (ctx == null) return;
                        String appClass = app.getClass().getName();
                        log(lpparam, "Application.onCreate: " + appClass);
                        if (appClass.equals("android.app.Application")) return;

                        android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                        if (shouldDoToday(sp.getLong("last_module_toast", 0))) {
                            sp.edit().putLong("last_module_toast", System.currentTimeMillis()).apply();
                            new Handler(Looper.getMainLooper()).postDelayed(() ->
                                showToastOnce(ctx, "模块已激活"), 3000);
                        }
                        if (!tasksScheduled) {
                            tasksScheduled = true;
                            new Handler(Looper.getMainLooper()).postDelayed(() ->
                                performAutoSignIn(ctx, lpparam), 15000);
                        }
                    }
                });
            log(lpparam, "已Hook Application.onCreate");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Hook Application 失败: " + e.getMessage());
        }
    }

    private void performAutoSignIn(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!readSwitch(KEY_ENABLE_SIGN, true)) { log(lpparam, "自动签到已关闭"); return; }
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (!shouldDoToday(sp.getLong(KEY_LAST_SIGN, 0))) { log(lpparam, "今日已签到"); return; }
            if (signDetectedToday) { log(lpparam, "Hook已检测到签到"); return; }
            log(lpparam, "开始签到补强");
            doSignInRetry(ctx, lpparam, 1);
        } catch (Throwable e) { Log.e(TAG, "performAutoSignIn: " + e.getMessage()); }
    }

    private void doSignInRetry(Context ctx, XC_LoadPackage.LoadPackageParam lpparam, int attempt) {
        if (attempt > SIGN_RETRY_TIMES || signDetectedToday) return;
        boolean ok = doSignIn(ctx, lpparam);
        log(lpparam, "补强签到第" + attempt + "次: " + (ok ? "成功" : "失败"));
        if (ok) { recordSignTime(ctx, "retry#" + attempt); showToastOnce(ctx, "补强签到成功 ✓"); return; }
        if (attempt < SIGN_RETRY_TIMES) {
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                doSignInRetry(ctx, lpparam, attempt + 1), SIGN_RETRY_INTERVAL_MS);
        }
    }

    private boolean doSignIn(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        if (doSignInViaManager(ctx, lpparam)) return true;
        return doSignInViaDirectRequest(ctx, lpparam);
    }

    private boolean doSignInViaManager(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(MGR_CLASS, ctx.getClassLoader());
            Object mgr = getManagerInstance(mgrClass);
            if (mgr == null) {
                java.lang.reflect.Method gi = findMethodBySignature(mgrClass, MGR_GET_INSTANCE, 1);
                if (gi != null) { gi.setAccessible(true); mgr = gi.invoke(null, (Object) null); }
            }
            if (mgr == null) { log(lpparam, "Manager实例为null"); return false; }
            java.lang.reflect.Method sm = findMethodBySignature(mgrClass, MGR_SIGN_IN, 2);
            if (sm == null) sm = findMethodByParamTypes(mgrClass, ValueCallback.class, String.class);
            if (sm != null) { sm.setAccessible(true); sm.invoke(mgr, null, "module_retry"); return true; }
            return false;
        } catch (Throwable e) { log(lpparam, "Manager签到异常: " + e.getMessage()); return false; }
    }

    private boolean doSignInViaDirectRequest(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rc = XposedHelpers.findClass(SIGN_REQUEST_CLASS, ctx.getClassLoader());
            Object req = rc.getConstructor().newInstance();
            java.lang.reflect.Method sm = findMethodBySignature(rc, SIGN_REQUEST_START, 1);
            if (sm == null) {
                for (java.lang.reflect.Method m : rc.getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == ValueCallback.class) { sm = m; break; }
                }
            }
            if (sm != null) {
                sm.setAccessible(true);
                sm.invoke(req, (ValueCallback<Object>) v -> {
                    log(lpparam, "直接签到响应: " + (v != null ? "有数据" : "null"));
                    if (v != null) { recordSignTime(ctx, "direct"); showToastOnce(ctx, "直接签到成功 ✓"); }
                });
                return true;
            }
            return false;
        } catch (Throwable e) { log(lpparam, "直接签到异常: " + e.getMessage()); return false; }
    }

    private Object getManagerInstance(Class<?> mgrClass) {
        try {
            java.lang.reflect.Field ff = null;
            try { ff = mgrClass.getDeclaredField(MGR_FIELD_INSTANCE); } catch (NoSuchFieldException e) {
                for (java.lang.reflect.Field f : mgrClass.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == mgrClass) { ff = f; break; }
                }
            }
            if (ff != null) { ff.setAccessible(true); Object o = ff.get(null); if (o != null) return o; }
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = findMethodBySignature(mgrClass, MGR_GET_INSTANCE, 1);
            if (m != null) { m.setAccessible(true); return m.invoke(null, (Object) null); }
        } catch (Throwable ignored) {}
        return null;
    }

    private java.lang.reflect.Method findMethodBySignature(Class<?> cls, String name, int pc) {
        try { for (java.lang.reflect.Method m : cls.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == pc) return m; } catch (Throwable ignored) {}
        return null;
    }

    private java.lang.reflect.Method findMethodByParamTypes(Class<?> cls, Class<?>... pt) {
        try { for (java.lang.reflect.Method m : cls.getDeclaredMethods()) { if (m.getParameterCount() != pt.length) continue; Class<?>[] a = m.getParameterTypes(); boolean ok = true; for (int i = 0; i < pt.length; i++) if (!pt[i].isAssignableFrom(a[i]) && !a[i].isAssignableFrom(pt[i])) { ok = false; break; } if (ok) return m; } } catch (Throwable ignored) {}
        return null;
    }

    private boolean shouldDoToday(long ts) {
        if (ts == 0) return true;
        try { Calendar l = Calendar.getInstance(); l.setTimeInMillis(ts); Calendar n = Calendar.getInstance(); return l.get(Calendar.DAY_OF_YEAR) != n.get(Calendar.DAY_OF_YEAR) || l.get(Calendar.YEAR) != n.get(Calendar.YEAR); } catch (Exception e) { return true; }
    }

    private void recordSignTime(Context ctx, String source) {
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            sp.edit().putLong(KEY_LAST_SIGN, now).apply();
            String h = sp.getString(KEY_SIGN_HISTORY, "");
            List<String> l = new ArrayList<>(); if (!h.isEmpty()) l.addAll(Arrays.asList(h.split(","))); l.add(now + "|" + source);
            while (l.size() > MAX_HISTORY) l.remove(0);
            sp.edit().putString(KEY_SIGN_HISTORY, String.join(",", l)).apply();
        } catch (Exception e) { Log.e(TAG, "recordSignTime: " + e.getMessage()); }
    }

    private XSharedPreferences modulePrefs;
    private void initModulePrefs() { if (modulePrefs == null) { modulePrefs = new XSharedPreferences("com.qurk.autosign", SP_NAME); modulePrefs.makeWorldReadable(); } }
    private boolean readSwitch(String key, boolean def) { try { initModulePrefs(); modulePrefs.reload(); return modulePrefs.getBoolean(key, def); } catch (Exception e) { return def; } }

    private void log(XC_LoadPackage.LoadPackageParam lpparam, String msg) {
        Log.i(TAG, msg); XposedBridge.log(TAG + " " + msg);
        try {
            Context ctx = getAppContext(lpparam); if (ctx == null) return;
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " " + msg;
            String ex = sp.getString(KEY_STATUS_LOG, ""); List<String> l = new ArrayList<>(); if (!ex.isEmpty()) l.addAll(Arrays.asList(ex.split("\n"))); l.add(line);
            while (l.size() > MAX_LOG) l.remove(0);
            sp.edit().putString(KEY_STATUS_LOG, String.join("\n", l)).apply();
        } catch (Exception e) {}
    }

    private void showToastOnce(Context ctx, String msg) {
        long now = System.currentTimeMillis(); if (msg.equals(lastToastMsg) && (now - lastToastTime) < 5000) return; lastToastMsg = msg; lastToastTime = now;
        try { if (!readSwitch(KEY_ENABLE_TOAST, true)) return; } catch (Exception ignore) {}
        new Handler(Looper.getMainLooper()).post(() -> { try { Toast.makeText(ctx, "[夸克签到] " + msg, Toast.LENGTH_SHORT).show(); } catch (Exception e) {} });
    }
}
