package org.shaderslang.wasm.lsp;

import run.endive.runtime.Store;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs slangd-wasm-lib.wasm as a {@link Process}-shaped in-JVM language server, so an editor
 * integration that already knows how to drive an LSP server through a {@code Process}'s stdio
 * (the standard pattern for LSP client libraries, including IntelliJ's generic LSP support) can
 * use this in place of spawning the native {@code slangd} binary -- without needing to know that
 * the "process" is actually a WASI module running inside Endive, not a real OS process.
 *
 * <p>This is the Phase 3 "host integration" piece described in {@code ../PLAN.md}: it wires
 * Option A (preopen each workspace root at instantiation -- see Phase 2, which proved this lets
 * the server resolve an unopened file purely through the filesystem) and exposes the module's
 * stdin/stdout/stderr as ordinary Java streams speaking the LSP JSON-RPC transport over
 * Content-Length-framed messages, exactly like the native binary does.
 *
 * <p><b>Guest paths are synthetic, not the real host path.</b> Each workspace root is preopened
 * under a short alias ({@code /workspace0}, {@code /workspace1}, ...) rather than its literal
 * host path (e.g. {@code /Users/alice/some/deeply/nested/project}). This was a deliberate fix, not
 * the original design: preopening under the real nested host path let {@code initialize} and
 * {@code textDocument/definition} both respond without error, but definition lookups on symbols
 * defined in unopened files silently returned a null result instead of a {@code Location} -- the
 * server's {@code Path::getCanonical}/realpath-style resolution does not reliably round-trip a
 * deep, multi-component absolute path through a WASI preopen the way it does a single short
 * top-level name (this mirrors exactly what Phase 2's spike used: a synthetic
 * {@code /workspace} guest path, never the spike's real temp-dir path). Callers must use {@link
 * #workspaceFolderUri} (not the host path) when building their {@code initialize} request's
 * {@code workspaceFolders}, and {@link #toGuestUri} for any other file URI under a given root.
 */
public final class SlangdWasmProcess extends Process {

    private final PipedOutputStream hostToModule;
    private final PipedInputStream stdoutSource;
    private final PipedInputStream stderrSource;
    private final Thread moduleThread;
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicInteger exitValue = new AtomicInteger(-1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Map<Path, String> guestPathByRoot = new LinkedHashMap<>();

    /**
     * Starts slangd-wasm-lib.wasm running on a background thread. The module begins executing
     * (and blocking on its first stdin read) before the constructor returns; callers can start
     * writing LSP messages to {@link #getOutputStream()} immediately.
     *
     * @param wasmPath path to the built {@code slangd-wasm-lib.wasm} artifact
     * @param workspaceRoots the real host directories to preopen -- one per LSP workspace folder
     *     the caller intends to send in its {@code initialize} request's {@code workspaceFolders}.
     *     Use {@link #workspaceFolderUri} to get the URI to actually put in that request -- it
     *     will not be this root's real host path (see the class doc).
     */
    public SlangdWasmProcess(Path wasmPath, List<Path> workspaceRoots) throws IOException {
        this.hostToModule = new PipedOutputStream();
        PipedInputStream moduleStdin = new PipedInputStream(hostToModule, 1 << 20);

        PipedOutputStream moduleStdoutSink = new PipedOutputStream();
        this.stdoutSource = new PipedInputStream(moduleStdoutSink, 1 << 20);

        PipedOutputStream moduleStderrSink = new PipedOutputStream();
        this.stderrSource = new PipedInputStream(moduleStderrSink, 1 << 20);

        var wasiOptionsBuilder = WasiOptions.builder()
                .withStdin(moduleStdin)
                .withStdout(moduleStdoutSink)
                .withStderr(moduleStderrSink);
        int index = 0;
        for (Path root : workspaceRoots) {
            String guestPath = "/workspace" + index++;
            guestPathByRoot.put(root.toAbsolutePath().normalize(), guestPath);
            wasiOptionsBuilder = wasiOptionsBuilder.withDirectory(guestPath, root);
        }
        WasiOptions wasiOptions = wasiOptionsBuilder.build();

        var module = Parser.parse(wasmPath.toFile());
        var wasi = WasiPreview1.builder().withOptions(wasiOptions).build();
        var store = new Store().addFunction(wasi.toHostFunctions());

        this.moduleThread = new Thread(() -> {
            try {
                store.instantiate("slangd-wasm-lib", module);
                exitValue.set(0);
            } catch (Throwable t) {
                failure.set(t);
                exitValue.set(1);
            } finally {
                exited.countDown();
            }
        }, "slangd-wasm-lib");
        this.moduleThread.setDaemon(true);
        this.moduleThread.start();
    }

    /**
     * Returns the {@code file://} URI to use as this root's {@code workspaceFolders} entry in the
     * {@code initialize} request -- the synthetic guest URI, not {@code root}'s real host path.
     *
     * @throws IllegalArgumentException if {@code root} was not one of the paths passed to the
     *     constructor
     */
    public String workspaceFolderUri(Path root) {
        return "file://" + guestPathFor(root);
    }

    /**
     * Translates a real host file path under {@code root} (one of the constructor's
     * {@code workspaceRoots}) into the {@code file://} URI slangd will actually recognize -- e.g.
     * for a {@code textDocument/didOpen} or {@code textDocument/definition} request's
     * {@code textDocument.uri}.
     *
     * @throws IllegalArgumentException if {@code hostFile} is not under any known workspace root
     */
    public String toGuestUri(Path hostFile) {
        Path absolute = hostFile.toAbsolutePath().normalize();
        for (Map.Entry<Path, String> entry : guestPathByRoot.entrySet()) {
            Path root = entry.getKey();
            if (absolute.startsWith(root)) {
                Path relative = root.relativize(absolute);
                String relativeSlashes = relative.toString().replace(java.io.File.separatorChar, '/');
                return "file://" + entry.getValue()
                        + (relativeSlashes.isEmpty() ? "" : "/" + relativeSlashes);
            }
        }
        throw new IllegalArgumentException(
                hostFile + " is not under any workspace root passed to this process: "
                        + guestPathByRoot.keySet());
    }

    private String guestPathFor(Path root) {
        String guestPath = guestPathByRoot.get(root.toAbsolutePath().normalize());
        if (guestPath == null) {
            throw new IllegalArgumentException(
                    root + " was not one of the workspace roots passed to this process: "
                            + guestPathByRoot.keySet());
        }
        return guestPath;
    }

    /** Caller writes LSP requests/notifications here, Content-Length-framed, as to any LSP server's stdin. */
    @Override
    public OutputStream getOutputStream() {
        return hostToModule;
    }

    /** Caller reads LSP responses/notifications here, exactly as from any LSP server's stdout. */
    @Override
    public InputStream getInputStream() {
        return stdoutSource;
    }

    /** slangd's own diagnostic/log output (never LSP-framed). */
    @Override
    public InputStream getErrorStream() {
        return stderrSource;
    }

    @Override
    public int waitFor() throws InterruptedException {
        exited.await();
        return exitValue.get();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return exited.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (exited.getCount() != 0) {
            throw new IllegalThreadStateException("slangd-wasm-lib is still running");
        }
        return exitValue.get();
    }

    @Override
    public boolean isAlive() {
        return exited.getCount() != 0;
    }

    /**
     * If the module thread exited because of an exception (rather than a normal return from
     * {@code main()}), returns it; {@code null} on a clean exit or while still running.
     */
    public Throwable failureCause() {
        return failure.get();
    }

    /**
     * There is no real OS process to send a signal to -- this closes the module's stdin, which is
     * the WASI-appropriate way to ask slangd to wind down: its connection read loop sees EOF.
     * Callers that want a clean shutdown should prefer sending the LSP {@code shutdown}/{@code
     * exit} sequence over {@link #getOutputStream()} instead of calling this directly.
     */
    @Override
    public void destroy() {
        try {
            hostToModule.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
