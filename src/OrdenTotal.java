import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Une coordinador.log, V1.log, V2.log y V3.log en logs/orden_total.log,
 * ordenado por marca de Lamport y, si empatan, por id de proceso.
 * Uso: java -cp out OrdenTotal [carpeta_logs]
 */
public class OrdenTotal {
    private static final String[] ARCHIVOS = {"coordinador.log", "V1.log", "V2.log", "V3.log"};

    record Evento(int lamport, int proceso, String linea) { }

    public static void main(String[] args) throws IOException {
        String dir = args.length > 0 ? args[0] : "logs";
        List<Evento> eventos = new ArrayList<>();

        for (String nombre : ARCHIVOS) {
            File f = new File(dir, nombre);
            if (!f.exists()) {
                System.out.println("Aviso: no existe " + f.getPath());
                continue;
            }
            int leidos = 0;
            for (String linea : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = linea.split(";", 4);  // lamport;id_proceso;evento;detalle
                if (p.length < 3) continue;
                try {
                    eventos.add(new Evento(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), linea));
                    leidos++;
                } catch (NumberFormatException e) {
                    // linea mal formada: se ignora
                }
            }
            System.out.println(nombre + ": " + leidos + " eventos");
        }

        // Orden total: primero Lamport, luego id de proceso (desempate)
        eventos.sort(Comparator.comparingInt(Evento::lamport).thenComparingInt(Evento::proceso));

        List<String> salida = new ArrayList<>();
        for (Evento e : eventos) salida.add(e.linea());
        File destino = new File(dir, "orden_total.log");
        Files.write(destino.toPath(), salida, StandardCharsets.UTF_8);
        System.out.println("Escrito " + destino.getPath() + " con " + salida.size() + " eventos");
    }
}
