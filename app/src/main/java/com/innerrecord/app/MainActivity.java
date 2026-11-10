package com.innerrecord.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String ACTION_FLOATING_START = "com.innerrecord.app.FLOATING_START";
    private static final int REQUEST_AUDIO = 100;
    private static final int REQUEST_PROJECTION = 101;
    private static final int REQUEST_SAVE_AS = 102;
    private static final int BG = Color.rgb(16, 20, 28);
    private static final int PANEL = Color.rgb(28, 36, 48);
    private static final int TEXT = Color.rgb(244, 246, 249);
    private static final int MUTED = Color.rgb(168, 179, 194);
    private static final int ACCENT = Color.rgb(236, 102, 95);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView timer;
    private String page = "record";
    private String mode = "internal";
    private String format = "wav";
    private String lastState = "idle";
    private boolean wasRecording;
    private boolean waitingProjection;
    private boolean pendingOverlayPermission;
    private boolean startedFromOverlay;
    private long elapsedBeforePause;
    private long resumedAt;
    private MediaPlayer player;
    private File playingFile;
    private File pendingSave;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        mode = getPreferences(MODE_PRIVATE).getString("mode", "internal");
        format = getPreferences(MODE_PRIVATE).getString("format", "wav");
        lastState = RecorderService.state;
        wasRecording = "recording".equals(lastState) || "paused".equals(lastState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        render();
        handler.post(tick);
        if (ACTION_FLOATING_START.equals(getIntent().getAction())) {
            startedFromOverlay = true;
            handler.post(this::startCapture);
        } else if (getPreferences(MODE_PRIVATE).getBoolean("overlay_enabled", false)
                && Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, FloatingControlService.class));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_FLOATING_START.equals(intent.getAction())) {
            startedFromOverlay = true;
            page = "record";
            render();
            if ("idle".equals(RecorderService.state)) startCapture();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingOverlayPermission) {
            pendingOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply();
                startForegroundService(new Intent(this, FloatingControlService.class));
                toast("悬浮按钮已开启");
            } else {
                toast("未获得悬浮窗权限");
            }
            render();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        stopPlayback();
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String state = RecorderService.state;
            if (!state.equals(lastState)) {
                lastState = state;
                if ("recording".equals(state)) {
                    if (!wasRecording) {
                        elapsedBeforePause = 0;
                        wasRecording = true;
                    }
                    resumedAt = SystemClock.elapsedRealtime();
                    waitingProjection = false;
                } else if ("paused".equals(state)) {
                    elapsedBeforePause += SystemClock.elapsedRealtime() - resumedAt;
                } else if ("idle".equals(state) && wasRecording) {
                    wasRecording = false;
                    page = "files";
                } else if (state.startsWith("error:")) {
                    wasRecording = false;
                    waitingProjection = false;
                    showMessage(state.substring(6));
                    RecorderService.state = "idle";
                }
                render();
            }
            if (timer != null && "record".equals(page)) {
                long ms = elapsedBeforePause;
                if ("recording".equals(state)) ms += SystemClock.elapsedRealtime() - resumedAt;
                long seconds = Math.max(0, ms / 1000);
                timer.setText(String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
            }
            handler.postDelayed(this, 250);
        }
    };

    private void render() {
        root.removeAllViews();
        timer = null;
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(10), dp(16), dp(10));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(72)));
        TextView title = text("record".equals(page) ? "InnerRecord" :
                ("files".equals(page) ? "录制文件" : "设置"), 24, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if ("record".equals(page)) {
            header.addView(button("文件", false, v -> { page = "files"; render(); }));
            header.addView(button("设置", false, v -> { page = "settings"; render(); }));
        } else {
            header.addView(button("返回", false, v -> { page = "record"; render(); }));
        }
        if ("record".equals(page)) renderRecorder();
        else if ("files".equals(page)) renderFiles();
        else renderSettings();
    }

    private void renderRecorder() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(24), 0, dp(24), 0);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        String state = RecorderService.state;
        TextView source = text("internal".equals(mode) ? "系统内录" : "通话 / 麦克风", 17, MUTED, false);
        source.setGravity(Gravity.CENTER);
        body.addView(source, wide(dp(48)));
        timer = text("00:00", 58, TEXT, true);
        timer.setGravity(Gravity.CENTER);
        body.addView(timer, wide(dp(100)));
        String status = waitingProjection ? "等待系统授权…" :
                ("recording".equals(state) ? "录制中" :
                ("paused".equals(state) ? "已暂停" :
                ("stopping".equals(state) ? "正在保存…" : "准备录制")));
        TextView statusView = text(status, 16, MUTED, false);
        statusView.setGravity(Gravity.CENTER);
        body.addView(statusView, wide(dp(58)));
        if ("recording".equals(state) || "paused".equals(state)) {
            Button stop = button("■  停止并保存", true, v -> sendService(RecorderService.STOP));
            body.addView(stop, wide(dp(64)));
            Button toggle = button("paused".equals(state) ? "▶  继续录制" : "Ⅱ  暂停", false,
                    v -> sendService("paused".equals(RecorderService.state)
                            ? RecorderService.RESUME : RecorderService.PAUSE));
            LinearLayout.LayoutParams space = wide(dp(58));
            space.topMargin = dp(14);
            body.addView(toggle, space);
        } else {
            Button start = button("●  开始录制", true, v -> startCapture());
            start.setEnabled(!waitingProjection && !"stopping".equals(state));
            body.addView(start, wide(dp(70)));
        }
        TextView hint = text("internal".equals(mode)
                ? "Android 10+ · 内录需系统授权\n只能录制允许采集的应用音频"
                : "通话模式使用麦克风\n远端声音取决于设备和通话应用", 14, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = wide(dp(90));
        hintParams.topMargin = dp(22);
        body.addView(hint, hintParams);
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        scroll.addView(body);
        body.addView(text("音频来源", 20, TEXT, true), wide(dp(48)));
        body.addView(button(("internal".equals(mode) ? "✓  " : "") + "内录 · 其他应用播放", false,
                v -> { mode = "internal"; saveSettings(); render(); }), wide(dp(62)));
        body.addView(button(("call".equals(mode) ? "✓  " : "") + "通话 · 麦克风", false,
                v -> { mode = "call"; saveSettings(); render(); }), wide(dp(62)));
        TextView formatLabel = text("文件格式", 20, TEXT, true);
        LinearLayout.LayoutParams gap = wide(dp(58));
        gap.topMargin = dp(24);
        body.addView(formatLabel, gap);
        body.addView(button(("wav".equals(format) ? "✓  " : "") + "WAV · 无损 PCM", false,
                v -> { format = "wav"; saveSettings(); render(); }), wide(dp(62)));
        body.addView(button(("mp3".equals(format) ? "✓  " : "") + "MP3 · 压缩音频", false,
                v -> { format = "mp3"; saveSettings(); render(); }), wide(dp(62)));
        TextView floatingLabel = text("悬浮录音", 20, TEXT, true);
        LinearLayout.LayoutParams floatingGap = wide(dp(58));
        floatingGap.topMargin = dp(24);
        body.addView(floatingLabel, floatingGap);
        boolean floatingEnabled = getPreferences(MODE_PRIVATE).getBoolean("overlay_enabled", false)
                && Settings.canDrawOverlays(this);
        body.addView(button(floatingEnabled ? "✓  悬浮按钮已开启 · 点击关闭" : "开启悬浮按钮",
                false, v -> toggleFloatingControl()), wide(dp(62)));
        body.addView(text("在其他应用上层快速开始、暂停、继续或停止录音。首次开启需要授予悬浮窗权限。",
                14, MUTED, false), wide(-2));
        TextView note = text("录音保存在应用私有目录。内录取决于播放应用的采集策略；普通应用不能保证录到通话对方的声音。", 14, MUTED, false);
        LinearLayout.LayoutParams noteParams = wide(-2);
        noteParams.topMargin = dp(30);
        body.addView(note, noteParams);
    }

    private void renderFiles() {
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        scroll.addView(body);
        File[] files = recordingsDir().listFiles(file -> file.isFile() &&
                (file.getName().endsWith(".wav") || file.getName().endsWith(".mp3")));
        if (files == null || files.length == 0) {
            TextView empty = text("还没有录制文件\n返回首页开始录制", 20, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            body.addView(empty, wide(dp(220)));
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(8), dp(8));
            row.setBackground(rounded(PANEL));
            LinearLayout.LayoutParams rowParams = wide(dp(80));
            rowParams.bottomMargin = dp(10);
            body.addView(row, rowParams);
            TextView name = text(file.getName(), 15, TEXT, false);
            name.setSingleLine(false);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> togglePlayback(file));
            name.setOnClickListener(v -> togglePlayback(file));
            row.setOnLongClickListener(v -> { showFileMenu(file); return true; });
            name.setOnLongClickListener(v -> { showFileMenu(file); return true; });
            Button play = button(file.equals(playingFile) ? "停止" : "播放", false,
                    v -> togglePlayback(file));
            row.addView(play);
        }
        body.addView(text("长按录音可重命名、查看位置、另存为或删除", 13, MUTED, false));
    }

    private void showFileMenu(File file) {
        String[] actions = {"重命名", "查看保存位置", "另存为…", "删除"};
        new AlertDialog.Builder(this).setTitle(file.getName()).setItems(actions, (dialog, index) -> {
            switch (index) {
                case 0: renameFile(file); break;
                case 1: showMessage("文件保存位置：\n" + file.getAbsolutePath()
                        + "\n\n这是应用私有目录；如需保存到其他位置，请使用“另存为”。"); break;
                case 2: saveAs(file); break;
                case 3: confirmDelete(file); break;
            }
        }).show();
    }

    private void renameFile(File file) {
        EditText input = new EditText(this);
        String ext = file.getName().substring(file.getName().lastIndexOf('.'));
        input.setText(file.getName().substring(0, file.getName().length() - ext.length()));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("重命名录音")
                .setMessage("输入新名称（扩展名保持不变）：")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.toLowerCase(Locale.ROOT).endsWith(ext))
                        name = name.substring(0, name.length() - ext.length());
                    if (name.isEmpty() || name.equals(".") || name.equals("..") ||
                            name.contains("/") || name.contains("\\")) {
                        showMessage("名称不能为空，也不能包含路径分隔符。"); return;
                    }
                    File target = new File(recordingsDir(), name + ext);
                    if (target.equals(file)) return;
                    if (target.exists()) { showMessage("同名文件已存在。"); return; }
                    if (file.equals(playingFile)) stopPlayback();
                    if (!file.renameTo(target)) showMessage("重命名失败。");
                    render();
                }).show();
    }

    private void confirmDelete(File file) {
        new AlertDialog.Builder(this).setTitle("删除录音")
                .setMessage("确定删除这条录音？此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (file.equals(playingFile)) stopPlayback();
                    if (!file.delete()) showMessage("删除失败。");
                    render();
                }).show();
    }

    private void saveAs(File file) {
        pendingSave = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(file.getName().endsWith(".mp3") ? "audio/mpeg" : "audio/wav");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SAVE_AS);
    }

    private void startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if ("internal".equals(mode)) {
            waitingProjection = true;
            render();
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
        } else {
            Intent intent = new Intent(this, RecorderService.class).setAction(RecorderService.START)
                    .putExtra("mode", "call").putExtra("format", format);
            startForegroundService(intent);
            returnToOtherApp();
        }
    }

    private void toggleFloatingControl() {
        boolean enabled = getPreferences(MODE_PRIVATE).getBoolean("overlay_enabled", false);
        if (enabled) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply();
            stopService(new Intent(this, FloatingControlService.class));
            render();
        } else if (Settings.canDrawOverlays(this)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply();
            startForegroundService(new Intent(this, FloatingControlService.class));
            render();
        } else {
            pendingOverlayPermission = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void returnToOtherApp() {
        if (startedFromOverlay) {
            startedFromOverlay = false;
            moveTaskToBack(true);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_AUDIO) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCapture();
            else { startedFromOverlay = false; showMessage("需要录音权限才能开始录制。"); }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION) {
            waitingProjection = false;
            if (resultCode != RESULT_OK || data == null) {
                startedFromOverlay = false;
                toast("已取消系统内录授权"); render(); return;
            }
            Intent intent = new Intent(this, RecorderService.class).setAction(RecorderService.START)
                    .putExtra("mode", "internal").putExtra("format", format)
                    .putExtra("resultCode", resultCode).putExtra("projectionData", data);
            startForegroundService(intent);
            render();
            returnToOtherApp();
        } else if (requestCode == REQUEST_SAVE_AS) {
            File source = pendingSave;
            pendingSave = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || source == null) return;
            android.net.Uri uri = data.getData();
            new Thread(() -> {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("无法写入选定位置");
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    runOnUiThread(() -> toast("另存为成功"));
                } catch (Exception error) {
                    runOnUiThread(() -> showMessage("另存为失败：" + error.getMessage()));
                }
            }).start();
        }
    }

    private void sendService(String action) {
        startService(new Intent(this, RecorderService.class).setAction(action));
    }

    private void togglePlayback(File file) {
        if (file.equals(playingFile)) { stopPlayback(); render(); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(done -> { stopPlayback(); if ("files".equals(page)) render(); });
            player.prepare();
            player.start();
            playingFile = file;
            render();
        } catch (Exception error) {
            stopPlayback();
            showMessage("无法播放文件：" + error.getMessage());
        }
    }

    private void stopPlayback() {
        if (player != null) { player.release(); player = null; }
        playingFile = null;
    }

    private File recordingsDir() {
        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void saveSettings() {
        getPreferences(MODE_PRIVATE).edit().putString("mode", mode).putString("format", format).apply();
    }

    private LinearLayout column() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(20), dp(24), dp(24));
        return body;
    }

    private LinearLayout.LayoutParams wide(int height) {
        return new LinearLayout.LayoutParams(-1, height);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String value, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(primary ? 18 : 15);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setBackground(rounded(primary ? ACCENT : PANEL));
        button.setPadding(dp(15), 0, dp(15), 0);
        button.setOnClickListener(action);
        return button;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        return bg;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void showMessage(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("确定", null).show(); }

    @Override public void onBackPressed() {
        if (!"record".equals(page)) { page = "record"; render(); }
        else super.onBackPressed();
    }
}
