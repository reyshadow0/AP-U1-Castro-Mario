import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Lee config.properties (por defecto en la carpeta desde donde se ejecuta). */
public class Config {
    public final String host;
    public final int puerto;
    public final String token;
    public final int cupo;
    public final int intervaloLatido;
    public final int timeoutLatido;
    public final boolean logLatidos;
    public final String logsDir;

    public Config(String ruta) throws IOException {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(ruta)) {
            p.load(in);
        }
        host = p.getProperty("host", "localhost").trim();
        puerto = Integer.parseInt(p.getProperty("puerto", "5000").trim());
        token = p.getProperty("token", "").trim();
        cupo = Integer.parseInt(p.getProperty("cupo", "10").trim());
        intervaloLatido = Integer.parseInt(p.getProperty("latido.intervalo.ms", "2000").trim());
        timeoutLatido = Integer.parseInt(p.getProperty("latido.timeout.ms", "6000").trim());
        logLatidos = Boolean.parseBoolean(p.getProperty("log.latidos", "false").trim());
        logsDir = p.getProperty("logs.dir", "logs").trim();
    }

    /** Busca "--config ruta" en los argumentos; si no esta, usa config.properties. */
    public static Config desdeArgs(String[] args) throws IOException {
        String ruta = "config.properties";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--config")) ruta = args[i + 1];
        }
        return new Config(ruta);
    }
}
