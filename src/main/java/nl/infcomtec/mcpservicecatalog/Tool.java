package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One MCP tool: a name, description, JSON-Schema for its arguments, the
 * POJO type Jackson should bind tools/call's arguments into, and the
 * behavior itself. Advertised via tools/list, invoked via tools/call.
 *
 * @param <A> the POJO type this tool's arguments bind to.
 */
public interface Tool<A> {

    String name();

    String description();

    /**
     * JSON Schema (as an ObjectNode, e.g. {"type":"object","properties":{...}})
     * describing this tool's arguments, per the MCP tools/list contract.
     */
    ObjectNode inputSchema();

    Class<A> argumentsType();

    String call(A arguments);
}
