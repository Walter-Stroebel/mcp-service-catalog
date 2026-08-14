package nl.infcomtec.mcpservicecatalog;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

/**
 * Static methods named by tools.json's default "method"-kind entries —
 * the successors to the old hardcoded AddTool/EchoTool/RollDiceTool.
 * Each must be public static, taking and returning what MethodInvoker
 * expects: Map<String,Object> in, String out.
 */
public class DemoBackend {

    private static final Random RANDOM = new Random();

    public static String add(Map<String, Object> arguments) {
        int a = ((Number) arguments.get("a")).intValue();
        int b = ((Number) arguments.get("b")).intValue();
        return Integer.toString(a + b);
    }

    public static String echo(Map<String, Object> arguments) {
        return String.valueOf(arguments.get("text"));
    }

    public static String rollDice(Map<String, Object> arguments) {
        int sides = ((Number) arguments.get("sides")).intValue();
        return Integer.toString(RANDOM.nextInt(sides) + 1);
    }

    /**
     * Resolves file_id through Main.FILES (uploaded earlier via
     * FileService's own PUT /files — see MANUAL.md/docs/archive), reads
     * and base64-encodes the bytes here, and POSTs to the local vision
     * model exactly as look_at_image's original http-kind entry did. The
     * caller (model) only ever handles a short file_id string, never the
     * image bytes themselves — see
     * docs/archive/2026-08-13-look-at-image-hang.md for why that matters:
     * a large base64 string as tool-call *input* stalls the calling
     * model's own response stream, independent of anything server-side.
     * An earlier attempt at this (before FileService existed) tried a
     * bare filesystem path instead and failed, because the caller and
     * this server are on separate machines with no shared filesystem —
     * FileService's own upload endpoint is what makes file_id valid here
     * regardless of which machine the caller is running on.
     */
    public static String lookAtImage(Map<String, Object> arguments) throws Exception {
        String fileId = String.valueOf(arguments.get("file_id"));
        String question = String.valueOf(arguments.get("question"));
        Path path = Main.FILES.resolve(fileId);
        if (path == null) {
            throw new IllegalArgumentException("Unknown file_id: " + fileId);
        }
        Object ext = arguments.get("image_ext");
        String extension = ext == null || ext.toString().isBlank() ? "jpg" : ext.toString();
        String imageData = Base64.getEncoder().encodeToString(Files.readAllBytes(path));

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("model", "gemma-vision");
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "text").put("text", question);
        ObjectNode imageBlock = content.addObject();
        imageBlock.put("type", "image_url");
        imageBlock.putObject("image_url").put("url", "data:image/" + extension + ";base64," + imageData);

        URL url = URI.create("http://localhost:8081/v1/chat/completions").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        OutputStream requestBody = connection.getOutputStream();
        try {
            requestBody.write(body.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            requestBody.close();
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
}
