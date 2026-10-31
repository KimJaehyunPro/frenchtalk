package com.jason.frenchtalk;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements LiveSession.Listener {

    private static final int REQ_MIC = 42;
    private static final String DEFAULT_MODEL = "gemini-3.8-live";

    private static final int C_BG = Color.parseColor("#F6F4EF");
    private static final int C_BLUE = Color.parseColor("#1F3A93");
    private static final int C_RED = Color.parseColor("#C0392B");
    private static final int C_TEXT = Color.parseColor("#1E1E1E");
    private static final int C_MUTED = Color.parseColor("#6B6B6B");

    private SharedPreferences prefs;
    private EditText apiKeyField, modelField, typeField;
    private Spinner scenarioSpin, levelSpin, modeSpin, voiceSpin;
    private LinearLayout settingsPanel;
    private TextView status, transcriptView;
    private ScrollView transcriptScroll;
    private Button talkButton, muteButton, settingsToggle;

    private LiveSession session;
    private AudioIO audio;
    private boolean active;
    private boolean muted;

    /** One line of the transcript. */
    private static final class Line {
        final boolean user;
        final StringBuilder text = new StringBuilder();
        Line(boolean user) { this.user = user; }
    }

    private final List<Line> transcript = new ArrayList<>();
    private boolean aiTurnClosed = true;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("frenchtalk", Context.MODE_PRIVATE);
        audio = new AudioIO(this);
        session = new LiveSession(this);
        setContentView(buildUi());
        boolean hasKey = !prefs.getString("apiKey", "").isEmpty();
        settingsPanel.setVisibility(hasKey ? View.GONE : View.VISIBLE);
        settingsToggle.setText(hasKey ? "Réglages ▾" : "Réglages ▴");
        setStatus(hasKey ? "Prêt. Appuie sur « Parler »." : "Colle ta clé API Gemini (gratuite) pour commencer.");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        LinearLayout header = row();
        TextView title = new TextView(this);
        title.setText("FrenchTalk");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_BLUE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        settingsToggle = smallButton("Réglages ▾");
        settingsToggle.setOnClickListener(v -> {
            boolean show = settingsPanel.getVisibility() != View.VISIBLE;
            settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            settingsToggle.setText(show ? "Réglages ▴" : "Réglages ▾");
        });
        header.addView(settingsToggle);
        root.addView(header);

        // ---- Settings ----
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(0, dp(8), 0, dp(4));

        apiKeyField = field("Gemini API key (aistudio.google.com/apikey)", prefs.getString("apiKey", ""));
        apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        settingsPanel.addView(label("Clé API"));
        settingsPanel.addView(apiKeyField);

        modelField = field("model", prefs.getString("model", DEFAULT_MODEL));
        settingsPanel.addView(label("Modèle Live"));
        settingsPanel.addView(modelField);

        settingsPanel.addView(label("Voix"));
        voiceSpin = spinner(Prompts.VOICES, prefs.getInt("voice", 1));
        settingsPanel.addView(voiceSpin);
        root.addView(settingsPanel);

        // ---- Session options (always visible) ----
        root.addView(label("Situation"));
        scenarioSpin = spinner(Prompts.SCENARIO_NAMES, prefs.getInt("scenario", 0));
        root.addView(scenarioSpin);

        LinearLayout opts = row();
        LinearLayout lvlCol = col();
        lvlCol.addView(label("Niveau"));
        levelSpin = spinner(Prompts.LEVELS, prefs.getInt("level", 2));
        lvlCol.addView(levelSpin);
        LinearLayout modeCol = col();
        modeCol.addView(label("Mode"));
        modeSpin = spinner(Prompts.MODES, prefs.getInt("mode", 0));
        modeCol.addView(modeSpin);
        opts.addView(lvlCol, new LinearLayout.LayoutParams(0, -2, 1));
        opts.addView(modeCol, new LinearLayout.LayoutParams(0, -2, 2));
        root.addView(opts);

        status = new TextView(this);
        status.setTextColor(C_MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status);

        // ---- Transcript ----
        transcriptScroll = new ScrollView(this);
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(14));
        transcriptScroll.setBackground(card);
        transcriptScroll.setPadding(dp(14), dp(12), dp(14), dp(12));
        transcriptView = new TextView(this);
        transcriptView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        transcriptView.setTextColor(C_TEXT);
        transcriptView.setLineSpacing(dp(3), 1f);
        transcriptView.setTextIsSelectable(true);
        transcriptView.setHint("La transcription s'affiche ici.\n\nAstuce : avec des écouteurs, "
                + "la conversation est plus fluide et tu peux couper la parole à l'IA.");
        transcriptScroll.addView(transcriptView);
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Type a word / line ----
        LinearLayout typeRow = row();
        typeRow.setPadding(0, dp(8), 0, 0);
        typeField = field("Écrire (si un mot ne passe pas à l'oral)…", "");
        typeField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typeField.setSingleLine(true);
        typeField.setOnEditorActionListener((v, actionId, e) -> { sendTyped(); return true; });
        typeRow.addView(typeField, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = smallButton("Envoyer");
        send.setOnClickListener(v -> sendTyped());
        typeRow.addView(send);
        root.addView(typeRow);

        // ---- Controls ----
        LinearLayout controls = row();
        controls.setPadding(0, dp(10), 0, 0);
        talkButton = new Button(this);
        talkButton.setText("Parler");
        talkButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        talkButton.setTextColor(Color.WHITE);
        talkButton.setAllCaps(false);
        styleTalkButton(false);
        talkButton.setOnClickListener(v -> { if (active) stopConversation(); else startConversation(); });
        controls.addView(talkButton, new LinearLayout.LayoutParams(0, dp(56), 1));

        muteButton = smallButton("Micro");
        muteButton.setEnabled(false);
        muteButton.setOnClickListener(v -> {
            muted = !muted;
            audio.setMicMuted(muted);
            muteButton.setText(muted ? "Micro coupé" : "Micro");
        });
        controls.addView(muteButton);
        Button copy = smallButton("Copier");
        copy.setOnClickListener(v -> copyTranscript());
        controls.addView(copy);
        root.addView(controls);

        return root;
    }

    // ------------------------------------------------------------ session

    private void startConversation() {
        String key = apiKeyField.getText().toString().trim();
        if (key.isEmpty()) {
            settingsPanel.setVisibility(View.VISIBLE);
            toast("Ajoute d'abord ta clé API Gemini.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        String model = modelField.getText().toString().trim();
        if (model.isEmpty()) model = DEFAULT_MODEL;

        prefs.edit()
                .putString("apiKey", key)
                .putString("model", model)
                .putInt("scenario", scenarioSpin.getSelectedItemPosition())
                .putInt("level", levelSpin.getSelectedItemPosition())
                .putInt("mode", modeSpin.getSelectedItemPosition())
                .putInt("voice", voiceSpin.getSelectedItemPosition())
                .apply();

        String prompt = Prompts.buildSystemPrompt(scenarioSpin.getSelectedItemPosition(),
                levelSpin.getSelectedItemPosition(), modeSpin.getSelectedItemPosition());

        transcript.clear();
        aiTurnClosed = true;
        renderTranscript();
        settingsPanel.setVisibility(View.GONE);
        settingsToggle.setText("Réglages ▾");
        setOptionsEnabled(false);

        active = true;
        muted = false;
        styleTalkButton(true);
        talkButton.setText("Terminer");
        setStatus("Connexion…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        session.connect(key, model, Prompts.VOICES[voiceSpin.getSelectedItemPosition()], prompt);
    }

    private void stopConversation() {
        active = false;
        session.close();
        audio.stop();
        styleTalkButton(false);
        talkButton.setText("Parler");
        muteButton.setEnabled(false);
        muteButton.setText("Micro");
        setOptionsEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus("Conversation terminée. « Copier » pour garder la transcription.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startConversation();
            } else {
                toast("Le micro est nécessaire pour parler.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (active) {
            session.close();
            audio.stop();
        }
        super.onDestroy();
    }

    private void sendTyped() {
        String t = typeField.getText().toString().trim();
        if (t.isEmpty() || !active) return;
        session.sendText(t);
        typeField.setText("");
        appendLine(true, t + " ⌨");
        aiTurnClosed = true;
    }

    // ------------------------------------------------- LiveSession.Listener
    // (called on OkHttp's background thread)

    @Override
    public void onReady() {
        runOnUiThread(() -> {
            if (!active) return;
            audio.start(pcm -> session.sendAudio(pcm));
            muteButton.setEnabled(true);
            setStatus("En ligne — parle quand tu veux. Tu peux l'interrompre.");
        });
    }

    @Override
    public void onAudio(byte[] pcm24k) {
        audio.play(pcm24k);
    }

    @Override
    public void onUserText(String chunk) {
        runOnUiThread(() -> {
            appendChunk(true, chunk);
            aiTurnClosed = true; // the next AI words start a fresh line
        });
    }

    @Override
    public void onAiText(String chunk) {
        runOnUiThread(() -> {
            if (aiTurnClosed) {
                transcript.add(new Line(false));
                aiTurnClosed = false;
            }
            appendChunk(false, chunk);
        });
    }

    @Override
    public void onInterrupted() {
        audio.stopPlaybackNow();
        runOnUiThread(() -> aiTurnClosed = true);
    }

    @Override
    public void onTurnComplete() {
        runOnUiThread(() -> aiTurnClosed = true);
    }

    @Override
    public void onInfo(String message) {
        runOnUiThread(() -> setStatus(message));
    }

    @Override
    public void onClosed(String reason) {
        runOnUiThread(() -> {
            if (active) stopConversation();
            setStatus(explain(reason));
        });
    }

    private String explain(String reason) {
        String r = reason == null ? "" : reason;
        String low = r.toLowerCase();
        if (low.contains("api key") || low.contains("403") || low.contains("permission")) {
            return "Clé API refusée : " + r;
        }
        if (low.contains("quota") || low.contains("429") || low.contains("resource_exhausted")) {
            return "Limite gratuite atteinte pour l'instant : " + r;
        }
        if (low.contains("not found") || low.contains("not supported") || low.contains("404")) {
            return "Modèle introuvable — vérifie le nom dans Réglages (ex. gemini-3.1-flash-live-preview). " + r;
        }
        return r;
    }

    // ------------------------------------------------------------ transcript

    private void appendChunk(boolean user, String chunk) {
        Line last = transcript.isEmpty() ? null : transcript.get(transcript.size() - 1);
        if (last == null || last.user != user) {
            last = new Line(user);
            transcript.add(last);
        }
        last.text.append(chunk);
        renderTranscript();
    }

    private void appendLine(boolean user, String text) {
        Line l = new Line(user);
        l.text.append(text);
        transcript.add(l);
        renderTranscript();
    }

    private void renderTranscript() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Line l : transcript) {
            String body = l.text.toString().trim();
            if (body.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            int start = sb.length();
            sb.append(l.user ? "Toi  " : "Elle/Lui  ");
            int end = sb.length();
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(l.user ? C_RED : C_BLUE), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(body);
        }
        transcriptView.setText(sb);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyTranscript() {
        if (transcriptView.getText().length() == 0) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("FrenchTalk", transcriptView.getText().toString()));
        toast("Transcription copiée.");
    }

    // --------------------------------------------------------------- helpers

    private void setStatus(String s) { status.setText(s); }

    private void setOptionsEnabled(boolean e) {
        scenarioSpin.setEnabled(e);
        levelSpin.setEnabled(e);
        modeSpin.setEnabled(e);
        voiceSpin.setEnabled(e);
        apiKeyField.setEnabled(e);
        modelField.setEnabled(e);
    }

    private void styleTalkButton(boolean live) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(live ? C_RED : C_BLUE);
        bg.setCornerRadius(dp(28));
        talkButton.setBackground(bg);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(C_MUTED);
        t.setPadding(dp(4), dp(8), 0, 0);
        return t;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        return e;
    }

    private Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(a);
        if (selected >= 0 && selected < items.length) s.setSelection(selected);
        return s;
    }

    private Button smallButton(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(C_BLUE);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
