package com.fastread.nativeapp;

import android.view.View;
import java.lang.reflect.*;

/**
 * Universal ScreenController:
 * Detects ONYX BOOX E-Ink firmware APIs at runtime via safe reflection.
 * On Onyx devices: activates A2 fast animation mode and hardware GC refresh.
 * On standard Android phones/tablets: runs normal hardware/software canvas safely without vendor calls.
 */
public final class ScreenController {
    public static final boolean BOOX;
    private static Method gc, enable, disable, query;
    private boolean ownsA2;
    private String error = "";

    static {
        Method mGc = null, mEn = null, mDis = null, mQu = null;
        boolean isBoox = false;
        try {
            mGc = View.class.getMethod("applyGCOnce");
            isBoox = true;
        } catch (Throwable ignored) {}
        try {
            mEn = View.class.getMethod("enableA2");
            mDis = View.class.getMethod("disableA2");
            mQu = View.class.getMethod("isInFastMode");
            if (mQu.getReturnType() != Boolean.TYPE && mQu.getReturnType() != Boolean.class) {
                mEn = mDis = mQu = null;
            } else {
                isBoox = true;
            }
        } catch (Throwable ignored) {
            mEn = mDis = mQu = null;
        }
        gc = mGc; enable = mEn; disable = mDis; query = mQu;
        BOOX = isBoox;
    }

    private Object invoke(Method method, View view) throws Exception {
        return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : view);
    }

    public void configure(View view) {
        if (BOOX) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
    }

    public void start(View view, boolean preferA2) {
        if (!preferA2 || !canA2() || ownsA2) return;
        try {
            if (!Boolean.TRUE.equals(invoke(query, view))) {
                invoke(enable, view);
                ownsA2 = true;
            }
        } catch (Throwable t) {
            error = "A2 API отклонён прошивкой";
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
        if (gc == null) return false;
        try {
            invoke(gc, view);
            view.invalidate();
            return true;
        } catch (Throwable t) {
            error = "Очистка отклонена прошивкой";
            return false;
        }
    }

    public boolean canRefresh() { return gc != null; }
    public boolean canA2() { return enable != null && disable != null && query != null; }
    public String description() {
        if (BOOX) {
            return "Onyx BOOX: A2 режим " + (canA2() ? "активен" : "не найден") + "; очистка " + (canRefresh() ? "доступна" : "не найдена") + (error.length() > 0 ? "; " + error : "");
        } else {
            return "Универсальный нативный вывод (Стандартный Android)";
        }
    }
}
