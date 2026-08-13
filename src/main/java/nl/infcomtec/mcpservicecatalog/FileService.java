package nl.infcomtec.mcpservicecatalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Own-port, plain-HTTP file up/download service the jar runs alongside its
 * stdio MCP loop — mirrors Anthropic's own Files API shape (upload once,
 * get a handle, pass the handle around) rather than smuggling file bytes
 * through MCP tool-call JSON, which is what MCP_VISION_BUG_REPORT.md's
 * investigation found to be the root of several independent hang/reject
 * bugs across the MCP ecosystem: newline-delimited stdio framing and
 * model-generation streaming both choke on large inline base64 arguments,
 * but neither is ever involved here, since the bytes never touch MCP.
 *
 * PUT /files       body = raw bytes, response = {"file_id":"..."}
 * GET /files/{id}  response = raw bytes of a previously uploaded file
 *
 * Binds 0.0.0.0 deliberately — this trusts the LAN, the same way predator's
 * LM Studio/Ollama backends already do; anyone who can reach this service
 * already has SSH/MCP-level access to the host.
 *
 * Files are staged under a process-lifetime temp directory and tracked in
 * memory only — no persistence, no TTL sweep, no config knob for the
 * storage location. This matches every other "trusted local config, keep
 * it simple" choice already made in this catalog's design (CLAUDE.md).
 */
public class FileService {

    private final Map<String, Path> filesById = new ConcurrentHashMap<String, Path>();
    private final Path stagingDir;

    public FileService() throws IOException {
        stagingDir = Files.createTempDirectory("mcp-catalog-files-");
    }

    /**
     * Resolves a previously uploaded file_id to its on-disk path, for use
     * by catalog tools (e.g. a "process"-kind ImageMagick convert entry
     * whose target template references {file_path}). Returns null if the
     * id is unknown.
     */
    public Path resolve(String fileId) {
        return filesById.get(fileId);
    }

    /**
     * Registers a file already on disk (e.g. a tool's output) under a new
     * file_id, so it can be downloaded via GET /files/{id} without a
     * client having to upload it first.
     */
    public String register(Path file) {
        String fileId = UUID.randomUUID().toString();
        filesById.put(fileId, file);
        return fileId;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/files", new FilesHandler());
        server.setExecutor(null);
        server.start();
    }

    private class FilesHandler implements HttpHandler {

        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if ("PUT".equals(method) && "/files".equals(path)) {
                    handleUpload(exchange);
                } else if ("GET".equals(method) && path.startsWith("/files/")) {
                    handleDownload(exchange, path.substring("/files/".length()));
                } else {
                    respond(exchange, 404, "Not found");
                }
            } finally {
                exchange.close();
            }
        }

        private void handleUpload(HttpExchange exchange) throws IOException {
            String fileId = UUID.randomUUID().toString();
            Path target = stagingDir.resolve(fileId);
            InputStream body = exchange.getRequestBody();
            OutputStream out = new FileOutputStream(target.toFile());
            try {
                body.transferTo(out);
            } finally {
                out.close();
            }
            filesById.put(fileId, target);
            respond(exchange, 200, "{\"file_id\":\"" + fileId + "\"}");
        }

        private void handleDownload(HttpExchange exchange, String fileId) throws IOException {
            Path file = filesById.get(fileId);
            if (file == null || !Files.exists(file)) {
                respond(exchange, 404, "Unknown file_id: " + fileId);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, Files.size(file));
            OutputStream responseBody = exchange.getResponseBody();
            try {
                Files.copy(file, responseBody);
            } finally {
                responseBody.close();
            }
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream responseBody = exchange.getResponseBody();
            try {
                responseBody.write(bytes);
            } finally {
                responseBody.close();
            }
        }
    }
}
