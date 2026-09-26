#include <jni.h>
#include <errno.h>

// Only linked by the explicit x86_64 emulator carrier; no ARM acceleration is claimed.
extern "C" {
JNIEXPORT jboolean JNICALL
Java_com_example_llamadroid_util_CpuFeatures_hasDotProd(JNIEnv *, jclass) { return JNI_FALSE; }
JNIEXPORT jboolean JNICALL
Java_com_example_llamadroid_util_CpuFeatures_hasArmV9(JNIEnv *, jclass) { return JNI_FALSE; }
JNIEXPORT jboolean JNICALL
Java_com_example_llamadroid_util_CpuFeatures_hasI8mm(JNIEnv *, jclass) { return JNI_FALSE; }
JNIEXPORT jstring JNICALL
Java_com_example_llamadroid_util_CpuFeatures_getBestTier(JNIEnv *env, jclass) {
    return env->NewStringUTF("baseline");
}
JNIEXPORT jint JNICALL
Java_com_example_llamadroid_harness_HarnessAtomicFileNative_nativePublishNoReplace(
    JNIEnv *, jclass, jstring, jstring, jstring) { return ENOSYS; }
}
