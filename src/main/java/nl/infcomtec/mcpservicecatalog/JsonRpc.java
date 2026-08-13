package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * JSON-RPC 2.0 envelope/framing, per the spec (https://www.jsonrpc.org/specification) —
 * not specific to MCP or to this server. Anything that speaks JSON-RPC 2.0
 * over any transport needs exactly this: the error-object shape and the
 * response envelope ("jsonrpc":"2.0" tag, id, result-or-error).
 */
public class JsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static ObjectNode makeError(int code, String message) {
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    /**
     * Builds one JSON-RPC 2.0 response envelope and serializes it to a
     * single line — transport-agnostic; the caller decides whether that
     * line goes to stdout (stdio transport) or an HTTP response body
     * (Streamable HTTP transport).
     */
    public static String responseString(ObjectMapper mapper, JsonNode id, JsonNode result, JsonNode error) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        if (error != null) {
            response.set("error", error);
        } else {
            response.set("result", result);
        }
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
