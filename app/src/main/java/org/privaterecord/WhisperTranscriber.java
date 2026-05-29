package org.privaterecord;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

class WhisperTranscriber {
    private static final String MODEL_ASSET_PATH = "models/ggml-tiny.en.bin";

    static {
        System.loadLibrary("private_record_whisper");
    }

    private final Context context;
    private File modelFile;

    WhisperTranscriber(Context context) {
        this.context = context.getApplicationContext();
        this.modelFile = prepareModel();
    }

    boolean isModelReady() {
        return modelFile != null && modelFile.exists() && modelFile.length() > 0;
    }

    String transcribe(File wavFile) {
        if (!isModelReady()) {
            return "";
        }
        return transcribe(modelFile.getAbsolutePath(), wavFile.getAbsolutePath());
    }

    String missingModelMessage() {
        return "Whisper model missing. Download ggml-tiny.en.bin and place it at app/src/main/assets/" + MODEL_ASSET_PATH + " before building.";
    }

    private File prepareModel() {
        File target = new File(new File(context.getFilesDir(), "models"), "ggml-tiny.en.bin");
        if (target.exists() && target.length() > 0) {
            return target;
        }
        if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) {
            return null;
        }
        try (InputStream input = context.getAssets().open(MODEL_ASSET_PATH);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return target;
        } catch (IOException exception) {
            target.delete();
            return null;
        }
    }

    private native String transcribe(String modelPath, String wavPath);
}
