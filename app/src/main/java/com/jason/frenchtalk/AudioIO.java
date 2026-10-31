package com.jason.frenchtalk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Microphone capture (16 kHz mono PCM16, what the Live API expects) and
 * streaming playback (24 kHz mono PCM16, what the Live API returns).
 * Uses the voice-communication path so Android's echo canceller stops the
 * AI from hearing (and interrupting) itself when you use the loudspeaker.
 */
public class AudioIO {

    public interface MicListener {
        void onMicChunk(byte[] pcm16);
    }

    private static final String TAG = "AudioIO";
    private static final int IN_RATE = 16000;
    private static final int OUT_RATE = 24000;
    private static final int CHUNK_BYTES = IN_RATE * 2 / 10; // 100 ms

    private final AudioManager audioManager;
    private AudioRecord recorder;
    private AudioTrack track;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread micThread;
    private Thread playThread;
    private volatile boolean running;
    private volatile boolean micMuted;
    private final LinkedBlockingQueue<byte[]> playQueue = new LinkedBlockingQueue<>();
    private int previousMode;

    public AudioIO(Context ctx) {
        audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    @SuppressLint("MissingPermission") // permission is checked in MainActivity before start()
    public void start(MicListener listener) {
        if (running) return;
        running = true;

        previousMode = audioManager.getMode();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        routeToSpeakerIfNoHeadset();

        // ---- Microphone ----
        int minIn = AudioRecord.getMinBufferSize(IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minIn, CHUNK_BYTES * 4));
        int session = recorder.getAudioSessionId();
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(session);
            if (aec != null) aec.setEnabled(true);
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(session);
            if (ns != null) ns.setEnabled(true);
        }

        // ---- Speaker ----
        int minOut = AudioTrack.getMinBufferSize(OUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(OUT_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minOut, OUT_RATE * 2 / 5)) // ~200 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();

        playThread = new Thread(() -> {
            while (running) {
                try {
                    byte[] data = playQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (data != null && track != null) track.write(data, 0, data.length);
                } catch (InterruptedException e) {
                    return;
                } catch (IllegalStateException e) {
                    Log.w(TAG, "playback write failed", e);
                }
            }
        }, "gemini-play");
        playThread.start();

        recorder.startRecording();
        micThread = new Thread(() -> {
            byte[] buf = new byte[CHUNK_BYTES];
            while (running) {
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0 && !micMuted) {
                    byte[] out = new byte[n];
                    System.arraycopy(buf, 0, out, 0, n);
                    listener.onMicChunk(out);
                }
            }
        }, "gemini-mic");
        micThread.start();
    }

    /** Queue a chunk of 24 kHz PCM16 audio from the model. */
    public void play(byte[] pcm16) {
        if (running) playQueue.offer(pcm16);
    }

    /** The user barged in: drop whatever the AI was still going to say. */
    public void stopPlaybackNow() {
        playQueue.clear();
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.play();
            } catch (IllegalStateException ignored) { }
        }
    }

    public void setMicMuted(boolean muted) {
        micMuted = muted;
    }

    public void stop() {
        if (!running) return;
        running = false;
        playQueue.clear();
        try { if (micThread != null) micThread.join(500); } catch (InterruptedException ignored) { }
        if (playThread != null) playThread.interrupt();
        try { if (playThread != null) playThread.join(500); } catch (InterruptedException ignored) { }
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            recorder.release();
            recorder = null;
        }
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
        if (aec != null) { aec.release(); aec = null; }
        if (ns != null) { ns.release(); ns = null; }

        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.clearCommunicationDevice();
        } else {
            audioManager.setSpeakerphoneOn(false);
        }
        audioManager.setMode(previousMode);
    }

    /** Loudspeaker unless wired/Bluetooth headphones are connected (headphones = best quality). */
    private void routeToSpeakerIfNoHeadset() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            AudioDeviceInfo speaker = null;
            for (AudioDeviceInfo d : devices) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    audioManager.setCommunicationDevice(d);
                    return;
                }
                if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = d;
            }
            if (speaker != null) audioManager.setCommunicationDevice(speaker);
        } else {
            boolean headset = false;
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    headset = true;
                    break;
                }
            }
            audioManager.setSpeakerphoneOn(!headset);
        }
    }
}
