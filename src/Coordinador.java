import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinador central de TurnoJusto (id 0).
 * Uso: java -cp out Coordinador [--inseguro] [--config ruta]
 */
public class Coordinador {
    private static final int ID = 0;
    private static final int[] VENTANILLAS = {1, 2, 3};

    private final Config cfg;
    private final boolean inseguro;
    private final RelojLamport reloj;

    // ----- Estado de los turnos -----
    private int ultimo = 0;                                  // ultimo numero emitido
    private final Deque<Integer> espera = new ArrayDeque<>(); // turnos en espera (FIFO)
    private final List<String> llamados = new ArrayList<>();  // "1->V2", "2->V3", ...
    private final AtomicInteger respuestasAsignado = new AtomicInteger(); // ASIGNADO enviados

    // ----- Estado de las ventanillas (latidos) -----
    private final ConcurrentHashMap<Integer, Long> ultimoLatido = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> estadoVent = new ConcurrentHashMap<>();

    public Coordinador(Config cfg, boolean inseguro) throws IOException {
        this.cfg = cfg;
        this.inseguro = inseguro;
        this.reloj = new RelojLamport(ID, "coordinador", cfg.logsDir);
        for (int v : VENTANILLAS) estadoVent.put(v, "CAIDA"); // aun no envia latidos
    }

    public static void main(String[] args) throws IOException {
        boolean inseguro = false;
        for (String a : args) {
            if (a.equals("--inseguro") || a.equals("-inseguro")) inseguro = true;
        }
        Config cfg = Config.desdeArgs(args);
        new Coordinador(cfg, inseguro).iniciar();
    }

    public void iniciar() throws IOException {
        reloj.evento("INICIO", "puerto=" + cfg.puerto + " cupo=" + cfg.cupo
                + " modo=" + (inseguro ? "INSEGURO" : "NORMAL"));
        System.out.println("[Coordinador] escuchando en puerto " + cfg.puerto
                + " | cupo=" + cfg.cupo + " | modo=" + (inseguro ? "INSEGURO" : "NORMAL"));

        // Hilo que revisa los latidos cada 500 ms
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(this::revisarLatidos, 500, 500, TimeUnit.MILLISECONDS);

        // Un hilo por conexion
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(cfg.puerto, 100)) {
            while (true) {
                Socket s = server.accept();
                pool.execute(() -> atenderConexion(s));
            }
        }
    }

    /** Lee mensajes de una conexion, uno por linea, y responde cada uno con una linea. */
    private void atenderConexion(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String linea;
            while ((linea = in.readLine()) != null) {
                if (linea.isBlank()) continue;
                Mensaje resp = procesar(Mensaje.deLinea(linea));
                out.println(resp.aLinea());
            }
        } catch (SocketException e) {
            // la ventanilla cerro la conexion (por ejemplo, se detuvo)
        } catch (IOException e) {
            System.out.println("[Coordinador] error de conexion: " + e.getMessage());
        }
    }

    private Mensaje procesar(Mensaje m) {
        String tipo = m.texto("tipo") == null ? "DESCONOCIDO" : m.texto("tipo");
        int v = m.entero("ventanilla", -1);
        int lamportMsg = m.entero("lamport", 0);
        boolean esLatido = tipo.equals("LATIDO");

        // 1) Recepcion: actualizar reloj de Lamport
        if (esLatido && !cfg.logLatidos) reloj.recibirSinLog(lamportMsg);
        else reloj.recibir(lamportMsg, "RECIBE_" + tipo, "de=V" + v + " lamport_msg=" + lamportMsg);

        // 2) Autenticacion
        String token = m.texto("token");
        if (token == null || !token.equals(cfg.token)) {
            reloj.evento("TOKEN_INVALIDO", "de=V" + v + " tipo=" + tipo
                    + " token=" + (token == null ? "(sin token)" : token));
            System.out.println("[Coordinador] RECHAZADA por token invalido (V" + v + ", " + tipo + ")");
            return rechazo(v, tipo, "token invalido");
        }

        // 3) Atender la solicitud
        return switch (tipo) {
            case "TURNO" -> turno(v);
            case "LLAMAR" -> llamar(v);
            case "ESTADO" -> estado(v);
            case "LATIDO" -> latido(v, lamportMsg);
            default -> rechazo(v, tipo, "tipo de solicitud desconocido");
        };
    }

    // ================= Parte B: turnos =================

    private Mensaje turno(int v) {
        Integer numero = inseguro ? asignarInseguro() : asignarSeguro();
        if (numero == null) {
            return rechazo(v, "TURNO", "cupo agotado");
        }
        respuestasAsignado.incrementAndGet();
        System.out.println("[Coordinador] turno " + numero + " ASIGNADO a V" + v);
        int L = reloj.evento("ENVIA_RESPUESTA", "a=V" + v + " resultado=ASIGNADO numero=" + numero);
        return new Mensaje().put("tipo", "RESPUESTA").put("resultado", "ASIGNADO")
                .put("numero", numero).put("emitidos", leerUltimo()).put("cupo", cfg.cupo)
                .put("lamport", L);
    }

    /**
     * Modo normal: leer, comprobar el cupo, incrementar y encolar se hacen
     * dentro de un mismo bloque synchronized (exclusion mutua). Solo un hilo a la vez.
     */
    private synchronized Integer asignarSeguro() {
        if (ultimo >= cfg.cupo) return null;
        ultimo = ultimo + 1;
        espera.addLast(ultimo);
        return ultimo;
    }

    /**
     * Modo --inseguro: la lectura y la actualizacion de 'ultimo' van separadas
     * por 50 ms y sin bloqueo. Varios hilos leen el mismo valor (condicion de carrera).
     */
    private Integer asignarInseguro() {
        int leido = ultimo;                 // lectura
        if (leido >= cfg.cupo) return null;
        dormir(50);                         // pausa entre lectura y actualizacion
        int nuevo = leido + 1;
        ultimo = nuevo;                     // actualizacion
        encolar(nuevo);
        return nuevo;
    }

    private synchronized void encolar(int numero) { espera.addLast(numero); }

    private synchronized int leerUltimo() { return ultimo; }

    /** LLAMAR: entrega el turno mas antiguo en espera; cada turno sale una sola vez. */
    private Mensaje llamar(int v) {
        Integer numero;
        synchronized (this) {
            numero = espera.pollFirst();
            if (numero != null) llamados.add(numero + "->V" + v);
        }
        if (numero == null) {
            return rechazo(v, "LLAMAR", "no hay turnos en espera");
        }
        System.out.println("[Coordinador] turno " + numero + " LLAMADO por V" + v);
        int L = reloj.evento("ENVIA_RESPUESTA", "a=V" + v + " resultado=LLAMADO numero=" + numero);
        return new Mensaje().put("tipo", "RESPUESTA").put("resultado", "LLAMADO")
                .put("numero", numero).put("lamport", L);
    }

    private Mensaje estado(int v) {
        String enEspera, listaLlamados;
        int emitidos;
        synchronized (this) {
            emitidos = ultimo;
            enEspera = unir(new ArrayList<>(espera));
            listaLlamados = String.join(",", llamados);
        }
        String vent = estadoVentanillas();
        int L = reloj.evento("ENVIA_RESPUESTA", "a=V" + v + " resultado=ESTADO emitidos=" + emitidos
                + " espera=[" + enEspera + "] " + vent);
        return new Mensaje().put("tipo", "RESPUESTA").put("resultado", "ESTADO")
                .put("emitidos", emitidos).put("cupo", cfg.cupo)
                .put("asignaciones", respuestasAsignado.get())
                .put("espera", enEspera).put("llamados", listaLlamados)
                .put("ventanillas", vent).put("modo", inseguro ? "INSEGURO" : "NORMAL")
                .put("lamport", L);
    }

    private Mensaje rechazo(int v, String tipo, String motivo) {
        int L = reloj.evento("ENVIA_RESPUESTA", "a=V" + v + " resultado=RECHAZADA solicitud=" + tipo
                + " motivo=" + motivo);
        return new Mensaje().put("tipo", "RESPUESTA").put("resultado", "RECHAZADA")
                .put("solicitud", tipo).put("motivo", motivo).put("lamport", L);
    }

    // ================= Parte C: latidos =================

    private Mensaje latido(int v, int lamportMsg) {
        ultimoLatido.put(v, System.currentTimeMillis());
        synchronized (estadoVent) {
            if (!"ACTIVA".equals(estadoVent.get(v))) {
                estadoVent.put(v, "ACTIVA");
                reloj.evento("VENTANILLA_ACTIVA", "V" + v + " envia latidos");
                System.out.println("[Coordinador] V" + v + " marcada como ACTIVA");
            }
        }
        int L = cfg.logLatidos
                ? reloj.evento("ENVIA_RESPUESTA", "a=V" + v + " resultado=LATIDO_OK")
                : reloj.eventoSinLog();
        return new Mensaje().put("tipo", "RESPUESTA").put("resultado", "LATIDO_OK").put("lamport", L);
    }

    /** Si una ventanilla activa lleva mas de timeout ms sin latir, se marca CAIDA. */
    private void revisarLatidos() {
        long ahora = System.currentTimeMillis();
        synchronized (estadoVent) {
            for (int v : VENTANILLAS) {
                Long ult = ultimoLatido.get(v);
                if (ult == null) continue;
                long sinLatido = ahora - ult;
                if ("ACTIVA".equals(estadoVent.get(v)) && sinLatido > cfg.timeoutLatido) {
                    estadoVent.put(v, "CAIDA");
                    reloj.evento("VENTANILLA_CAIDA", "V" + v + " sin latidos por " + sinLatido + " ms");
                    System.out.println("[Coordinador] V" + v + " marcada como CAIDA ("
                            + sinLatido + " ms sin latidos)");
                }
            }
        }
    }

    private String estadoVentanillas() {
        StringBuilder sb = new StringBuilder();
        for (int v : VENTANILLAS) {
            if (sb.length() > 0) sb.append(',');
            sb.append('V').append(v).append('=').append(estadoVent.get(v));
        }
        return sb.toString();
    }

    private static String unir(List<Integer> lista) {
        StringBuilder sb = new StringBuilder();
        for (Integer n : lista) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        return sb.toString();
    }

    private static void dormir(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
