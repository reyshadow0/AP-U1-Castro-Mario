package aforoseguro;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;


public class Coordinador {
    record Resultado(String resultado, String motivo, int dentro) {}

    private final int aforo;
    private final String token;
    private final boolean inseguro;
    private final long pausaMs;
    private final long timeoutLatidoMs;

    private final Object lockAforo = new Object();
    private volatile int dentro = 0;

    private final Object lockPuertas = new Object();
    private final Map<Integer, Long> ultimoLatido = new HashMap<>();
    private final Map<Integer, String> estadoPuertas = new TreeMap<>();

    private final RelojLamport reloj = new RelojLamport();
    private final Bitacora log = new Bitacora("coordinador", 0, true);

    Coordinador(Config cfg, int aforo, boolean inseguro) {
        this.aforo = aforo;
        this.token = cfg.token();
        this.inseguro = inseguro;
        this.pausaMs = cfg.entero("pausa_inseguro_ms", 50);
        this.timeoutLatidoMs = cfg.entero("timeout_latido", 6) * 1000L;
        for (int p : Config.NOMBRES.keySet()) estadoPuertas.put(p, "SIN_LATIDOS");
    }

    // ---------- aforo ----------
    Resultado entrar() throws InterruptedException {
        if (inseguro) {
            // consulta y actualizacion separadas, a proposito: condicion de carrera
            boolean hayCupo = dentro < aforo;
            Thread.sleep(pausaMs);
            if (hayCupo) {
                dentro++;
                return new Resultado("PERMITIDA", "", dentro);
            }
            return new Resultado("RECHAZADA", "aforo completo", dentro);
        }
        synchronized (lockAforo) { // consulta + actualizacion en una sola seccion critica
            if (dentro < aforo) {
                dentro++;
                return new Resultado("PERMITIDA", "", dentro);
            }
            return new Resultado("RECHAZADA", "aforo completo", dentro);
        }
    }

    Resultado salir() {
        synchronized (lockAforo) {
            if (dentro > 0) {
                dentro--;
                return new Resultado("PERMITIDA", "", dentro);
            }
            return new Resultado("RECHAZADA", "nadie dentro", dentro);
        }
    }

    // ---------- latidos ----------
    void latido(int puerta) {
        synchronized (lockPuertas) {
            ultimoLatido.put(puerta, System.nanoTime());
            String anterior = estadoPuertas.get(puerta);
            if (!"ACTIVA".equals(anterior)) {
                estadoPuertas.put(puerta, "ACTIVA");
                log.registrar(reloj.tick(), "PUERTA_ACTIVA", Config.NOMBRES.get(puerta) + " (antes " + anterior + ")");
            }
        }
    }

    void vigilar() {
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            long ahora = System.nanoTime();
            synchronized (lockPuertas) {
                for (Map.Entry<Integer, Long> e : ultimoLatido.entrySet()) {
                    int p = e.getKey();
                    long sinLatidoMs = (ahora - e.getValue()) / 1_000_000;
                    if ("ACTIVA".equals(estadoPuertas.get(p)) && sinLatidoMs > timeoutLatidoMs) {
                        estadoPuertas.put(p, "CAIDA");
                        log.registrar(reloj.tick(), "PUERTA_CAIDA",
                                Config.NOMBRES.get(p) + " sin latidos hace " + sinLatidoMs + " ms");
                    }
                }
            }
        }
    }

    String puertasTexto() {
        synchronized (lockPuertas) {
            StringJoiner sj = new StringJoiner(", ");
            estadoPuertas.forEach((p, e) -> sj.add(Config.NOMBRES.get(p) + "=" + e));
            return sj.toString();
        }
    }

    // ---------- mensajes ----------
    /** Procesa un mensaje. Devuelve la respuesta, o null si no lleva respuesta (latidos). */
    Map<String, Object> atender(Map<String, Object> msg) throws InterruptedException {
        String tipo = String.valueOf(Mensajes.texto(msg, "tipo"));
        int puerta = Mensajes.entero(msg, "puerta", -1);
        log.registrar(reloj.recibir(Mensajes.entero(msg, "lamport", 0)), "RECIBIR", tipo + " de puerta " + puerta);

        String tokenRecibido = Mensajes.texto(msg, "token");
        if (!token.equals(tokenRecibido)) {
            log.registrar(reloj.tick(), "TOKEN_INVALIDO",
                    tipo + " de puerta " + puerta + " con token '" + tokenRecibido + "'");
            if (tipo.equals("LATIDO")) return null;
            return responder(tipo, puerta, new Resultado("RECHAZADA", "token invalido", dentro));
        }

        Resultado r;
        switch (tipo) {
            case "LATIDO" -> {
                if (Config.NOMBRES.containsKey(puerta)) latido(puerta);
                return null;
            }
            case "ENTRAR" -> r = entrar();
            case "SALIR" -> r = salir();
            case "ESTADO" -> r = new Resultado("PERMITIDA", "", dentro);
            default -> r = new Resultado("RECHAZADA", "tipo desconocido " + tipo, dentro);
        }
        return responder(tipo, puerta, r);
    }

    Map<String, Object> responder(String tipo, int puerta, Resultado r) {
        int l = reloj.tick();
        String detalle = "RESPUESTA " + tipo + " " + r.resultado() + " a puerta " + puerta + " dentro=" + r.dentro();
        if (!r.motivo().isEmpty()) detalle += " motivo=" + r.motivo();
        log.registrar(l, "ENVIAR", detalle);
        return Mensajes.mensaje("tipo", "RESPUESTA", "solicitud", tipo, "resultado", r.resultado(),
                "motivo", r.motivo(), "dentro", r.dentro(), "aforo", aforo,
                "puertas", puertasTexto(), "lamport", l);
    }

    void atenderConexion(Socket s) {
        try (s; BufferedReader in = Mensajes.lector(s); BufferedWriter out = Mensajes.escritor(s)) {
            String linea;
            while ((linea = in.readLine()) != null) { // una conexion puede traer varias solicitudes seguidas
                if (linea.isBlank()) continue;
                Map<String, Object> msg;
                try {
                    msg = Mensajes.deJson(linea);
                } catch (IllegalArgumentException e) {
                    log.registrar(reloj.tick(), "MENSAJE_INVALIDO", e.getMessage());
                    return;
                }
                Map<String, Object> resp = atender(msg);
                if (resp != null) Mensajes.enviar(out, resp);
            }
        } catch (IOException e) {
            // la puerta cerro la conexion; no hay nada que hacer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws IOException {
        boolean inseguro = false;
        Integer aforoArg = null;
        for (int k = 0; k < args.length; k++) {
            String a = args[k].replace('–', '-');
            if (a.equals("--inseguro") || a.equals("-inseguro")) inseguro = true;
            else if (a.equals("--aforo") && k + 1 < args.length) aforoArg = Integer.parseInt(args[++k]);
        }
        Config cfg = new Config();
        int aforo = aforoArg != null ? aforoArg : cfg.aforo();
        Coordinador c = new Coordinador(cfg, aforo, inseguro);
        c.log.registrar(c.reloj.tick(), "INICIO",
                "aforo=" + aforo + " modo=" + (inseguro ? "INSEGURO" : "NORMAL") + " puerto=" + cfg.puerto());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> c.log.registrar(c.reloj.tick(), "FIN", "coordinador detenido")));

        Thread vigia = new Thread(c::vigilar, "vigia-latidos");
        vigia.setDaemon(true);
        vigia.start();

        // backlog 64 para aguantar las 30 conexiones simultaneas de PruebaCarga
        try (ServerSocket servidor = new ServerSocket(cfg.puerto(), 64, InetAddress.getByName(cfg.host()))) {
            System.out.println("Coordinador escuchando en " + cfg.host() + ":" + cfg.puerto());
            while (true) {
                Socket s = servidor.accept();
                new Thread(() -> c.atenderConexion(s)).start();
            }
        }
    }
}
