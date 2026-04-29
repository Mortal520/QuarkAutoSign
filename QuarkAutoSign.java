package com.qurk.autosign;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Calendar;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class QuarkAutoSign implements IXposedHookLoadPackage {

    private static final String TAG = "QuarkAutoSign";
    private static final String TARGET_PKG = "com.quark.scank";
    private static final String SP_NAME = "quark_autosign_prefs";
    private static final String KEY_LAST_SIGN = "last_sign_time";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        Log.i(TAG, "QuarkAutoSign module loaded for: " + lpparam.packageName);

        hookApplication(lpparam);
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
                        Log.i(TAG, "App started, check sign-in");
                        performAutoSign(ctx);
                    }
                }
            );
        } catch (Exception e) {
            Log.e(TAG, "Hook failed: " + e.getMessage());
        }
    }

    private void performAutoSign(Context ctx) {
        try {
            if (!shouldSignToday(ctx)) {
                Log.i(TAG, "Already signed today, skip");
                return;
            }

            // 延迟3秒执行签到，等待APP初始化完成
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    doSignIn(ctx);
                } catch (Exception e) {
                    Log.e(TAG, "Sign-in delayed task error: " + e.getMessage());
                }
            }, 3000);

        } catch (Exception e) {
            Log.e(TAG, "performAutoSign error: " + e.getMessage());
        }
    }

    private boolean shouldSignToday(Context ctx) {
        try {
            long lastSign = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SIGN, 0);
            if (lastSign == 0) return true;

            Calendar last = Calendar.getInstance();
            last.setTimeInMillis(lastSign);
            Calendar now = Calendar.getInstance();

            return last.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)
                || last.get(Calendar.YEAR) != now.get(Calendar.YEAR);
        } catch (Exception e) {
            Log.e(TAG, "shouldSignToday error: " + e.getMessage());
            return true;
        }
    }

    private void doSignIn(Context ctx) {
        try {
            Class<?> managerClass = XposedHelpers.findClass(
                "com.ucpro.feature.study.userop.CameraCheckInManager",
                ctx.getClassLoader()
            );

            Object manager = XposedHelpers.callStaticMethod(managerClass, "m63467i", new Object[]{null});
            if (manager == null) {
                Log.w(TAG, "CameraCheckInManager is null");
                return;
            }

            Log.i(TAG, "Triggering sign-in...");
            XposedHelpers.callMethod(manager, "m63476q", null, "xposed_auto_sign");
            recordSignTime(ctx);
            Log.i(TAG, "Sign-in triggered OK");
        } catch (Exception e) {
            Log.e(TAG, "doSignIn error: " + e.getMessage());
        }
    }

    private void recordSignTime(Context ctx) {
        try {
            ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SIGN, System.currentTimeMillis())
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "recordSignTime error: " + e.getMessage());
        }
    }

}
