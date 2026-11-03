//! stdout and stderr to logcat. An Android app's standard streams go nowhere;
//! Horizon prints its errors, the crash report's path and its startup steps on
//! stderr, so a pipe takes them to the log, one line per entry.
use std::ffi::CString;
use std::io::{BufRead, BufReader};
use std::os::fd::FromRawFd;

const ANDROID_LOG_INFO: i32 = 4;
const ANDROID_LOG_WARN: i32 = 5;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const libc::c_char, text: *const libc::c_char) -> i32;
}

/// One line to logcat under `tag`.
pub fn write(prio: i32, tag: &str, line: &str) {
    let (Ok(tag), Ok(text)) = (CString::new(tag), CString::new(line.replace('\0', " "))) else {
        return;
    };
    // SAFETY: both are NUL-terminated strings that live through the call.
    unsafe {
        __android_log_write(prio, tag.as_ptr(), text.as_ptr());
    }
}

/// Points file descriptors 1 and 2 at a pipe read by a thread that logs each line.
pub fn redirect_stdio(tag: &'static str) {
    let mut fds = [0; 2];
    // SAFETY: `fds` has room for the two descriptors pipe() writes.
    if unsafe { libc::pipe(fds.as_mut_ptr()) } != 0 {
        return;
    }
    let [read, write_end] = fds;
    // SAFETY: plain descriptor calls; on failure the streams stay as they were.
    unsafe {
        libc::dup2(write_end, 1);
        libc::dup2(write_end, 2);
        libc::close(write_end);
    }
    let _ = std::thread::Builder::new()
        .name("horizon-logcat".into())
        .spawn(move || {
            // SAFETY: `read` is the pipe's read end, owned by this thread from now on.
            let file = unsafe { std::fs::File::from_raw_fd(read) };
            for line in BufReader::new(file).lines() {
                let Ok(line) = line else { break };
                let prio = if line.contains("error") || line.contains("panicked") {
                    ANDROID_LOG_WARN
                } else {
                    ANDROID_LOG_INFO
                };
                write(prio, tag, &line);
            }
        });
}
