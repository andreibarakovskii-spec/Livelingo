package com.imagine.livelingo.security;

import android.app.Activity;
import android.view.WindowManager;

public final class SecureScreen {
    private SecureScreen(){}
    public static void protect(Activity activity){
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    public static void unprotect(Activity activity){
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
}
