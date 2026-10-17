// Ponte JNI entre o app Android (Kotlin) e o núcleo nativo.
#include <jni.h>

#include <android/log.h>

#include <cinttypes>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include "m64/aot.hpp"
#include "m64/input.hpp"
#include "m64/rom.hpp"

#define LOG_TAG "m64port"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::unique_ptr<m64::AotProgram> g_program;

std::string to_string(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out{chars};
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jobject make_result(JNIEnv* env, bool ok, const std::string& message, const std::string& version_id) {
    jclass cls = env->FindClass("io/github/m64port/NativeResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZLjava/lang/String;Ljava/lang/String;)V");
    return env->NewObject(cls, ctor, jboolean(ok), env->NewStringUTF(message.c_str()),
                          env->NewStringUTF(version_id.c_str()));
}

} // namespace

extern "C" {

// Valida a ROM importada e, se for suportada, sobrescreve `rom_bytes` com a
// versão normalizada (.z64) para o app guardar no armazenamento privado.
// Nada sai do aparelho.
JNIEXPORT jobject JNICALL Java_io_github_m64port_NativeBridge_validateRom(JNIEnv* env, jclass, jbyteArray rom_bytes) {
    const jsize len = env->GetArrayLength(rom_bytes);
    std::vector<uint8_t> data(static_cast<size_t>(len));
    env->GetByteArrayRegion(rom_bytes, 0, len, reinterpret_cast<jbyte*>(data.data()));

    m64::LoadedRom rom;
    const m64::RomError err = m64::load_rom(std::move(data), rom);
    if (err != m64::RomError::None) {
        std::string msg = m64::rom_error_message(err);
        if (!rom.info.sha1.empty()) {
            msg += "\nSHA-1: " + rom.info.sha1;
        }
        return make_result(env, false, msg, "");
    }

    env->SetByteArrayRegion(rom_bytes, 0, len, reinterpret_cast<const jbyte*>(rom.data.data()));
    LOGI("ROM reconhecida: %s (%s)", std::string(rom.info.known->description).c_str(), rom.info.sha1.c_str());
    return make_result(env, true, std::string(rom.info.known->description), std::string(rom.info.known->id));
}

// Recompila a ROM (já normalizada) para código nativo, na memória.
JNIEXPORT jobject JNICALL Java_io_github_m64port_NativeBridge_recompile(JNIEnv* env, jclass, jstring rom_path,
                                                                        jstring syms_path) {
    const std::string rom_file = to_string(env, rom_path);
    const std::string syms_file = to_string(env, syms_path);

    std::vector<uint8_t> data;
    if (FILE* f = std::fopen(rom_file.c_str(), "rb")) {
        std::fseek(f, 0, SEEK_END);
        data.resize(size_t(std::ftell(f)));
        std::fseek(f, 0, SEEK_SET);
        const size_t read = std::fread(data.data(), 1, data.size(), f);
        std::fclose(f);
        data.resize(read);
    } else {
        return make_result(env, false, "Não foi possível abrir a ROM importada.", "");
    }

    m64::LoadedRom rom;
    const m64::RomError err = m64::load_rom(std::move(data), rom);
    if (err != m64::RomError::None) {
        return make_result(env, false, m64::rom_error_message(err), "");
    }

    auto program = std::make_unique<m64::AotProgram>();
    std::string error;
    if (!m64::recompile_program(rom.data, syms_file, *program, error)) {
        LOGE("AOT falhou: %s", error.c_str());
        return make_result(env, false, error, std::string(rom.info.known->id));
    }

    const m64::AotStats& s = program->stats();
    char summary[256];
    std::snprintf(summary, sizeof(summary), "%zu funções recompiladas (%zu KiB de código nativo) em %" PRIu64 " ms",
                  s.num_functions, s.code_size / 1024, s.codegen_microseconds / 1000);
    LOGI("%s", summary);
    g_program = std::move(program);
    return make_result(env, true, summary, std::string(rom.info.known->id));
}

JNIEXPORT void JNICALL Java_io_github_m64port_NativeBridge_setController(JNIEnv*, jclass, jint port, jint buttons,
                                                                         jint stick_x, jint stick_y) {
    m64::controller(port).set({uint16_t(buttons), int8_t(stick_x), int8_t(stick_y)});
}

} // extern "C"
