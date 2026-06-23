// slang-wasi-process.cpp — Process interface stubs for the WASI target.
//
// WASI has no process spawning (no fork/exec/posix_spawn) and no signals,
// so most of the Process interface is either stubbed to SLANG_FAIL or
// implemented with the subset of POSIX that wasi-libc does provide
// (clock_gettime, nanosleep).

#include "../slang-common.h"
#include "../slang-process.h"
#include "../slang-string-escape-util.h"

#include <poll.h>
#include <time.h>
#include <unistd.h>

namespace Slang
{

namespace
{

/// Stream wrapping a standard WASI file descriptor (0/1/2). WASI Preview 1
/// implements poll_oneoff, which wasi-libc's poll() is built on, so this
/// reuses the same non-blocking-poll-then-read pattern as UnixPipeStream
/// (source/core/unix/slang-unix-process.cpp): a poll() with a zero timeout
/// checks readiness without blocking the WASM instance, and the caller
/// (JSONRPCConnection's read loop, driven by slang-language-server.cpp's own
/// retry/wait loop) is responsible for calling read() again later. This
/// split -- non-blocking Stream::read, blocking behavior only at the
/// caller's retry loop.
class WasiStdStream : public Stream
{
public:
    virtual Int64 getPosition() SLANG_OVERRIDE { return 0; }
    virtual SlangResult seek(SeekOrigin origin, Int64 offset) SLANG_OVERRIDE
    {
        SLANG_UNUSED(origin);
        SLANG_UNUSED(offset);
        return SLANG_E_NOT_AVAILABLE;
    }
    virtual SlangResult read(void* buffer, size_t length, size_t& outReadBytes) SLANG_OVERRIDE;
    virtual SlangResult write(const void* buffer, size_t length) SLANG_OVERRIDE;
    virtual bool isEnd() SLANG_OVERRIDE { return m_isClosed; }
    virtual bool canRead() SLANG_OVERRIDE { return _has(FileAccess::Read) && !m_isClosed; }
    virtual bool canWrite() SLANG_OVERRIDE { return _has(FileAccess::Write) && !m_isClosed; }
    virtual void close() SLANG_OVERRIDE { m_isClosed = true; }
    virtual SlangResult flush() SLANG_OVERRIDE { return SLANG_OK; }

    WasiStdStream(int fd, FileAccess access)
        : m_fd(fd), m_access(access), m_isClosed(false)
    {
    }

protected:
    bool _has(FileAccess access) const { return (Index(access) & Index(m_access)) != 0; }

    int m_fd;
    FileAccess m_access;
    bool m_isClosed;
};

SlangResult WasiStdStream::read(void* buffer, size_t length, size_t& outReadBytes)
{
    outReadBytes = 0;

    if (!_has(FileAccess::Read))
    {
        return SLANG_E_NOT_AVAILABLE;
    }
    if (m_isClosed)
    {
        return SLANG_OK;
    }

    pollfd pollInfo;
    pollInfo.fd = m_fd;
    pollInfo.events = POLLIN | POLLHUP;
    pollInfo.revents = 0;

    // Return immediately: readiness is checked here, not waited for. The
    // caller's own loop is what blocks/retries (see the class comment above).
    const int pollResult = ::poll(&pollInfo, 1, 0);
    if (pollResult < 0)
    {
        return SLANG_FAIL;
    }
    if (pollResult == 0)
    {
        return SLANG_OK;
    }

    if (pollInfo.revents & POLLIN)
    {
        const ssize_t count = ::read(m_fd, buffer, length);
        if (count < 0)
        {
            return SLANG_FAIL;
        }
        outReadBytes = size_t(count);
        if (count == 0)
        {
            // EOF: the host closed its end of stdin.
            m_isClosed = true;
        }
        return SLANG_OK;
    }

    if (pollInfo.revents & POLLHUP)
    {
        m_isClosed = true;
    }
    return SLANG_OK;
}

SlangResult WasiStdStream::write(const void* buffer, size_t length)
{
    if (!_has(FileAccess::Write))
    {
        return SLANG_E_NOT_AVAILABLE;
    }
    if (m_isClosed)
    {
        return SLANG_FAIL;
    }

    const ssize_t writeResult = ::write(m_fd, buffer, length);
    if (writeResult < 0 || size_t(writeResult) != length)
    {
        return SLANG_FAIL;
    }
    return SLANG_OK;
}

} // anonymous namespace

/* static */ SlangResult Process::getStdStream(StdStreamType type, RefPtr<Stream>& out)
{
    switch (type)
    {
    case StdStreamType::In:
        out = new WasiStdStream(0, FileAccess::Read);
        break;
    case StdStreamType::Out:
        out = new WasiStdStream(1, FileAccess::Write);
        break;
    case StdStreamType::ErrorOut:
        out = new WasiStdStream(2, FileAccess::Write);
        break;
    default:
        return SLANG_FAIL;
    }
    return SLANG_OK;
}

/* static */ UnownedStringSlice Process::getExecutableSuffix()
{
    // WASI has no executable concept; return an empty suffix.
    return UnownedStringSlice::fromLiteral("");
}

/* static */ StringEscapeHandler* Process::getEscapeHandler()
{
    return StringEscapeUtil::getHandler(StringEscapeUtil::Style::Space);
}

/* static */ SlangResult Process::create(
    const CommandLine& /* commandLine */,
    Process::Flags /* flags */,
    RefPtr<Process>& /* outProcess */)
{
    // Process spawning is not available on WASI.
    return SLANG_FAIL;
}

/* static */ uint64_t Process::getClockFrequency()
{
    return 1000000000; // nanoseconds
}

/* static */ uint64_t Process::getClockTick()
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return uint64_t(now.tv_sec) * 1000000000 + uint64_t(now.tv_nsec);
}

/* static */ void Process::sleepCurrentThread(Int timeInMs)
{
    struct timespec ts;
    if (timeInMs >= 1000)
    {
        ts.tv_sec = timeInMs / 1000;
        ts.tv_nsec = (timeInMs % 1000) * 1000000;
    }
    else if (timeInMs > 0)
    {
        ts.tv_sec = 0;
        ts.tv_nsec = timeInMs * 1000000;
    }
    else
    {
        return;
    }
    nanosleep(&ts, nullptr);
}

} // namespace Slang
