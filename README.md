# AP-U1 · TurnoJusto

Aporte práctico de la Unidad I de Aplicaciones Distribuidas (ISR-701), Grupo 2.
Estudiante: Mario Sebastián Castro Coello.

Un coordinador (id 0) entrega números de turno consecutivos y sin repetir, con un cupo por jornada. Tres ventanillas (V1, V2 y V3, ids 1 a 3) le piden turnos, llaman al siguiente en espera y le envían latidos. Todos los procesos llevan un reloj de Lamport y escriben su bitácora en `logs/`.

Hecho en Java 21 con sockets TCP. No usa bibliotecas externas.

## Estructura

```
src/                 Coordinador, Ventanilla, PruebaCarga, OrdenTotal y clases de apoyo
config.properties    cupo, token, host, puerto y tiempos de latido
logs/                bitacora de cada proceso y orden_total.log
docs/informe.tex     informe (y informe.pdf)
docs/img/            capturas de E0 a E6
docs/entrega_SGA.tex pagina unica para el SGA (y su PDF)
```

## Configuración

`config.properties` se lee desde la carpeta donde se ejecuta el proceso, que debe ser la raíz del repositorio.

| Clave | Valor | Uso |
|---|---|---|
| `host` | `localhost` | dirección del coordinador |
| `puerto` | `5000` | puerto TCP del coordinador |
| `token` | `TJ-2026-UTEQ` | token que debe llevar toda solicitud |
| `cupo` | `10` | turnos máximos por jornada |
| `latido.intervalo.ms` | `2000` | cada cuánto envía latido una ventanilla |
| `latido.timeout.ms` | `6000` | tiempo sin latidos para marcar una ventanilla como CAIDA |
| `log.latidos` | `false` | si es `true`, los latidos también se escriben en la bitácora |
| `logs.dir` | `logs` | carpeta de los registros |

Todos los procesos aceptan `--config <ruta>` para usar otro archivo.

## Compilar

Desde la raíz del repositorio, en PowerShell:

```
javac -encoding UTF-8 -d out src\*.java
```

En IntelliJ IDEA: marcar `src` como Sources Root, dejar el *Working directory* de cada configuración en la raíz del proyecto y usar Build > Build Project.

## Ejecutar

Cada proceso va en su propia terminal. Orden de arranque:

1. Coordinador:
   ```
   java -cp out Coordinador
   ```
   Para el modo inseguro del escenario E3:
   ```
   java -cp out Coordinador --inseguro
   ```
2. Ventanillas, una por terminal:
   ```
   java -cp out Ventanilla 1
   java -cp out Ventanilla 2
   java -cp out Ventanilla 3
   ```
   Órdenes por consola: `turno`, `llamar`, `estado` y `salir`.
   Para probar un token incorrecto (E2): `java -cp out Ventanilla 2 --token MALO`
3. Prueba de carga, con el coordinador recién iniciado (los turnos emitidos en 0):
   ```
   java -cp out PruebaCarga
   ```
4. Orden total, al terminar los escenarios:
   ```
   java -cp out OrdenTotal
   ```
   Genera `logs/orden_total.log`. Acepta como argumento otra carpeta de registros.

Las bitácoras se abren en modo de agregar, así que se acumulan entre ejecuciones. Para empezar de cero hay que borrar el contenido de `logs/`.

## Formato de los mensajes

Cada mensaje es un JSON plano de una sola línea terminado en salto de línea (`\n`). El salto de línea es el delimitador: el receptor lee una línea completa por mensaje, así que varias solicitudes enviadas seguidas no se mezclan. El coordinador responde cada línea con otra línea.

Solicitudes de la ventanilla al coordinador:

```
{"tipo":"TURNO","token":"TJ-2026-UTEQ","ventanilla":2,"lamport":17}
{"tipo":"LLAMAR","token":"TJ-2026-UTEQ","ventanilla":2,"lamport":21}
{"tipo":"ESTADO","token":"TJ-2026-UTEQ","ventanilla":2,"lamport":25}
{"tipo":"LATIDO","token":"TJ-2026-UTEQ","ventanilla":2,"lamport":30}
```

Respuestas del coordinador:

```
{"tipo":"RESPUESTA","resultado":"ASIGNADO","numero":8,"emitidos":8,"cupo":10,"lamport":19}
{"tipo":"RESPUESTA","resultado":"LLAMADO","numero":3,"lamport":23}
{"tipo":"RESPUESTA","resultado":"ESTADO","emitidos":4,"cupo":10,"asignaciones":4,"espera":"3,4","llamados":"1->V2,2->V3","ventanillas":"V1=ACTIVA,V2=ACTIVA,V3=CAIDA","modo":"NORMAL","lamport":27}
{"tipo":"RESPUESTA","resultado":"RECHAZADA","solicitud":"TURNO","motivo":"cupo agotado","lamport":31}
{"tipo":"RESPUESTA","resultado":"LATIDO_OK","lamport":32}
```

Motivos de rechazo: `cupo agotado`, `no hay turnos en espera`, `token invalido` y `tipo de solicitud desconocido`. Si la ventanilla no puede conectarse con el coordinador, rechaza la solicitud por su cuenta y no entrega ningún número.

El campo `lamport` es el reloj del emisor al enviar. Al recibir, el proceso fija su reloj en `max(local, recibido) + 1`.

## Bitácoras

Archivos: `logs/coordinador.log`, `logs/V1.log`, `logs/V2.log`, `logs/V3.log` y `logs/orden_total.log`.

Una línea por evento, con el formato:

```
lamport;id_proceso;evento;detalle
```

`OrdenTotal` ordena por la marca de Lamport y, si hay empate, por el id del proceso.

## Compilar el informe

- Compilador: `pdflatex`
- Archivo principal: `docs/informe.tex`
- Paquetes: `inputenc`, `fontenc`, `babel` (spanish), `geometry`, `graphicx`, `array`, `booktabs`, `float`
- Las capturas deben estar en `docs/img/` con los nombres `e0.png` a `e6.png`

```
cd docs
pdflatex informe.tex
pdflatex informe.tex
```

Se compila dos veces para que se resuelvan las referencias a tablas y figuras.

La página del SGA se compila igual:

```
cd docs
pdflatex entrega_SGA.tex
```

## Código reutilizado

| Archivo de este repo | Repositorio propio de origen (URL) | Archivo y commit de origen | Qué se cambió |
|---|---|---|---|
| - | - | - | - |

No se reutilizó código.
