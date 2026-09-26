// CpuFeatures.cpp - Native CPU feature detection for multi-tier binary loading
// Compile as a shared library: libcpufeatures.so

#include <android/log.h>
#include <asm/hwcap.h>
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <limits.h>
#include <string>

#define LOG_TAG "CpuFeatures"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ARM64 HWCAP2 flags (from Linux kernel headers)
#ifndef HWCAP2_ASIMDDP
#define HWCAP2_ASIMDDP (1 << 20) // ASIMD dot product
#endif

#ifndef HWCAP2_SVE2
#define HWCAP2_SVE2 (1 << 1) // SVE2 (ARMv9)
#endif

#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1 << 13) // Int8 matrix multiply
#endif

#ifndef AT_FDCWD
#define AT_FDCWD (-100)
#endif

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1 << 0)
#endif

// Helper to check /proc/cpuinfo for a flag
bool cpuinfo_has_flag(const char *flag) {
  FILE *fp = fopen("/proc/cpuinfo", "r");
  if (!fp)
    return false;

  char line[1024];
  bool found = false;
  while (fgets(line, sizeof(line), fp)) {
    if (strncmp(line, "Features", 8) == 0 || strncmp(line, "flags", 5) == 0) {
      if (strstr(line, flag) != NULL) {
        found = true;
        break;
      }
    }
  }
  fclose(fp);
  return found;
}

extern "C" {

// Check if CPU supports dot product instructions (armv8.2-a+dotprod)
JNIEXPORT jboolean JNICALL
Java_com_example_llamadroid_util_CpuFeatures_hasDotProd(JNIEnv *env,
                                                        jclass clazz) {
  unsigned long hwcap2 = getauxval(AT_HWCAP2);
  bool has_dotprod = (hwcap2 & HWCAP2_ASIMDDP) != 0;

  if (!has_dotprod) {
    // Fallback to cpuinfo
    has_dotprod = cpuinfo_has_flag(
        "asimdjn"); // 'asimdjn' often indicates dotprod support on some kernels
    if (!has_dotprod)
      has_dotprod = cpuinfo_has_flag("dotprod"); // standard name
  }

  LOGI("HWCAP2: 0x%lx, DotProd: %d", hwcap2, has_dotprod);
  return has_dotprod ? JNI_TRUE : JNI_FALSE;
}

// Check if CPU supports ARMv9 features (SVE2)
JNIEXPORT jboolean JNICALL
Java_com_example_llamadroid_util_CpuFeatures_hasArmV9(JNIEnv *env,
                                                      jclass clazz) {
  unsigned long hwcap2 = getauxval(AT_HWCAP2);
  bool has_sve2 = (hwcap2 & HWCAP2_SVE2) != 0;

  if (!has_sve2) {
    // Fallback to cpuinfo
    has_sve2 = cpuinfo_has_flag("sve2");
  }

  LOGI("HWCAP2: 0x%lx, SVE2 (ARMv9): %d", hwcap2, has_sve2);
  return has_sve2 ? JNI_TRUE : JNI_FALSE;
}

// Check if CPU supports i8mm (int8 matrix multiply, for CPU repack)
JNIEXPORT jboolean JNICALL Java_com_example_llamadroid_util_CpuFeatures_hasI8mm(
    JNIEnv *env, jclass clazz) {
#if defined(__aarch64__) && defined(HWCAP2_I8MM)
  unsigned long hwcap2 = getauxval(AT_HWCAP2);
  bool has_i8mm = (hwcap2 & HWCAP2_I8MM) != 0;

  LOGI("HWCAP2: 0x%lx, I8MM: %d", hwcap2, has_i8mm);
  return has_i8mm ? JNI_TRUE : JNI_FALSE;
#else
  LOGI("I8MM unavailable: non-aarch64 build or HWCAP2_I8MM missing");
  return JNI_FALSE;
#endif
}

// Get the best CPU tier for this device: "armv9", "dotprod", or "baseline"
JNIEXPORT jstring JNICALL
Java_com_example_llamadroid_util_CpuFeatures_getBestTier(JNIEnv *env,
                                                         jclass clazz) {
  unsigned long hwcap2 = getauxval(AT_HWCAP2);
  bool has_sve2 = (hwcap2 & HWCAP2_SVE2) != 0;
  bool has_dotprod = (hwcap2 & HWCAP2_ASIMDDP) != 0;

  // Fallbacks
  if (!has_sve2)
    has_sve2 = cpuinfo_has_flag("sve2");
  if (!has_dotprod) {
    has_dotprod = cpuinfo_has_flag("dotprod") || cpuinfo_has_flag("asimddp");
  }

  const char *tier;
  if (has_sve2) {
    tier = "armv9";
  } else if (has_dotprod) {
    tier = "dotprod";
  } else {
    tier = "baseline";
  }

  LOGI("Selected CPU tier: %s (HWCAP2: 0x%lx, SVE2: %d)", tier, hwcap2,
       has_sve2);
  return env->NewStringUTF(tier);
}

namespace {

// Open a validated absolute directory below an already pinned root. Opening
// every component with O_NOFOLLOW keeps a concurrent guest rename from
// redirecting renameat2 through a parent symlink after Kotlin's validation.
int openPinnedDirectory(int rootFd, const char *rootPath, const char *parentPath) {
  const size_t rootLength = strlen(rootPath);
  if (rootLength == 0 || strncmp(parentPath, rootPath, rootLength) != 0) return -EINVAL;
  if (parentPath[rootLength] == '\0') {
    const int copy = dup(rootFd);
    return copy < 0 ? -errno : copy;
  }
  if (parentPath[rootLength] != '/') return -EINVAL;

  int current = dup(rootFd);
  if (current < 0) return -errno;
  const char *cursor = parentPath + rootLength + 1;
  while (*cursor != '\0') {
    const char *slash = strchr(cursor, '/');
    const size_t length = slash == nullptr ? strlen(cursor) : static_cast<size_t>(slash - cursor);
    if (length == 0 || length > NAME_MAX) {
      close(current);
      return -EINVAL;
    }
    char component[NAME_MAX + 1];
    memcpy(component, cursor, length);
    component[length] = '\0';
    if (strcmp(component, ".") == 0 || strcmp(component, "..") == 0) {
      close(current);
      return -EINVAL;
    }
    const int next = openat(current, component,
                            O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    const int openError = errno;
    close(current);
    if (next < 0) return -openError;
    current = next;
    if (slash == nullptr) break;
    cursor = slash + 1;
  }
  return current;
}

bool splitParent(const std::string &path, std::string *parent, std::string *name) {
  const size_t slash = path.find_last_of('/');
  if (slash == std::string::npos || slash == 0 || slash + 1 >= path.size()) return false;
  *parent = path.substr(0, slash);
  *name = path.substr(slash + 1);
  return name->find('/') == std::string::npos && name->find('\0') == std::string::npos &&
         *name != "." && *name != "..";
}

}  // namespace

// Publish one already-synced staging file without replacing an existing
// destination. The Android bridge validates the captured project/session
// scope; this native layer pins both parent directory walks before renaming.
JNIEXPORT jint JNICALL
Java_com_example_llamadroid_harness_HarnessAtomicFileNative_nativePublishNoReplace(
    JNIEnv *env, jclass clazz, jstring root_path, jstring source_path, jstring destination_path) {
  if (root_path == nullptr || source_path == nullptr || destination_path == nullptr) return EINVAL;
  const char *root = env->GetStringUTFChars(root_path, nullptr);
  const char *source = env->GetStringUTFChars(source_path, nullptr);
  const char *destination = env->GetStringUTFChars(destination_path, nullptr);
  if (root == nullptr || source == nullptr || destination == nullptr) {
    if (root != nullptr) env->ReleaseStringUTFChars(root_path, root);
    if (source != nullptr) env->ReleaseStringUTFChars(source_path, source);
    if (destination != nullptr) env->ReleaseStringUTFChars(destination_path, destination);
    return ENOMEM;
  }
  int result = EINVAL;
  if (root[0] == '/' && source[0] == '/' && destination[0] == '/') {
    const std::string sourceString(source);
    const std::string destinationString(destination);
    std::string sourceParent;
    std::string sourceName;
    std::string destinationParent;
    std::string destinationName;
    if (splitParent(sourceString, &sourceParent, &sourceName) &&
        splitParent(destinationString, &destinationParent, &destinationName)) {
      const int rootFd = open(root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
      if (rootFd < 0) {
        result = errno;
      } else {
        const int sourceFd = openPinnedDirectory(rootFd, root, sourceParent.c_str());
        const int sourceError = sourceFd < 0 ? -sourceFd : 0;
        const int destinationFd = sourceFd < 0 ? -1 : openPinnedDirectory(rootFd, root, destinationParent.c_str());
        const int destinationError = destinationFd < 0 ? -destinationFd : 0;
        if (sourceError != 0) {
          result = sourceError;
        } else if (destinationError != 0) {
          result = destinationError;
        } else {
          struct stat sourceStat {};
          if (fstatat(sourceFd, sourceName.c_str(), &sourceStat, AT_SYMLINK_NOFOLLOW) != 0) {
            result = errno;
          } else if (!S_ISREG(sourceStat.st_mode)) {
            result = EINVAL;
          } else {
            struct stat destinationStat {};
            if (fstatat(destinationFd, destinationName.c_str(), &destinationStat, AT_SYMLINK_NOFOLLOW) == 0) {
              result = S_ISREG(destinationStat.st_mode) ? EEXIST : EINVAL;
            } else if (errno != ENOENT) {
              result = errno;
            } else {
#if defined(__NR_renameat2)
              if (syscall(__NR_renameat2, sourceFd, sourceName.c_str(), destinationFd,
                          destinationName.c_str(), RENAME_NOREPLACE) == 0) {
                result = 0;
              } else {
                result = errno;
              }
#elif defined(SYS_renameat2)
              if (syscall(SYS_renameat2, sourceFd, sourceName.c_str(), destinationFd,
                          destinationName.c_str(), RENAME_NOREPLACE) == 0) {
                result = 0;
              } else {
                result = errno;
              }
#else
              result = ENOSYS;
#endif
            }
          }
        }
        if (destinationFd >= 0) close(destinationFd);
        if (sourceFd >= 0) close(sourceFd);
        close(rootFd);
      }
    }
  }
  env->ReleaseStringUTFChars(root_path, root);
  env->ReleaseStringUTFChars(source_path, source);
  env->ReleaseStringUTFChars(destination_path, destination);
  return result;
}
}
