import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Reloj de Lamport de un proceso junto con su bitacora logs/<proceso>.log.
 * El incremento del reloj y la escritura de la linea se hacen dentro del mismo
 * bloque synchronized, asi el valor registrado es exactamente el del evento.
 * Formato de cada linea: lamport;id_proceso;evento;detalle
 */
public class RelojLamport {
    private int valor = 0;
    private final int idProceso;
    private final PrintWriter log;

    public RelojLamport(int idProceso, String nombreProceso, String logsDir) throws IOException {
        this.idProceso = idProceso;
        File dir = new File(logsDir);
        if (!dir.exists()) dir.mkdirs();
        // append = true: los registros de varias ejecuciones (E1..E5) se conservan
        this.log = new PrintWriter(new FileWriter(new File(dir, nombreProceso + ".log"),
                StandardCharsets.UTF_8, true), true);
    }

    /** Evento local o envio: reloj = reloj + 1. Devuelve el valor para ponerlo en el mensaje. */
    public synchronized int evento(String evento, String detalle) {
        valor++;
        escribir(evento, detalle);
        return valor;
    }

    /** Igual que evento() pero sin escribir en la bitacora (se usa para latidos si log.latidos=false). */
    public synchronized int eventoSinLog() {
        valor++;
        return valor;
    }

    /** Recepcion: reloj = max(local, recibido) + 1. */
    public synchronized int recibir(int recibido, String evento, String detalle) {
        valor = Math.max(valor, recibido) + 1;
        escribir(evento, detalle);
        return valor;
    }

    public synchronized int recibirSinLog(int recibido) {
        valor = Math.max(valor, recibido) + 1;
        return valor;
    }

    public synchronized int valor() { return valor; }

    private void escribir(String evento, String detalle) {
        // el ';' es el separador, por eso no puede aparecer en el detalle
        String d = detalle == null ? "" : detalle.replace(';', ',');
        log.println(valor + ";" + idProceso + ";" + evento + ";" + d);
    }
}
