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
 */
public class HttpInvoker implements Invoker {

    public String invoke(CatalogEntry entry, Map<String, Object> arguments) throws Exception {
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

        String urlText = TemplateSubstitution.fill(urlTemplate, arguments);
        URL url = URI.create(urlText).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);

        if (bodyTemplate != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            String bodyText = fillJson(bodyTemplate, arguments).toString();
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
