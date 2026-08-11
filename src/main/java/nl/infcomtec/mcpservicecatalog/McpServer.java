package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hand-rolled MCP server: newline-delimited JSON-RPC 2.0 over stdin/stdout.
 * No MCP SDK involved — this class implements the handshake and the
 * tools/list, tools/call methods directly against the wire protocol.
 */
public class McpServer {

    private final Map<String, Tool<?>> tools = new TreeMap<String, Tool<?>>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream out;

    public McpServer(PrintStream out) {
        this.out = out;
    }

    public void addTool(Tool<?> tool) {
        tools.put(tool.name(), tool);
    }

    public void run(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            handleLine(line);
        }
    }

    private void handleLine(String line) throws IOException {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            // Per spec, a parse error's response id is Null: we can't trust
            // anything in a request we failed to parse, including its id.
            JsonRpc.sendResponse(out, mapper, NullNode.getInstance(), null,
                    JsonRpc.makeError(JsonRpc.PARSE_ERROR, "Parse error: " + e.getOriginalMessage()));
            return;
        }
        String method = request.path("method").asText();
        JsonNode idNode = request.get("id");

        if ("notifications/initialized".equals(method)) {
            // Notification: no id, no reply expected.
            return;
        }

        ObjectNode params = request.has("params") && request.get("params").isObject()
                ? (ObjectNode) request.get("params")
                : JsonNodeFactory.instance.objectNode();

        JsonNode result;
        JsonNode error = null;
        if ("initialize".equals(method)) {
            result = handleInitialize();
        } else if ("tools/list".equals(method)) {
            result = handleToolsList();
        } else if ("tools/call".equals(method)) {
            result = handleToolsCall(params);
        } else {
            result = null;
            error = JsonRpc.makeError(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
        }

        if (idNode == null) {
            // Notification for a method we don't recognize: silently ignore.
            return;
        }
        JsonRpc.sendResponse(out, mapper, idNode, result, error);
    }

    private ObjectNode handleInitialize() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "mcp-service-catalog");
        serverInfo.put("version", "1.0");
        return result;
    }

    private ObjectNode handleToolsList() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode toolArray = result.putArray("tools");
        for (Tool<?> tool : tools.values()) {
            ObjectNode toolNode = toolArray.addObject();
            toolNode.put("name", tool.name());
            toolNode.put("description", tool.description());
            toolNode.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private JsonNode handleToolsCall(ObjectNode params) {
        String toolName = params.path("name").asText();
        Tool<?> tool = tools.get(toolName);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (tool == null) {
            result.put("isError", true);
            ArrayNode content = result.putArray("content");
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", "Unknown tool: " + toolName);
            return result;
        }
        ObjectNode argumentsNode = params.has("arguments") && params.get("arguments").isObject()
                ? (ObjectNode) params.get("arguments")
                : JsonNodeFactory.instance.objectNode();
        String text;
        boolean isError = false;
        try {
            text = invokeTool(tool, argumentsNode);
        } catch (RuntimeException e) {
            text = "Tool error: " + e.getMessage();
            isError = true;
        }
        if (isError) {
            result.put("isError", true);
        }
        ArrayNode content = result.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        return result;
    }

    /**
     * Binds the raw arguments tree into the tool's own POJO type and calls
     * it. A separate method (rather than inline in handleToolsCall) so the
     * type variable A can tie tool.argumentsType(), the Jackson conversion,
     * and tool.call(...) together consistently for a single wildcard-typed
     * Tool<?> instance.
     */
    private <A> String invokeTool(Tool<A> tool, ObjectNode argumentsNode) {
        A arguments = mapper.convertValue(argumentsNode, tool.argumentsType());
        return tool.call(arguments);
    }

}
