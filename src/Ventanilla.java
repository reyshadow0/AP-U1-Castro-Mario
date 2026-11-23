import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Ventanilla de TurnoJusto (id 1, 2 o 3).
 * Uso: java -cp out Ventanilla <id> [--token valor] [--config ruta]
 * Ordenes por consola: turno, llamar, estado, salir
 */
public class Ventanilla {
    private final int id;
    private final Config cfg;
    private final String token;
    private final RelojLamport reloj;

    // Una sola conexion compartida por la consola y el hilo de latidos
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private volatile boolean coordinadorAccesible = true;

    public Ventanilla(int id, Config cfg, String token) throws IOException {
        this.id = id;
        this.cfg = cfg;
        this.token = token;
        this.reloj = new RelojLamport(id, "V" + id, cfg.logsDir);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Uso: java -cp out Ventanilla <1|2|3> [--token valor] [--config ruta]");
            return;
        }
        int id = Integer.parseInt(args[0]);
        Config cfg = Config.desdeArgs(args);
        String token = cfg.token;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--token")) token = args[i + 1]; // para probar token invalido (E2)
        }
        new Ventanilla(id, cfg, token).iniciar();
    }

    public void iniciar() {
        reloj.evento("INICIO", "ventanilla V" + id);
        System.out.println("[V" + id + "] lista. Ordenes: turno | llamar | estado | salir");

        Thread latidos = new Thread(this::cicloLatidos, "latidos-V" + id);
        latidos.setDaemon(true);
        latidos.start();

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("V" + id + "> ");
            if (!sc.hasNextLine()) break;
            String orden = sc.nextLine().trim().toLowerCase();
            switch (orden) {
                case "turno" -> ejecutar("TURNO");
                case "llamar" -> ejecutar("LLAMAR");
                case "estado" -> ejecutar("ESTADO");
                case "salir" -> { reloj.evento("FIN", "ventanilla V" + id); System.exit(0); }
                case "" -> { }
                default -> System.out.println("Orden no valida. Use: turno | llamar | estado | salir");
            }
        }
    }

    /** Envia TURNO, LLAMAR o ESTADO y muestra la respuesta. */
    private void ejecutar(String tipo) {
        int L = reloj.evento("ENVIA_" + tipo, "a=coordinador");
        Mensaje m = new Mensaje().put("tipo", tipo).put("token", token)
                .put("ventanilla", id).put("lamport", L);
        try {
            Mensaje r = enviar(m);
            int lr = r.entero("lamport", 0);
            String res = r.texto("resultado");
            reloj.recibir(lr, "RECIBE_RESPUESTA", "solicitud=" + tipo + " resultado=" + res
                    + (r.texto("numero") != null ? " numero=" + r.texto("numero") : "")
                    + (r.texto("motivo") != null ? " motivo=" + r.texto("motivo") : "")
                    + " lamport_msg=" + lr);
            mostrar(r);
        } catch (IOException e) {
            // E5: sin coordinador no se entrega ningun numero (se prioriza la consistencia)
            reloj.evento("SIN_COORDINADOR", "solicitud=" + tipo + " no atendida: " + e.getMessage());
            System.out.println("[V" + id + "] RECHAZADA localmente: coordinador no disponible ("
                    + e.getMessage() + ").");
            if (tipo.equals("TURNO")) {
                System.out.println("[V" + id + "] No se entrega un numero propio para no repetir turnos."
                        + " Pida a la persona que espere y reintente cuando el coordinador vuelva.");
            }
        }
    }

    private void mostrar(Mensaje r) {
        String res = r.texto("resultado");
        if (res == null) { System.out.println("Respuesta no valida: " + r); return; }
        switch (res) {
            case "ASIGNADO" -> System.out.println("ASIGNADO: turno " + r.texto("numero")
                    + " (emitidos " + r.texto("emitidos") + " de " + r.texto("cupo") + ")");
            case "LLAMADO" -> System.out.println("LLAMADO: atender el turno " + r.texto("numero"));
            case "RECHAZADA" -> System.out.println("RECHAZADA (" + r.texto("solicitud") + "): "
                    + r.texto("motivo"));
            case "ESTADO" -> {
                System.out.println("---- ESTADO (modo " + r.texto("modo") + ") ----");
                System.out.println("Emitidos  : " + r.texto("emitidos") + " de " + r.texto("cupo"));
                System.out.println("En espera : [" + r.texto("espera") + "]");
                System.out.println("Llamados  : [" + r.texto("llamados") + "]");
                System.out.println("Ventanillas: " + r.texto("ventanillas"));
            }
            default -> System.out.println(r);
        }
    }

    // ================= Latidos =================

    private void cicloLatidos() {
        while (true) {
            int L = cfg.logLatidos ? reloj.evento("ENVIA_LATIDO", "a=coordinador") : reloj.eventoSinLog();
            Mensaje m = new Mensaje().put("tipo", "LATIDO").put("token", token)
                    .put("ventanilla", id).put("lamport", L);
            try {
                Mensaje r = enviar(m);
                int lr = r.entero("lamport", 0);
                if (cfg.logLatidos) reloj.recibir(lr, "RECIBE_RESPUESTA", "solicitud=LATIDO resultado="
                        + r.texto("resultado") + " lamport_msg=" + lr);
                else reloj.recibirSinLog(lr);
                if (!coordinadorAccesible) {
                    coordinadorAccesible = true;
                    reloj.evento("COORDINADOR_ACCESIBLE", "responde latidos otra vez");
                    System.out.println("\n[V" + id + "] el coordinador vuelve a responder");
                }
            } catch (IOException e) {
                if (coordinadorAccesible) {
                    coordinadorAccesible = false;
                    reloj.evento("COORDINADOR_INACCESIBLE", "latido sin respuesta: " + e.getMessage());
                    System.out.println("\n[V" + id + "] el coordinador no responde a los latidos");
                }
            }
            try { Thread.sleep(cfg.intervaloLatido); } catch (InterruptedException e) { return; }
        }
    }

    // ================= Comunicacion =================

    /**
     * Envia un mensaje (una linea) y espera su respuesta (una linea).
     * synchronized: la consola y el hilo de latidos no mezclan sus mensajes en el socket.
     * Si la conexion esta rota, se reconecta una vez y se reintenta.
     */
    private synchronized Mensaje enviar(Mensaje m) throws IOException {
        for (int intento = 1; intento <= 2; intento++) {
            try {
                if (socket == null || socket.isClosed()) conectar();
                out.println(m.aLinea());
                if (out.checkError()) throw new IOException("no se pudo escribir");
                String linea = in.readLine();
                if (linea == null) throw new IOException("conexion cerrada por el coordinador");
                return Mensaje.deLinea(linea);
            } catch (IOException e) {
                cerrar();
                if (intento == 2) throw e;
            }
        }
        throw new IOException("sin respuesta");
    }

    private void conectar() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(cfg.host, cfg.puerto), 2000); // 2 s para conectar
        socket.setSoTimeout(3000);                                         // 3 s para responder
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    private void cerrar() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
    }
}
