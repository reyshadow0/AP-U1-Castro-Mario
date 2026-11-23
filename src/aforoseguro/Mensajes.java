package aforoseguro;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Mensajes {
    private Mensajes() {}

    // ---------- JSON ----------
    public static String aJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean primero = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!primero) sb.append(',');
            primero = false;
            sb.append(cadena(e.getKey())).append(':');
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else if (v == null) sb.append("null");
            else sb.append(cadena(v.toString()));
        }
        return sb.append('}').toString();
    }

    private static String cadena(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public static Map<String, Object> deJson(String texto) {
        return new Lector(texto).objeto();
    }

    /** Lector minimo para objetos JSON planos. Lanza IllegalArgumentException si el texto no es valido. */
    private static final class Lector {
        private final String s;
        private int i = 0;

        Lector(String s) { this.s = s; }

        Map<String, Object> objeto() {
            Map<String, Object> m = new LinkedHashMap<>();
            espacios();
            esperar('{');
            espacios();
            if (ver() == '}') { i++; return m; }
            while (true) {
                espacios();
                String clave = texto();
                espacios();
                esperar(':');
                espacios();
                m.put(clave, valor());
                espacios();
                char c = siguiente();
                if (c == '}') break;
                if (c != ',') throw error("se esperaba , o }");
            }
            espacios();
            if (i != s.length()) throw error("texto sobrante");
            return m;
        }

        private Object valor() {
            char c = ver();
            if (c == '"') return texto();
            if (c == '-' || Character.isDigit(c)) {
                int inicio = i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                try {
                    return Long.parseLong(s.substring(inicio, i));
                } catch (NumberFormatException e) {
                    throw error("numero invalido");
                }
            }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return "true"; }
            if (s.startsWith("false", i)) { i += 5; return "false"; }
            throw error("valor no soportado");
        }

        private String texto() {
            esperar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = siguiente();
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = siguiente();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("escape incompleto");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("escape invalido");
                }
            }
        }

        private void espacios() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private char ver() { if (i >= s.length()) throw error("fin inesperado"); return s.charAt(i); }
        private char siguiente() { char c = ver(); i++; return c; }
        private void esperar(char c) { if (siguiente() != c) throw error("se esperaba " + c); }
        private IllegalArgumentException error(String m) { return new IllegalArgumentException(m + " en posicion " + i); }
    }

    // ---------- lectura de campos ----------
    public static int entero(Map<String, Object> m, String clave, int porDefecto) {
        Object v = m.get(clave);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? porDefecto : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    public static String texto(Map<String, Object> m, String clave) {
        Object v = m.get(clave);
        return v == null ? null : v.toString();
    }

    // ---------- red ----------
    public static BufferedReader lector(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    public static BufferedWriter escritor(Socket s) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static void enviar(BufferedWriter out, Map<String, Object> m) throws IOException {
        out.write(aJson(m));
        out.write('\n');
        out.flush();
    }

    /** Abre conexion, manda un mensaje y espera la respuesta. */
    public static Map<String, Object> pedir(String host, int puerto, Map<String, Object> m, int timeoutMs)
            throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, puerto), timeoutMs);
            s.setSoTimeout(timeoutMs);
            enviar(escritor(s), m);
            String linea = lector(s).readLine();
            if (linea == null) throw new IOException("el coordinador cerro la conexion sin responder");
            try {
                return deJson(linea);
            } catch (IllegalArgumentException e) {
                throw new IOException("respuesta invalida: " + e.getMessage());
            }
        }
    }

    /** Manda un mensaje sin esperar respuesta (latidos). */
    public static void avisar(String host, int puerto, Map<String, Object> m, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, puerto), timeoutMs);
            enviar(escritor(s), m);
        }
    }

    /** Atajo para armar un mensaje en orden. */
    public static Map<String, Object> mensaje(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < pares.length; k += 2) m.put((String) pares[k], pares[k + 1]);
        return m;
    }
}
