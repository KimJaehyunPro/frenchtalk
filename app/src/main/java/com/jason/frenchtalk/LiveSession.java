package com.jason.frenchtalk;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Minimal client for the Gemini Live API (bidirectional WebSocket, speech-to-speech).
 * Protocol: https://ai.google.dev/api/live
 */
public class LiveSession {

    public interface Listener {
        void onReady();
        void onAudio(byte[] pcm24k);
        void onUserText(String chunk);
        void onAiText(String chunk);
        void onInterrupted();
        void onTurnComplete();
        void onInfo(String message);
        void onClosed(String reason);
    }

    private static final String TAG = "LiveSession";
    private static final String WS_URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private final Listener listener;
    private WebSocket ws;
    private volatile boolean ready;
    private volatile boolean closedByUser;

    public LiveSession(Listener listener) {
        this.listener = listener;
    }

    public void connect(String apiKey, String model, String voice, String systemPrompt) {
        closedByUser = false;
        ready = false;
        Request req = new Request.Builder().url(WS_URL + apiKey.trim()).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try {
                    webSocket.send(buildSetup(model, voice, systemPrompt).toString());
                } catch (JSONException e) {
                    listener.onClosed("Setup error: " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handle(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                // The server sends JSON, sometimes framed as binary.
                handle(bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                ready = false;
                if (!closedByUser) {
                    listener.onClosed(reason == null || reason.isEmpty()
                            ? "Connection closed (code " + code + ")"
                            : reason + " (code " + code + ")");
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                ready = false;
                if (closedByUser) return;
                String msg = t.getMessage();
                if (response != null) msg = "HTTP " + response.code() + " " + response.message();
                listener.onClosed("Connection failed: " + msg);
            }
        });
    }

    private JSONObject buildSetup(String model, String voice, String systemPrompt) throws JSONException {
        JSONObject generationConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"));
        if (voice != null && !"default".equals(voice)) {
            generationConfig.put("speechConfig", new JSONObject()
                    .put("voiceConfig", new JSONObject()
                            .put("prebuiltVoiceConfig", new JSONObject()
                                    .put("voiceName", voice))));
        }

        JSONObject setup = new JSONObject()
                .put("model", "models/" + model.trim())
                .put("generationConfig", generationConfig)
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))))
                // Auto language detection so the transcript also catches English you slip in.
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject())
                // Lets a practice session run longer than the default audio-session limit.
                .put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
        return new JSONObject().put("setup", setup);
    }

    private void handle(String raw) {
        try {
            JSONObject msg = new JSONObject(raw);

            if (msg.has("setupComplete")) {
                ready = true;
                listener.onReady();
                // Ask the character to open the scene (the system prompt says to start).
                sendText("(La scène commence. Dis ta première réplique.)");
                return;
            }

            if (msg.has("goAway")) {
                listener.onInfo("Server will end this session soon — free-tier session limit.");
            }

            JSONObject sc = msg.optJSONObject("serverContent");
            if (sc == null) return;

            if (sc.optBoolean("interrupted", false)) listener.onInterrupted();

            JSONObject modelTurn = sc.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                        if (inline != null) {
                            String mime = inline.optString("mimeType", "");
                            if (mime.isEmpty() || mime.startsWith("audio/pcm")) {
                                listener.onAudio(Base64.decode(inline.getString("data"), Base64.DEFAULT));
                            }
                        }
                    }
                }
            }

            JSONObject in = sc.optJSONObject("inputTranscription");
            if (in != null && !in.optString("text").isEmpty()) listener.onUserText(in.optString("text"));

            JSONObject out = sc.optJSONObject("outputTranscription");
            if (out != null && !out.optString("text").isEmpty()) listener.onAiText(out.optString("text"));

            if (sc.optBoolean("turnComplete", false)) listener.onTurnComplete();
        } catch (JSONException e) {
            Log.w(TAG, "Bad server message: " + raw, e);
        }
    }

    /** Stream one chunk of 16 kHz PCM16 mic audio. */
    public void sendAudio(byte[] pcm16k) {
        if (!ready || ws == null) return;
        try {
            JSONObject m = new JSONObject().put("realtimeInput", new JSONObject()
                    .put("audio", new JSONObject()
                            .put("data", Base64.encodeToString(pcm16k, Base64.NO_WRAP))
                            .put("mimeType", "audio/pcm;rate=16000")));
            ws.send(m.toString());
        } catch (JSONException ignored) { }
    }

    /** Send typed text into the conversation (e.g. a word you can't pronounce). */
    public void sendText(String text) {
        if (!ready || ws == null) return;
        try {
            ws.send(new JSONObject().put("realtimeInput", new JSONObject().put("text", text)).toString());
        } catch (JSONException ignored) { }
    }

    public void close() {
        closedByUser = true;
        ready = false;
        if (ws != null) {
            ws.close(1000, "bye");
            ws = null;
        }
    }
}
