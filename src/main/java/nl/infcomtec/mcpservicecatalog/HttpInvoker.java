package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Invoker for CatalogEntry.kind "http". Two target shapes:
 * - a bare string: GET that URL (after TemplateSubstitution), no body.
 * - an object {"url":..., "method":"POST", "body":{...template...}}:
 *   method defaults to GET if absent; body, if present, has every string
 *   value in it (recursively) run through TemplateSubstitution and is
 *   sent as the request body with Content-Type: application/json — this
 *   is what a chat-completions call (LM Studio/Ollama, OpenAI-style
 *   /v1/chat/completions) needs, since the prompt has to go in the JSON
 *   body, not the URL.
 *
 * If the call arguments include "file_id", it is resolved through
 * FileService to an on-disk path (same convention as ProcessInvoker) and
 * exposed to the target template as {file_base64} — the file's bytes,
 * base64-encoded — so a catalog entry can embed uploaded file contents
 * (e.g. an image) into a JSON request body, such as an OpenAI-style
 * vision chat-completions call's inline "data:...;base64,{file_base64}"
 * image_url, entirely from tools.json with no bespoke Java needed.
 */
public class HttpInvoker implements Invoker {

    public String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception {
        Map<String, Object> filled = new HashMap<String, Object>(arguments);
        Object fileId = arguments.get("file_id");
        if (fileId != null) {
            Path resolved = Main.FILES.resolve(fileId.toString());
            if (resolved == null) {
                throw new IllegalArgumentException("Unknown file_id: " + fileId);
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(resolved));
            filled.put("file_base64", base64);
        }
        JsonNode target = entry.target;
        String urlTemplate;
        String method;
        JsonNode bodyTemplate;
        if (target.isTextual()) {
            urlTemplate = target.asText();
            method = "GET";
            bodyTemplate = null;
        } else {
            urlTemplate = target.path("url").asText();
            method = target.has("method") ? target.get("method").asText() : "GET";
            bodyTemplate = target.has("body") ? target.get("body") : null;
        }

        String urlText = TemplateSubstitution.fill(urlTemplate, filled);
        URL url = URI.create(urlText).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);

        if (bodyTemplate != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            String bodyText = fillJson(bodyTemplate, filled).toString();
            OutputStream requestBody = connection.getOutputStream();
            try {
                requestBody.write(bodyText.getBytes(StandardCharsets.UTF_8));
            } finally {
                requestBody.close();
            }
        }

        StringBuilder responseBody = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                responseBody.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        return responseBody.toString();
    }

    /**
     * Recursively rebuilds a JsonNode tree, running TemplateSubstitution
     * over every textual leaf, so {arg} placeholders can appear anywhere
     * inside a nested body template (e.g. a chat "messages" array).
     */
    private JsonNode fillJson(JsonNode template, Map<String, Object> arguments) {
        if (template.isTextual()) {
            return JsonNodeFactory.instance.textNode(TemplateSubstitution.fill(template.asText(), arguments));
        }
        if (template.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), fillJson(field.getValue(), arguments));
            }
            return result;
        }
        if (template.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : template) {
                result.add(fillJson(element, arguments));
            }
            return result;
        }
        return template;
    }
}
