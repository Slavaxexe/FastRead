package com.fastread.nativeapp;

import android.view.View;

/**
 * Universal ScreenController:
 * Manages display rendering on standard Android devices (phones, tablets).
 * Operates purely through standard Android View and hardware-accelerated Canvas.
 */
public final class ScreenController {
    public static final boolean BOOX = false;

    public void configure(View view) {
        // Standard hardware-accelerated view on normal Android devices
    }

    public void start(View view, boolean preferA2) {
        // Standard Android refresh loop
    }

    public void stop(View view) {
        // Standard Android lifecycle
    }

    public boolean fullRefresh(View view) {
        view.invalidate();
        return true;
    }

    public boolean canRefresh() { return false; }
    public boolean canA2() { return false; }

    public String description() {
        return "Универсальный нативный вывод (Стандартный Android)";
    }
}
