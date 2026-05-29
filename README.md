# PrivateRecord

PrivateRecord is a simple Android voice recorder focused on local storage and offline-first transcription.

## Features

- Record AAC/M4A voice notes to the app's private external files directory.
- Capture a transcript while recording with Android's built-in `SpeechRecognizer` using `EXTRA_PREFER_OFFLINE`.
- Store recording metadata and transcripts in an on-device SQLite database.
- Search recordings by name or transcript text.
- Play, rename, and delete saved recordings.
- Light and dark color resources for a dark-mode-friendly interface.

## Offline transcription note

Android does not expose a stable public API for transcribing an arbitrary audio file with the platform speech recognizer. PrivateRecord therefore transcribes while the recording is being made, requesting offline recognition from the device speech service. Transcription remains device-dependent: users need an installed/enabled Android speech recognizer with offline language data. Audio recording, playback, metadata storage, and search all work offline.

## Build

```bash
gradle :app:assembleDebug
```

The build requires the Android SDK and access to the Android Gradle Plugin from Google's Maven repository.
