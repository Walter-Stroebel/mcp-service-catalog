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
}
