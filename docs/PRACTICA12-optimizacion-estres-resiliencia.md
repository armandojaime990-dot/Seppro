# Práctica 12 — Optimización, pruebas de estrés y resiliencia (SEPPRO)

**Alumno:** Armando Jaime Ramírez · **Matrícula:** 2022348004 · **Grupo:** IDGS14 · **Materia:** Aplicaciones Móviles
**Proyecto:** SEPPRO — app Android (Kotlin, `seppro-movil`) + backend Supabase (PostgREST + PostgreSQL 15 + Auth + Realtime + Storage)
**Fechas de medición:** 11 y 12 de noviembre de 2026

## Entorno de prueba

| Elemento | Valor |
|---|---|
| Dispositivo | Emulador Pixel 8a, API 36 (Android 16), 4 núcleos, GPU por software (SwiftShader) |
| Herramientas de perfilado | `adb top` (CPU), `dumpsys meminfo` (RAM PSS), `dumpsys gfxinfo`, Logcat (`Choreographer`, `SEPPRO_Perf`) |
| Carga de API | Node.js 25 (`fetch` nativo), script propio `estres-api.mjs` |
| Base de datos | Supabase SQL Editor, `EXPLAIN (ANALYZE, BUFFERS)` como rol `authenticated` con RLS activo |
| Pruebas automáticas | JUnit 4 + MockWebServer (57 pruebas, todas pasan) |
| Volumen de datos | 3 005 pedidos sintéticos para `cliente2` (marcados `[CARGA-P12]`, generados con `generar-volumen.mjs`) |

Las cifras de este reporte salen de las evidencias guardadas en `pruebas/practica12/evidencias/`; ninguna se estimó a mano.

---

## Fase 1. Hipótesis a partir de la auditoría anterior (Práctica 11)

| # | Problema seleccionado | Hipótesis técnica | ¿Se confirmó? |
|---|---|---|---|
| H1 | **Método lento:** `ListaPedidosFragment.mostrar()` crea una tarjeta (`crearTarjetaPedido`) por cada pedido en el hilo principal. En la P11 también se marcó `VerificarFragment.onEstado` (complejidad ciclomática 20). | Con muchos pedidos, construir cientos de vistas en el hilo principal congela la interfaz y el costo crece con el número de pedidos. | **Sí.** Con 670 tarjetas el hilo principal se bloqueó **822 ms** en promedio (máx. 1 419 ms) y hubo **13 congelamientos**. |
| H2 | **Endpoint con latencia:** `GET /rest/v1/pedidos?select=*,pedido_items(*)` (“Mis pedidos”) sin paginar. | Descarga todos los pedidos y todas sus columnas, así que la latencia y el tamaño crecen con el historial del cliente. | **Sí, y salió un defecto más grave:** PostgREST corta en 1 000 filas (respuesta **206 `0-999/3005`**). La app recibía **512 KB** y **perdía en silencio 2 005 pedidos**. |
| H3 | **Consulta optimizable:** pedidos por `usuario_id` ordenados por `created_at` y la subconsulta de `pedido_items`. | No hay índice en `pedidos.usuario_id` ni en `pedido_items.pedido_id`, así que cada pedido provoca un escaneo secuencial. | **Sí.** `Seq Scan on pedido_items` ×3 005 (≈9 millones de filas descartadas), **523 ms**. |
| H4 | **Conectividad/recuperación:** envío de mensajes y edición de reportes. | Un reintento después de un corte puede duplicar mensajes, y dos ediciones simultáneas pueden pisarse. | **Sí.** De 20 ediciones simultáneas, **19 se perdieron sin aviso**. El envío no era idempotente. |
| H5 | **Memoria/CPU:** la RAM crece con las tarjetas creadas. | La RAM sube en proporción a las vistas en pantalla. | **Sí.** RAM PSS de 60.9 a **146.4 MB**; CPU con picos de 130 % (de 400 %). |

---

## Fase 2. Perfilado de la aplicación móvil

**Guion reproducible:** `pruebas/practica12/perfil-app.sh <antes|despues>`, que dura unos 5 minutos y cubre las 6 pruebas obligatorias:

1. **Consulta repetida:** inicio de sesión como `cliente2` y “Actualizar” ×3 en *Mis pedidos*.
2. **Escritura:** 3 mensajes enviados en el chat de un reporte de soporte.
3. **Navegación:** pestañas Verificar, Historial, Soporte y Pedidos.
4. **Procesamiento de datos:** filtrado y construcción de tarjetas (y el filtro activo/historial).
5. **CPU y RAM:** `top` cada 1 s (150 muestras) y `dumpsys meminfo` cada 2 s.
6. **Bloqueos de interfaz:** `Choreographer: Skipped N frames`, `gfxinfo` y la marca `SEPPRO_Perf` que separa red, procesamiento e hilo principal.

`resumen-perfil.mjs` calcula los resúmenes y los guarda en `evidencias/<antes|despues>/resumen.json`.

| Medición | Antes | Después |
|---|---|---|
| CPU global prom. / p90 / máx. (% de 400) | 19.4 / 68 / 130 | 7.6 / 22.5 / 78.2 |
| CPU mientras la app trabaja (prom.) | 29.5 % | 12.6 % |
| RAM PSS inicial / máx. / final | 60.9 / **146.4** / 124.0 MB | 54.1 / **73.8** / 72.8 MB |
| *Mis pedidos*: tiempo total hasta dibujar (prom., 7 cargas) | **2 213.6 ms** (1 712 – 2 927) | **1 048.3 ms** (413 – 2 197) |
| *Mis pedidos*: hilo principal (prom. / máx.) | **822.4 / 1 419 ms** | **73.4 / 102 ms** |
| *Mis pedidos*: red (prom.) | 980.1 ms | 844 ms |
| Filas, bytes y vistas por carga | 670 filas · 512 236 B · 670 tarjetas | 20 filas · 3 206 B · 20 tarjetas |
| Congelamientos (Choreographer) | 13 eventos · 575 cuadros saltados | 3 eventos · 160 cuadros saltados |
| Cuadros lentos (gfxinfo) | 97.25 % (p95 200 ms) | 95.20 % (p95 200 ms) |
| Errores o cierres | 0 | 0 |

**Diagnóstico:**
- **El cuello de botella era el hilo principal, no la red.** Con 670 tarjetas, casi 40 % del tiempo de la operación (822 de 2 214 ms) se iba en construir vistas.
- **gfxinfo no mejora en el emulador.** Usa GPU por software (SwiftShader), así que casi todos los cuadros superan 16 ms con o sin optimización. La mejora real se ve en los congelamientos largos de Choreographer, que bajaron de 575 a 160 cuadros saltados.
- **La red es la parte más variable del “después”.** El valor máximo de 2 109 ms coincide con una latencia alta de Supabase en ese momento, no con la app.

---

## Fase 3. Pruebas de estrés de la API

**Script reproducible:** `SEPPRO_PASS=123456 node pruebas/practica12/estres-api.mjs <antes|despues>`. Hace 100 solicitudes por escenario con 1, 5, 10, 25 y 50 usuarios concurrentes.

**Endpoints probados:**
- **Lectura:** `GET /rest/v1/pedidos?select=*,pedido_items(*)&usuario_id=eq.<cliente1>`.
- **Escritura:** `POST /rest/v1/mensajes` (mensaje de chat en un reporte de prueba).

**Resultados del día 1** (`evidencias/estres-api-antes-dia1.json`), sin otra carga en la red:

| Endpoint | Usuarios | RPS | Prom. (ms) | P90 | P95 | Máx. | Errores | Códigos HTTP |
|---|---|---|---|---|---|---|---|---|
| Lectura | 1 | 9.75 | 102.6 | 107.9 | 113.8 | 161.0 | 0 % | 200 ×100 |
| Lectura | 5 | 34.98 | 142.1 | 267.1 | 316.5 | 363.8 | 0 % | 200 ×100 |
| Lectura | 10 | 70.65 | 133.3 | 255.3 | 288.3 | 304.8 | 0 % | 200 ×100 |
| Lectura | 25 | 124.63 | 168.3 | 299.1 | 314.9 | 329.1 | 0 % | 200 ×100 |
| Lectura | 50 | **176.29** | 213.5 | 386.1 | **415.9** | 450.4 | 0 % | 200 ×100 |
| Escritura | 1 | 10.29 | 97.1 | 102.6 | 106.3 | 119.0 | 0 % | 201 ×100 |
| Escritura | 5 | 48.94 | 101.2 | 110.1 | 112.8 | 132.4 | 0 % | 201 ×100 |
| Escritura | 10 | 91.41 | 101.4 | 112.4 | 118.6 | 195.2 | 0 % | 201 ×100 |
| Escritura | 25 | 222.92 | 105.4 | 125.6 | 129.3 | 133.8 | 0 % | 201 ×100 |
| Escritura | 50 | **258.75** | 160.8 | 200.2 | **223.1** | 289.1 | 0 % | 201 ×100 |

**Integridad:** los 500 mensajes enviados en la prueba de escritura quedaron guardados (conteo exacto en la base: 500).

**Repetición del día 2** (`estres-api-antes.json` y `estres-api-despues.json`, corridos uno tras otro): el mismo patrón, 0 % de errores y 1 000 de 1 000 respuestas 200/201. La latencia base fue unos 60 ms mayor por el estado de la red (escritura con 1 usuario: 97 ms el día 1 contra 155–186 ms el día 2). Los endpoints del servidor son los mismos en ambas corridas, así que esa diferencia es ruido de red y no se atribuye a la optimización.

**Comportamiento del servidor:**
- No hubo errores 429/5xx, cortes de conexión ni rechazos.
- Las 50 conexiones concurrentes de PostgREST se atendieron sin cola visible: `pg_stat_activity` mostró 0 sesiones esperando un bloqueo.

**Pregunta técnica: ¿el tiempo de respuesta crece en proporción a los usuarios, o hay un punto de saturación?**
- **No es proporcional.** De 1 a 50 usuarios (×50), el throughput de lectura creció ×18 (9.75 → 176 RPS) y la latencia promedio solo ×2.1 (102 → 213 ms).
- **En escritura la curva es aún más plana:** de 97 a 161 ms con ×25 en throughput.
- **No se alcanzó un punto de saturación en el rango probado.** El RPS siguió subiendo y no apareció ningún error. Sí hay degradación: el P95 de lectura se cuadruplica (114 → 416 ms) a partir de 5 usuarios, señal de que empieza a formarse cola en el pool de conexiones.
- **Para encontrar el punto de quiebre** habría que subir a más de 100 usuarios, lo cual no se hizo para no rebasar los límites del plan gratuito de Supabase.

**El endpoint crítico sí era lento por diseño, no por carga:** la consulta de *Mis pedidos* de `cliente2` (10 solicitudes seguidas) tardaba 834 ms y devolvía 512 KB. Esta es la consulta que se optimizó (ver la Fase 6).

---

## Fase 4. Pruebas avanzadas de base de datos

**Consulta crítica:** *Mis pedidos* de `cliente2` (3 005 pedidos), ejecutada con RLS igual que PostgREST.

**Script:** `pruebas/practica12/db-antes-despues.sql`. En una sola ejecución mide, aplica la optimización O2 y vuelve a medir.

**Evidencia:** `evidencias/db-antes-despues.txt`.

### Pruebas 1–4: tiempo, registros e índices

| Prueba | Registros | Antes: plan | Antes (ms) | Después: plan | Después (ms) | Mejora |
|---|---|---|---|---|---|---|
| 1. Individual por id | 1 | Index Scan pkey + **Seq Scan pedido_items** (3 019 descartadas) | 11.631 | Index Scan pkey + Index Scan `pedido_items_pedido_idx` | **0.085** | −99.3 % |
| 2. Conjunto pequeño (20) | 20 | Seq Scan pedidos + Sort top-N + Seq Scan items ×20 | 8.392 | Index Scan `pedidos_usuario_fecha_idx` (sin Sort) | **0.308** | −96.3 % |
| 3. Conjunto grande (consulta original de la app) | 3 005 | Seq Scan + Sort 902 kB + Seq Scan items **×3 005** · 96 217 buffers | **523.039** | Index Scan sin Sort · 20 944 buffers | **30.856** | **−94.1 %** |
| 4. Con filtros (activos, página 1, consulta nueva) | 20 de 2 046 | Seq Scan (973 descartadas) + Sort | 7.138 | Index Scan, 9 filas descartadas | **0.242** | −96.6 % |
| 4b. Conteo para “Mostrando 20 de N” | 1 (N = 2 046) | Seq Scan | 3.220 | Seq Scan (sin cambio) | 3.221 | 0 % |

### Pruebas 5–6: concurrencia

**Script:** `concurrencia.mjs`. 20 clientes leen el mismo reporte, le agregan su marca y lo guardan al mismo tiempo, con el patrón leer-modificar-escribir de “Editar reporte”.

| Medición | Antes (PATCH por id) | Después (PATCH por id + `updated_at`) |
|---|---|---|
| Respuestas “200 OK” | 20 | 20 |
| Marcas realmente guardadas | **1** | **20** |
| Escrituras perdidas sin aviso | **19** | **0** |
| Conflictos detectados y reintentados | 0 | 190 |
| Duración total | 625 ms | 20 749 ms |

### Análisis obligatorio

**Tiempo y registros:** están en las tablas anteriores. La consulta original devolvía 3 005 filas a la base, pero PostgREST solo entregaba **1 000** a la app.

**Uso de índices:**
- **Antes:** solo existían las llaves primarias. `pedido_items` no tenía índice en `pedido_id`, así que la subconsulta de artículos recorría la tabla completa por cada pedido.
- **Después:** `pedidos_usuario_fecha_idx (usuario_id, created_at DESC, id DESC)` resuelve a la vez el filtro y el orden, sin Sort; `pedido_items_pedido_idx` resuelve la subconsulta.
- **Costo en espacio:** 168 kB + 88 kB.

**Bloqueos o esperas:** 0 sesiones esperando un bloqueo durante las pruebas. PostgreSQL usa bloqueo por fila en `UPDATE`, así que las escrituras simultáneas se serializan sin interbloqueos.

**Consultas repetidas** (`pg_stat_statements`):
- La lectura completa de pedidos se ejecutó **534 veces** (18.8 ms en promedio, **10 s acumulados**) y la de `cliente2` 10 veces a **572 ms** cada una.
- La app pedía la lista entera en cada “Actualizar” y en cada cambio de pestaña, y Historial volvía a descargar lo mismo que Pedidos.
- La paginación con filtro en el servidor (O1) resuelve esto: cada pestaña pide solo su página de 20.

**Problemas de concurrencia:**
- **Actualización perdida:** el patrón leer-modificar-escribir sin control de versión hace que el último en guardar borre a los demás. Ningún cliente recibe un error, así que el usuario cree que su cambio quedó guardado.

**Integridad posterior:**
- **Antes:** la descripción final quedó solo con `W16`; se perdieron 19 de 20 cambios.
- **Después:** quedaron las 20 marcas, sin duplicados.
- **Mensajes:** los 500 del estrés se guardaron exactamente una vez.
- **Idempotencia:** el índice UNIQUE en `id_cliente` impide duplicados aunque el cliente reintente.

**Técnicas aplicadas:** índices; paginación; solo los campos necesarios; filtros y ordenamiento en el servidor, con orden estable `created_at DESC, id DESC`; menos consultas repetidas; y bloqueo optimista en lugar de una transacción larga (ver la Fase 6).

---

## Fase 5. Pruebas de resiliencia y recuperación

Se ejecutaron de tres formas:
- **Automáticas:** `ResilienciaTest.kt`, con MockWebServer para provocar cortes, 500, 401/403, silencio del servidor y respuestas perdidas.
- **En el emulador real:** modo avión con `cmd connectivity airplane-mode`.
- **Contra Supabase real:** `idempotencia-real.mjs`.

### Matriz de resiliencia

| Código | Escenario | Cómo se provocó | Resultado observado | Resultado esperado | Estado |
|---|---|---|---|---|---|
| RES-01 | Pérdida de Internet durante una consulta | Emulador en modo avión + “Actualizar” | Muestra “Error de red. Toca Actualizar.”; la app no se cierra; Logcat: `UnknownHostException` (evidencia `res01-sin-red.png`) | Error controlado | ✅ Cumple |
| RES-02 | Pérdida de Internet durante una escritura | `DISCONNECT_AT_START` ×3 | 3 intentos con espera progresiva y error a los 1 656 ms; el mensaje **no** aparece como enviado y el texto se conserva en la caja | No confirmar falsamente | ✅ Cumple |
| RES-03 | Servidor sin respuesta | `SocketPolicy.NO_RESPONSE` | `SocketTimeoutException` a los **20 015 ms** (readTimeout = 20 s) | Timeout controlado | ✅ Cumple (tiempo alto, ver D-09) |
| RES-04 | HTTP 500 | Respuesta 500 | `ApiException(500)`, mensaje en pantalla, **1 sola solicitud** (no se reintenta lo que el servidor rechazó) | Error y estabilidad | ✅ Cumple |
| RES-05 | HTTP 401 / 403 | 401 “JWT expired” y 403 de RLS | 401 → `SesionExpiradaException`, se cierra la sesión y vuelve al login; 403 → mensaje de permiso | Autenticación y permisos correctos | ✅ Cumple |
| RES-06 | Recuperación de conexión | Emulador: se quita el modo avión + “Actualizar”; prueba: 2 cortes y luego 201 | Emulador: vuelve “Mostrando 20 de 2046 pedidos” en **1 945 ms**; prueba: recuperado al 3er intento tras **1 735 ms** | Restablecer comunicación | ✅ Cumple |
| RES-07 | Reintento de una escritura | El servidor guarda pero se corta la respuesta (`DISCONNECT_AFTER_REQUEST`); y en Supabase real, el mismo `id_cliente` ×3 | Prueba: 3 solicitudes, **1 registro**, la app recupera el mensaje ya guardado (750 ms). Supabase real: **201 → 409 → 409, 1 registro** | Evitar duplicados | ✅ Cumple (antes de O3: ❌) |
| RES-08 | Respuesta tardía | Doble toque en Enviar con el mismo id; “Actualizar” con una respuesta anterior pendiente | Mismo id → 1 registro; el botón Enviar se desactiva mientras envía; las respuestas de cargas viejas se descartan (contador `generacion`) | Sin duplicados ni inconsistencias | ✅ Cumple |

### Análisis requerido

**¿Cuánto tarda la app en detectar una interrupción?**
- **Sin red (no hay DNS ni ruta):** es inmediato, menos de 0.6 s por intento (3 intentos con esperas incluidas suman 1 656 ms).
- **Servidor que acepta la conexión pero no responde:** 20 s, según `readTimeout`.
- **Servidor que no acepta la conexión:** hasta 15 s, según `connectTimeout`.

**¿Qué ocurre con una operación pendiente?**
- **Lecturas:** se cancelan con un mensaje y el usuario puede tocar “Actualizar”.
- **Mensajes:** el texto se conserva junto con su `id_cliente`, de modo que si el usuario vuelve a tocar Enviar se reutiliza el mismo id.
- **Ediciones de reporte:** si hay conflicto, el usuario recibe “Otra persona modificó el reporte” y el reporte se recarga.

**¿Se repite automáticamente la solicitud?**
- **Solo en los envíos de mensajes y solo ante fallas de red (`IOException`).** Hace hasta 3 intentos con espera progresiva de 500 ms → 1 s → 2 s, con tope de 4 s y +0–250 ms de variación aleatoria.
- **No se reintenta** ante 4xx ni 500, porque son respuestas definitivas del servidor.
- **Las lecturas no se reintentan solas**; es el usuario quien decide.

**¿Puede generarse un registro duplicado?**
- **Antes de O3, sí:** si la respuesta se perdía, el usuario reenviaba y se creaba otro mensaje.
- **Ahora no:** cada mensaje lleva un UUID `id_cliente` generado en el teléfono, y el índice `UNIQUE (id_cliente)` rechaza la segunda inserción con 409. Se comprobó contra Supabase real.

**¿Cómo se recupera el estado de la interfaz?**
- La lista se reinicia en cada “Actualizar” y el contador se limpia (corregido en D-06).
- Si falla una página posterior, las ya cargadas se conservan y aparece “Reintentar cargar más”.
- Las respuestas tardías de una carga anterior se ignoran.

**¿Qué pasa si el servidor procesa la operación pero la respuesta nunca llega?**
1. El cliente recibe `IOException` y reintenta con el **mismo** `id_cliente`.
2. El servidor responde 409 (el registro ya existe).
3. La app consulta `mensajes?id_cliente=eq.<uuid>` y muestra el mensaje ya guardado.

Resultado: un solo registro y una interfaz coherente (RES-07).

**Reto implementado:**
- **Reintento con espera progresiva:** `SoporteApi.esperaReintentoMs()`, con valores verificados en la prueba `esperaProgresiva_valores`.
- **Idempotencia por clave del cliente:** columna `mensajes.id_cliente` + `mensajes_id_cliente_uq`.
- **Bloqueo optimista para ediciones:** `PATCH …&updated_at=eq.<leído>`; 0 filas actualizadas → `ConflictoException`.

---

## Fase 6. Optimización real y comparación

| Id | Optimización | Archivos | Problema que resuelve |
|---|---|---|---|
| **O1** | Paginación de 20, solo los campos necesarios (`id,total,estado,created_at,pedido_items(nombre_producto)`), filtro activo/historial **en el servidor**, orden estable, botón “Cargar más” y contador “Mostrando X de N” (`Prefer: count=exact`) | `PedidosApi.kt`, `Api.kt` (`getConTotal`), `ListaPedidosFragment.kt`, `PedidosFragment.kt`, `HistorialFragment.kt` | H1, H2, H5, el corte silencioso en 1 000 y las consultas repetidas |
| **O2** | Índices `pedidos(usuario_id, created_at DESC, id DESC)` y `pedido_items(pedido_id)` | `db-antes-despues.sql` | H3 |
| **O3** | Envío idempotente (`id_cliente` + UNIQUE + manejo de 409) con reintento y espera progresiva | `SoporteApi.kt`, `ReporteActivity.kt`, `db-antes-despues.sql` | H4, RES-02/06/07/08 |
| **O4** | Bloqueo optimista por `updated_at` en la edición de reportes | `SoporteApi.kt`, `ReporteActivity.kt` | Actualizaciones perdidas |

- **Código original:** `pruebas/practica12/version-original/`.
- **Código optimizado:** `seppro-movil/app/src/main/java/com/seppro/movil/`.

Las mediciones del “después” repitieron **los mismos scripts, el mismo emulador, el mismo usuario y los mismos datos**. Además, la consulta crítica y la concurrencia se corrieron antes y después **uno tras otro el mismo día**, para quitar el efecto de la red.

### Tabla comparativa antes y después (obligatoria)

| Métrica | Antes | Después | Diferencia |
|---|---|---|---|
| Latencia promedio de la API, consulta crítica *Mis pedidos* (ms) | 453.3 (834.4 sin índices, día 1) | **165.2** | **−63.6 %** (−80.2 % contra el día 1) |
| P95 de la API, consulta crítica (ms) | 643.8 (901.1 sin índices) | **187.1** | **−70.9 %** (−79.2 %) |
| RPS, consulta crítica con 1 usuario (1 000 / prom.) | 2.21 (1.20 sin índices) | **6.05** | **+174 %** (×5) |
| Tamaño de la respuesta (bytes) | 512 236 (1 000 filas de 3 005) | **3 206** (20 filas + total exacto) | **−99.4 %** |
| Errores (%) en la API, estrés 1 000 solicitudes | 0 % | 0 % | Sin cambio (sin defectos nuevos) |
| Tiempo de consulta en BD, consulta original de la app (ms) | 523.04 | **30.86** | **−94.1 %** |
| Tiempo de consulta en BD, consulta de la app nueva (ms) | 523.04 (consulta original) | **0.242** | **−99.95 %** |
| RAM máxima (MB, PSS) | 146.4 | **73.8** | **−49.6 %** |
| RAM final (MB, PSS) | 124.0 | **72.8** | −41.3 % |
| CPU promedio (% de 400) | 19.4 (29.5 en actividad) | **7.6** (12.6 en actividad) | **−60.8 %** (−57.3 %) |
| CPU máxima (% de 400) | 130 | 78.2 | −39.8 % |
| Tiempo de la operación crítica, *Mis pedidos* hasta dibujar (ms) | 2 213.6 | **1 048.3** | **−52.6 %** |
| Hilo principal bloqueado por carga (ms) | 822.4 | **73.4** | **−91.1 %** |
| Congelamientos / cuadros saltados | 13 / 575 | 3 / 160 | −76.9 % / −72.2 % |
| Escrituras concurrentes perdidas (de 20) | 19 | **0** | −100 % |
| Mensajes duplicados al reintentar tras perder la respuesta | 1 por reintento | **0** | −100 % |
| Pedidos de cliente2 accesibles desde la app | 1 000 de 3 005 | **3 005 de 3 005** (paginado) | Defecto corregido |

**Cómo leer la tabla:**
- **Consulta crítica:** la referencia más justa es la columna “Antes” del mismo día, que ya tenía los índices (O2). Así se aísla el efecto de O1 (−63.6 %). La cifra del día 1 suma O1 + O2.
- **Concurrencia:** el costo de O4 es la latencia cuando hay conflicto (20.7 s para 20 escritores sobre **el mismo** registro, un caso extremo). En la app no se reintenta en ciclo; se avisa al usuario y se recarga.

---

## Resultados de rendimiento (PER-01 a PER-11)

| Código | Métrica | Resultado | Evidencia |
|---|---|---|---|
| PER-01 CPU | Promedio y máximo | Antes 19.4 % prom. / 130 % máx. (de 400 %) → después 7.6 % / 78.2 % | `evidencias/{antes,despues}/top_cpu.txt`, `resumen.json` |
| PER-02 Memoria | RAM inicial, máxima y final | Antes 60.9 / 146.4 / 124.0 MB → después 54.1 / 73.8 / 72.8 MB | `memoria_pss_kb.txt`, `meminfo-final.txt` |
| PER-03 Tiempo de operación | ms por operación | *Mis pedidos*: 2 213.6 → 1 048.3 ms; hilo principal 822.4 → 73.4 ms | `logcat.txt` (`SEPPRO_Perf`), `choreographer.txt` |
| PER-04 Carga de API | RPS y solicitudes exitosas | Lectura hasta 176.3 RPS, escritura hasta 258.8 RPS; 1 000 de 1 000 exitosas por corrida | `estres-api-antes-dia1.json`, `estres-api-antes.json`, `estres-api-despues.json` |
| PER-05 Latencia de API | Promedio, P90, P95 | Con 50 usuarios: lectura 213.5 / 386.1 / 415.9 ms; escritura 160.8 / 200.2 / 223.1 ms. Consulta crítica: 453.3 → 165.2 ms prom. | Mismos archivos |
| PER-06 Errores HTTP | Cantidad y porcentaje | 0 de 3 000 solicitudes (0 %); códigos 200 y 201. Un único 206 esperado en la consulta crítica (paginación) | Mismos archivos (`codigos`) |
| PER-07 Consultas de BD | Tiempo y registros | Grande: 523.0 ms / 3 005 filas → 30.9 ms; página: 7.1 → 0.24 ms / 20 filas; por id 11.6 → 0.085 ms | `db-antes-despues.txt` |
| PER-08 Concurrencia de BD | Conflictos y consistencia | Antes 0 conflictos detectados y 19 de 20 perdidas; después 190 conflictos detectados, 0 perdidas, 20 de 20 guardadas; 0 bloqueos en espera | `concurrencia-antes.json`, `concurrencia-despues.json` |
| PER-09 Pérdida de conexión | Tiempo de detección y manejo | Sin red: <0.6 s por intento, mensaje controlado; servidor mudo: 20.0 s (timeout) | `ResilienciaTest` (RES-02/03), `res01-sin-red.png`, `res01-06-logcat.txt` |
| PER-10 Recuperación | Tiempo y consistencia posterior | Emulador: 1 945 ms hasta volver a dibujar la lista; reintento automático: 1 735 ms; 1 solo registro tras perder la respuesta | `res06-recuperado.png`, `ResilienciaTest` (RES-06/07), `idempotencia-real.json` |
| PER-11 Optimización | Comparación antes y después | Ver la tabla comparativa: −91 % de hilo principal, −50 % de RAM máxima, −94 % en la consulta de BD, −99.4 % de bytes, 0 escrituras perdidas | Este documento + `evidencias/` |

---

## Registro de defectos y correcciones

| Id | Defecto | Severidad | Cómo se detectó | Corrección | Estado |
|---|---|---|---|---|---|
| D-01 | La app recibe solo 1 000 de 3 005 pedidos (PostgREST `max-rows`, respuesta 206) **sin avisar al usuario** | Alta | Estrés, consulta crítica (`Content-Range 0-999/3005`) | O1: paginación + total exacto | ✅ Corregido |
| D-02 | 670 tarjetas creadas en el hilo principal (822 ms, 13 congelamientos) | Alta | Perfilado (`SEPPRO_Perf`, Choreographer) | O1: páginas de 20 | ✅ Corregido |
| D-03 | Actualizaciones perdidas al editar un reporte al mismo tiempo (19 de 20) | Alta | `concurrencia.mjs` | O4: bloqueo optimista por `updated_at` | ✅ Corregido |
| D-04 | Mensaje duplicado si se pierde la respuesta y el usuario reenvía | Media | RES-07 | O3: `id_cliente` UNIQUE + manejo de 409 | ✅ Corregido |
| D-05 | Seq Scan de `pedido_items` por cada pedido (523 ms) | Media | `EXPLAIN ANALYZE` | O2: índices | ✅ Corregido |
| D-06 | **Introducido por O1:** tras un error de red seguía visible “Mostrando 20 de 2046” con la lista vacía, junto con el botón “Reintentar cargar más” | Baja | RES-01 en el emulador | Se limpia el contador al reiniciar y se oculta el botón si no hay nada cargado | ✅ Corregido y verificado |
| D-07 | Script de perfilado: `monkey` no traía la app al frente tras un arranque en frío | Herramienta | Corrida “después” sin operaciones | Se usa `am start -W` | ✅ Corregido |
| D-08 | Script de concurrencia: un tope de 10 reintentos no alcanza para 20 escritores | Herramienta | 10 fallidas (con aviso, no perdidas) | Tope de 25 y métrica `perdidas_sin_aviso` | ✅ Corregido |
| D-09 | Detectar un servidor mudo tarda 20 s | Media | RES-03 | Propuesta: `readTimeout` de 10 s para lecturas y 20 s para subir archivos | ⏳ Pendiente |
| D-10 | El conteo `count=exact` sigue con Seq Scan (3.2 ms) | Baja | `EXPLAIN` 4b | Aceptado: 3 ms con 3 000 filas; con millones se usaría `count=estimated` | ⏳ Aceptado |
| D-11 | 95 % de cuadros lentos en gfxinfo | Entorno | gfxinfo | El emulador usa GPU por software; se debe medir en un dispositivo físico | ℹ️ No es de la app |

**Ausencia de defectos críticos nuevos:**
- Las 57 pruebas automáticas pasan después de la optimización (17 de validación de login, 16 de procesamiento, 16 de API y 8 de resiliencia).
- Hubo 0 errores HTTP en el estrés y 0 cierres en los dos perfilados.
- El único defecto introducido (D-06) es de severidad baja y quedó corregido.

---

## Entregables

| # | Entregable | Ubicación |
|---|---|---|
| 1 | Código original y optimizado | `pruebas/practica12/version-original/` · `seppro-movil/app/src/main/java/com/seppro/movil/` |
| 2 | Evidencias de perfilado | `pruebas/practica12/evidencias/antes/`, `.../despues/` (CPU, RAM, Logcat, Choreographer, gfxinfo, capturas) · `perfil-app.sh`, `resumen-perfil.mjs` |
| 3 | Script de carga | `pruebas/practica12/estres-api.mjs`, `concurrencia.mjs`, `generar-volumen.mjs` |
| 4 | Reporte de métricas de API | Fase 3 + `evidencias/estres-api-*.json` |
| 5 | Reporte de rendimiento de BD | Fase 4 + `db-antes-despues.sql` + `evidencias/db-antes-despues.txt` |
| 6 | Matriz de resiliencia | Fase 5 + `seppro-movil/app/src/test/.../ResilienciaTest.kt` + `idempotencia-real.mjs` |
| 7 | Comparativa antes y después | Fase 6 (tabla obligatoria) |
| 8 | Registro de defectos | Sección anterior |
| 9 | Conclusiones | Sección siguiente |

**Cómo reproducir:**
```bash
SEPPRO_PASS=123456 node pruebas/practica12/estres-api.mjs antes|despues
SEPPRO_PASS=123456 node pruebas/practica12/concurrencia.mjs antes|despues
SEPPRO_PASS=123456 node pruebas/practica12/idempotencia-real.mjs
bash pruebas/practica12/perfil-app.sh antes|despues && node pruebas/practica12/resumen-perfil.mjs antes|despues
cd seppro-movil && ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport   # con el JDK 17 de Android Studio
```

---

## Conclusiones técnicas

**1. El problema más grave no era de rendimiento, sino de exactitud.**
- La consulta original de *Mis pedidos* pedía todos los pedidos con todas sus columnas.
- Con un cliente de 3 005 pedidos, Supabase cortó la respuesta en 1 000 filas (código 206) y la app mostró una lista incompleta sin avisar.
- Ninguna prueba funcional lo había detectado, porque los datos de prueba eran pocos. Solo apareció al cargar volumen.
- Las pruebas de rendimiento deben hacerse con datos realistas, no con la base de demostración.

**2. El cuello de botella principal estaba en el teléfono, no en el servidor.**
- La API resistió 50 usuarios concurrentes con 0 % de errores y la latencia creció de forma sublineal (×2 con ×18 de throughput). No se alcanzó un punto de saturación.
- En cambio, construir 670 tarjetas bloqueaba el hilo principal 822 ms por carga y llevaba la RAM a 146 MB.
- La paginación (O1) atacó la causa: menos datos por la red (−99.4 % de bytes), menos vistas (20 en lugar de 670), menos memoria (−50 %) y menos CPU (−61 %).
- El hilo principal pasó de 822 a 73 ms (−91 %) y los congelamientos de 13 a 3.
- El tiempo total de la operación bajó 53 %. Lo que queda corresponde sobre todo a la latencia de red hacia Supabase.

**3. Los índices dieron la mayor mejora en la base de datos con muy poco costo.**
- Dos índices (256 kB en total) convirtieron cuatro escaneos secuenciales en búsquedas por índice.
- La consulta original pasó de 523 ms a 31 ms y la consulta paginada nueva tarda 0.24 ms.
- `pg_stat_statements` mostró además que la lectura completa se repetía cientos de veces. Esa carga desapareció al paginar y filtrar en el servidor.

**4. La resiliencia exigía integridad, no solo mensajes de error.**
- La versión original ya manejaba bien los errores visibles: sin red, 500, 401/403 y timeout.
- Pero tenía dos fallas silenciosas:
  - **Actualizaciones perdidas:** 19 de 20 ediciones simultáneas desaparecían mientras el servidor respondía 200.
  - **Mensajes duplicados** cuando la respuesta se perdía.
- Se resolvieron con dos técnicas estándar:
  - **Bloqueo optimista por `updated_at`:** el conflicto se detecta y se informa.
  - **Idempotencia con una clave generada por el cliente (UUID) y un índice UNIQUE**, junto con reintento con espera progresiva solo ante fallas de red.
- Se verificaron con pruebas automáticas y contra Supabase real (201 → 409 → 409, un solo registro).
- **Costo aceptado:** bajo contención extrema, los escritores tardan más porque deben releer y reintentar.

**5. Limitaciones:**
- El emulador usa GPU por software, así que gfxinfo no refleja el rendimiento real de dibujo.
- La latencia de red varió entre días; por eso las comparaciones decisivas se corrieron una tras otra el mismo día.
- La prueba de estrés se limitó a 50 usuarios por el plan gratuito.

**6. Pendientes:**
- Bajar el `readTimeout` de lectura a 10 s (D-09).
- Aplicar la misma paginación al panel de administración.
- Repetir el perfilado en un dispositivo físico.
- Limpiar los 3 005 pedidos sintéticos con `supabase-datos-prueba.sql` al terminar la evaluación.
