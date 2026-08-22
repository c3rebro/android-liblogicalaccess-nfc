#include <jni.h>
#include <mutex>

namespace {
jobject g_transport = nullptr;
std::mutex g_mutex;

void clear_transport(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_transport != nullptr) {
        env->DeleteGlobalRef(g_transport);
        g_transport = nullptr;
    }
}
}

extern "C"
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_version(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF("JNI bridge active; liblogicalaccess not linked yet");
}

extern "C"
JNIEXPORT void JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_attachTransport(
        JNIEnv* env,
        jobject,
        jobject transport) {
    clear_transport(env);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_transport = env->NewGlobalRef(transport);
}

extern "C"
JNIEXPORT void JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_detachTransport(
        JNIEnv* env,
        jobject) {
    clear_transport(env);
}
