#include "log_capture.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unwind.h>
#include <signal.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>

namespace m64 {

namespace {

std::mutex g_mutex;
std::deque<std::string> g_lines;
constexpr size_t kMaxLines = 200;
int g_file_fd = -1;

struct sigaction g_old_actions[32];

void append_line(const std::string& line) {
    __android_log_write(ANDROID_LOG_INFO, "m64port", line.c_str());
    std::lock_guard lock{g_mutex};
    g_lines.push_back(line);
    if (g_lines.size() > kMaxLines) {
        g_lines.pop_front();
    }
    if (g_file_fd >= 0) {
        const std::string with_newline = line + "\n";
        (void)!write(g_file_fd, with_newline.data(), with_newline.size());
    }
}

void reader_thread(int read_fd) {
    std::string pending;
    char buffer[1024];
    while (true) {
        const ssize_t n = read(read_fd, buffer, sizeof(buffer));
        if (n <= 0) {
            break;
        }
        pending.append(buffer, size_t(n));
        size_t newline;
        while ((newline = pending.find('\n')) != std::string::npos) {
            append_line(pending.substr(0, newline));
            pending.erase(0, newline + 1);
        }
    }
}

struct BacktraceState {
    uintptr_t* frames;
    size_t count;
    size_t max;
};

_Unwind_Reason_Code unwind_callback(_Unwind_Context* context, void* arg) {
    auto* state = static_cast<BacktraceState*>(arg);
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        if (state->count == state->max) {
            return _URC_END_OF_STACK;
        }
        state->frames[state->count++] = pc;
    }
    return _URC_NO_REASON;
}

// Grava biblioteca+deslocamento de cada quadro da pilha. Os deslocamentos
// dentro de libm64port.so são traduzidos para funções com a biblioteca
// não-stripada do build (llvm-symbolizer / addr2line).
void write_backtrace() {
    uintptr_t frames[48];
    BacktraceState state{frames, 0, 48};
    _Unwind_Backtrace(unwind_callback, &state);
    for (size_t i = 0; i < state.count; i++) {
        Dl_info dl{};
        char line[512];
        int len;
        if (dladdr(reinterpret_cast<void*>(frames[i]), &dl) && dl.dli_fname != nullptr) {
            const char* lib = strrchr(dl.dli_fname, '/');
            len = snprintf(line, sizeof(line), "[m64]   #%02zu %s+0x%zx %s\n", i, lib ? lib + 1 : dl.dli_fname,
                           size_t(frames[i] - uintptr_t(dl.dli_fbase)), dl.dli_sname ? dl.dli_sname : "");
        } else {
            len = snprintf(line, sizeof(line), "[m64]   #%02zu 0x%zx\n", i, size_t(frames[i]));
        }
        (void)!write(g_file_fd, line, size_t(len));
    }
}

// Registra o sinal fatal e a pilha no arquivo antes do tratamento padrão do Android.
void crash_handler(int sig, siginfo_t* info, void* context) {
    if (g_file_fd >= 0) {
        char message[128];
        const int len = snprintf(message, sizeof(message), "[m64] processo encerrado pelo sinal %d (%s), endereço %p\n",
                                 sig, strsignal(sig), info ? info->si_addr : nullptr);
        (void)!write(g_file_fd, message, size_t(len));
        write_backtrace();
    }
    sigaction(sig, &g_old_actions[sig], nullptr);
    raise(sig);
    (void)context;
}

} // namespace

void start_log_capture(const std::string& file_path) {
    static bool started = false;
    if (started) {
        return;
    }
    started = true;

    g_file_fd = open(file_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);

    int fds[2];
    if (pipe(fds) == 0) {
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        std::thread{reader_thread, fds[0]}.detach();
    }

    struct sigaction action{};
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (int sig : {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE}) {
        sigaction(sig, &action, &g_old_actions[sig]);
    }
}

std::string recent_log(size_t max_lines) {
    std::lock_guard lock{g_mutex};
    std::string out;
    const size_t start = g_lines.size() > max_lines ? g_lines.size() - max_lines : 0;
    for (size_t i = start; i < g_lines.size(); i++) {
        out += g_lines[i];
        out += '\n';
    }
    return out;
}

} // namespace m64
