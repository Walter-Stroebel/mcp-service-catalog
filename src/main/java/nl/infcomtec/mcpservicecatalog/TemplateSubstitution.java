package nl.infcomtec.mcpservicecatalog;

import java.util.Map;

/**
 * Fills {argName} placeholders in a template string from a call's
 * arguments map. Shared by ProcessInvoker (per argv element) and
 * HttpInvoker (URL). Not URL-encoding or shell-escaping aware — the
 * catalog is trusted local input, not attacker input; arguments still
 * come from whatever MCP client is calling, so this is a demo-grade
 * substitution, not a hardening boundary.
 */
public class TemplateSubstitution {

    public static String fill(String template, Map<String, Object> arguments) {
        String result = template;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            result = result.replace("{" + entry.getKey() + "}", value == null ? "" : value.toString());
        }
        return result;
    }
}
