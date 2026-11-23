# AP-U1 AforoSeguro - Castro Mario

Prototipo de control de aforo distribuido para el Coliseo de Quevedo. Tiene un coordinador (id 0) que lleva la cuenta global y tres puertas (P1 Norte, P2 Sur y P3 Este, ids 1 a 3) que le piden permiso para cada entrada y salida. Todo se comunica por sockets TCP en Java 21, sin bibliotecas externas.

## Requisitos

- JDK 21
- IntelliJ IDEA, o `javac` y `java` desde la consola
- MiKTeX o TeX Live para compilar el informe

## Configuración

El archivo `config.properties` está en la raíz del proyecto:

| Clave | Valor | Uso |
|---|---|---|
| `aforo` | 10 | Máximo de personas dentro |
| `token` | AFORO-UTEQ-2026 | Token que debe llevar toda solicitud |
| `host` | 127.0.0.1 | Dirección del coordinador |
| `puerto` | 5000 | Puerto del coordinador |
| `intervalo_latido` | 2 | Segundos entre latidos de cada puerta |
| `timeout_latido` | 6 | Segundos sin latido para marcar una puerta como CAIDA |
| `pausa_inseguro_ms` | 50 | Pausa entre consulta y actualización en modo `--inseguro` |

Todos los procesos leen el archivo desde el directorio de trabajo, así que hay que ejecutarlos desde la raíz del proyecto.

## Compilación y ejecución

### Desde la consola (PowerShell, en la raíz del proyecto)

```powershell
javac -d out src/aforoseguro/*.java
```

Cada proceso va en una consola distinta, en este orden:

```powershell
java -cp out aforoseguro.Coordinador            # 1. coordinador normal
java -cp out aforoseguro.Puerta 1               # 2. P1 Norte
java -cp out aforoseguro.Puerta 2               # 3. P2 Sur
java -cp out aforoseguro.Puerta 3               # 4. P3 Este
```

Programas de prueba:

```powershell
java -cp out aforoseguro.Coordinador --inseguro # coordinador en modo inseguro (E3)
java -cp out aforoseguro.PruebaCarga            # 30 ENTRAR simultáneas, 10 hilos por puerta
java -cp out aforoseguro.OrdenTotal             # genera logs/orden_total.log
```

### Argumentos

- Coordinador: `--inseguro` activa el modo inseguro y `--aforo N` reemplaza el aforo del archivo de configuración.
- Puerta: `1`, `2` o `3`.
- PruebaCarga: `--hilos N` cambia los hilos por puerta (10 por defecto).

### Órdenes en la consola de cada puerta

| Orden | Qué hace |
|---|---|
| `entrar` | Pide permiso para que entre una persona |
| `entrar N` | Repite la solicitud N veces |
| `salir` | Avisa que salió una persona |
| `estado` | Muestra personas dentro, aforo y el estado de las tres puertas |
| `probar-token VALOR` | Manda un ENTRAR con un token distinto, para el escenario E2 |
| `fin` | Cierra la puerta |

## Formato de los mensajes

Cada mensaje es un objeto JSON en una sola línea terminada en salto de línea (`\n`). El JSON generado nunca contiene saltos de línea, así que el `\n` separa un mensaje del siguiente y varias solicitudes seguidas no se mezclan (requisito A2). Del lado que recibe se lee con `readLine()`.

Solicitud de una puerta:

```json
{"tipo":"ENTRAR","token":"AFORO-UTEQ-2026","puerta":2,"lamport":17}
```

`tipo` puede ser `ENTRAR`, `SALIR`, `ESTADO` o `LATIDO`. El latido no recibe respuesta.

Respuesta del coordinador:

```json
{"tipo":"RESPUESTA","solicitud":"ENTRAR","resultado":"PERMITIDA","motivo":"","dentro":8,"aforo":10,"puertas":"P1 Norte=ACTIVA, P2 Sur=ACTIVA, P3 Este=CAIDA","lamport":19}
```

`resultado` es `PERMITIDA` o `RECHAZADA`. Cuando es `RECHAZADA`, `motivo` dice `aforo completo`, `token invalido` o `nadie dentro`.

## Registros

Cada proceso escribe `logs/<proceso>.log` (`coordinador.log`, `P1.log`, `P2.log`, `P3.log`), con una línea por evento:

```
lamport;id_proceso;evento;detalle
```

`OrdenTotal` une los cuatro archivos y los ordena por Lamport. Si hay empate, va primero el id de proceso menor. El resultado queda en `logs/orden_total.log`. Las cifras de PruebaCarga se agregan a `logs/prueba_carga.txt`.

## Compilación del informe

- Compilador: `pdflatex`
- Archivos principales: `docs/informe.tex` y `docs/entrega_SGA.tex`
- Las imágenes se leen desde `docs/img/`

```powershell
cd docs
pdflatex informe.tex
pdflatex informe.tex
pdflatex entrega_SGA.tex
```

`informe.tex` se compila dos veces para que se resuelvan las referencias a figuras y tablas.

## Código reutilizado

| Archivo de este repo | Repositorio propio de origen (URL) | Archivo y commit de origen | Qué se cambió |
|---|---|---|---|
| `src/aforoseguro/*.java` | COMPLETAR: URL de tu repositorio anterior | COMPLETAR: archivo y hash del commit | COMPLETAR |

<!-- Si no reutilizaste código, borra la tabla y escribe: No se reutilizó código. -->