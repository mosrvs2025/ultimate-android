// JNI bridge to Cactus Needle (https://github.com/cactus-compute/needle), a small on-device tool-calling model.
// Everything runs on the phone. One model per process; the engine isn't thread-safe, so Kotlin calls it from one thread.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef HAVE_NEEDLE
#include "needle.h"
#endif

#define FN(name) Java_app_ultimate_ai_NeedleNative_##name

static unsigned char* model = NULL; // kept for the life of the process: the engine reads the archive in place
static char stub_error[] = "The local AI engine isn't included in this build or for this processor.";

JNIEXPORT jboolean JNICALL FN(engine)(JNIEnv* env, jclass c) {
#ifdef HAVE_NEEDLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL FN(load)(JNIEnv* env, jclass c, jstring path) {
#ifdef HAVE_NEEDLE
    if (model) return 0; // already loaded
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE* f = fopen(p, "rb");
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!f) return -2;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* buf = (unsigned char*) malloc((size_t) n);
    if (!buf) { fclose(f); return -3; }
    size_t got = fread(buf, 1, (size_t) n, f); fclose(f);
    if (got != (size_t) n) { free(buf); return -4; }
    int r = needle_load(buf, (unsigned long long) n);
    if (r < 0) { free(buf); return r; }
    model = buf;
    return r;
#else
    return -1;
#endif
}

JNIEXPORT jint JNICALL FN(init)(JNIEnv* env, jclass c, jstring system, jstring tools) {
#ifdef HAVE_NEEDLE
    const char* s = system ? (*env)->GetStringUTFChars(env, system, NULL) : NULL;
    const char* t = (*env)->GetStringUTFChars(env, tools, NULL);
    int r = needle_init(s, t, NULL);
    if (s) (*env)->ReleaseStringUTFChars(env, system, s);
    (*env)->ReleaseStringUTFChars(env, tools, t);
    return r;
#else
    return -1;
#endif
}

JNIEXPORT jstring JNICALL FN(complete)(JNIEnv* env, jclass c, jstring input, jint maxTokens) {
#ifdef HAVE_NEEDLE
    const int cap = 32768;
    char* out = (char*) malloc(cap);
    if (!out) return NULL;
    out[0] = 0;
    const char* in = (*env)->GetStringUTFChars(env, input, NULL);
    needle_reset(); // each request stands alone
    int r = needle_complete(in, NULL, 0, maxTokens, out, cap);
    (*env)->ReleaseStringUTFChars(env, input, in);
    jstring s = r < 0 ? NULL : (*env)->NewStringUTF(env, out);
    free(out);
    return s;
#else
    return NULL;
#endif
}

JNIEXPORT jstring JNICALL FN(lastError)(JNIEnv* env, jclass c) {
#ifdef HAVE_NEEDLE
    const char* e = needle_last_error();
    return (*env)->NewStringUTF(env, e ? e : "");
#else
    return (*env)->NewStringUTF(env, stub_error);
#endif
}
