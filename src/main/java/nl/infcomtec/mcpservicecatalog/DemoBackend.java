package nl.infcomtec.mcpservicecatalog;

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
}
