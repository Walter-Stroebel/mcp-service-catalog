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

    public static void main(String[] args) throws IOException {
        File catalogFile = new File(args.length > 0 ? args[0] : "tools.json");
        ObjectMapper mapper = new ObjectMapper();
        List<CatalogEntry> entries = mapper.readValue(catalogFile,
                mapper.getTypeFactory().constructCollectionType(List.class, CatalogEntry.class));

        McpServer server = new McpServer(System.out);
        for (CatalogEntry entry : entries) {
            server.addTool(new CatalogTool(entry));
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        server.run(in);
    }
}
