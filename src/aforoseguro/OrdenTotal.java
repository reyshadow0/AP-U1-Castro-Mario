package aforoseguro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OrdenTotal {
    record Evento(int lamport, int idProceso, String linea) {}

    public static void main(String[] args) throws IOException {
        String[] archivos = {"coordinador.log", "P1.log", "P2.log", "P3.log"};
        List<Evento> eventos = new ArrayList<>();
        for (String nombre : archivos) {
            Path ruta = Path.of("logs", nombre);
            if (!Files.exists(ruta)) {
                System.out.println("Aviso: no existe " + ruta);
                continue;
            }
            int n = 0;
            for (String linea : Files.readAllLines(ruta, StandardCharsets.UTF_8)) {
                n++;
                linea = linea.strip();
                if (linea.isEmpty()) continue;
                String[] partes = linea.split(";", 4);
                try {
                    eventos.add(new Evento(Integer.parseInt(partes[0]), Integer.parseInt(partes[1]), linea));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    System.out.println("Aviso: linea " + n + " de " + nombre + " con formato incorrecto, se omite");
                }
            }
        }
        // empate en Lamport: va primero el id menor (List.sort es estable)
        eventos.sort(Comparator.comparingInt(Evento::lamport).thenComparingInt(Evento::idProceso));
        List<String> salida = eventos.stream().map(Evento::linea).toList();
        Files.write(Path.of("logs", "orden_total.log"), salida, StandardCharsets.UTF_8);
        System.out.println(salida.size() + " eventos escritos en logs/orden_total.log");
    }
}
