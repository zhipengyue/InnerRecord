package com.innerrecord.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecorderService extends Service {
    public static final String START = "com.innerrecord.app.START";
    public static final String PAUSE = "com.innerrecord.app.PAUSE";
    public static final String RESUME = "com.innerrecord.app.RESUME";
    public static final String STOP = "com.innerrecord.app.STOP";
    public static volatile String state = "idle";
    public static volatile String lastFile = "";

    private static final int RATE = 44100;
    private static final int CHANNELS = 2;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private AudioRecord recorder;
    private MediaProjection projection;
    private Thread writer;
    private File target;
    private FileOutputStream output;
    private AndroidLame lame;
    private long pcmBytes;
    private boolean mp3;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (START.equals(action)) startCapture(intent);
        else if (PAUSE.equals(action) && running.get()) { paused.set(true); state = "paused"; }
        else if (RESUME.equals(action) && running.get()) { paused.set(false); state = "recording"; }
        else if (STOP.equals(action)) stopCapture();
        return START_NOT_STICKY;
    }

    private void startCapture(Intent intent) {
        if (running.get()) return;
        String mode = intent.getStringExtra("mode");
        mp3 = "mp3".equals(intent.getStringExtra("format"));
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel(
                    "recording", "录音", NotificationManager.IMPORTANCE_LOW));
            Notification notification = new Notification.Builder(this, "recording")
                    .setSmallIcon(R.drawable.ic_record)
                    .setContentTitle("InnerRecord 正在录音")
                    .setContentText("返回应用可暂停或停止")
                    .build();
            int serviceType = "internal".equals(mode)
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(41, notification, serviceType);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("缺少录音权限");

            int mask = AudioFormat.CHANNEL_IN_STEREO;
            int min = AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("无法初始化音频设备");
            int bufferSize = Math.max(min * 2, 8192);
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask).build();
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioFormat(format).setBufferSizeInBytes(bufferSize);
            if ("internal".equals(mode)) {
                @SuppressWarnings("deprecation")
                Intent data = intent.getParcelableExtra("projectionData");
                if (data == null) throw new IllegalStateException("缺少系统内录授权");
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                projection = manager.getMediaProjection(intent.getIntExtra("resultCode", 0), data);
                if (projection == null) throw new IllegalStateException("系统拒绝内录");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stopCapture(); }
                }, null);
                AudioPlaybackCaptureConfiguration config =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();
                builder.setAudioPlaybackCaptureConfig(config);
            } else {
                builder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            recorder = builder.build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("无法启动音频采集");
            File dir = new File(getFilesDir(), "recordings");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建录音目录");
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
            String source = "internal".equals(mode) ? "系统播放" : "麦克风";
            target = new File(dir, source + "_" + stamp + (mp3 ? ".mp3" : ".wav"));
            output = new FileOutputStream(target);
            if (mp3) {
                lame = new LameBuilder().setInSampleRate(RATE).setOutChannels(CHANNELS)
                        .setOutBitrate(192).build();
            } else {
                output.write(new byte[44]);
            }
            pcmBytes = 0;
            paused.set(false);
            running.set(true);
            recorder.startRecording();
            state = "recording";
            writer = new Thread(() -> recordLoop(bufferSize), "InnerRecordWriter");
            writer.start();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void recordLoop(int bufferSize) {
        short[] pcm = new short[bufferSize / 2];
        byte[] encoded = new byte[bufferSize * 2];
        try {
            while (running.get()) {
                int count = recorder.read(pcm, 0, pcm.length);
                if (count < 0) throw new IllegalStateException("音频读取失败：" + count);
                if (paused.get() || count == 0) continue;
                if (mp3) {
                    int bytes = lame.encodeBufferInterLeaved(pcm, count / CHANNELS, encoded);
                    if (bytes < 0) throw new IllegalStateException("MP3 编码失败：" + bytes);
                    output.write(encoded, 0, bytes);
                    pcmBytes += count * 2L;
                } else {
                    ByteBuffer buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < count; i++) buffer.putShort(pcm[i]);
                    output.write(buffer.array());
                    pcmBytes += count * 2L;
                }
            }
            finishFile();
            if (state.startsWith("error:")) return;
            lastFile = target == null ? "" : target.getAbsolutePath();
            state = "idle";
        } catch (Throwable error) {
            fail(error);
        } finally {
            releaseCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void finishFile() throws Exception {
        if (mp3) {
            byte[] tail = new byte[8192];
            int count = lame.flush(tail);
            if (count > 0) output.write(tail, 0, count);
            lame.close();
            lame = null;
        }
        output.flush();
        output.close();
        output = null;
        if (pcmBytes == 0) {
            if (target != null) target.delete();
            lastFile = "";
        } else if (!mp3) {
            try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
                file.seek(0);
                file.write(wavHeader((int) pcmBytes));
            }
        }
    }

    private byte[] wavHeader(int length) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()); b.putInt(36 + length);
        b.put("WAVEfmt ".getBytes()); b.putInt(16);
        b.putShort((short) 1); b.putShort((short) CHANNELS); b.putInt(RATE);
        b.putInt(RATE * CHANNELS * 2); b.putShort((short) (CHANNELS * 2)); b.putShort((short) 16);
        b.put("data".getBytes()); b.putInt(length);
        return b.array();
    }

    private void stopCapture() {
        if (!running.getAndSet(false)) return;
        state = "stopping";
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) { }
    }

    private void releaseCapture() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        MediaProjection current = projection;
        projection = null;
        try { if (current != null) current.stop(); } catch (Exception ignored) { }
    }

    private void fail(Throwable error) {
        running.set(false);
        state = "error:" + (error.getMessage() == null ? "录音失败" : error.getMessage());
        try { if (output != null) output.close(); } catch (Exception ignored) { }
        output = null;
        try { if (lame != null) lame.close(); } catch (Throwable ignored) { }
        lame = null;
        if (target != null) target.delete();
        releaseCapture();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running.get()) stopCapture();
        super.onDestroy();
    }
}
