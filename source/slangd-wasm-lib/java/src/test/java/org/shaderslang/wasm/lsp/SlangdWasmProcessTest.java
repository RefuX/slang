package org.shaderslang.wasm.lsp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SlangdWasmProcess} as a real LSP client would: write Content-Length-framed
 * requests to {@link Process#getOutputStream()}, read responses from {@link
 * Process#getInputStream()}. This is the same end-to-end scenario phase2's throwaway spike proved
 * (textDocument/definition resolving a symbol through a file that was never opened, only
 * reachable via the preopened workspace root), now exercised through the reusable host-
 * integration class an editor plugin would actually depend on, rather than one-off harness code.
 *
 * <p>Skips automatically when the slangd-wasm-lib.wasm artifact hasn't been built yet, same as
 * slang-wasm-lib's Java tests.
 */
class SlangdWasmProcessTest {

    private static Path wasmPath;

    @BeforeAll
    static void locateWasm() {
        String raw = System.getProperty("slangd.wasm.path", "");
        wasmPath = Path.of(raw.isEmpty() ? "slangd-wasm-lib.wasm" : raw);
        Assumptions.assumeTrue(
                Files.exists(wasmPath),
                "slangd-wasm-lib.wasm not found at " + wasmPath.toAbsolutePath()
                        + " — build it first with: cmake --build --preset wasi --target slangd-wasm-lib");
    }

    @Test
    void definitionResolvesSymbolFromUnopenedFileThroughPreopen() throws Exception {
        Path workspaceDir = Files.createTempDirectory("slangd-wasm-lib-java-test");
        String libSlang = "public struct Foo\n" + "{\n" + "    public int value;\n" + "};\n";
        String mainSlang =
                "import lib;\n" + "\n" + "void test(Foo f)\n" + "{\n" + "}\n";
        Path libFile = workspaceDir.resolve("lib.slang");
        Path mainFile = workspaceDir.resolve("main.slang");
        Files.writeString(libFile, libSlang);
        Files.writeString(mainFile, mainSlang);

        SlangdWasmProcess process = new SlangdWasmProcess(wasmPath, List.of(workspaceDir));
        // Note: these are the process's synthetic guest URIs, not workspaceDir's real host path
        // -- see SlangdWasmProcess's class doc for why preopening under the real (often deeply
        // nested) host path silently broke definition lookups.
        String rootUri = process.workspaceFolderUri(workspaceDir);
        String mainUri = process.toGuestUri(mainFile);
        try {
            OutputStream out = process.getOutputStream();
            InputStream in = process.getInputStream();

            writeMessage(
                    out,
                    1,
                    "initialize",
                    "{\"workspaceFolders\":[{\"uri\":\"" + rootUri + "\",\"name\":\"test\"}]}");
            String initializeResponse = awaitResponse(in, 1, 30_000);
            assertFalse(
                    initializeResponse.contains("\"error\""),
                    "initialize returned an error: " + initializeResponse);

            writeNotification(out, "initialized", "{}");
            writeNotification(
                    out,
                    "textDocument/didOpen",
                    "{\"textDocument\":{\"uri\":\"" + mainUri
                            + "\",\"languageId\":\"slang\",\"version\":1,\"text\":"
                            + jsonString(mainSlang) + "}}");
            // "void test(Foo f)" -- "void test(" is 10 characters, so 'F' is at character 10.
            writeMessage(
                    out,
                    2,
                    "textDocument/definition",
                    "{\"textDocument\":{\"uri\":\"" + mainUri
                            + "\"},\"position\":{\"line\":2,\"character\":10}}");
            String definitionResponse = awaitResponse(in, 2, 30_000);

            assertFalse(
                    definitionResponse.contains("\"error\""),
                    "textDocument/definition returned an error: " + definitionResponse);
            assertTrue(
                    definitionResponse.contains("lib.slang"),
                    "expected the definition response to point into lib.slang (resolved only "
                            + "through the preopened workspace directory), got: "
                            + definitionResponse);

            writeMessage(out, 3, "shutdown", "{}");
            awaitResponse(in, 3, 30_000);
            writeNotification(out, "exit", "{}");
            out.close();

            int exitCode = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                    ? process.exitValue()
                    : -1;
            assertTrue(
                    !process.isAlive(),
                    "slangd-wasm-lib did not exit after the exit notification"
                            + (process.failureCause() != null
                                    ? " (failure: " + process.failureCause() + ")"
                                    : ""));
        } finally {
            process.destroy();
        }
    }

    private static void writeMessage(OutputStream out, int id, String method, String paramsJson)
            throws IOException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + paramsJson + "}";
        frame(out, body);
    }

    private static void writeNotification(OutputStream out, String method, String paramsJson)
            throws IOException {
        String body =
                "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        frame(out, body);
    }

    private static void frame(OutputStream out, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    /** Reads framed messages from {@code in} until one with the given id is found, or times out. */
    private static String awaitResponse(InputStream in, int id, long timeoutMs) throws IOException {
        Pattern idPattern = Pattern.compile("\"id\"\\s*:\\s*" + id + "\\b");
        StringBuilder buffer = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] chunk = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int n = in.read(chunk, 0, Math.min(available, chunk.length));
                if (n > 0) {
                    buffer.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                    for (String message : splitFramedMessages(buffer.toString())) {
                        if (idPattern.matcher(message).find()) {
                            return message;
                        }
                    }
                }
            } else {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while awaiting LSP response", e);
                }
            }
        }
        throw new IOException("no response with id=" + id + " within " + timeoutMs + "ms");
    }

    private static java.util.List<String> splitFramedMessages(String raw) {
        java.util.List<String> result = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < raw.length()) {
            int headerEnd = raw.indexOf("\r\n\r\n", pos);
            if (headerEnd < 0) break;
            String header = raw.substring(pos, headerEnd);
            int contentLength = -1;
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            int bodyStart = headerEnd + 4;
            if (contentLength < 0 || bodyStart + contentLength > raw.length()) break;
            result.add(raw.substring(bodyStart, bodyStart + contentLength));
            pos = bodyStart + contentLength;
        }
        return result;
    }
}
