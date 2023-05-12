package lt.lukasa.packetwrapper.generator;

import java.util.LinkedList;
import java.util.Queue;

/**
 * @author Lukas Alt
 * @since 05.05.2023
 */
public class Utils {
    public static String parseByteCodeType(String s) {
        System.out.println("Parsing '" + s + "'");
        final LinkedList<Character> queue = new LinkedList<>();
        for (char c : s.toCharArray()) {
            queue.add(c);
        }
        return parseByteCodeType(queue);
    }

    private static String parseByteCodeType(Queue<Character> queue) {
        char first = queue.poll();
        System.out.println(first);
        switch (Character.toUpperCase(first)) {
            case 'V':
                return "void";
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'D':
                return "double";
            case 'F':
                return "float";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'S':
                return "short";
            case 'Z':
                return "boolean";
            case '[':
                return parseByteCodeType(queue) + "[]";
            case 'L':

                StringBuilder sb = new StringBuilder();
                while (!queue.isEmpty()) {
                    char c = queue.poll();
                    if (c == ';') {
                        break;
                    }
                    sb.append(c);
                }
                return sb.toString();
            default:
                throw new IllegalArgumentException("Invalid type indicator: " + first);
        }
    }
}
