Te paso el zip de mi proyecto **SEPPRO** y mi documento **"Reporte de Avance del Proyecto Móvil: SEPPRO"**. Soy Armando Jaime Ramírez, matrícula 2022348004, grupo IDGS14, materia Aplicaciones Móviles.

Necesito el **reporte técnico final**, entregado como **Word y PDF**. Se evalúa con esta rúbrica: A1 a A3 (documento de seguimiento), B1 y B2 (diseño), C1 a C4 (PWA), D1 a D4 (dispositivos inteligentes), E1 a E3 (seguridad y calidad) y F (demostración).

## Formato obligatorio
- Respeta el estilo de mi reporte: títulos azul marino, viñetas y las secciones "Cronograma de Entregables" y "Respuestas a Criterios de Selección y Arquitectura".
- Agrega portada, índice, numeración de figuras y tablas, y un pie bajo cada imagen.
- **Pie de página en todas las hojas:** "SEPPRO — Reporte técnico final · Armando Jaime Ramírez · IDGS14" a la izquierda y "Página X de Y" a la derecha.
- **No inventes datos.** Usa solo lo que está en el zip. Las fuentes principales son:
  - `docs/RUBRICA-cumplimiento.md`, que es la guía principal: tiene la evidencia de cada criterio, las decisiones, el guion de la demo y las preguntas;
  - `docs/ENTREGABLES-2-y-3-cierre-del-proyecto.md`, sobre todo la sección 9;
  - `docs/PRACTICA7-*`, `PRACTICA8-*`, `PRACTICA11-*`, `PRACTICA12-*` y `PRACTICA13-14-*`;
  - `docs/GUIA-SMART-TAG-BLUETOOTH.md`.

## Estructura del reporte
1. **Planteamiento del problema y justificación (A1).** Sección nueva, tomada de `RUBRICA-cumplimiento.md`.
2. **Objetivo general y específicos.**
3. **Cronograma de Entregables (A2).** Conserva mi texto de los Entregables 1 a 3 y de las Prácticas 1, 2, 5, 6, 7 y 8, y reemplaza cada "Aún nada" con lo realizado:
   - lectura GATT del Smart Tag;
   - `verificar_entrega`;
   - ViewModel + StateFlow (Kotlin) y signals (PWA), con aviso por vibración;
   - sincronización en tiempo real y garantía con certificado;
   - alerta de integridad con la opción de rechazar;
   - pruebas;
   - UX/UI;
   - PWA instalable y APK.

   Agrega, con el mismo formato ("qué se hizo" + "cómo se aplica en SEPPRO"):
   - **Práctica 11** (caja blanca);
   - **Práctica 12** (optimización, estrés y resiliencia);
   - **Prácticas 13 y 14** (automatización y CI, matriz AUT-01 a AUT-08).
4. **Decisiones y cambios (A3).** Tabla tomada de `RUBRICA-cumplimiento.md`. Incluye:
   - el cambio de Firestore a Supabase;
   - la contraseña de 6 caracteres;
   - la migración a PWA por iOS;
   - Angular 18 → 21;
   - el retiro del reloj Wear OS;
   - nRF Connect en lugar de ESP32;
   - el modo sin conexión.
5. **Arquitectura (B1).** Inserta `docs/diagramas/arquitectura.png` y explica el flujo de 3 pasos.
6. **Diseño de interfaz y experiencia de usuario (B2).** Diseño Americano, navegación, estados con color e ícono, mensajes y accesibilidad, con capturas.
7. **Aplicación PWA (C1 a C4).** Una subsección por criterio:
   - funciones;
   - el manifiesto: muestra el contenido de `seppro-angular/public/manifest.webmanifest` y explica cada campo;
   - el service worker (`ngsw-config.json`, `provideServiceWorker` en `app.config.ts`) y el modo sin conexión;
   - diseño responsivo e instalación en iPhone y Android.
8. **Integración con el Smart Tag (D1 a D4).**
   - Protocolo GATT: tabla de UUID y formato `SEPPRO-42;0`.
   - Procesamiento de la lectura (`telemetria.ts`) y la RPC `verificar_entrega`.
   - Capacidades usadas: Bluetooth, vibración, cámara y almacenamiento.
   - Permisos y compatibilidad, incluida la limitación de iOS.
   - Demostración con nRF Connect (resume `GUIA-SMART-TAG-BLUETOOTH.md`).
9. **Seguridad y calidad (E1 a E3).** Tabla de seguridad, resumen de pruebas (**114 automáticas: 80 Kotlin + 34 Angular**, más caja negra y caja blanca), CI con GitHub Actions y rendimiento/recuperación (Práctica 12 + modo sin conexión).
10. **Respuestas a Criterios de Selección y Arquitectura.** Conserva mi sección y actualízala:
    - la app móvil principal ahora es una **PWA en Angular 21**, y la app Kotlin queda como versión nativa para Android;
    - la web también usa Angular 21;
    - el teléfono ya usa ViewModel;
    - hay 10 tablas: se suma `garantias`;
    - **quita todo lo del reloj Wear OS** del texto actual (canales, almacenamiento, interfaz). Menciónalo solo en "Decisiones y cambios".
11. **Guion de la demostración (F1)** y **preguntas frecuentes (F2)**, tomados de `RUBRICA-cumplimiento.md`.
12. **Conclusiones**, de una cuartilla.

## Capturas (tómalas tú después del reinicio de datos)
Antes de capturar, en Supabase → SQL Editor se ejecuta `seppro-angular/supabase-reinicio-usuarios.sql`. Ese script deja 6 cuentas con datos simulados. La contraseña está en el propio archivo, en la línea `v_password`.

Usa la app en `/app`, en Chrome con vista de teléfono (F12 → Ctrl+Shift+M) o en el celular, con `cliente2@seppro.com`:

| # | Captura | Sección |
|---|---|---|
| 1 | Login de la app con la contraseña oculta | E1 |
| 2 | Pedidos, con uno "En puerta" y su botón "Verificar paquete" | C1 |
| 3 | Verificar: Bluetooth disponible y botón "Buscar Smart Tag" | D1 |
| 4 | Selector de Bluetooth de Chrome mostrando `SEPPRO-Tag` (nRF Connect) | D1 y D4 |
| 5 | nRF Connect con el servidor GATT configurado (UUID y valor) | D1 y D3 |
| 6 | Resultado "Entrega verificada" con la garantía | D3 |
| 7 | "ALERTA DE INTEGRIDAD" con Aceptar/Rechazar, luego el diálogo de rechazo y el reporte creado | C1 |
| 8 | "¿No encuentras el Smart Tag?" con la confirmación manual (vista en iPhone) | D4 |
| 9 | Soporte y chat en tiempo real (cliente y agente) | C1 |
| 10 | Modo sin conexión: modo avión y la lista con el aviso "Sin conexión" | C3 |
| 11 | DevTools → Application → **Manifest** | C2 |
| 12 | DevTools → Application → **Service Workers** activo, y la prueba **Offline** | C3 |
| 13 | iPhone: Compartir → Agregar a pantalla de inicio, y el ícono de SEPPRO en el inicio | C4 |
| 14 | Tienda web: inicio con "Lleva SEPPRO en tu teléfono", Mis pedidos actualizado y el certificado de garantía | C4 y D3 |
| 15 | Dashboard del administrador y gestión de usuarios | B2 |
| 16 | GitHub Actions con la última corrida en verde (https://github.com/armandojaime990-dot/Seppro/actions) | E2 |

También hay capturas listas en el zip:
- `pruebas/reporte-tecnico/capturas/app-11-sin-conexion.png`, que es la evidencia del modo sin conexión;
- el diagrama `docs/diagramas/arquitectura.png`;
- las evidencias de las prácticas en `pruebas/practica12/evidencias/` y `pruebas/practica13-14/evidencias/`.

Las otras capturas de `pruebas/reporte-tecnico/capturas/` son anteriores al reinicio de datos. Úsalas solo si no puedes tomar las nuevas.

Entrégame el documento final completo, no solo los cambios.
