package nl.infcomtec.mcpservicecatalog;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Invoker for CatalogEntry.kind "method": target is
 * "fully.qualified.ClassName#methodName" naming a public static method
 * with signature String methodName(Map<String,Object>). Reflection by
 * string is normally avoided in this codebase's style, but here the
 * catalog is trusted local configuration, not attacker input — the whole
 * point is naming existing code from data instead of hardcoding it.
 */
public class MethodInvoker implements Invoker {

    public String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception {
        String target = entry.target.asText();
        int hash = target.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("method target must be Class#method: " + target);
        }
        String className = target.substring(0, hash);
        String methodName = target.substring(hash + 1);
        Class<?> targetClass = Class.forName(className);
        Method method = targetClass.getMethod(methodName, Map.class);
        return (String) method.invoke(null, arguments);
    }
}
