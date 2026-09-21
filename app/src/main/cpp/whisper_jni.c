// The narrowest possible bridge to whisper.cpp: load a model, transcribe one
// buffer of 16 kHz mono float PCM, read the segments back.
//
// One transcription runs at a time (the service guarantees it), which is what
// lets progress and cancellation be plain process-wide atomics rather than
// callbacks into the JVM from whisper's worker threads.

#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <string.h>
#include <android/log.h>

#include "whisper.h"

#define TAG "subread-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static atomic_int  g_progress = 0;
static atomic_bool g_abort    = false;

static void on_progress(struct whisper_context *ctx, struct whisper_state *state,
                        int progress, void *user_data) {
    (void) ctx; (void) state; (void) user_data;
    atomic_store(&g_progress, progress);
}

static bool should_abort(void *user_data) {
    (void) user_data;
    return atomic_load(&g_abort);
}

#define JNI_FN(name) Java_space_subread_app_whisper_WhisperLib_##name

JNIEXPORT jlong JNICALL
JNI_FN(initContext)(JNIEnv *env, jobject thiz, jstring model_path) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == NULL) LOGW("could not load model %s", path);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
JNI_FN(freeContext)(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    if (ptr != 0) whisper_free((struct whisper_context *) ptr);
}

// Returns 0 on success, 1000 if cancelled, anything else is whisper's own error.
JNIEXPORT jint JNICALL
JNI_FN(transcribe)(JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
                   jint n_threads, jstring language) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) ptr;
    if (ctx == NULL) return -2;

    const jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime   = false;
    p.print_progress   = false;
    p.print_timestamps = false;
    p.print_special    = false;
    p.translate        = false;
    p.language         = lang;         // "auto" detects; the caller pins it after the first chunk
    p.n_threads        = n_threads;
    // Each chunk stands alone. Carrying text over as a prompt is how one
    // hallucinated line turns into a page of the same line.
    p.no_context       = true;
    p.single_segment   = false;
    p.suppress_blank   = true;
    p.suppress_nst     = true;
    p.progress_callback = on_progress;
    p.abort_callback    = should_abort;

    atomic_store(&g_progress, 0);
    atomic_store(&g_abort, false);

    int rc = whisper_full(ctx, p, pcm, n);

    (*env)->ReleaseStringUTFChars(env, language, lang);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);

    if (atomic_load(&g_abort)) return 1000;
    if (rc != 0) LOGW("whisper_full failed: %d", rc);
    return rc;
}

JNIEXPORT void JNICALL
JNI_FN(cancel)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    atomic_store(&g_abort, true);
}

JNIEXPORT jint JNICALL
JNI_FN(progress)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return atomic_load(&g_progress);
}

JNIEXPORT jint JNICALL
JNI_FN(segmentCount)(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    return whisper_full_n_segments((struct whisper_context *) ptr);
}

// Text as raw UTF-8 bytes. NewStringUTF wants *modified* UTF-8 and mangles or
// aborts on anything outside the BMP, and the tiny model will happily emit a
// truncated multi-byte sequence at a segment edge. Let Kotlin decode leniently.
JNIEXPORT jbyteArray JNICALL
JNI_FN(segmentText)(JNIEnv *env, jobject thiz, jlong ptr, jint index) {
    (void) thiz;
    const char *text = whisper_full_get_segment_text((struct whisper_context *) ptr, index);
    if (text == NULL) text = "";
    const jsize len = (jsize) strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *) text);
    return out;
}

// Both in centiseconds, as whisper reports them.
JNIEXPORT jlong JNICALL
JNI_FN(segmentStart)(JNIEnv *env, jobject thiz, jlong ptr, jint index) {
    (void) env; (void) thiz;
    return whisper_full_get_segment_t0((struct whisper_context *) ptr, index);
}

JNIEXPORT jlong JNICALL
JNI_FN(segmentEnd)(JNIEnv *env, jobject thiz, jlong ptr, jint index) {
    (void) env; (void) thiz;
    return whisper_full_get_segment_t1((struct whisper_context *) ptr, index);
}

JNIEXPORT jstring JNICALL
JNI_FN(detectedLanguage)(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) thiz;
    const int id = whisper_full_lang_id((struct whisper_context *) ptr);
    const char *code = id >= 0 ? whisper_lang_str(id) : NULL;
    return (*env)->NewStringUTF(env, code != NULL ? code : "");
}

JNIEXPORT jstring JNICALL
JNI_FN(systemInfo)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
