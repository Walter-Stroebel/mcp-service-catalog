package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One tool as declared in the catalog JSON file: name/description/schema for
 * MCP's tools/list, plus which backend kind serves it and that kind's
 * target. The gateway (McpServer/CatalogTool) never interprets target
 * itself beyond picking the right Invoker for kind — see Invoker.
 */
public class CatalogEntry {

    public String name;
    public String description;
    public ObjectNode inputSchema;
    public String kind;
    public JsonNode target;

    /**
     * For kind "process" only: when true, ProcessInvoker creates a fresh
     * temp file before running the command, exposes its path to the
     * target template as {output_path}, and — once the process exits —
     * registers whatever ended up at that path with Main.FILES, appending
     * the resulting file_id to the returned text. Lets a process-kind
     * tool (e.g. an ImageMagick convert) hand back a downloadable result
     * without ever base64-encoding it into the MCP response itself.
     */
    public boolean producesFile;
}
