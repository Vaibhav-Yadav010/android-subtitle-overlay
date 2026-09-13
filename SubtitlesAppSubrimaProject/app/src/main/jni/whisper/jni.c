#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include <limits.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static inline int min(int a, int b) { return (a < b) ? a : b; }
static inline int max(int a, int b) { return (a > b) ? a : b; }

struct input_stream_context {
    size_t offset;
    JNIEnv *env;
    jobject thiz;
    jobject input_stream;
    jmethodID mid_read;
    bool eof;
    bool failed;
};

// InputStream.available() is not an EOF/remaining-length API. It may report zero
// even when a subsequent read can produce data. Read the requested bytes directly.
size_t inputStreamRead(void *ctx, void *output, size_t read_size) {
    struct input_stream_context *is = (struct input_stream_context *)ctx;
    if (is == NULL || output == NULL || read_size == 0 || is->eof || is->failed) {
        return 0;
    }

    size_t total = 0;
    while (total < read_size) {
        size_t remaining = read_size - total;
        jint request = remaining > (size_t)INT_MAX ? INT_MAX : (jint)remaining;
        jbyteArray byte_array = (*is->env)->NewByteArray(is->env, request);
        if (byte_array == NULL) {
            is->failed = true;
            LOGW("Failed to allocate InputStream read buffer");
            return total;
        }

        jint n_read = (*is->env)->CallIntMethod(
                is->env, is->input_stream, is->mid_read, byte_array, 0, request);
        if ((*is->env)->ExceptionCheck(is->env)) {
            is->failed = true;
            (*is->env)->DeleteLocalRef(is->env, byte_array);
            LOGW("InputStream.read() raised a Java exception");
            return total;
        }

        if (n_read < 0) {
            is->eof = true;
            (*is->env)->DeleteLocalRef(is->env, byte_array);
            return total;
        }
        if (n_read == 0) {
            (*is->env)->DeleteLocalRef(is->env, byte_array);
            LOGW("InputStream.read() returned zero bytes before EOF");
            return total;
        }

        jbyte *elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
        if (elements == NULL) {
            is->failed = true;
            (*is->env)->DeleteLocalRef(is->env, byte_array);
            LOGW("Failed to access InputStream read buffer");
            return total;
        }
        memcpy((char *)output + total, elements, (size_t)n_read);
        (*is->env)->ReleaseByteArrayElements(is->env, byte_array, elements, JNI_ABORT);
        (*is->env)->DeleteLocalRef(is->env, byte_array);

        total += (size_t)n_read;
        is->offset += (size_t)n_read;

        if ((jint)n_read < request) {
            // A short read is legal; return what was actually received. The next
            // loader callback can continue reading from the same stream.
            break;
        }
    }
    return total;
}

bool inputStreamEof(void *ctx) {
    struct input_stream_context *is = (struct input_stream_context *)ctx;
    return is == NULL || is->eof || is->failed;
}

void inputStreamClose(void *ctx) {
    UNUSED(ctx);
}

static void throwJavaException(JNIEnv *env, const char *class_name, const char *message) {
    if (env == NULL || (*env)->ExceptionCheck(env)) return;
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class == NULL) return;
    (*env)->ThrowNew(env, exception_class, message);
    (*env)->DeleteLocalRef(env, exception_class);
}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    if (env == NULL || input_stream == NULL) {
        if (env != NULL) {
            throwJavaException(env, "java/lang/IllegalArgumentException", "InputStream must not be null");
        }
        return 0L;
    }

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;
    inp_ctx.eof = false;
    inp_ctx.failed = false;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    if (cls == NULL || (*env)->ExceptionCheck(env)) {
        throwJavaException(env, "java/lang/RuntimeException", "Failed to inspect InputStream");
        return 0L;
    }
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    (*env)->DeleteLocalRef(env, cls);
    if (inp_ctx.mid_read == NULL || (*env)->ExceptionCheck(env)) {
        throwJavaException(env, "java/lang/RuntimeException", "InputStream.read(byte[], int, int) is unavailable");
        return 0L;
    }

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    struct whisper_context_params p = whisper_context_default_params();
    p.flash_attn = true;
    p.use_gpu = false;
    context = whisper_init_with_params(&loader, p);
    if (inp_ctx.failed) {
        LOGW("Whisper InputStream model loading failed");
        if (context != NULL) whisper_free(context);
        throwJavaException(env, "java/io/IOException", "Whisper model could not be read from InputStream");
        return 0L;
    }
    if (context == NULL) {
        throwJavaException(env, "java/io/IOException", "Whisper model initialization failed");
        return 0L;
    }
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}
static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}
static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env, jobject assetManager, const char *asset_path) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    if (asset_manager == NULL) {
        LOGW("Failed to obtain native AssetManager");
        return NULL;
    }
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    cparams.dtw_token_timestamps = false;
    struct whisper_context *context = whisper_init_with_params(&loader, cparams);
    if (context == NULL) {
        AAsset_close(asset);
    }
    return context;
}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    if (env == NULL || assetManager == NULL || asset_path_str == NULL) {
        if (env != NULL) {
            throwJavaException(env, "java/lang/IllegalArgumentException", "AssetManager and asset path must not be null");
        }
        return 0L;
    }
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    if (asset_path_chars == NULL) {
        throwJavaException(env, "java/lang/RuntimeException", "Failed to read asset path");
        return 0L;
    }
    struct whisper_context *context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    if (context == NULL) {
        throwJavaException(env, "java/io/IOException", "Whisper model asset could not be initialized");
    }
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    if (env == NULL || model_path_str == NULL) {
        if (env != NULL) {
            throwJavaException(env, "java/lang/IllegalArgumentException", "Model path must not be null");
        }
        return 0L;
    }
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    if (model_path_chars == NULL) {
        throwJavaException(env, "java/lang/RuntimeException", "Failed to read model path");
        return 0L;
    }
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    cparams.dtw_token_timestamps = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path_chars, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    if (ctx == NULL) {
        throwJavaException(env, "java/io/IOException", "Whisper model initialization failed");
    }
    return (jlong)ctx;
}

JNIEXPORT void JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    if (context_ptr == 0) return;
    whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT void JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    if (env == NULL) return;
    if (context_ptr == 0) {
        throwJavaException(env, "java/lang/IllegalStateException", "Whisper context is null");
        return;
    }
    if (audio_data == NULL) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "Audio data must not be null");
        return;
    }
    if (num_threads <= 0) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "Whisper thread count must be positive");
        return;
    }
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    if (audio_data_arr == NULL) {
        throwJavaException(env, "java/lang/RuntimeException", "Failed to access audio buffer");
        return;
    }
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    if (audio_data_length <= 0) {
        (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
        throwJavaException(env, "java/lang/IllegalArgumentException", "Audio data must not be empty");
        return;
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(context);
    LOGI("About to run whisper_full");
    const int result = whisper_full_parallel(context, params, audio_data_arr, audio_data_length, num_threads);
    if (result != 0) {
        LOGW("Failed to run the model (code=%d)", result);
        (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
        throwJavaException(env, "java/lang/RuntimeException", "Whisper transcription failed");
        return;
    }
    whisper_print_timings(context);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    if (env == NULL || context_ptr == 0) {
        if (env != NULL) throwJavaException(env, "java/lang/IllegalStateException", "Whisper context is null");
        return 0;
    }
    int count = whisper_full_n_segments((struct whisper_context *) context_ptr);
    if (count < 0) {
        throwJavaException(env, "java/lang/RuntimeException", "Whisper returned an invalid segment count");
        return 0;
    }
    return count;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    if (env == NULL || context_ptr == 0 || index < 0) {
        if (env != NULL && context_ptr == 0) {
            throwJavaException(env, "java/lang/IllegalStateException", "Whisper context is null");
        } else if (env != NULL && index < 0) {
            throwJavaException(env, "java/lang/IndexOutOfBoundsException", "Whisper segment index is negative");
        }
        return NULL;
    }
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    int count = whisper_full_n_segments(context);
    if (index >= count) {
        throwJavaException(env, "java/lang/IndexOutOfBoundsException", "Whisper segment index is out of bounds");
        return NULL;
    }
    const char *text = whisper_full_get_segment_text(context, index);
    if (text == NULL) {
        throwJavaException(env, "java/lang/RuntimeException", "Whisper returned a null segment text");
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, text);
    if (result == NULL && !(*env)->ExceptionCheck(env)) {
        throwJavaException(env, "java/lang/RuntimeException", "Failed to create Java segment text");
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegmentT0(JNIEnv *env, jobject thiz,jlong context_ptr, jint index) {
    UNUSED(thiz);
    if (env == NULL || context_ptr == 0 || index < 0) {
        if (env != NULL && context_ptr == 0) {
            throwJavaException(env, "java/lang/IllegalStateException", "Whisper context is null");
        } else if (env != NULL && index < 0) {
            throwJavaException(env, "java/lang/IndexOutOfBoundsException", "Whisper segment index is negative");
        }
        return 0;
    }
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (index >= whisper_full_n_segments(context)) {
        throwJavaException(env, "java/lang/IndexOutOfBoundsException", "Whisper segment index is out of bounds");
        return 0;
    }
    return (jlong)whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegmentT1(JNIEnv *env, jobject thiz,jlong context_ptr, jint index) {
    UNUSED(thiz);
    if (env == NULL || context_ptr == 0 || index < 0) {
        if (env != NULL && context_ptr == 0) {
            throwJavaException(env, "java/lang/IllegalStateException", "Whisper context is null");
        } else if (env != NULL && index < 0) {
            throwJavaException(env, "java/lang/IndexOutOfBoundsException", "Whisper segment index is negative");
        }
        return 0;
    }
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (index >= whisper_full_n_segments(context)) {
        throwJavaException(env, "java/lang/IndexOutOfBoundsException", "Whisper segment index is out of bounds");
        return 0;
    }
    return (jlong)whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getSystemInfo(JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    if (env == NULL) return NULL;
    const char *sysinfo = whisper_print_system_info();
    return sysinfo ? (*env)->NewStringUTF(env, sysinfo) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_benchMemcpy(JNIEnv *env, jobject thiz, jint n_threads) {
    UNUSED(thiz);
    if (env == NULL || n_threads <= 0) return NULL;
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    return bench_ggml_memcpy ? (*env)->NewStringUTF(env, bench_ggml_memcpy) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_benchGgmlMulMat(JNIEnv *env, jobject thiz, jint n_threads) {
    UNUSED(thiz);
    if (env == NULL || n_threads <= 0) return NULL;
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    return bench_ggml_mul_mat ? (*env)->NewStringUTF(env, bench_ggml_mul_mat) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getDetectedLanguage(JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    UNUSED(thiz);
    if (env == NULL || ctx_ptr == 0) {
        return env ? (*env)->NewStringUTF(env, "und") : NULL;
    }
    struct whisper_context* ctx = (struct whisper_context*)ctx_ptr;
    const int lang_id = whisper_full_lang_id(ctx);
    const char* code = whisper_lang_str(lang_id);
    if (code == NULL) code = "und";
    return (*env)->NewStringUTF(env, code);
}
