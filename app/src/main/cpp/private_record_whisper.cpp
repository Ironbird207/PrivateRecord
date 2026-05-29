#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <cstdint>
#include <stdexcept>

#include "whisper.h"

namespace {
struct WavData {
    int sample_rate;
    std::vector<float> samples;
};

uint32_t read_u32(const char *data) {
    return static_cast<uint8_t>(data[0]) |
           (static_cast<uint8_t>(data[1]) << 8) |
           (static_cast<uint8_t>(data[2]) << 16) |
           (static_cast<uint8_t>(data[3]) << 24);
}

uint16_t read_u16(const char *data) {
    return static_cast<uint8_t>(data[0]) | (static_cast<uint8_t>(data[1]) << 8);
}

WavData read_wav(const std::string &path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("Unable to open WAV recording");
    }

    char riff[12];
    input.read(riff, sizeof(riff));
    if (input.gcount() != sizeof(riff) || std::string(riff, 4) != "RIFF" || std::string(riff + 8, 4) != "WAVE") {
        throw std::runtime_error("Recording is not a WAV file");
    }

    int channels = 0;
    int sample_rate = 0;
    int bits_per_sample = 0;
    std::vector<int16_t> pcm;

    while (input) {
        char chunk_header[8];
        input.read(chunk_header, sizeof(chunk_header));
        if (input.gcount() != sizeof(chunk_header)) break;
        const std::string chunk_id(chunk_header, 4);
        const uint32_t chunk_size = read_u32(chunk_header + 4);
        if (chunk_id == "fmt ") {
            std::vector<char> fmt(chunk_size);
            input.read(fmt.data(), chunk_size);
            if (chunk_size < 16) {
                throw std::runtime_error("Invalid WAV format chunk");
            }
            const int audio_format = read_u16(fmt.data());
            channels = read_u16(fmt.data() + 2);
            sample_rate = static_cast<int>(read_u32(fmt.data() + 4));
            bits_per_sample = read_u16(fmt.data() + 14);
            if (audio_format != 1 || channels != 1 || bits_per_sample != 16) {
                throw std::runtime_error("Whisper expects mono 16-bit PCM WAV audio");
            }
        } else if (chunk_id == "data") {
            std::vector<char> data(chunk_size);
            input.read(data.data(), chunk_size);
            const size_t sample_count = chunk_size / sizeof(int16_t);
            pcm.resize(sample_count);
            for (size_t i = 0; i < sample_count; ++i) {
                pcm[i] = static_cast<int16_t>(static_cast<uint8_t>(data[i * 2]) |
                        (static_cast<uint8_t>(data[i * 2 + 1]) << 8));
            }
        } else {
            input.seekg(chunk_size, std::ios::cur);
        }
        if (chunk_size % 2 == 1) {
            input.seekg(1, std::ios::cur);
        }
    }

    if (sample_rate != 16000) {
        throw std::runtime_error("Whisper expects 16 kHz WAV audio");
    }
    if (pcm.empty()) {
        throw std::runtime_error("Recording contains no audio samples");
    }

    WavData wav{sample_rate, {}};
    wav.samples.reserve(pcm.size());
    for (int16_t sample : pcm) {
        wav.samples.push_back(static_cast<float>(sample) / 32768.0f);
    }
    return wav;
}

std::string jstring_to_string(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_privaterecord_WhisperTranscriber_transcribe(JNIEnv *env, jobject, jstring model_path, jstring wav_path) {
    try {
        const std::string model = jstring_to_string(env, model_path);
        const std::string wav = jstring_to_string(env, wav_path);
        WavData wav_data = read_wav(wav);

        whisper_context_params context_params = whisper_context_default_params();
        whisper_context *context = whisper_init_from_file_with_params(model.c_str(), context_params);
        if (context == nullptr) {
            throw std::runtime_error("Unable to load Whisper model");
        }

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.language = "en";
        params.n_threads = 4;

        const int result = whisper_full(context, params, wav_data.samples.data(), static_cast<int>(wav_data.samples.size()));
        if (result != 0) {
            whisper_free(context);
            throw std::runtime_error("Whisper transcription failed");
        }

        std::string transcript;
        const int segments = whisper_full_n_segments(context);
        for (int i = 0; i < segments; ++i) {
            if (!transcript.empty()) transcript += " ";
            transcript += whisper_full_get_segment_text(context, i);
        }
        whisper_free(context);
        return env->NewStringUTF(transcript.c_str());
    } catch (const std::exception &exception) {
        return env->NewStringUTF(exception.what());
    }
}
