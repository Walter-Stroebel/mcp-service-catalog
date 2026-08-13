package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Entry point: wires the McpServer's JSON-RPC loop to real stdin/stdout,
 * loading the tool catalog from tools.json (or the path given as the
 * first argument) rather than registering hardcoded tool classes — see
 * CatalogEntry/CatalogTool/Invoker.
 */
public class Main {

    /**
     * Own-port file up/download service, shared by every ProcessInvoker
     * call in this process — see FileService and MCP_VISION_BUG_REPORT.md
     * for why file bytes are kept off the MCP stdio channel entirely.
     * Static, not passed around, matching this codebase's existing
     * preference for simple statics over dependency-injection machinery.
     */
    public static final FileService FILES;

    static {
        try {
            FILES = new FileService();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        File catalogFile = new File(args.length > 0 ? args[0] : "tools.json");
        ObjectMapper mapper = new ObjectMapper();
        List<CatalogEntry> entries = mapper.readValue(catalogFile,
                mapper.getTypeFactory().constructCollectionType(List.class, CatalogEntry.class));

        int filePort = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        FILES.start(filePort);

        McpServer server = new McpServer(System.out);
        for (CatalogEntry entry : entries) {
            server.addTool(new CatalogTool(entry));
        }

        int mcpHttpPort = args.length > 2 ? Integer.parseInt(args[2]) : 8764;
        new HttpMcpTransport(server).start(mcpHttpPort);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        server.run(in);
    }
}
