package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invoker for CatalogEntry.kind "process": target is a JSON array of argv
 * elements, each run through TemplateSubstitution before exec. Stdout is
 * captured and returned as the result; stderr is merged into stdout so
 * nothing silently vanishes.
 *
 * If the call arguments include "file_id", it is resolved through
 * FileService to an on-disk path (uploaded earlier via the FileService's
 * own PUT /files endpoint, not through this call's JSON at all) and
 * exposed to the target template as {file_path}. If the catalog entry
 * has producesFile set, a fresh temp path is exposed as {output_path};
 * whatever the process leaves there is registered with FileService after
 * it exits, and the resulting file_id is appended to the returned text.
 * See MCP_VISION_BUG_REPORT.md for why file bytes never travel through
 * MCP tool-call arguments or results in this catalog.
 */
public class ProcessInvoker implements Invoker {

    public String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception {
        Map<String, Object> filled = new HashMap<String, Object>(arguments);
        Object fileId = arguments.get("file_id");
        if (fileId != null) {
            Path resolved = Main.FILES.resolve(fileId.toString());
            if (resolved == null) {
                throw new IllegalArgumentException("Unknown file_id: " + fileId);
            }
            filled.put("file_path", resolved.toAbsolutePath().toString());
        }
        Path outputPath = null;
        if (entry.producesFile) {
            Object outputExt = arguments.get("output_ext");
            String suffix = outputExt == null || outputExt.toString().isBlank() ? "" : "." + outputExt;
            outputPath = Files.createTempFile("mcp-catalog-output-", suffix);
            Files.delete(outputPath);
            filled.put("output_path", outputPath.toAbsolutePath().toString());
        }

        List<String> argv = new ArrayList<String>();
        for (JsonNode element : entry.target) {
            argv.add(TemplateSubstitution.fill(element.asText(), filled));
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

        if (outputPath != null) {
            if (!Files.exists(outputPath)) {
                throw new IllegalStateException("producesFile tool did not write to " + outputPath);
            }
            String outputFileId = Main.FILES.register(outputPath);
            output.append("file_id: ").append(outputFileId).append('\n');
        }
        return output.toString();
    }
}
