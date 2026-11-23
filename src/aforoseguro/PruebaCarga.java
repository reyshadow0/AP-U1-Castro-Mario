package aforoseguro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class PruebaCarga {
    public static void main(String[] args) throws Exception {
        int hilosPorPuerta = 10;
        for (int k = 0; k < args.length; k++)
            if (args[k].equals("--hilos") && k + 1 < args.length) hilosPorPuerta = Integer.parseInt(args[++k]);

        Config cfg = new Config();
        String host = cfg.host();
        int puerto = cfg.puerto();
        String token = cfg.token();
        RelojLamport reloj = new RelojLamport();
        int total = hilosPorPuerta * Config.NOMBRES.size();
        CyclicBarrier barrera = new CyclicBarrier(total); // todos salen a la vez
        AtomicInteger permitidas = new AtomicInteger();
        AtomicInteger rechazadas = new AtomicInteger();
        AtomicInteger errores = new AtomicInteger();

        Map<String, Object> inicial = estado(host, puerto, token, reloj);
        System.out.println("Antes: dentro " + inicial.get("dentro") + "/" + inicial.get("aforo"));

        List<Thread> hilos = new ArrayList<>();
        for (int puerta : Config.NOMBRES.keySet()) {
            for (int k = 0; k < hilosPorPuerta; k++) {
                hilos.add(new Thread(() -> {
                    try {
                        barrera.await();
                        Map<String, Object> resp = Mensajes.pedir(host, puerto, Mensajes.mensaje(
                                "tipo", "ENTRAR", "token", token, "puerta", puerta, "lamport", reloj.tick()), 15000);
                        reloj.recibir(Mensajes.entero(resp, "lamport", 0));
                        if ("PERMITIDA".equals(resp.get("resultado"))) permitidas.incrementAndGet();
                        else rechazadas.incrementAndGet();
                    } catch (Exception e) {
                        errores.incrementAndGet();
                    }
                }));
            }
        }
        for (Thread h : hilos) h.start();
        for (Thread h : hilos) h.join();

        Map<String, Object> fin = estado(host, puerto, token, reloj);
        String resumen = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " | solicitudes=" + total + " permitidas=" + permitidas + " rechazadas=" + rechazadas
                + " errores=" + errores + " | coordinador: dentro=" + fin.get("dentro") + " aforo=" + fin.get("aforo");
        System.out.println(resumen);
        Files.createDirectories(Path.of("logs"));
        Files.writeString(Path.of("logs", "prueba_carga.txt"), resumen + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Map<String, Object> estado(String host, int puerto, String token, RelojLamport reloj)
            throws IOException {
        Map<String, Object> resp = Mensajes.pedir(host, puerto, Mensajes.mensaje(
                "tipo", "ESTADO", "token", token, "puerta", 1, "lamport", reloj.tick()), 3000);
        reloj.recibir(Mensajes.entero(resp, "lamport", 0));
        return resp;
    }
}
