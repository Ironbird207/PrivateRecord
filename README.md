# PrivateRecord

PrivateRecord is a simple Android voice recorder focused on local storage and offline transcription.

## Features

- Record 16 kHz mono WAV voice notes to the app's private external files directory.
- Transcribe saved recordings fully offline with [`whisper.cpp`](https://github.com/ggml-org/whisper.cpp) through a small JNI bridge.
- Store recording metadata and transcripts in an on-device SQLite database.
- Search recordings by name or transcript text.
- Play, re-transcribe, rename, and delete saved recordings.
- Light and dark color resources for a dark-mode-friendly interface.

## Whisper model setup

The app intentionally does **not** commit a Whisper model binary because even the tiny English model is large. To enable transcription:

```bash
mkdir -p app/src/main/assets/models
curl -L \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin \
  -o app/src/main/assets/models/ggml-tiny.en.bin
```

After rebuilding and reinstalling the app, new recordings are transcribed offline after you tap **Stop and transcribe**. Existing WAV recordings can be transcribed again with the **Transcribe** button.

## Build

```bash
gradle :app:assembleDebug
```

The build requires the Android SDK, CMake/NDK, access to Google's Maven repository for the Android Gradle Plugin, and network access for CMake to fetch the pinned `whisper.cpp` source (`v1.8.4`) unless it is already cached.
