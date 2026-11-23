import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mensaje plano tipo JSON de una sola linea:
 *   {"tipo":"TURNO","token":"...","ventanilla":2,"lamport":17}
 * Cada mensaje viaja en una linea terminada en '\n' (delimitacion por separador de linea).
 * Solo admite valores texto o enteros, sin objetos anidados.
 */
public class Mensaje {
    private final Map<String, Object> campos = new LinkedHashMap<>();

    private static final Pattern PAR =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+)");

    public Mensaje put(String clave, String valor) { campos.put(clave, valor); return this; }
    public Mensaje put(String clave, int valor)    { campos.put(clave, valor); return this; }

    public String texto(String clave) {
        Object v = campos.get(clave);
        return v == null ? null : v.toString();
    }

    public int entero(String clave, int porDefecto) {
        Object v = campos.get(clave);
        if (v == null) return porDefecto;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return porDefecto; }
    }

    /** Convierte el mensaje a una linea (sin el '\n' final). */
    public String aLinea() {
        StringBuilder sb = new StringBuilder("{");
        boolean primero = true;
        for (Map.Entry<String, Object> e : campos.entrySet()) {
            if (!primero) sb.append(',');
            primero = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Integer) sb.append(v);
            else sb.append('"').append(escapar(v.toString())).append('"');
        }
        return sb.append('}').toString();
    }

    /** Lee una linea recibida. Devuelve un mensaje vacio si la linea no tiene pares validos. */
    public static Mensaje deLinea(String linea) {
        Mensaje m = new Mensaje();
        if (linea == null) return m;
        Matcher mt = PAR.matcher(linea);
        while (mt.find()) {
            String clave = mt.group(1);
            if (mt.group(3) != null) m.campos.put(clave, desescapar(mt.group(3)));
            else m.campos.put(clave, Integer.parseInt(mt.group(2)));
        }
        return m;
    }

    private static String escapar(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String desescapar(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    @Override
    public String toString() { return aLinea(); }
}
