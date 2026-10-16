package com.biruk.androidbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class BridgeRelayService extends Service {
    private static final String CHANNEL_ID = "android_bridge_connection";
    private static final int NOTIFICATION_ID = 8765;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification;
        if (Build.VERSION.SDK_INT >= 26) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Android Bridge is active")
                    .setContentText("Secure relay connection is enabled. Tap Emergency Stop in the app to stop.")
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("Android Bridge is active")
                    .setContentText("Secure relay connection is enabled.")
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setOngoing(true)
                    .build();
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Android Bridge connection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when the user-authorized Android Bridge is enabled.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        BridgeServer.startIfNeeded();
        RelayClient.start(this);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        RelayClient.stop();
        BridgeServer.stop();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
