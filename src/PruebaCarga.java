import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lanza 30 solicitudes TURNO simultaneas (10 hilos por ventanilla).
 * Arranque el coordinador desde cero antes de cada ejecucion (cupo 10).
 * Uso: java -cp out PruebaCarga [--config ruta]
 */
public class PruebaCarga {
    private static final int HILOS_POR_VENTANILLA = 10;
    private static final AtomicInteger lamport = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        Config cfg = Config.desdeArgs(args);
        int total = HILOS_POR_VENTANILLA * 3;

        List<Integer> numeros = Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> motivos = new ConcurrentHashMap<>();
        AtomicInteger asignadas = new AtomicInteger();
        AtomicInteger rechazadas = new AtomicInteger();
        AtomicInteger errores = new AtomicInteger();

        CountDownLatch listos = new CountDownLatch(total);  // todos conectados
        CountDownLatch salida = new CountDownLatch(1);      // senal de inicio comun
        List<Thread> hilos = new ArrayList<>();

        for (int v = 1; v <= 3; v++) {
            for (int i = 0; i < HILOS_POR_VENTANILLA; i++) {
                final int ventanilla = v;
                Thread t = new Thread(() -> {
                    try (Socket s = new Socket(cfg.host, cfg.puerto);
                         BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                         PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
                        Mensaje m = new Mensaje().put("tipo", "TURNO").put("token", cfg.token)
                                .put("ventanilla", ventanilla).put("lamport", lamport.incrementAndGet());
                        listos.countDown();
                        salida.await();                 // todos envian al mismo tiempo
                        out.println(m.aLinea());
                        Mensaje r = Mensaje.deLinea(in.readLine());
                        if ("ASIGNADO".equals(r.texto("resultado"))) {
                            asignadas.incrementAndGet();
                            numeros.add(r.entero("numero", -1));
                        } else {
                            rechazadas.incrementAndGet();
                            motivos.merge(String.valueOf(r.texto("motivo")), 1, Integer::sum);
                        }
                    } catch (Exception e) {
                        errores.incrementAndGet();
                        listos.countDown();
                    }
                });
                hilos.add(t);
                t.start();
            }
        }

        listos.await();
        long t0 = System.currentTimeMillis();
        salida.countDown();
        for (Thread t : hilos) t.join();
        long ms = System.currentTimeMillis() - t0;

        // Contar repetidos
        Map<Integer, Integer> veces = new TreeMap<>();
        for (int n : numeros) veces.merge(n, 1, Integer::sum);
        TreeSet<Integer> distintos = new TreeSet<>(numeros);
        List<String> repetidos = new ArrayList<>();
        veces.forEach((n, c) -> { if (c > 1) repetidos.add(n + " (x" + c + ")"); });

        Mensaje estado = pedirEstado(cfg);

        System.out.println("========== PruebaCarga ==========");
        System.out.println("Modo del coordinador   : " + estado.texto("modo"));
        System.out.println("Solicitudes enviadas   : " + total + " (10 por ventanilla)");
        System.out.println("Asignadas              : " + asignadas.get());
        System.out.println("Rechazadas             : " + rechazadas.get() + " " + motivos);
        System.out.println("Errores de conexion    : " + errores.get());
        System.out.println("Numeros distintos      : " + distintos.size() + " " + distintos);
        System.out.println("Numeros repetidos      : " + (repetidos.isEmpty() ? "ninguno" : repetidos));
        System.out.println("Coordinador dice emitidos: " + estado.texto("emitidos")
                + " (cupo " + estado.texto("cupo") + ")");
        System.out.println("Tiempo                 : " + ms + " ms");

        boolean ok = asignadas.get() == cfg.cupo && distintos.size() == cfg.cupo
                && distintos.first() == 1 && distintos.last() == cfg.cupo;
        System.out.println(ok
                ? "RESULTADO: OK, " + cfg.cupo + " numeros distintos del 1 al " + cfg.cupo
                : "RESULTADO: INCORRECTO, hay repetidos, saltos o se supero el cupo");
    }

    private static Mensaje pedirEstado(Config cfg) throws IOException {
        try (Socket s = new Socket(cfg.host, cfg.puerto);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            out.println(new Mensaje().put("tipo", "ESTADO").put("token", cfg.token)
                    .put("ventanilla", 0).put("lamport", lamport.incrementAndGet()).aLinea());
            return Mensaje.deLinea(in.readLine());
        }
    }
}
