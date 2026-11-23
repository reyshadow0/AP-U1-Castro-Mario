package aforoseguro;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public final class Config {
    public static final Map<Integer, String> NOMBRES = Map.of(1, "P1 Norte", 2, "P2 Sur", 3, "P3 Este");

    private final Properties p = new Properties();

    public Config() throws IOException {
        try (Reader r = Files.newBufferedReader(Path.of("config.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        }
    }

    public String texto(String clave) {
        String v = p.getProperty(clave);
        if (v == null) throw new IllegalStateException("Falta '" + clave + "' en config.properties");
        return v.trim();
    }

    public int entero(String clave, int porDefecto) {
        String v = p.getProperty(clave);
        return v == null ? porDefecto : Integer.parseInt(v.trim());
    }

    public String host() { return texto("host"); }
    public int puerto() { return Integer.parseInt(texto("puerto")); }
    public String token() { return texto("token"); }
    public int aforo() { return Integer.parseInt(texto("aforo")); }
}
