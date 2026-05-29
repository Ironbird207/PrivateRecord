package org.privaterecord;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 8;
    private static final DateFormat FILE_DATE = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private RecorderDb db;
    private MediaRecorder recorder;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private MediaPlayer player;
    private long startedAt;
    private File activeFile;
    private boolean recording;
    private boolean speechActive;
    private final StringBuilder transcript = new StringBuilder();

    private LinearLayout root;
    private TextView status;
    private Button recordButton;
    private EditText searchBox;
    private LinearLayout list;

    private int background;
    private int surface;
    private int primary;
    private int onPrimary;
    private int textPrimary;
    private int textSecondary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new RecorderDb(this);
        loadColors();
        buildUi();
        configureSpeechRecognizer();
        renderRecordings();
    }

    private void loadColors() {
        background = getColor(R.color.background);
        surface = getColor(R.color.surface);
        primary = getColor(R.color.primary);
        onPrimary = getColor(R.color.primary_on);
        textPrimary = getColor(R.color.text_primary);
        textSecondary = getColor(R.color.text_secondary);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));
        root.setBackgroundColor(background);

        TextView title = text("PrivateRecord", 28, true);
        TextView subtitle = text("Offline-first voice notes with private, on-device speech recognition when your Android speech service supports it.", 14, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));

        status = text("Ready to record", 16, false);
        status.setPadding(0, 0, 0, dp(12));

        recordButton = new Button(this);
        recordButton.setText("Start recording");
        recordButton.setTextColor(onPrimary);
        recordButton.setBackgroundColor(primary);
        recordButton.setOnClickListener(view -> toggleRecording());

        searchBox = new EditText(this);
        searchBox.setHint("Search names and transcripts");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(textPrimary);
        searchBox.setHintTextColor(textSecondary);
        searchBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderRecordings(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(list);

        root.addView(title);
        root.addView(subtitle);
        root.addView(status);
        root.addView(recordButton, matchWrap());
        root.addView(searchBox, matchWrapWithMargins(0, dp(16), 0, dp(12)));
        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void configureSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Recording works offline. No on-device speech recognizer is installed for transcription.");
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { speechActive = true; }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { speechActive = false; }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onPartialResults(Bundle partialResults) {
                showPartial(partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
            }

            @Override
            public void onResults(Bundle results) {
                appendSpeech(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
                restartSpeechIfRecording();
            }

            @Override
            public void onError(int error) {
                speechActive = false;
                if (recording && (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)) {
                    status.setText("Recording audio. Offline transcription is unavailable on this device.");
                }
                restartSpeechIfRecording();
            }
        });
    }

    private void toggleRecording() {
        if (recording) {
            stopRecording();
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            Toast.makeText(this, "Microphone permission is required to record.", Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording() {
        File directory = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "Could not create the recordings folder.", Toast.LENGTH_LONG).show();
            return;
        }
        activeFile = new File(directory, "PrivateRecord-" + FILE_DATE.format(new Date()) + ".m4a");
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(activeFile.getAbsolutePath());
        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException exception) {
            recorder.release();
            recorder = null;
            Toast.makeText(this, "Unable to start recording: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        startedAt = System.currentTimeMillis();
        recording = true;
        transcript.setLength(0);
        recordButton.setText("Stop and save");
        status.setText("Recording audio and listening for offline transcription...");
        startSpeech();
    }

    private void stopRecording() {
        recording = false;
        stopSpeech();
        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
            if (activeFile != null) activeFile.delete();
        } finally {
            recorder.release();
            recorder = null;
        }
        long duration = Math.max(0, System.currentTimeMillis() - startedAt);
        String name = "Recording " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(startedAt));
        saveRecording(name, activeFile.getAbsolutePath(), startedAt, duration, transcript.toString().trim());
        recordButton.setText("Start recording");
        status.setText("Saved " + name);
        renderRecordings();
    }

    private void startSpeech() {
        if (speechRecognizer == null || speechIntent == null || speechActive) return;
        try {
            speechRecognizer.startListening(speechIntent);
            speechActive = true;
        } catch (RuntimeException ignored) {
            speechActive = false;
        }
    }

    private void stopSpeech() {
        if (speechRecognizer == null) return;
        try {
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
        } catch (RuntimeException ignored) {
        }
        speechActive = false;
    }

    private void restartSpeechIfRecording() {
        if (!recording || speechRecognizer == null) return;
        status.postDelayed(() -> {
            if (recording) startSpeech();
        }, 350);
    }

    private void showPartial(ArrayList<String> matches) {
        if (!recording || matches == null || matches.isEmpty()) return;
        String base = transcript.toString().trim();
        String partial = matches.get(0);
        status.setText("Recording... " + (base.isEmpty() ? partial : base + " " + partial));
    }

    private void appendSpeech(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return;
        if (transcript.length() > 0) transcript.append(' ');
        transcript.append(matches.get(0).trim());
        status.setText("Recording... " + transcript);
    }

    private void saveRecording(String name, String path, long createdAt, long durationMs, String text) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("file_path", path);
        values.put("created_at", createdAt);
        values.put("duration_ms", durationMs);
        values.put("transcript", text);
        db.getWritableDatabase().insert("recordings", null, values);
    }

    private void renderRecordings() {
        if (list == null) return;
        list.removeAllViews();
        List<Recording> recordings = loadRecordings(searchBox == null ? "" : searchBox.getText().toString());
        if (recordings.isEmpty()) {
            TextView empty = text("No recordings found. Tap Start recording to create your first private voice note.", 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, 0);
            list.addView(empty);
            return;
        }
        for (Recording recording : recordings) {
            list.addView(recordingCard(recording), matchWrapWithMargins(0, 0, 0, dp(12)));
        }
    }

    private List<Recording> loadRecordings(String query) {
        List<Recording> recordings = new ArrayList<>();
        SQLiteDatabase database = db.getReadableDatabase();
        String selection = null;
        String[] args = null;
        if (query != null && !query.trim().isEmpty()) {
            selection = "name LIKE ? OR transcript LIKE ?";
            String like = "%" + query.trim() + "%";
            args = new String[]{like, like};
        }
        try (Cursor cursor = database.query("recordings", null, selection, args, null, null, "created_at DESC")) {
            while (cursor.moveToNext()) {
                recordings.add(new Recording(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                        cursor.getString(cursor.getColumnIndexOrThrow("transcript"))));
            }
        }
        return recordings;
    }

    private View recordingCard(Recording recording) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(surface);

        TextView name = text(recording.name, 18, true);
        TextView details = text(formatDate(recording.createdAt) + " • " + formatDuration(recording.durationMs), 13, false);
        String transcriptText = recording.transcript == null || recording.transcript.isEmpty()
                ? "No transcript captured. Install or enable an offline Android speech recognizer, then record again."
                : recording.transcript;
        TextView body = text(transcriptText, 15, false);
        body.setPadding(0, dp(8), 0, dp(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button play = smallButton("Play");
        Button rename = smallButton("Rename");
        Button delete = smallButton("Delete");
        play.setOnClickListener(v -> play(recording));
        rename.setOnClickListener(v -> rename(recording));
        delete.setOnClickListener(v -> delete(recording));
        actions.addView(play, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(rename, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(delete, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        card.addView(name);
        card.addView(details);
        card.addView(body);
        card.addView(actions);
        return card;
    }

    private void play(Recording recording) {
        stopPlayback();
        player = new MediaPlayer();
        try {
            player.setDataSource(recording.filePath);
            player.prepare();
            player.start();
            player.setOnCompletionListener(mp -> stopPlayback());
            status.setText("Playing " + recording.name);
        } catch (IOException | RuntimeException exception) {
            stopPlayback();
            Toast.makeText(this, "Unable to play recording.", Toast.LENGTH_LONG).show();
        }
    }

    private void rename(Recording recording) {
        EditText input = new EditText(this);
        input.setText(recording.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename recording")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    ContentValues values = new ContentValues();
                    values.put("name", input.getText().toString().trim());
                    db.getWritableDatabase().update("recordings", values, "id = ?", new String[]{String.valueOf(recording.id)});
                    renderRecordings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete(Recording recording) {
        new AlertDialog.Builder(this)
                .setTitle("Delete recording?")
                .setMessage("This removes the audio and transcript from this device.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    db.getWritableDatabase().delete("recordings", "id = ?", new String[]{String.valueOf(recording.id)});
                    new File(recording.filePath).delete();
                    renderRecordings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(bold ? textPrimary : textSecondary);
        if (bold) textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        return textView;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(onPrimary);
        button.setBackgroundColor(primary);
        return button;
    }

    private String formatDate(long createdAt) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(createdAt));
    }

    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (recording) stopRecording();
        stopPlayback();
        if (speechRecognizer != null) speechRecognizer.destroy();
        db.close();
        super.onDestroy();
    }

    private static class Recording {
        final long id;
        final String name;
        final String filePath;
        final long createdAt;
        final long durationMs;
        final String transcript;

        Recording(long id, String name, String filePath, long createdAt, long durationMs, String transcript) {
            this.id = id;
            this.name = name;
            this.filePath = filePath;
            this.createdAt = createdAt;
            this.durationMs = durationMs;
            this.transcript = transcript == null ? "" : transcript;
        }
    }

    private static class RecorderDb extends SQLiteOpenHelper {
        RecorderDb(Activity context) {
            super(context, "private_record.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE recordings (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "file_path TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "duration_ms INTEGER NOT NULL," +
                    "transcript TEXT NOT NULL DEFAULT ''" +
                    ")");
            db.execSQL("CREATE INDEX idx_recordings_name ON recordings(name)");
            db.execSQL("CREATE INDEX idx_recordings_transcript ON recordings(transcript)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS recordings");
            onCreate(db);
        }
    }
}
