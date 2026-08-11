package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Invoker for CatalogEntry.kind "process": target is a JSON array of argv
 * elements, each run through TemplateSubstitution before exec. Stdout is
 * captured and returned as the result; stderr is merged into stdout so
 * nothing silently vanishes.
 */
public class ProcessInvoker implements Invoker {

    public String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception {
        List<String> argv = new ArrayList<String>();
        for (JsonNode element : entry.target) {
            argv.add(TemplateSubstitution.fill(element.asText(), arguments));
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        process.waitFor();
        return output.toString();
    }
}
