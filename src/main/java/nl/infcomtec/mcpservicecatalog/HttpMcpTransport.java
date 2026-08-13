package nl.infcomtec.mcpservicecatalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Streamable HTTP transport for McpServer, per the MCP spec's
 * "Streamable HTTP" transport section: a single POST endpoint accepting
 * one JSON-RPC message per request. Only the simple
 * "Content-Type: application/json, one JSON object back" branch is
 * implemented — no SSE streaming, no Mcp-Session-Id — since this server
 * has no server-initiated messages or per-client state to justify either;
 * both are spec-optional.
 *
 * Runs alongside the existing stdio transport (McpServer.run), not
 * instead of it: this lets predator run the catalog as a long-lived,
 * systemd-managed process serving every client over HTTP, while stdio
 * remains available for anyone who still wants the per-session
 * ssh-spawn pattern (see MCP_VISION_BUG_REPORT.md's DTAP discussion for
 * why per-machine reachability, not protocol purity, decides which
 * transport a given deployment actually needs).
 */
public class HttpMcpTransport {

    private final McpServer server;

    public HttpMcpTransport(McpServer server) {
        this.server = server;
    }

    public void start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        httpServer.createContext("/mcp", new McpHandler());
        httpServer.setExecutor(null);
        httpServer.start();
    }

    private class McpHandler implements HttpHandler {

        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                InputStream body = exchange.getRequestBody();
                String requestLine = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                String responseLine = server.handleLine(requestLine);

                if (responseLine == null) {
                    // Notification: per spec, respond 202 Accepted, no body.
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                byte[] responseBytes = responseLine.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                OutputStream responseBody = exchange.getResponseBody();
                try {
                    responseBody.write(responseBytes);
                } finally {
                    responseBody.close();
                }
            } finally {
                exchange.close();
            }
        }
    }
}
