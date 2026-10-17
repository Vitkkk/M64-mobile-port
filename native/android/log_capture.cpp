#include "log_capture.hpp"

#include <android/log.h>
#include <fcntl.h>
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

// Registra o sinal fatal no arquivo antes do tratamento padrão do Android.
void crash_handler(int sig, siginfo_t* info, void* context) {
    if (g_file_fd >= 0) {
        char message[128];
        const int len = snprintf(message, sizeof(message), "[m64] processo encerrado pelo sinal %d (%s), endereço %p\n",
                                 sig, strsignal(sig), info ? info->si_addr : nullptr);
        (void)!write(g_file_fd, message, size_t(len));
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
