package aforoseguro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Bitacora {
    private final Path ruta;
    private final int idProceso;
    private final boolean eco;

    public Bitacora(String nombre, int idProceso, boolean eco) {
        this.ruta = Path.of("logs", nombre + ".log");
        this.idProceso = idProceso;
        this.eco = eco;
        try {
            Files.createDirectories(ruta.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo crear logs/", e);
        }
    }

    public synchronized void registrar(int lamport, String evento, String detalle) {
        String limpio = detalle.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        String linea = lamport + ";" + idProceso + ";" + evento + ";" + limpio;
        try {
            Files.writeString(ruta, linea + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("No se pudo escribir en " + ruta + ": " + e.getMessage());
        }
        if (eco) System.out.println(linea);
    }
}
