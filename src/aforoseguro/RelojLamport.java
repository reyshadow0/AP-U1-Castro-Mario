package aforoseguro;

public final class RelojLamport {
    private int valor = 0;

    public synchronized int tick() {
        valor++;
        return valor;
    }

    public synchronized int recibir(int recibido) {
        valor = Math.max(valor, recibido) + 1;
        return valor;
    }
}
