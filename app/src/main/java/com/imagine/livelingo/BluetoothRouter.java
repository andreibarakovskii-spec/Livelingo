package com.imagine.livelingo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

public final class BluetoothRouter {
    private BluetoothRouter() {}
    public static String routeToHeadset(Context context) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return "Разрешите подключение к Bluetooth";
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                boolean ok = am.setCommunicationDevice(d);
                return ok ? "Наушники: " + d.getProductName() : "Наушники найдены, маршрут не выбран";
            }
        }
        return "Используется микрофон телефона";
    }
}
