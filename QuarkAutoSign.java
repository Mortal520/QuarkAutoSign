package com.qurk.autosign;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

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
    private static final String KEY_LAST_LOTTERY = "last_lottery_time";
    private static final String KEY_LOTTERY_COUNT = "lottery_count_today";
    private static final String KEY_SIGN_HISTORY = "sign_history";
    private static final String KEY_STATUS_LOG = "status_log";
    private static final String KEY_ENABLE_SIGN = "enable_auto_sign";
    private static final String KEY_ENABLE_LOTTERY = "enable_auto_lottery";
    private static final String KEY_ENABLE_TOAST = "enable_toast";
    private static final int MAX_HISTORY = 30;
    private static final int MAX_LOG = 50;
    private static final int SIGN_RETRY_TIMES = 3;
    private static final int DAILY_LOTTERY_TIMES = 3;
    private static final long SIGN_RETRY_INTERVAL_MS = 10000L;
    private static final String LOTTERY_URL = "https://scan-order.quark.cn/api/lottery/v1/lottery";
    private static final String AUTH_REQ_CLASS = "iq0.g";  // 签到请求类，继承 iq0.d
    private static final String AUTH_BUILD_METHOD = "e";   // iq0.d.e() 构建含token的auth JSON

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
    private static final String LOG_FILE_NAME = "autosign_log.txt";

    private static volatile boolean hookToastShown = false;
    private static volatile boolean signDetectedToday = false;
    private static volatile boolean tasksScheduled = false;
    private static volatile boolean verifyScheduled = false;
    private static volatile int signRetryRound = 0;
    private static final int MAX_SIGN_ROUNDS = 3;       // 最多3轮重试，每轮3次
    private static final long VERIFY_DELAY_MS = 120000L; // 2分钟后二次验证
    private static volatile int lotteryDoneToday = 0;
    private static volatile long lastToastTime = 0;
    private static volatile String lastToastMsg = "";
    private static volatile long lastCheckTime = 0;

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

                        // 延迟检测状态并执行任务
                        if (!tasksScheduled) {
                            tasksScheduled = true;
                            // 3秒后检查状态并弹窗提醒，然后立即执行需要的任务
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                checkStatusAndExecute(ctx, lpparam);
                            }, 3000);
                        }
                    }
                }
            );
            log(lpparam, "已Hook Application.onCreate");
        } catch (Exception e) {
            XposedBridge.log(TAG + " Hook Application 失败: " + e.getMessage());
        }
    }

    // ==================== 状态检查与执行 ====================

    private void checkStatusAndExecute(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 防止60秒内重复检查（多进程/多Activity防护）
            long now = System.currentTimeMillis();
            if (now - lastCheckTime < 60000) {
                log(lpparam, "跳过重复状态检查");
                return;
            }
            lastCheckTime = now;

            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            boolean needSign = readSwitch(KEY_ENABLE_SIGN, true)
                && shouldDoToday(sp.getLong(KEY_LAST_SIGN, 0))
                && !signDetectedToday;
            boolean lotteryEnabled = readSwitch(KEY_ENABLE_LOTTERY, true);
            boolean lotteryDone = !shouldDoToday(sp.getLong(KEY_LAST_LOTTERY, 0));
            boolean needLottery = lotteryEnabled && !lotteryDone;

            // 构建状态摘要（不显示具体剩余次数，由服务器决定）
            StringBuilder status = new StringBuilder("模块已激活 | ");
            if (needSign) {
                status.append("待签到");
            } else {
                status.append("已签到");
            }
            status.append(" | ");
            if (lotteryDone) {
                status.append("抽奖已完成");
            } else if (needLottery) {
                status.append("抽奖待执行");
            } else if (!lotteryEnabled) {
                status.append("抽奖已关闭");
            } else {
                status.append("抽奖已完成");
            }

            log(lpparam, status.toString());
            showToastOnce(ctx, status.toString());

            // 执行任务：先签到，签到完成后立即抽奖
            if (needSign) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performAutoSignIn(ctx, lpparam, needLottery);
                }, 2000);
                // 调度延迟二次验证：2分钟后确认是否真的签到成功
                scheduleVerify(ctx, lpparam);
            } else if (needLottery) {
                // 无需签到，直接抽奖
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performAutoLottery(ctx, lpparam);
                }, 2000);
            }
        } catch (Throwable e) {
            log(lpparam, "状态检查异常: " + e.getMessage());
        }
    }

    // ==================== 签到补强逻辑 ====================

    private void performAutoSignIn(Context ctx, XC_LoadPackage.LoadPackageParam lpparam, boolean chainLottery) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (!shouldDoToday(sp.getLong(KEY_LAST_SIGN, 0))) {
                log(lpparam, "今日已签到，跳过补强");
                if (chainLottery) performAutoLottery(ctx, lpparam);
                return;
            }
            if (signDetectedToday) {
                log(lpparam, "Hook已检测到签到，跳过补强");
                if (chainLottery) performAutoLottery(ctx, lpparam);
                return;
            }

            log(lpparam, "开始签到补强（" + SIGN_RETRY_TIMES + "次重试）");
            doSignInRetry(ctx, lpparam, 1, chainLottery);

        } catch (Throwable e) {
            Log.e(TAG, "performAutoSignIn error: " + e.getMessage());
            if (chainLottery) performAutoLottery(ctx, lpparam);
        }
    }

    private void doSignInRetry(Context ctx, XC_LoadPackage.LoadPackageParam lpparam, int attempt, boolean chainLottery) {
        if (attempt > SIGN_RETRY_TIMES || signDetectedToday) {
            if (chainLottery) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performAutoLottery(ctx, lpparam);
                }, 3000);
            }
            return;
        }

        log(lpparam, "补强签到第" + attempt + "次...");
        boolean ok = doSignIn(ctx, lpparam);
        log(lpparam, "补强签到第" + attempt + "次: " + (ok ? "成功" : "失败"));
        if (ok) {
            recordSignTime(ctx, "retry#" + attempt);
            showToastOnce(ctx, "补强签到成功 ✓");
            // 签到成功后3秒后开始抽奖
            if (chainLottery) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performAutoLottery(ctx, lpparam);
                }, 3000);
            }
            return;
        }

        if (attempt < SIGN_RETRY_TIMES) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                doSignInRetry(ctx, lpparam, attempt + 1, chainLottery);
            }, SIGN_RETRY_INTERVAL_MS);
        } else {
            signRetryRound++;
            log(lpparam, "签到第" + signRetryRound + "轮全部失败");
            showToastOnce(ctx, "⚠ 签到失败，将在2分钟后重试");
            if (chainLottery) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performAutoLottery(ctx, lpparam);
                }, 3000);
            }
        }
    }

    private void scheduleVerify(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        if (verifyScheduled) return;
        verifyScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            verifyScheduled = false;
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (!shouldDoToday(sp.getLong(KEY_LAST_SIGN, 0)) || signDetectedToday) {
                log(lpparam, "二次验证: 签到已确认成功 ✓");
                return;
            }
            if (signRetryRound >= MAX_SIGN_ROUNDS) {
                log(lpparam, "签到已达最大重试轮数(" + MAX_SIGN_ROUNDS + "), 请手动签到!");
                showToastOnce(ctx, "❗ 自动签到失败，请手动签到!");
                return;
            }
            log(lpparam, "二次验证: 签到未成功，开始第" + (signRetryRound + 1) + "轮重试");
            showToastOnce(ctx, "签到未确认，重新尝试...");
            performAutoSignIn(ctx, lpparam, false);
            // 再次调度验证
            scheduleVerify(ctx, lpparam);
        }, VERIFY_DELAY_MS);
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

    // ==================== 抽奖逻辑 ====================

    private void performAutoLottery(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!readSwitch(KEY_ENABLE_LOTTERY, true)) {
                log(lpparam, "自动抽奖已关闭");
                return;
            }
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            if (!shouldDoToday(sp.getLong(KEY_LAST_LOTTERY, 0))) {
                log(lpparam, "今日已完成抽奖");
                return;
            }
            // 检查是否有未完成的抽奖（app中途重启时恢复）
            int doneSoFar = shouldDoToday(sp.getLong("lottery_progress_day", 0))
                ? 0 : sp.getInt(KEY_LOTTERY_COUNT, 0);
            lotteryDoneToday = doneSoFar;
            int startDraw = doneSoFar + 1;
            if (startDraw > DAILY_LOTTERY_TIMES) {
                log(lpparam, "今日抽奖已全部完成");
                markLotteryDone(ctx);
                return;
            }
            log(lpparam, "开始自动抽奖（每日" + DAILY_LOTTERY_TIMES + "次免费），从第" + startDraw + "次开始");
            doLotterySequence(ctx, lpparam, startDraw);
        } catch (Throwable e) {
            log(lpparam, "抽奖启动异常: " + e.getMessage());
        }
    }

    private void doLotterySequence(Context ctx, XC_LoadPackage.LoadPackageParam lpparam, int drawNum) {
        if (drawNum > DAILY_LOTTERY_TIMES) {
            log(lpparam, "抽奖完成: " + lotteryDoneToday + "/" + DAILY_LOTTERY_TIMES);
            showToastOnce(ctx, "抽奖完成 (" + lotteryDoneToday + "/" + DAILY_LOTTERY_TIMES + ")");
            return;
        }
        new Thread(() -> {
            try {
                // 每次抽奖间隔3~5秒（防风控）
                if (drawNum > 1) Thread.sleep(3000 + (long)(Math.random() * 2000));
                String result = doSingleLotteryDraw(ctx, lpparam);
                log(lpparam, "抽奖第" + drawNum + "次: " + result);

                if (result.startsWith("ok:")) {
                    lotteryDoneToday++;
                    saveLotteryProgress(ctx, lotteryDoneToday);
                    recordSignTime(ctx, "lottery#" + drawNum);
                    String prize = result.length() > 3 ? result.substring(3) : "";
                    showToastOnce(ctx, "抽奖" + drawNum + "成功" + (prize.isEmpty() ? "" : ": " + prize));
                    if (drawNum >= DAILY_LOTTERY_TIMES) {
                        // 全部完成
                        markLotteryDone(ctx);
                        log(lpparam, "抽奖全部完成 " + lotteryDoneToday + "/" + DAILY_LOTTERY_TIMES);
                        showToastOnce(ctx, "今日抽奖全部完成");
                    } else {
                        // 继续下一次
                        new Handler(Looper.getMainLooper()).post(() ->
                            doLotterySequence(ctx, lpparam, drawNum + 1));
                    }
                } else if (result.startsWith("no_chance")) {
                    log(lpparam, "抽奖次数已用完");
                    showToastOnce(ctx, "今日抽奖次数已用完");
                    markLotteryDone(ctx);
                } else {
                    log(lpparam, "抽奖失败停止: " + result);
                    showToastOnce(ctx, "抽奖失败: " + result);
                }
            } catch (Throwable e) {
                log(lpparam, "抽奖线程异常: " + e.getMessage());
            }
        }).start();
    }

    private String doSingleLotteryDraw(Context ctx, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            String bodyStr = null;
            String lotteryUrl = LOTTERY_URL;

            // ===== 方式1: 通过UCParamExpander构建URL(auth在URL) + 简单body =====
            try {
                String urlWithUcParam = LOTTERY_URL + "?uc_param_str=vesvutkpfrcgprospc";
                Class<?> expanderClass = XposedHelpers.findClass(
                    "com.uc.platform.base.ucparam.UCParamExpander", cl);
                String expanded = (String) XposedHelpers.callStaticMethod(
                    expanderClass, "expandUCParamFromUrl", urlWithUcParam);
                if (expanded != null && expanded.contains("ut=")) {
                    lotteryUrl = expanded + "&timestamp=" + System.currentTimeMillis();
                    log(lpparam, "抽奖: UCParam展开成功, url含ut参数");
                } else {
                    log(lpparam, "抽奖: UCParam展开结果无ut参数");
                }
            } catch (Throwable e) {
                log(lpparam, "抽奖: UCParam展开失败: " + e.getClass().getSimpleName());
            }

            // 简单lottery body (auth在URL中，body只放抽奖参数)
            org.json.JSONObject simpleBody = new org.json.JSONObject();
            simpleBody.put("isCoin", false);
            simpleBody.put("pay_entry", "daily_lottery");
            simpleBody.put("pay_source", "daily_lottery");
            simpleBody.put("awardForTest", "");
            simpleBody.put("chid", UUID.randomUUID().toString().replace("-", ""));

            // ===== 方式2: 如果URL中没有auth参数，尝试e()构建含auth的完整body =====
            if (!lotteryUrl.contains("ut=")) {
                log(lpparam, "抽奖: 尝试e()构建含auth的body");
                try {
                    Class<?> reqClass = XposedHelpers.findClass(AUTH_REQ_CLASS, cl);
                    Object reqInst = reqClass.getConstructor().newInstance();
                    Class<?> baseClass = reqClass.getSuperclass();
                    java.lang.reflect.Method authBuild = findMethodBySignature(baseClass, AUTH_BUILD_METHOD, 0);
                    if (authBuild == null) {
                        for (java.lang.reflect.Method m : baseClass.getDeclaredMethods()) {
                            if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("JSON")) {
                                authBuild = m;
                                break;
                            }
                        }
                    }
                    if (authBuild != null) {
                        authBuild.setAccessible(true);
                        Object authJson = authBuild.invoke(reqInst);
                        if (authJson != null) {
                            java.lang.reflect.Method putM = authJson.getClass().getMethod("put", String.class, Object.class);
                            putM.invoke(authJson, "isCoin", Boolean.FALSE);
                            putM.invoke(authJson, "pay_entry", "daily_lottery");
                            putM.invoke(authJson, "pay_source", "daily_lottery");
                            putM.invoke(authJson, "awardForTest", "");
                            java.lang.reflect.Method toStr = authJson.getClass().getMethod("toJSONString");
                            bodyStr = (String) toStr.invoke(authJson);
                            log(lpparam, "抽奖: e()构建成功, body_len=" + bodyStr.length());
                        } else {
                            log(lpparam, "抽奖: e()返回null");
                        }
                    } else {
                        log(lpparam, "抽奖: 未找到e()方法");
                    }
                } catch (Throwable e) {
                    log(lpparam, "抽奖: e()构建失败: " + e.getClass().getSimpleName() + ":" + e.getMessage());
                }
            }

            // 最终确定body
            if (bodyStr == null) {
                bodyStr = simpleBody.toString();
            }

            log(lpparam, "抽奖: POST url_len=" + lotteryUrl.length() + " body_len=" + bodyStr.length());

            // HTTPS POST
            URL url = new URL(lotteryUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Origin", "https://vt.quark.cn");
            conn.setRequestProperty("Referer", "https://vt.quark.cn/");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            OutputStream os = conn.getOutputStream();
            os.write(bodyStr.getBytes("UTF-8"));
            os.flush();
            os.close();

            int httpCode = conn.getResponseCode();
            InputStream is = (httpCode >= 200 && httpCode < 400)
                ? conn.getInputStream() : conn.getErrorStream();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            String resp = baos.toString("UTF-8");
            conn.disconnect();

            log(lpparam, "抽奖响应: HTTP" + httpCode + " resp=" + resp.substring(0, Math.min(200, resp.length())));

            if (httpCode != 200) return "http_error:" + httpCode;

            org.json.JSONObject json = new org.json.JSONObject(resp);
            int code = json.optInt("code", -1);
            String msg = json.optString("msg", "");

            if (code != 0) return "server_error:code=" + code + ",msg=" + msg;

            org.json.JSONObject data = json.optJSONObject("data");
            if (data == null) return "ok:";

            int lotteryCount = data.optInt("lotteryCount", 0);
            String prize = "";
            if (data.has("couponList")) {
                org.json.JSONArray coupons = data.optJSONArray("couponList");
                if (coupons != null && coupons.length() > 0) {
                    org.json.JSONObject coupon = coupons.getJSONObject(0);
                    prize = coupon.optString("metaName", coupon.optString("productName", ""));
                }
            }
            int coins = data.optInt("validCoins", 0);
            String info = prize;
            if (coins > 0) info += (info.isEmpty() ? "" : ",") + "金币:" + coins;
            log(lpparam, "抽奖成功 剩余=" + lotteryCount + " 奖品=" + info);

            if (lotteryCount <= 0) return "no_chance";
            return "ok:" + info;

        } catch (Throwable e) {
            Log.e(TAG, "doSingleLotteryDraw", e);
            return "exception:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    private void saveLotteryProgress(Context ctx, int completed) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            sp.edit()
                .putInt(KEY_LOTTERY_COUNT, completed)
                .putLong("lottery_progress_day", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveLotteryProgress: " + e.getMessage());
        }
    }

    private void markLotteryDone(Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
            sp.edit()
                .putLong(KEY_LAST_LOTTERY, System.currentTimeMillis())
                .putInt(KEY_LOTTERY_COUNT, lotteryDoneToday)
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "markLotteryDone: " + e.getMessage());
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
            String fullLog = String.join("\n", list);
            sp.edit().putString(KEY_STATUS_LOG, fullLog).apply();
            makePrefsWorldReadable(ctx);
            // 同时写入文件（解决XSharedPreferences跨进程读取问题）
            writeLogFile(ctx, fullLog);
        } catch (Exception e) {
            // ignore
        }
    }

    private void writeLogFile(Context ctx, String fullLog) {
        try {
            File dir = ctx.getFilesDir();
            dir.setExecutable(true, false);
            dir.setReadable(true, false);
            File logFile = new File(dir, LOG_FILE_NAME);
            FileWriter fw = new FileWriter(logFile, false);
            fw.write(fullLog);
            fw.close();
            logFile.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    private void makePrefsWorldReadable(Context ctx) {
        try {
            java.io.File prefsDir = new java.io.File(
                ctx.getFilesDir().getParentFile(), "shared_prefs");
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            java.io.File prefsFile = new java.io.File(prefsDir, SP_NAME + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {}
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
