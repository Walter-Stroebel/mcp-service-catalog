package nl.infcomtec.mcpservicecatalog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a process and returns immediately without reading its output or
 * waiting for exit — for targets whose value is a detached, long-running
 * effect (e.g. opening a GUI window) rather than a captured result. See
 * ProcessInvoker for the run-and-capture counterpart.
 *
 * If the call arguments include "image_data" (a base64-encoded payload,
 * optionally paired with "image_ext" for the file extension), it is
 * decoded to a temp file whose path is exposed to the target template as
 * {uploaded_file} — the launched process outlives this call, so the temp
 * file is deliberately never deleted here; it is left for the OS/user to
 * clean up.
 */
public class LaunchInvoker implements Invoker {

    public String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception {
        Map<String, Object> filled = new HashMap<String, Object>(arguments);
        if (!filled.containsKey("slot")) {
            filled.put("slot", 0);
        }
        Object imageData = arguments.get("image_data");
        if (imageData != null) {
            Object ext = arguments.get("image_ext");
            String suffix = ext == null || ext.toString().isBlank() ? ".png" : "." + ext.toString();
            File temp = File.createTempFile("mcp-catalog-upload-", suffix);
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(Base64.getDecoder().decode(imageData.toString()));
            } finally {
                out.close();
            }
            filled.put("uploaded_file", temp.getAbsolutePath());
        }

        List<String> argv = new ArrayList<String>();
        for (com.fasterxml.jackson.databind.JsonNode element : entry.target) {
            argv.add(TemplateSubstitution.fill(element.asText(), filled));
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        return "Launched pid " + process.pid();
    }
}
