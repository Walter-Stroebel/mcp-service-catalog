package nl.infcomtec.mcpservicecatalog;

import java.util.Map;

/**
 * Executes one CatalogEntry's target against a call's arguments. One
 * implementation per CatalogEntry.kind ("method", "process", "http",
 * "launch") — CatalogTool picks the Invoker by kind and never interprets
 * target itself.
 */
public interface Invoker {

    String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception;
}
