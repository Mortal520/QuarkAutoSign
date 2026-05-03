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

    // CameraCheckInManager 的实际字节码方法/字段名
    private static final String MGR_CLASS = "com.ucpro.feature.study.userop.CameraCheckInManager";
    private static final String MGR_GET_INSTANCE = "i";      // static CameraCheckInManager i(AbsWindow)
    private static final String MGR_SIGN_IN = "q";           // void q(ValueCallback, String)
    private static final String MGR_DO_REQUEST = "r";        // void r(ValueCallback, boolean)
    private static final String MGR_SIGN_WITH_UID = "p";     // void p(String, ValueCallback)
    private static final String MGR_FIELD_INSTANCE = "f";    // static CameraCheckInManager f
    // 签到网络请求类
    private static final String SIGN_REQUEST_CLASS = "iq0.m"; // extends iq0.d
    private static final String SIGN_REQUEST_START = "k";     // void k(ValueCallback)

    private static volatile boolean hookToastShown = false;
    private static volatile boolean signDetectedToday = false;
    private static volatile boolean tasksScheduled = false;
    private static volatile long lastToastTime = 0;
    private static volatile String lastToastMsg = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 记录所有进程加载（调试用）
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
                "currentApplication"
            );
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================== Hook签到方法 ====================

    private void hookSignInMethod(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(MGR_CLASS, lpparam.classLoader);
            log(lpparam, "找到 CameraCheckInManager 类");

            // 列出所有方法（调试）
            java.lang.reflect.Method[] methods = mgrClass.getDeclaredMethods();
            StringBuilder sb = new StringBuilder("CameraCheckInManager 方法列表: ");
            for (java.lang.reflect.Method m : methods) {
                sb.append(m.getName()).append("(");
                for (Class<?> p : m.getParameterTypes()) {
                    sb.append(p.getSimpleName()).append(",");
                }
                sb.append(") ");
            }
            log(lpparam, sb.toString());

            // 查找签到入口方法: q(ValueCallback, String)
            java.lang.reflect.Method signMethod = findMethodBySignature(mgrClass, MGR_SIGN_IN, 2);
            if (signMethod == null) {
                // 备选：查找接受(ValueCallback, String)参数的方法
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

    // ==================== Hook Application ====================

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Application.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.thisObject;
                        final Context ctx = app.getApplicationContext();
                        if (ctx == null) return;

                        String appClass = app.getClass().getName();
                        log(lpparam, "Application.onCreate: " + appClass + " pkg=" + lpparam.packageName);

                        // 只在主Application中执行（忽略ContentProvider等子进程）
                        if (appClass.equals("android.app.Application")) {
                            return; // 跳过基类，等自定义Application
                        }

                        // 显示一次Toast确认模块已加载
                        android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
                        if (shouldDoToday(sp.getLong("last_module_toast", 0))) {
                            sp.edit().putLong("last_module_toast", System.currentTimeMillis()).apply();
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                showToastOnce(ctx, "模块已激活");
                            }, 3000);
                        }

                        // 延迟触发签到补强
                        if (!tasksScheduled) {
                            tasksScheduled = true;
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                performAutoSignIn(ctx, lpparam);
                            }, 15000);
                        }
                    }
                }
            );
            log(lpparam, "已Hook Application.onCreate");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Hook Application 失败: " + e.getMessage());
        }
    }

    // ==================== 签到补强逻辑 ====================

    private void performAutoSignIn(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            boolean signEnabled = readSwitch(KEY_ENABLE_SIGN, true);
            if (!signEnabled) {
                log(lpparam, "自动签到已关闭");
                return;
            }

            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (!shouldDoToday(sp.getLong(KEY_LAST_SIGN, 0))) {
                log(lpparam, "今日已签到，跳过补强");
                return;
            }

            if (signDetectedToday) {
                log(lpparam, "Hook已检测到签到，跳过补强");
                return;
            }

            log(lpparam, "开始签到补强（" + SIGN_RETRY_TIMES + "次重试）");
            doSignInRetry(ctx, lpparam, 1);

        } catch (Throwable e) {
            Log.e(TAG, "performAutoSignIn error: " + e.getMessage());
        }
    }

    private void doSignInRetry(Context ctx, XC_LoadPackage.LoadPackageParam lpparam, int attempt) {
        if (attempt > SIGN_RETRY_TIMES || signDetectedToday) return;

        log(lpparam, "补强签到第" + attempt + "次...");
        boolean ok = doSignIn(ctx, lpparam);
        log(lpparam, "补强签到第" + attempt + "次: " + (ok ? "成功" : "失败"));
        if (ok) {
            recordSignTime(ctx, "retry#" + attempt);
            showToastOnce(ctx, "补强签到成功 ✓");
            return;
        }

        if (attempt < SIGN_RETRY_TIMES) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                doSignInRetry(ctx, lpparam, attempt + 1);
            }, SIGN_RETRY_INTERVAL_MS);
        }
    }

    // ==================== 签到执行 ====================

    private boolean doSignIn(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        // 方式1: 通过Manager单例调用签到入口方法
        if (doSignInViaManager(ctx, lpparam)) return true;
        // 方式2: 直接创建网络请求对象发送签到
        if (doSignInViaDirectRequest(ctx, lpparam)) return true;
        return false;
    }

    private boolean doSignInViaManager(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(MGR_CLASS, ctx.getClassLoader());
            Object mgr = getManagerInstance(mgrClass);

            if (mgr == null) {
                // 尝试创建实例: i(null)
                try {
                    java.lang.reflect.Method getInstance = findMethodBySignature(mgrClass, MGR_GET_INSTANCE, 1);
                    if (getInstance != null) {
                        getInstance.setAccessible(true);
                        mgr = getInstance.invoke(null, (Object) null);
                        log(lpparam, "通过 " + MGR_GET_INSTANCE + "(null) 创建Manager");
                    }
                } catch (Throwable e) {
                    log(lpparam, "创建Manager失败: " + e.getMessage());
                }
            }

            if (mgr == null) {
                log(lpparam, "Manager实例为null");
                return false;
            }

            // 调用签到入口: q(null, "module_retry")
            java.lang.reflect.Method signMethod = findMethodBySignature(mgrClass, MGR_SIGN_IN, 2);
            if (signMethod == null) {
                signMethod = findMethodByParamTypes(mgrClass, ValueCallback.class, String.class);
            }
            if (signMethod != null) {
                signMethod.setAccessible(true);
                signMethod.invoke(mgr, null, "module_retry");
                log(lpparam, "Manager签到调用成功: " + signMethod.getName());
                return true;
            }

            log(lpparam, "未找到签到方法");
            return false;
        } catch (Throwable e) {
            log(lpparam, "Manager签到异常: " + e.getMessage());
            return false;
        }
    }

    private boolean doSignInViaDirectRequest(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 直接创建签到网络请求: new iq0.m() 然后调用 k(callback)
            Class<?> requestClass = XposedHelpers.findClass(SIGN_REQUEST_CLASS, ctx.getClassLoader());
            Object request = requestClass.getConstructor().newInstance();
            log(lpparam, "创建直接签到请求: " + SIGN_REQUEST_CLASS);

            // 找到启动方法: k(ValueCallback)
            java.lang.reflect.Method startMethod = findMethodBySignature(requestClass, SIGN_REQUEST_START, 1);
            if (startMethod == null) {
                // 备选：找接受 ValueCallback 参数的方法
                for (java.lang.reflect.Method m : requestClass.getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == ValueCallback.class) {
                        startMethod = m;
                        break;
                    }
                }
            }

            if (startMethod != null) {
                startMethod.setAccessible(true);
                // 创建回调来记录结果
                final java.lang.reflect.Method finalStartMethod = startMethod;
                ValueCallback<Object> callback = value -> {
                    Log.i(TAG, "直接签到响应: " + (value != null ? value.toString().substring(0, Math.min(200, value.toString().length())) : "null"));
                    log(lpparam, "直接签到响应: " + (value != null ? "有数据" : "null"));
                    if (value != null) {
                        recordSignTime(ctx, "direct");
                        showToastOnce(ctx, "直接签到成功 ✓");
                    }
                };
                startMethod.invoke(request, callback);
                log(lpparam, "直接签到请求已发送");
                return true;
            }

            log(lpparam, "未找到请求启动方法");
            return false;
        } catch (Throwable e) {
            log(lpparam, "直接签到异常: " + e.getMessage());
            return false;
        }
    }

    // ==================== 工具方法 ====================

    private Object getManagerInstance(Class<?> mgrClass) {
        // 方式1: 通过反射读取静态单例字段
        try {
            java.lang.reflect.Field instanceField = null;
            // 优先按名称查找
            try {
                instanceField = mgrClass.getDeclaredField(MGR_FIELD_INSTANCE);
            } catch (NoSuchFieldException e) {
                // 按类型查找
                for (java.lang.reflect.Field f : mgrClass.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType() == mgrClass) {
                        instanceField = f;
                        break;
                    }
                }
            }
            if (instanceField != null) {
                instanceField.setAccessible(true);
                Object instance = instanceField.get(null);
                if (instance != null) return instance;
            }
        } catch (Throwable ignored) {}

        // 方式2: 调用工厂方法 i(null)
        try {
            java.lang.reflect.Method m = findMethodBySignature(mgrClass, MGR_GET_INSTANCE, 1);
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(null, (Object) null);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private java.lang.reflect.Method findMethodBySignature(Class<?> cls, String name, int paramCount) {
        try {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private java.lang.reflect.Method findMethodByParamTypes(Class<?> cls, Class<?>... paramTypes) {
        try {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != paramTypes.length) continue;
                Class<?>[] actual = m.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!paramTypes[i].isAssignableFrom(actual[i])
                        && !actual[i].isAssignableFrom(paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return m;
            }
        } catch (Throwable ignored) {}
        return null;
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
            while (list.size() > MAX_HISTORY) list.remove(0);
            sp.edit().putString(KEY_SIGN_HISTORY, String.join(",", list)).apply();
        } catch (Exception e) {
            Log.e(TAG, "recordSignTime error: " + e.getMessage());
        }
    }

    // ==================== 开关读取 ====================

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
            return def;
        }
    }

    // ==================== 日志和Toast ====================

    private void log(XC_LoadPackage.LoadPackageParam lpparam, String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + " " + msg);
        try {
            Context ctx = getAppContext(lpparam);
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
            while (list.size() > MAX_LOG) list.remove(0);
            sp.edit().putString(KEY_STATUS_LOG, String.join("\n", list)).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    private void showToastOnce(Context ctx, String msg) {
        long now = System.currentTimeMillis();
        if (msg.equals(lastToastMsg) && (now - lastToastTime) < 5000) return;
        lastToastMsg = msg;
        lastToastTime = now;
        try {
            boolean enabled = readSwitch(KEY_ENABLE_TOAST, true);
            if (!enabled) return;
        } catch (Exception ignore) {}
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, "[夸克签到] " + msg, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                // ignore
            }
        });
    }
}
