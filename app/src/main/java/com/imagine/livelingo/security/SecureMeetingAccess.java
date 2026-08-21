package com.imagine.livelingo.security;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;

/** Requests device authentication before decrypting protected meeting content. */
public final class SecureMeetingAccess {
    private SecureMeetingAccess(){}

    public static boolean requestDeviceUnlock(Activity activity,int requestCode){
        KeyguardManager km=(KeyguardManager)activity.getSystemService(Context.KEYGUARD_SERVICE);
        if(km==null || !km.isDeviceSecure()) return false;
        Intent intent=km.createConfirmDeviceCredentialIntent("Открыть защищённые встречи","Подтвердите личность для расшифровки данных LiveLingo");
        if(intent==null) return false;
        activity.startActivityForResult(intent,requestCode);
        return true;
    }
}
