package com.imagine.livelingo.background;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import com.imagine.livelingo.MainActivity;
import com.imagine.livelingo.R;

/** Owns the active translation/meeting runtime while UI may be backgrounded or recreated. */
public final class LiveLingoForegroundService extends Service {
    public static final String ACTION_START = "com.imagine.livelingo.action.START_BACKGROUND_SESSION";
    public static final String ACTION_STOP = "com.imagine.livelingo.action.STOP_BACKGROUND_SESSION";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_INPUT = "input";
    public static final String EXTRA_TARGET = "target";
    private static final String CHANNEL_ID = "livelingo_live_session";
    private static final int NOTIFICATION_ID = 2201;
    private String mode = "translate";

    public static void start(Context context, String mode, String inputLanguage, String targetLanguage) {
        Intent i = new Intent(context, LiveLingoForegroundService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_INPUT, inputLanguage);
        i.putExtra(EXTRA_TARGET, targetLanguage);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i);
    }

    public static void start(Context context, String mode) { start(context, mode, "auto", "ru"); }

    public static void stop(Context context) {
        Intent i = new Intent(context, LiveLingoForegroundService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SessionRuntime runtime = SessionRuntime.get(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            runtime.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        String input="auto", target="ru";
        if (intent != null) {
            mode = intent.getStringExtra(EXTRA_MODE) == null ? "translate" : intent.getStringExtra(EXTRA_MODE);
            input = intent.getStringExtra(EXTRA_INPUT) == null ? "auto" : intent.getStringExtra(EXTRA_INPUT);
            target = intent.getStringExtra(EXTRA_TARGET) == null ? "ru" : intent.getStringExtra(EXTRA_TARGET);
        }
        startForeground(NOTIFICATION_ID, notification());
        runtime.configure(mode,input,target);
        runtime.start();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "LiveLingo active session", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps translation and meeting capture running in background");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, LiveLingoForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = "meeting".equals(mode) ? "LiveLingo · встреча идёт" : "LiveLingo · перевод активен";
        String text = "Распознавание и запись продолжаются в фоне";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(0, "Остановить", stop)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
