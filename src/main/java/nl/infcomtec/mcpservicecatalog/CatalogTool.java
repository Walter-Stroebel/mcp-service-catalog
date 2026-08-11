package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapts one CatalogEntry to the Tool interface. Holds no behavior of its
 * own beyond picking an Invoker by entry.kind and reporting exceptions as
 * tool errors — CatalogTool never knows what a "method", "process", or
 * "http" tool actually does, only how to reach one.
 */
public class CatalogTool implements Tool<Map> {

    private static final Map<String, Invoker> INVOKERS = new HashMap<String, Invoker>();

    static {
        INVOKERS.put("method", new MethodInvoker());
        INVOKERS.put("process", new ProcessInvoker());
        INVOKERS.put("http", new HttpInvoker());
    }

    private final CatalogEntry entry;

    public CatalogTool(CatalogEntry entry) {
        this.entry = entry;
    }

    public String name() {
        return entry.name;
    }

    public String description() {
        return entry.description;
    }

    public ObjectNode inputSchema() {
        return entry.inputSchema;
    }

    public Class<Map> argumentsType() {
        return Map.class;
    }

    public String call(Map arguments) {
        Invoker invoker = INVOKERS.get(entry.kind);
        if (invoker == null) {
            throw new IllegalArgumentException("Unknown tool kind: " + entry.kind);
        }
        try {
            return invoker.invoke(entry, arguments);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
