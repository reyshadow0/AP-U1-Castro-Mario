package aforoseguro;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;


public class Puerta {
    private final int id;
    private final String nombre;
    private final String host;
    private final int puerto;
    private final String token;
    private final long intervaloMs;
    private final RelojLamport reloj = new RelojLamport();
    private final Bitacora log;
    private volatile boolean coordinadorOk = true;

    Puerta(int id, Config cfg) {
        this.id = id;
        this.nombre = Config.NOMBRES.get(id);
        this.host = cfg.host();
        this.puerto = cfg.puerto();
        this.token = cfg.token();
        this.intervaloMs = cfg.entero("intervalo_latido", 2) * 1000L;
        this.log = new Bitacora("P" + id, id, false);
    }

    void latir() {
        long siguiente = System.nanoTime();
        while (true) {
            int l = reloj.tick();
            log.registrar(l, "ENVIAR", "LATIDO");
            try {
                Mensajes.avisar(host, puerto, Mensajes.mensaje(
                        "tipo", "LATIDO", "token", token, "puerta", id, "lamport", l), 1000);
                if (!coordinadorOk) {
                    coordinadorOk = true;
                    log.registrar(reloj.tick(), "COORDINADOR_RECUPERADO", "el latido volvio a llegar");
                }
            } catch (IOException e) {
                if (coordinadorOk) {
                    coordinadorOk = false;
                    log.registrar(reloj.tick(), "COORDINADOR_NO_RESPONDE", "fallo el envio del latido");
                }
            }
            siguiente += intervaloMs * 1_000_000;
            long esperaMs = (siguiente - System.nanoTime()) / 1_000_000;
            try {
                if (esperaMs > 0) Thread.sleep(esperaMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    void solicitar(String tipo, String tokenUsado) {
        int l = reloj.tick();
        log.registrar(l, "ENVIAR", tipo);
        Map<String, Object> resp;
        try {
            resp = Mensajes.pedir(host, puerto, Mensajes.mensaje(
                    "tipo", tipo, "token", tokenUsado, "puerta", id, "lamport", l), 3000);
        } catch (IOException e) {
            // Sin coordinador no hay cuenta global: se niega la entrada (consistencia antes que disponibilidad)
            log.registrar(reloj.tick(), "COORDINADOR_NO_DISPONIBLE", tipo + " sin respuesta: " + e.getMessage());
            switch (tipo) {
                case "ENTRAR" -> System.out.println("  ENTRADA NO PERMITIDA: el coordinador no responde y no se puede asegurar el aforo.");
                case "SALIR" -> System.out.println("  SALIDA NO REGISTRADA: el coordinador no responde. Anote la salida y repita luego.");
                default -> System.out.println("  Estado no disponible: el coordinador no responde.");
            }
            return;
        }
        l = reloj.recibir(Mensajes.entero(resp, "lamport", 0));
        log.registrar(l, "RECIBIR", "RESPUESTA " + tipo + " " + resp.get("resultado") + " dentro=" + resp.get("dentro"));
        mostrar(tipo, resp);
    }

    void mostrar(String tipo, Map<String, Object> resp) {
        String linea = "  " + tipo + ": " + resp.get("resultado");
        String motivo = Mensajes.texto(resp, "motivo");
        if (motivo != null && !motivo.isEmpty()) linea += " (" + motivo + ")";
        System.out.println(linea + " | dentro " + resp.get("dentro") + "/" + resp.get("aforo"));
        if (tipo.equals("ESTADO")) {
            for (String p : String.valueOf(resp.get("puertas")).split(", ")) System.out.println("    " + p);
        }
    }

    void consola() throws IOException {
        System.out.println("Puerta " + nombre + " lista. Ordenes: entrar [n], salir, estado, probar-token <valor>, fin");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("P" + id + "> ");
            System.out.flush();
            String linea = in.readLine();
            if (linea == null) break;
            String[] partes = linea.trim().split("\\s+");
            String orden = partes[0].toLowerCase();
            switch (orden) {
                case "" -> { }
                case "entrar" -> {
                    int veces = 1;
                    if (partes.length > 1 && partes[1].matches("\\d+")) veces = Integer.parseInt(partes[1]);
                    for (int k = 0; k < veces; k++) solicitar("ENTRAR", token);
                }
                case "salir" -> solicitar("SALIR", token);
                case "estado" -> solicitar("ESTADO", token);
                case "probar-token" -> {
                    if (partes.length > 1) solicitar("ENTRAR", partes[1]);
                    else System.out.println("  Uso: probar-token <valor>");
                }
                case "fin" -> { return; }
                default -> System.out.println("  Orden no reconocida");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int id = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        if (!Config.NOMBRES.containsKey(id)) {
            System.err.println("Uso: Puerta <1|2|3>");
            return;
        }
        Puerta p = new Puerta(id, new Config());
        p.log.registrar(p.reloj.tick(), "INICIO", p.nombre);
        Thread latidos = new Thread(p::latir, "latidos");
        latidos.setDaemon(true);
        latidos.start();
        p.consola();
        p.log.registrar(p.reloj.tick(), "FIN", p.nombre);
        System.exit(0);
    }
}
