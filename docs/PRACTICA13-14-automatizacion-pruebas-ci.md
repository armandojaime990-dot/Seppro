# Prácticas 13 y 14 — Automatización de pruebas y validación continua (SEPPRO)

**Alumno:** Armando Jaime Ramírez · **Matrícula:** 2022348004 · **Grupo:** IDGS14 · **Materia:** Aplicaciones Móviles
**Proyecto:** SEPPRO. Tiene cuatro partes:
- app Android Kotlin (`seppro-movil`);
- reloj Wear OS (`seppro-wearos`);
- web y app móvil web PWA en Angular 21 (`seppro-angular`);
- backend Supabase.

**Repositorio:** https://github.com/armandojaime990-dot/Seppro
**Pipeline:** https://github.com/armandojaime990-dot/Seppro/actions
**Fecha:** 24 de noviembre de 2026

---

## Fase 1. Componentes críticos seleccionados

| Tipo | Componente | Por qué es crítico | Riesgo previo que una prueba automática previene |
|---|---|---|---|
| **Función crítica del móvil** | Verificación de la entrega con el Smart Tag: `TelemetriaTag` + `VerificacionViewModel` (Kotlin) y `telemetria.ts` + `VerificacionStore` (PWA) | Decide si el pedido queda verificado (garantía) o en "Alerta de integridad" | Lectura inválida del sensor (estatus 2, paquete de otra caja) que se registraba como válida. Un byte binario `0x3B` (`;`) podía confundirse con el formato de texto |
| **Servicio / comunicación** | `VerificacionApi` y `Api` (OkHttp → Supabase REST) y `MovilApi` (supabase-js) | Toda la app depende de la API REST de Supabase | P11 y P12: mensajes de error poco claros y servidor sin respuesta. P12 (D-01): la app recibía 1 000 de 3 005 pedidos sin avisar |
| **Persistencia** | `resolver_alerta` / `verificar_entrega`, envío idempotente de mensajes (`id_cliente`) y almacenamiento local (`AlmacenLocal`, equivalente a SharedPreferences) | El rechazo del equipo y los mensajes deben guardarse exactamente una vez | P12 (D-04): mensaje duplicado al reintentar tras perder la respuesta. P12 (D-03): actualizaciones perdidas |

**Defecto anterior elegido para prevenirlo con una prueba automática:**
- **Mensaje duplicado al reintentar (P12, D-04).**
- **Lo previenen:** `ResilienciaTest.res07_*` en Kotlin y `movil-api.integracion.spec.ts`, prueba AUT-06, en la PWA.
- **Por qué funciona:** si alguien quita el `id_cliente` o el manejo del 409, el CI falla.

---

## Fase 2. Pruebas unitarias (más de 5)

**Herramientas:**
- **Kotlin:** JUnit 4 y `kotlinx-coroutines-test` (para el ViewModel con StateFlow).
- **Angular:** Jasmine y Karma en Chrome sin ventana.
- **Equivalencia:** `flutter_test` corresponde a JUnit / Jasmine en este proyecto.

| Archivo | Pruebas | Válida | Vacía / nula | Fuera de rango | Resultado esperado | Excepciones |
|---|---|---|---|---|---|---|
| `seppro-movil/.../TelemetriaTagTest.kt` | 9 | ✅ texto y binario | ✅ `null`, `[]`, `";"` | ✅ estatus 2, -1, 7; pedido 0; >64 bytes | ✅ `Telemetria("SEPPRO-42",0)` | ✅ `TelemetriaInvalidaException` con mensaje |
| `seppro-movil/.../VerificacionViewModelTest.kt` | 10 | ✅ sin golpe → garantía | ✅ pedido 0 | ✅ sensor de otro paquete | ✅ estados del StateFlow | ✅ 400, 404, red, sesión |
| `seppro-movil/.../ValidacionLoginTest.kt` (P11) | 17 | ✅ | ✅ | ✅ 73 caracteres | ✅ | ✅ |
| `seppro-angular/.../telemetria.spec.ts` | 5 | ✅ | ✅ | ✅ | ✅ | ✅ `TelemetriaInvalidaError` |
| `seppro-angular/.../validacion-login.spec.ts` | 4 | ✅ | ✅ `null`/`undefined` | ✅ | ✅ | ✅ inyección `' OR 1=1 --` |
| `seppro-angular/.../estados-reintento.spec.ts` | 6 | ✅ | ✅ descripción vacía | ✅ >1000 caracteres | ✅ espera 500/1000/2000/4000 ms | ✅ 500 sin reintento, 409 recupera |
| `seppro-angular/.../verificacion.store.spec.ts` | 5 | ✅ | ✅ | ✅ | ✅ | ✅ |

**Ejemplo (Kotlin):**
```kotlin
@Test fun estatusFueraDeRango_lanzaExcepcion() {
    val e = assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-4;2".toByteArray()) }
    assertTrue(e.message!!.contains("fuera de rango"))
}
```

## Fase 3. Pruebas de integración (más de 3)

Se prueba la comunicación **real** entre componentes:
- **Kotlin:** la app (`VerificacionApi`, `Api`, OkHttp, `ViewModel`) habla con un servidor HTTP simulado (MockWebServer) que responde con el mismo protocolo que Supabase.
- **PWA:** el cliente **supabase-js real** habla con un servidor simulado.

| Código | Prueba | Kotlin | PWA |
|---|---|---|---|
| AUT-04 | La app envía la telemetría a `/rest/v1/rpc/verificar_entrega` con JWT y apikey, y procesa la respuesta válida | `verificarEntrega_enviaTelemetriaYProcesaRespuesta` | `AUT-04: envía la telemetría...` |
| AUT-05 | La API devuelve el código HTTP esperado: 400 con el mensaje del servidor, 401 lleva al login, 200/503 en el certificado | `codigosHttp_seRespetan` | `AUT-05: la API devuelve el código HTTP...` |
| AUT-05 | El servidor no responde: la pantalla muestra "Sin conexión..." y no se cuelga | `servidorCaido_pantallaMuestraMensajeAdecuado` | Mismo caso (red caída → `ErrorDeRed`) |
| AUT-05 | Paginación con filtro en el servidor y total de `Content-Range` | `ApiTest` (P11/P12) | `AUT-05: pedidos paginados...` |
| AUT-06 | Persistencia: el rechazo se guarda y devuelve el reporte creado | `rechazarEquipo_persisteYDevuelveReporte` | — |
| AUT-06 | Persistencia: el mensaje se guarda **una sola vez** aunque se pierda la respuesta (409 → recupera) | `ResilienciaTest.res07_*` | `AUT-06 (persistencia)...` |
| AUT-06 | Persistencia local en el almacenamiento real del navegador; no guarda la contraseña; tolera datos dañados y el modo privado de Safari | — | `almacen-local.spec.ts` (4) |

**Justificación de la adaptación:**
- Las pruebas de integración del CI usan un servidor simulado para ser **reproducibles**: no dependen de Internet ni modifican los datos reales.
- La integración contra Supabase real se probó aparte con los scripts de la Práctica 12 (`estres-api.mjs`, `idempotencia-real.mjs`).
- También se probó en el emulador (capturas en `pruebas/practica13-14/evidencias/telefono/`).

## Fase 4. Flujo de integración continua (GitHub Actions)

**Archivo:** [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)

**Cuándo corre:** en cada `push` y `pull_request` a `main`, y a mano con `workflow_dispatch`.

| Trabajo | Pasos |
|---|---|
| Teléfono (Kotlin) | JDK 17 → `./gradlew testDebugUnitTest` → `createDebugUnitTestCoverageReport` (JaCoCo) → `lintDebug` → `assembleDebug` → resumen y artefactos |
| Reloj (Wear OS) | `./gradlew lintDebug assembleDebug` |
| Web y PWA (Angular) | Node 22 → `npm ci` → `npm audit --audit-level=high` → `npm run test:ci` (Karma + ChromeHeadless + cobertura) → `npm run build` |

**Configuración:**
- Lint está configurado con `abortOnError = true` en las dos apps Android: un error de lint hace fallar el CI.
- La auditoría de npm hace fallar el CI si aparece una vulnerabilidad alta o crítica.

## Fase 5. Validación y resultados

### Ejecuciones del pipeline (evidencia: `pruebas/practica13-14/evidencias/ci/*.json`)

| Ejecución | Commit | Evento | Resultado | Duración total | Teléfono | Reloj | Web |
|---|---|---|---|---|---|---|---|
| [#1](https://github.com/armandojaime990-dot/Seppro/actions/runs/45416438659) | `26bd3c6` | push | ✅ success | 200 s | 196 s | 161 s | 66 s |
| [#2](https://github.com/armandojaime990-dot/Seppro/actions/runs/45420679697) | `4076250` | push (nuevo) | ✅ success | 161 s | 156 s | 88 s | 67 s |

La ejecución #2 confirma que el flujo **se vuelve a ejecutar solo** con el nuevo push. Además fue más rápida (161 s contra 200 s) gracias a la caché de Gradle.

### Pruebas ejecutadas

| Suite | Ejecutadas | Aprobadas | Fallidas | Tiempo |
|---|---|---|---|---|
| Kotlin (7 clases) | **80** | 80 | 0 | ~38 s local (25.7 s de pruebas; 24 s son el timeout real de RES-03) |
| Angular (6 archivos) | **28** | 28 | 0 | 0.72 s de pruebas (2 min 20 s con la compilación de Karma) |
| **Total** | **108** | **108** | **0** | |

### Cobertura

| Elemento | Cobertura |
|---|---|
| Kotlin: componentes críticos | `TelemetriaTag` 100 %, `VerificacionApi` 100 %, `ValidacionLogin` 100 %, `VerificacionViewModel` 97.1 %, `Api` 90 % (líneas) |
| Kotlin: app completa | 12.3 % de líneas. La mayoría del código son pantallas (Views/Fragments), que no se prueban con JUnit |
| Angular (todo el código cargado en las pruebas) | 38.9 % de sentencias, 40.8 % de líneas |

### Errores encontrados por la automatización y cambios para corregirlos

| # | Error | Cómo se detectó | Corrección |
|---|---|---|---|
| E-01 | **Lint (Fatal) `InvalidFragmentVersionForActivityResult`** en el reloj: `registerForActivityResult` sin la dependencia `fragment` ≥ 1.3. Lo introdujo el cambio del permiso de notificaciones | `./gradlew lintDebug` (mismo paso del CI) | Se agregó `androidx.fragment:fragment-ktx:1.6.2` |
| E-02 | **Lint (Error) `WearPasswordInput`**: el reloj pide contraseña (ya existía) | `lintDebug` | Se justificó con `@SuppressLint`: el reloj es independiente y la contraseña no se guarda ni se registra |
| E-03 | **`npm audit`: 10 vulnerabilidades en producción (5 altas, XSS en Angular 18) y 76 en total (4 críticas)** | Paso de auditoría del CI | Angular 18 → 21 + `overrides` de `glob`, `minimatch` y `uuid` → **0 vulnerabilidades** |
| E-04 | **Rutas profundas de la web en blanco** (`/app/login`, `/producto/5`): faltaba `<base href="/">` | Script automático de capturas con Chrome sin ventana (`capturas-pwa.mjs`) | Se agregó `<base href="/">` en `index.html` |
| E-05 | La barra inferior y la caja de mensajes de la PWA quedaban fuera de la pantalla | Mismas capturas automáticas | Cada pantalla ocupa el alto completo (flex) |
| E-06 | Mensaje técnico en inglés si falta la función SQL ("Could not find the function...") | Prueba en el emulador | Se traduce a "La verificación no está instalada... ejecuta supabase-entregable3.sql". Pruebas nuevas en Kotlin y Angular |
| E-07 | Con la CSP estricta, el CSS crítico en línea (`onload=`) quedaría bloqueado | Build + servidor con los encabezados de Vercel | `inlineCritical: false`. Recorrido automático sin violaciones de CSP |

**Ninguna prueba falló en el CI:**
- Los errores E-01 y E-03 se detectaron **corriendo localmente los mismos comandos del pipeline** antes del push, y se corrigieron antes de subirlos.
- Las dos ejecuciones del CI terminaron en verde.

## Matriz de automatización

| Código | Tipo | Qué se automatiza | Herramienta | Estado |
|---|---|---|---|---|
| AUT-01 | Unitarias | Lectura de la telemetría del Smart Tag (Kotlin 9 + Angular 5) | JUnit / Jasmine | ✅ Aprobada |
| AUT-02 | Unitarias | Lógica de verificación (ViewModel 10 + Store 5) y reintento/estados (6) | JUnit + coroutines-test / Jasmine | ✅ Aprobada |
| AUT-03 | Unitarias | Validación del inicio de sesión (Kotlin 17 + Angular 4) | JUnit / Jasmine | ✅ Aprobada |
| AUT-04 | Integración | App → `verificar_entrega` (petición, encabezados y respuesta) | MockWebServer / supabase-js + servidor simulado | ✅ Aprobada |
| AUT-05 | Integración | Códigos HTTP (400/401/503), servidor caído y paginación | MockWebServer / supabase-js | ✅ Aprobada |
| AUT-06 | Persistencia | Rechazo guardado; mensaje guardado una sola vez (409); almacenamiento local real | MockWebServer / localStorage del navegador | ✅ Aprobada |
| AUT-07 | CI | Pipeline en push / pull request: pruebas, lint, cobertura, auditoría y build | GitHub Actions (run #1) | ✅ Aprobada |
| AUT-08 | CI | Nuevo push → el pipeline se vuelve a ejecutar y queda en verde | GitHub Actions (run #2) | ✅ Aprobada |

## Fase 6. Conclusión técnica

**Lo que encontró la automatización:**
- **El lint en el pipeline detectó un error que yo mismo introduje.** El permiso de notificaciones del reloj usaba una API que exigía otra versión de `fragment` (E-01). Sin ese paso, el APK habría compilado, pero podía cerrarse al pedir el permiso.
- **La auditoría automática hizo visible un riesgo de seguridad.** El proyecto corría sobre una versión de Angular con vulnerabilidades XSS (E-03), y nadie lo habría notado con pruebas manuales.
- **Las capturas automáticas encontraron un defecto viejo.** Las rutas profundas de la web cargaban en blanco (E-04).

**Por qué las pruebas protegen el proyecto:**
- Las pruebas unitarias amarran las reglas del negocio:
  - estatus 0 o 1;
  - el paquete debe ser el del pedido;
  - sin golpe hay garantía, con golpe hay alerta;
  - 409 significa "ya estaba guardado".
- Así, cualquier modificación futura que rompa una de esas reglas hace fallar el CI **antes** de llegar a los usuarios.
- Las pruebas de integración vigilan el contrato con Supabase: la ruta de la RPC, el cuerpo JSON, los encabezados de sesión y los códigos HTTP. Si alguien cambia el nombre de un parámetro en la app, la prueba lo detecta aunque compile.
- Que el mismo protocolo esté probado en Kotlin y en Angular garantiza que el teléfono Android y la PWA para iPhone se comporten igual.

**En resumen:**
- La automatización convierte la calidad en una condición del repositorio, no en una revisión manual que se olvida.
- Cada push vuelve a ejecutar 108 pruebas, el lint de dos apps, la auditoría de seguridad y tres builds en unos 3 minutos.
- El resultado queda registrado con su evidencia.

**Limitación:**
- La cobertura de pantallas (Views de Android y plantillas de Angular) es baja.
- El siguiente paso sería agregar pruebas instrumentadas (Espresso) y pruebas de punta a punta en el CI (Playwright), aprovechando el script `capturas-pwa.mjs` que ya recorre la PWA.

---

## Entregables

| # | Entregable | Ubicación |
|---|---|---|
| 1 | Repositorio actualizado | https://github.com/armandojaime990-dot/Seppro |
| 2 | Pruebas unitarias (más de 5) | `seppro-movil/app/src/test/.../TelemetriaTagTest.kt`, `VerificacionViewModelTest.kt`; `seppro-angular/src/app/movil/**/*.spec.ts` |
| 3 | Pruebas de integración (más de 3) | `VerificacionIntegracionTest.kt`, `ResilienciaTest.kt`, `movil-api.integracion.spec.ts` |
| 4 | Configuración del pipeline | `.github/workflows/ci.yml` |
| 5 | Evidencia del pipeline | `pruebas/practica13-14/evidencias/ci/` (JSON de las ejecuciones #1 y #2) + enlaces de arriba |
| 6 | Matriz de validación | Sección "Matriz de automatización" |
| 7 | Reporte técnico | Este documento |

**Cómo correr todo en local:**
```bash
cd seppro-movil && ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport lintDebug   # JDK 17
cd seppro-wearos && ./gradlew lintDebug assembleDebug
cd seppro-angular && npm ci && npm audit --audit-level=high && npm run test:ci && npm run build
```
