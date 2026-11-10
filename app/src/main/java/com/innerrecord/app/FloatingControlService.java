package com.innerrecord.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

public class FloatingControlService extends Service {
    private static final int NOTIFICATION_ID = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private LinearLayout panel;
    private Button primary;
    private Button stop;
    private String shownState = "";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!getSharedPreferences("MainActivity", MODE_PRIVATE).getBoolean("overlay_enabled", false)
                || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                "floating_control", "悬浮录音控件", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "floating_control")
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("InnerRecord 悬浮控件已开启")
                .setContentText("点击打开录音界面")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (panel == null) showOverlay();
        return START_STICKY;
    }

    private void showOverlay() {
        windowManager = getSystemService(WindowManager.class);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(28, 36, 48));
        background.setCornerRadius(dp(28));
        panel.setBackground(background);
        panel.setElevation(dp(8));

        primary = new Button(this);
        primary.setAllCaps(false);
        primary.setTextColor(Color.WHITE);
        primary.setTextSize(15);
        primary.setMinWidth(dp(94));
        primary.setOnClickListener(v -> {
            String state = RecorderService.state;
            if ("recording".equals(state)) command(RecorderService.PAUSE);
            else if ("paused".equals(state)) command(RecorderService.RESUME);
            else if ("idle".equals(state) || state.startsWith("error:")) {
                Intent launch = new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_FLOATING_START)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launch);
            }
            handler.postDelayed(this::refresh, 100);
        });
        panel.addView(primary, new LinearLayout.LayoutParams(dp(102), dp(50)));

        stop = new Button(this);
        stop.setAllCaps(false);
        stop.setText("■ 停止");
        stop.setTextColor(Color.WHITE);
        stop.setTextSize(13);
        stop.setOnClickListener(v -> { command(RecorderService.STOP); handler.postDelayed(this::refresh, 100); });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(dp(102), dp(43));
        stopParams.topMargin = dp(3);
        panel.addView(stop, stopParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(8);
        windowManager.addView(panel, params);
        handler.post(refreshTask);
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 300);
        }
    };

    private void refresh() {
        if (primary == null) return;
        String state = RecorderService.state;
        if (state.equals(shownState)) return;
        shownState = state;
        primary.setText("recording".equals(state) ? "Ⅱ 暂停" :
                "paused".equals(state) ? "▶ 继续" :
                "stopping".equals(state) ? "保存中" : "● 录制");
        primary.setEnabled(!"stopping".equals(state));
        stop.setVisibility("recording".equals(state) || "paused".equals(state)
                ? View.VISIBLE : View.GONE);
    }

    private void command(String action) {
        startService(new Intent(this, RecorderService.class).setAction(action));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (panel != null) windowManager.removeView(panel);
        panel = null;
        super.onDestroy();
    }
}
