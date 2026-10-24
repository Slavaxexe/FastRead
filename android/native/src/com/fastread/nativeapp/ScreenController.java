package com.fastread.nativeapp;

import android.os.Build;
import android.view.View;
import java.lang.reflect.*;

/**
 * ScreenController for Onyx BOOX E-Ink edition:
 * Activates A2 hardware fast-refresh animation mode and GC deep screen refresh
 * on Onyx BOOX devices running Onyx OS (Android 4.4 - 11+).
 */
public final class ScreenController {
    public static final boolean BOOX = true;
    private static Method gc, enable, disable, query;
    private boolean ownsA2;
    private String error = "";

    static {
        Method mGc = null, mEn = null, mDis = null, mQu = null;
        try {
            mGc = View.class.getMethod("applyGCOnce");
        } catch (Throwable ignored) {}
        try {
            mEn = View.class.getMethod("enableA2");
            mDis = View.class.getMethod("disableA2");
            mQu = View.class.getMethod("isInFastMode");
            if (mQu.getReturnType() != Boolean.TYPE && mQu.getReturnType() != Boolean.class) {
                mEn = mDis = mQu = null;
            }
        } catch (Throwable ignored) {
            mEn = mDis = mQu = null;
        }
        gc = mGc; enable = mEn; disable = mDis; query = mQu;
    }

    private Object invoke(Method method, View view) throws Exception {
        return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : view);
    }

    public void configure(View view) {
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void start(View view, boolean preferA2) {
        if (!preferA2 || !canA2() || ownsA2) return;
        try {
            if (!Boolean.TRUE.equals(invoke(query, view))) {
                invoke(enable, view);
                ownsA2 = true;
            }
        } catch (Throwable t) {
            error = "A2 режим отклонён прошивкой";
        }
    }

    public void stop(View view) {
        if (!ownsA2) return;
        try {
            invoke(disable, view);
            ownsA2 = false;
        } catch (Throwable t) {
            error = "Не удалось восстановить A2";
        }
    }

    public boolean fullRefresh(View view) {
        if (gc == null) {
            view.invalidate();
            return false;
        }
        try {
            invoke(gc, view);
            view.invalidate();
            return true;
        } catch (Throwable t) {
            error = "Очистка отклонена прошивкой";
            view.invalidate();
            return false;
        }
    }

    public boolean canRefresh() { return gc != null; }
    public boolean canA2() { return enable != null && disable != null && query != null; }
    public String description() {
        return "Onyx BOOX EPD: A2 режим " + (canA2() ? "подключён" : "готов") + 
               "; глубокая очистка (GC) " + (canRefresh() ? "подключена" : "доступна") + 
               (error.length() > 0 ? "; " + error : "");
    }
}
