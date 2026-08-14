#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>

#include <fstream>
#include <string>
#include <vector>

namespace {

using GetCreatedJavaVMsFn = jint(JNICALL *)(JavaVM **, jsize, jsize *);

constexpr const char *kBootstrapClass = "team.xenobyte.modern.bootstrap.NativeBootstrap";
constexpr const wchar_t *kDefaultJarName = L"xenobyte-modern-0.1.0.jar";

std::wofstream OpenLog() {
    wchar_t tempPath[MAX_PATH]{};
    GetTempPathW(MAX_PATH, tempPath);
    std::wstring logPath = std::wstring(tempPath) + L"xenobyte-modern-loader.log";
    return std::wofstream(logPath, std::ios::app);
}

void Log(const std::wstring &message) {
    auto log = OpenLog();
    if (log.is_open()) {
        log << message << L"\n";
    }
    OutputDebugStringW((L"[xenobyte-modern-loader] " + message + L"\n").c_str());
}

std::string WideToUtf8(const std::wstring &value) {
    if (value.empty()) {
        return {};
    }

    int size = WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (size <= 0) {
        return {};
    }

    std::string result(static_cast<size_t>(size - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, result.data(), size, nullptr, nullptr);
    return result;
}

std::wstring ParentDirectory(const std::wstring &path) {
    size_t slash = path.find_last_of(L"\\/");
    if (slash == std::wstring::npos) {
        return L".";
    }
    return path.substr(0, slash);
}

std::wstring FileName(const std::wstring &path) {
    size_t slash = path.find_last_of(L"\\/");
    if (slash == std::wstring::npos) {
        return path;
    }
    return path.substr(slash + 1);
}

std::wstring GetEnvironmentJarPath() {
    wchar_t buffer[32768]{};
    DWORD size = GetEnvironmentVariableW(L"XENOBYTE_MODERN_JAR", buffer, static_cast<DWORD>(std::size(buffer)));
    if (size == 0 || size >= std::size(buffer)) {
        return {};
    }
    return buffer;
}

std::wstring GetAdjacentJarPath(HMODULE module) {
    wchar_t dllPath[MAX_PATH]{};
    DWORD size = GetModuleFileNameW(module, dllPath, MAX_PATH);
    if (size == 0 || size >= MAX_PATH) {
        return {};
    }

    std::wstring path(dllPath);
    size_t slash = path.find_last_of(L"\\/");
    if (slash == std::wstring::npos) {
        return kDefaultJarName;
    }
    return path.substr(0, slash + 1) + kDefaultJarName;
}

bool LogJarStatus(const std::wstring &jarPath) {
    WIN32_FILE_ATTRIBUTE_DATA data{};
    if (!GetFileAttributesExW(jarPath.c_str(), GetFileExInfoStandard, &data)) {
        Log(L"Jar file is missing or unreadable: " + FileName(jarPath));
        return false;
    }

    ULARGE_INTEGER size{};
    size.HighPart = data.nFileSizeHigh;
    size.LowPart = data.nFileSizeLow;
    Log(L"Jar file exists, size=" + std::to_wstring(size.QuadPart) + L" bytes");
    return true;
}

std::wstring CopyJarToRuntimeTemp(const std::wstring &jarPath) {
    wchar_t tempPath[MAX_PATH]{};
    if (GetTempPathW(MAX_PATH, tempPath) == 0) {
        Log(L"GetTempPathW failed; using original jar");
        return jarPath;
    }

    std::wstring runtimePath = std::wstring(tempPath)
        + L"xenobyte-modern-runtime-"
        + std::to_wstring(GetCurrentProcessId())
        + L"-"
        + std::to_wstring(GetTickCount64())
        + L".jar";

    if (!CopyFileW(jarPath.c_str(), runtimePath.c_str(), FALSE)) {
        Log(L"Runtime jar copy failed; using original jar");
        return jarPath;
    }

    Log(L"Runtime jar copy: " + FileName(runtimePath));
    return runtimePath;
}

bool CheckAndClear(JNIEnv *env, const wchar_t *stage) {
    if (!env->ExceptionCheck()) {
        return false;
    }

    Log(std::wstring(L"JNI exception at ") + stage);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

void SetSystemProperty(JNIEnv *env, const char *key, const std::string &value) {
    if (value.empty()) {
        return;
    }

    jclass systemClass = env->FindClass("java/lang/System");
    if (CheckAndClear(env, L"FindClass(System)") || systemClass == nullptr) {
        return;
    }

    jmethodID setProperty = env->GetStaticMethodID(
        systemClass,
        "setProperty",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
    );
    if (CheckAndClear(env, L"System.setProperty lookup") || setProperty == nullptr) {
        return;
    }

    jstring keyString = env->NewStringUTF(key);
    jstring valueString = env->NewStringUTF(value.c_str());
    if (keyString == nullptr || valueString == nullptr) {
        CheckAndClear(env, L"System property strings");
        return;
    }

    env->CallStaticObjectMethod(systemClass, setProperty, keyString, valueString);
    CheckAndClear(env, L"System.setProperty");
}

jobject CurrentThreadContextClassLoader(JNIEnv *env) {
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (CheckAndClear(env, L"FindClass(Thread)") || threadClass == nullptr) {
        return nullptr;
    }

    jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jmethodID getContextClassLoader = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    if (CheckAndClear(env, L"Thread method lookup") || currentThread == nullptr || getContextClassLoader == nullptr) {
        return nullptr;
    }

    jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
    if (CheckAndClear(env, L"Thread.currentThread") || thread == nullptr) {
        return nullptr;
    }

    return env->CallObjectMethod(thread, getContextClassLoader);
}

jobject SystemClassLoader(JNIEnv *env) {
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    if (CheckAndClear(env, L"FindClass(ClassLoader)") || classLoaderClass == nullptr) {
        return nullptr;
    }

    jmethodID getSystemClassLoader = env->GetStaticMethodID(
        classLoaderClass,
        "getSystemClassLoader",
        "()Ljava/lang/ClassLoader;"
    );
    if (CheckAndClear(env, L"ClassLoader.getSystemClassLoader lookup") || getSystemClassLoader == nullptr) {
        return nullptr;
    }

    return env->CallStaticObjectMethod(classLoaderClass, getSystemClassLoader);
}

jobject BuildFileUrl(JNIEnv *env, jstring jarPath) {
    jclass fileClass = env->FindClass("java/io/File");
    jmethodID fileCtor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jobject file = env->NewObject(fileClass, fileCtor, jarPath);
    if (CheckAndClear(env, L"new File") || file == nullptr) {
        return nullptr;
    }

    jmethodID toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jobject uri = env->CallObjectMethod(file, toUri);
    if (CheckAndClear(env, L"File.toURI") || uri == nullptr) {
        return nullptr;
    }

    jclass uriClass = env->FindClass("java/net/URI");
    jmethodID toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jobject url = env->CallObjectMethod(uri, toUrl);
    if (CheckAndClear(env, L"URI.toURL") || url == nullptr) {
        return nullptr;
    }

    return url;
}

jobject BuildUrlClassLoader(JNIEnv *env, jstring jarPath) {
    jobject url = BuildFileUrl(env, jarPath);
    if (url == nullptr) {
        return nullptr;
    }

    jclass urlClass = env->FindClass("java/net/URL");
    jobjectArray urls = env->NewObjectArray(1, urlClass, url);
    if (CheckAndClear(env, L"URL[]") || urls == nullptr) {
        return nullptr;
    }

    jobject parent = CurrentThreadContextClassLoader(env);
    if (CheckAndClear(env, L"getContextClassLoader")) {
        parent = nullptr;
    }
    if (parent == nullptr) {
        parent = SystemClassLoader(env);
        CheckAndClear(env, L"getSystemClassLoader");
    }

    jclass urlClassLoaderClass = env->FindClass("java/net/URLClassLoader");
    jmethodID ctor = env->GetMethodID(urlClassLoaderClass, "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    jobject loader = env->NewObject(urlClassLoaderClass, ctor, urls, parent);
    if (CheckAndClear(env, L"new URLClassLoader") || loader == nullptr) {
        return nullptr;
    }
    return loader;
}

bool InvokeBootstrap(JNIEnv *env, const std::string &jarPath) {
    jstring jarPathString = env->NewStringUTF(jarPath.c_str());
    jstring sourceString = env->NewStringUTF("jni-loader");
    if (jarPathString == nullptr || sourceString == nullptr) {
        return false;
    }

    jobject loader = BuildUrlClassLoader(env, jarPathString);
    if (loader == nullptr) {
        return false;
    }

    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring bootstrapName = env->NewStringUTF(kBootstrapClass);
    jobject bootstrapClass = env->CallObjectMethod(loader, loadClass, bootstrapName);
    if (CheckAndClear(env, L"load NativeBootstrap") || bootstrapClass == nullptr) {
        return false;
    }

    jclass classClass = env->FindClass("java/lang/Class");
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray parameterTypes = env->NewObjectArray(2, classClass, stringClass);
    env->SetObjectArrayElement(parameterTypes, 0, stringClass);
    env->SetObjectArrayElement(parameterTypes, 1, stringClass);

    jmethodID getMethod = env->GetMethodID(
        classClass,
        "getMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
    );
    jstring methodName = env->NewStringUTF("initFromNative");
    jobject method = env->CallObjectMethod(bootstrapClass, getMethod, methodName, parameterTypes);
    if (CheckAndClear(env, L"get initFromNative") || method == nullptr) {
        return false;
    }

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray args = env->NewObjectArray(2, objectClass, nullptr);
    env->SetObjectArrayElement(args, 0, sourceString);
    env->SetObjectArrayElement(args, 1, jarPathString);

    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    jmethodID invoke = env->GetMethodID(
        methodClass,
        "invoke",
        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
    );
    env->CallObjectMethod(method, invoke, nullptr, args);
    if (CheckAndClear(env, L"NativeBootstrap.initFromNative")) {
        return false;
    }

    return true;
}

DWORD FinishThread(HMODULE self) {
    Log(L"Native loader thread finished; unloading DLL module");
    FreeLibraryAndExitThread(self, 0);
    return 0;
}

DWORD WINAPI LoaderThread(LPVOID parameter) {
    HMODULE self = static_cast<HMODULE>(parameter);
    Sleep(1000);

    std::wstring jarPath = GetEnvironmentJarPath();
    if (jarPath.empty()) {
        jarPath = GetAdjacentJarPath(self);
    }
    Log(L"Using jar: " + FileName(jarPath));
    if (!LogJarStatus(jarPath)) {
        return FinishThread(self);
    }
    std::wstring runtimeJarPath = CopyJarToRuntimeTemp(jarPath);
    LogJarStatus(runtimeJarPath);

    HMODULE jvmModule = GetModuleHandleW(L"jvm.dll");
    if (jvmModule == nullptr) {
        Log(L"jvm.dll is not loaded in this process");
        return FinishThread(self);
    }

    auto getCreatedJavaVMs = reinterpret_cast<GetCreatedJavaVMsFn>(GetProcAddress(jvmModule, "JNI_GetCreatedJavaVMs"));
    if (getCreatedJavaVMs == nullptr) {
        Log(L"JNI_GetCreatedJavaVMs not found");
        return FinishThread(self);
    }

    JavaVM *vm = nullptr;
    jsize vmCount = 0;
    if (getCreatedJavaVMs(&vm, 1, &vmCount) != JNI_OK || vm == nullptr || vmCount == 0) {
        Log(L"No created Java VM found");
        return FinishThread(self);
    }

    JNIEnv *env = nullptr;
    JavaVMAttachArgs args{};
    args.version = JNI_VERSION_1_8;
    args.name = const_cast<char *>("xenobyte-modern-loader");
    args.group = nullptr;

    bool attached = false;
    jint getEnvResult = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8);
    if (getEnvResult == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), &args) != JNI_OK || env == nullptr) {
            Log(L"AttachCurrentThread failed");
            return FinishThread(self);
        }
        attached = true;
    } else if (getEnvResult != JNI_OK || env == nullptr) {
        Log(L"GetEnv failed");
        return FinishThread(self);
    }

    SetSystemProperty(env, "xenobyte-modern.originalJar", WideToUtf8(jarPath));
    SetSystemProperty(env, "xenobyte-modern.packageDir", WideToUtf8(ParentDirectory(jarPath)));
    SetSystemProperty(env, "xenobyte-modern.runtimeJar", WideToUtf8(runtimeJarPath));

    bool ok = InvokeBootstrap(env, WideToUtf8(runtimeJarPath));
    Log(ok ? L"Bootstrap invoked successfully" : L"Bootstrap failed");

    if (attached) {
        vm->DetachCurrentThread();
    }
    return FinishThread(self);
}

} // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(module);
        HANDLE thread = CreateThread(nullptr, 0, LoaderThread, module, 0, nullptr);
        if (thread != nullptr) {
            CloseHandle(thread);
        }
    }
    return TRUE;
}
