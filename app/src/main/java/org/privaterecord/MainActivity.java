package org.privaterecord;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 8;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final DateFormat FILE_DATE = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private RecorderDb db;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private MediaPlayer player;
    private WhisperTranscriber whisperTranscriber;
    private ExecutorService transcriptionExecutor;
    private long startedAt;
    private File activeFile;
    private volatile boolean recording;

    private LinearLayout root;
    private TextView status;
    private Button recordButton;
    private EditText searchBox;
    private LinearLayout list;
    private ProgressBar progressBar;

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
        transcriptionExecutor = Executors.newSingleThreadExecutor();
        whisperTranscriber = new WhisperTranscriber(this);
        loadColors();
        buildUi();
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
        TextView subtitle = text("Offline voice notes transcribed locally with whisper.cpp when a Whisper model is installed.", 14, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));

        status = text(initialStatus(), 16, false);
        status.setPadding(0, 0, 0, dp(12));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);

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
        searchBox.addTextChangedListener(new SimpleTextWatcher(this::renderRecordings));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(list);

        root.addView(title);
        root.addView(subtitle);
        root.addView(status);
        root.addView(progressBar, matchWrap());
        root.addView(recordButton, matchWrap());
        root.addView(searchBox, matchWrapWithMargins(0, dp(16), 0, dp(12)));
        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private String initialStatus() {
        if (whisperTranscriber.isModelReady()) {
            return "Ready. Recordings are transcribed fully offline after you save them.";
        }
        return "Ready to record. Add a Whisper ggml model to enable offline transcription.";
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

    @SuppressLint("MissingPermission")
    private void startRecording() {
        File directory = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "Could not create the recordings folder.", Toast.LENGTH_LONG).show();
            return;
        }
        activeFile = new File(directory, "PrivateRecord-" + FILE_DATE.format(new Date()) + ".wav");
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE * 2);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            Toast.makeText(this, "Unable to initialize the microphone.", Toast.LENGTH_LONG).show();
            return;
        }
        startedAt = System.currentTimeMillis();
        recording = true;
        recordButton.setText("Stop and transcribe");
        status.setText("Recording 16 kHz WAV audio for Whisper...");
        audioRecord.startRecording();
        recordingThread = new Thread(() -> writeWavRecording(activeFile, bufferSize), "wav-recorder");
        recordingThread.start();
    }

    private void writeWavRecording(File file, int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        long audioLength = 0;
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(0);
            writeWavHeader(output, 0);
            while (recording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    output.write(buffer, 0, read);
                    audioLength += read;
                }
            }
            output.seek(0);
            writeWavHeader(output, audioLength);
        } catch (IOException exception) {
            runOnUiThread(() -> Toast.makeText(this, "Unable to save recording: " + exception.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void stopRecording() {
        recording = false;
        try {
            audioRecord.stop();
        } catch (RuntimeException ignored) {
        }
        if (recordingThread != null) {
            try {
                recordingThread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        audioRecord.release();
        audioRecord = null;

        long duration = Math.max(0, System.currentTimeMillis() - startedAt);
        String name = "Recording " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(startedAt));
        long recordingId = saveRecording(name, activeFile.getAbsolutePath(), startedAt, duration, "");
        recordButton.setText("Start recording");
        status.setText("Saved " + name + ". Transcribing offline...");
        progressBar.setVisibility(View.VISIBLE);
        renderRecordings();
        transcribeRecording(recordingId, activeFile);
    }

    private void transcribeRecording(long recordingId, File wavFile) {
        transcriptionExecutor.execute(() -> {
            String transcript;
            try {
                transcript = whisperTranscriber.transcribe(wavFile);
            } catch (RuntimeException exception) {
                transcript = "Transcription failed: " + exception.getMessage();
            }
            String finalTranscript = transcript == null ? "" : transcript.trim();
            ContentValues values = new ContentValues();
            values.put("transcript", finalTranscript);
            db.getWritableDatabase().update("recordings", values, "id = ?", new String[]{String.valueOf(recordingId)});
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                status.setText(finalTranscript.isEmpty() ? whisperTranscriber.missingModelMessage() : "Transcription complete.");
                renderRecordings();
            });
        });
    }

    private long saveRecording(String name, String path, long createdAt, long durationMs, String transcript) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("file_path", path);
        values.put("created_at", createdAt);
        values.put("duration_ms", durationMs);
        values.put("transcript", transcript);
        return db.getWritableDatabase().insert("recordings", null, values);
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
                ? "Waiting for a Whisper transcript. Add app/src/main/assets/models/ggml-tiny.en.bin before building if this stays empty."
                : recording.transcript;
        TextView body = text(transcriptText, 15, false);
        body.setPadding(0, dp(8), 0, dp(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button play = smallButton("Play");
        Button transcribe = smallButton("Transcribe");
        Button rename = smallButton("Rename");
        Button delete = smallButton("Delete");
        play.setOnClickListener(v -> play(recording));
        transcribe.setOnClickListener(v -> {
            progressBar.setVisibility(View.VISIBLE);
            status.setText("Transcribing offline...");
            transcribeRecording(recording.id, new File(recording.filePath));
        });
        rename.setOnClickListener(v -> rename(recording));
        delete.setOnClickListener(v -> delete(recording));
        actions.addView(play, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(transcribe, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
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

    private void writeWavHeader(RandomAccessFile output, long audioLength) throws IOException {
        long totalLength = audioLength + 36;
        long byteRate = SAMPLE_RATE * 2L;
        output.writeBytes("RIFF");
        writeInt(output, totalLength);
        output.writeBytes("WAVE");
        output.writeBytes("fmt ");
        writeInt(output, 16);
        writeShort(output, 1);
        writeShort(output, 1);
        writeInt(output, SAMPLE_RATE);
        writeInt(output, byteRate);
        writeShort(output, 2);
        writeShort(output, 16);
        output.writeBytes("data");
        writeInt(output, audioLength);
    }

    private void writeInt(RandomAccessFile output, long value) throws IOException {
        output.write((int) (value & 0xff));
        output.write((int) ((value >> 8) & 0xff));
        output.write((int) ((value >> 16) & 0xff));
        output.write((int) ((value >> 24) & 0xff));
    }

    private void writeShort(RandomAccessFile output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    @Override
    protected void onDestroy() {
        if (recording) stopRecording();
        stopPlayback();
        transcriptionExecutor.shutdownNow();
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
