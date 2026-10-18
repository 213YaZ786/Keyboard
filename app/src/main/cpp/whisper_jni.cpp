// The bridge between the app and whisper.cpp (MIT): open a model, write out sound (16 kHz mono floats), and Silero's
// voice detector that ships with it. Nothing is logged: the engines' own logs are turned off.
//
// Sources:
// - the bridge (open, transcribe, close) is SMS's own, built on whisper.cpp's include/whisper.h
//   https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/include/whisper.h
// - the voice detector calls are whisper.cpp's: whisper_vad_default_context_params, whisper_vad_init_from_file_with_params,
//   whisper_vad_detect_speech_no_reset, whisper_vad_n_probs, whisper_vad_probs, whisper_vad_reset_state, whisper_vad_free
//   (same header, "Voice Activity Detection"); no_reset is the call made for streaming, as its comment says
// - the live settings (suppress_nst, no timestamps) follow examples/stream/stream.cpp in its VAD mode
//   https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/stream/stream.cpp
// - the abort callback is whisper_full_params.abort_callback of whisper.h
// - the methods are registered by name in JNI_OnLoad, so this file belongs to no package
//   (https://developer.android.com/training/articles/perf-jni#native-libraries, RegisterNatives)
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"
#include "ggml-backend.h"

namespace {

std::atomic<bool> g_abort{false};

bool abort_requested(void *) { return g_abort.load(); }

void silent(enum ggml_log_level, const char *, void *) {}

// The CPU variants live with the app's own libraries; the best one for this phone is taken, once.
void ensure_backends(JNIEnv *env, jstring lib_dir) {
    static bool loaded = false;
    if (loaded) return;
    whisper_log_set(silent, nullptr);
    ggml_log_set(silent, nullptr);
    const char *dir = env->GetStringUTFChars(lib_dir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(lib_dir, dir);
    loaded = true;
}

jlong open_model(JNIEnv *env, jclass, jstring lib_dir, jstring model) {
    ensure_backends(env, lib_dir);
    const char *path = env->GetStringUTFChars(model, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(ctx);
}

jbyteArray transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint threads, jstring language, jboolean live) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // The language asked for when whisper knows it, else found from the sound itself; never translated.
    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string code = lang;
    env->ReleaseStringUTFChars(language, lang);
    if (code != "auto" && whisper_lang_id(code.c_str()) < 0) code = "auto";
    params.language = code.c_str();
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    // Dictation: no sound effects written as words ([MUSIC]), and the work stops when asked.
    params.suppress_nst = live == JNI_TRUE;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = nullptr;
    params.n_threads = threads;
    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    int failed = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (failed != 0) return nullptr;
    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    // Plain UTF-8 bytes: Java's own string maker refuses some of it (emoji).
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

void close_model(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

void abort_work(JNIEnv *, jclass, jboolean on) { g_abort.store(on == JNI_TRUE); }

jboolean knows_language(JNIEnv *env, jclass, jstring language) {
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jboolean known = whisper_lang_id(lang) >= 0 ? JNI_TRUE : JNI_FALSE;
    env->ReleaseStringUTFChars(language, lang);
    return known;
}

jlong vad_open(JNIEnv *env, jclass, jstring lib_dir, jstring model) {
    ensure_backends(env, lib_dir);
    const char *path = env->GetStringUTFChars(model, nullptr);
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.use_gpu = false;
    params.n_threads = 1; // 32 ms windows of a small network: one thread, the least power
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    // whisper_vad_detect_speech clears the network's memory before each sound, but the streaming call
    // (no_reset) does not: a new context starts from zero as whisper.h says ("Reset LSTM hidden/cell states to zero")
    if (vctx != nullptr) whisper_vad_reset_state(vctx);
    return reinterpret_cast<jlong>(vctx);
}

// The probability of speech in each window of the sound, the network keeping its memory from one call to the next.
jfloatArray vad_probs(JNIEnv *env, jclass, jlong handle, jfloatArray pcm) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx == nullptr) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    bool ok = whisper_vad_detect_speech_no_reset(vctx, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (!ok) return nullptr;
    const int count = whisper_vad_n_probs(vctx);
    jfloatArray out = env->NewFloatArray(count);
    env->SetFloatArrayRegion(out, 0, count, whisper_vad_probs(vctx));
    return out;
}

void vad_reset(JNIEnv *, jclass, jlong handle) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx != nullptr) whisper_vad_reset_state(vctx);
}

void vad_close(JNIEnv *, jclass, jlong handle) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx != nullptr) whisper_vad_free(vctx);
}

// The Kotlin object these methods belong to: the app's package, then core.voice.Whisper.
const char kClass[] = "helium314.keyboard.latin.core.voice.Whisper";

const JNINativeMethod kMethods[] = {
    {"open", "(Ljava/lang/String;Ljava/lang/String;)J", reinterpret_cast<void *>(open_model)},
    {"transcribe", "(J[FILjava/lang/String;Z)[B", reinterpret_cast<void *>(transcribe)},
    {"close", "(J)V", reinterpret_cast<void *>(close_model)},
    {"abort", "(Z)V", reinterpret_cast<void *>(abort_work)},
    {"knows", "(Ljava/lang/String;)Z", reinterpret_cast<void *>(knows_language)},
    {"vadOpen", "(Ljava/lang/String;Ljava/lang/String;)J", reinterpret_cast<void *>(vad_open)},
    {"vadProbs", "(J[F)[F", reinterpret_cast<void *>(vad_probs)},
    {"vadReset", "(J)V", reinterpret_cast<void *>(vad_reset)},
    {"vadClose", "(J)V", reinterpret_cast<void *>(vad_close)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    std::string name = kClass;
    for (char &c : name) if (c == '.') c = '/';
    jclass cls = env->FindClass(name.c_str());
    if (cls == nullptr) return JNI_ERR;
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
