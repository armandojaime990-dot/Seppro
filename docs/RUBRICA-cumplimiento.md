# SEPPRO: cumplimiento de la rúbrica de evaluación

Este documento indica, para cada criterio, dónde está la evidencia en el proyecto y qué mostrar en la defensa.

## A. Documento de seguimiento

| Criterio | Evidencia |
|---|---|
| **A1. Planteamiento del problema y justificación** | Problema: las bombas de agua son equipos caros y pesados que se dañan en el traslado. Hoy el cliente no tiene cómo demostrar que el golpe ocurrió antes de recibir el equipo, y la tienda no sabe si una reclamación es válida. Solución: un Smart Tag (sensor BLE) en la caja registra si hubo golpe fuerte. El teléfono lo lee en la entrega y el resultado se registra en el e-commerce: sin golpe se activa la garantía y con golpe el cliente puede rechazar el equipo con evidencia. Justificación: menos devoluciones y reclamaciones sin evidencia, y más confianza para comprar en línea |
| **A2. Seguimiento de avances** | Reporte de avance: cronograma por entregables (octubre, noviembre y diciembre), las prácticas 1, 2, 5, 6, 7, 8, 11, 12, 13 y 14, y los commits en GitHub con fecha |
| **A3. Decisiones y cambios** | Tabla de la sección "Decisiones y cambios" (abajo) y la sección 9 de `ENTREGABLES-2-y-3-cierre-del-proyecto.md` |

### Decisiones y cambios del proyecto

| Decisión o cambio | Motivo |
|---|---|
| Backend Supabase (PostgreSQL) en lugar de Firestore | La tienda web ya lo usaba. Es relacional, tiene RLS, Realtime y Storage, y no requiere servidor propio |
| Contraseña de mínimo 6 caracteres (no 8, como en AccesoSeguro) | Supabase Auth y las cuentas que ya existían usan 6. Con 8, esos clientes no podrían entrar |
| La app del teléfono se migró a **PWA** (Angular) | En iPhone no se puede instalar un APK. La PWA tiene la misma interfaz y las mismas funciones, y usa la misma base |
| En iPhone, confirmación manual del paquete | Safari no implementa Web Bluetooth. Chrome y Edge (Android y PC) sí leen el Smart Tag |
| Angular 18 → 21 | `npm audit` reportaba vulnerabilidades altas (XSS) en Angular 18. Después de la actualización, 0 vulnerabilidades |
| Se quitó del chat el modelo del dispositivo y el contador por dispositivo | Venía de la Práctica 6 y no aportaba nada al usuario final |
| Se retiró la app del reloj (Wear OS) | La evaluación califica la app móvil. El aviso del resultado pasó a ser la vibración del teléfono |
| Smart Tag simulado con nRF Connect en lugar de un ESP32 | Demuestra la comunicación BLE/GATT real sin comprar hardware. El protocolo es el mismo |
| Modo sin conexión | Criterio C3: la app se usa en la puerta del domicilio, donde la señal puede fallar |

## B. Diseño y arquitectura

| Criterio | Evidencia |
|---|---|
| **B1. Arquitectura** | `docs/diagramas/arquitectura.png`: cliente-servidor con BaaS. Smart Tag ↔ app móvil (BLE/GATT); app ↔ Supabase (HTTPS + JWT, RLS, RPC); Realtime hacia la tienda web |
| **B2. Interfaz y experiencia de usuario** | Diseño "Americano": barra superior y barra inferior (Pedidos, Verificar, Historial, Soporte) al alcance del pulgar. Estados con color e ícono. Botones de 52 px. Mensajes claros en español. Aviso "Sin conexión". Confirmación con vibración. Capturas en `pruebas/reporte-tecnico/capturas/` |

## C. Aplicación tipo PWA

| Criterio | Evidencia | Qué mostrar |
|---|---|---|
| **C1. Funcionamiento** | `seppro-angular/src/app/movil/`: login, pedidos paginados, verificación con el Smart Tag, alerta de integridad (aceptar/rechazar), garantía, soporte por rol con chat en tiempo real y fotos | El flujo completo con un pedido "En puerta" |
| **C2. Manifiesto y configuración** | `public/manifest.webmanifest`: `name`, `short_name`, `id` y `start_url` `/app`, `display: standalone`, `orientation`, `theme_color`, `background_color`, `lang`, íconos de 72 a 512 (`any` y `maskable`). Además, `index.html` tiene las etiquetas `apple-mobile-web-app-*` y `apple-touch-icon` para iPhone | Chrome DevTools → Application → Manifest |
| **C3. Service worker y modo sin conexión** | `ngsw-config.json` (Angular Service Worker): la app (index, JS, CSS y manifest) se descarga por adelantado con `prefetch`, y las imágenes se guardan al usarse con `lazy`. Se registra en `app.config.ts` con `provideServiceWorker`. Sin red: la app abre, entra con el perfil guardado y muestra la última lista de pedidos con el aviso "Sin conexión" (`almacen-local.ts`, `lista-pedidos.component.ts`, `shell.component.ts`). Al cerrar sesión se borra todo | DevTools → Application → Service Workers → **Offline** → recargar `/app`. Evidencia: `pruebas/reporte-tecnico/capturas/app-11-sin-conexion.png` |
| **C4. Diseño responsivo e instalable** | Interfaz móvil con `safe-area-inset` (notch del iPhone) y una tienda web responsiva. Se instala en iPhone (Compartir → Agregar a pantalla de inicio) y en Android (Chrome → Instalar app). La tienda enlaza a la app desde el inicio y desde el pie de página | Instalarla en vivo en el teléfono |

## D. Integración con dispositivos inteligentes

| Criterio | Evidencia |
|---|---|
| **D1. Comunicación con el dispositivo** | Web Bluetooth y `BluetoothGatt` en Android: `requestDevice` filtrado por servicio o por el prefijo `SEPPRO` → `gatt.connect()` → `getPrimaryService` → `readValue()`, con un tiempo límite de 15 s. Guía de la demo con nRF Connect: `docs/GUIA-SMART-TAG-BLUETOOTH.md` |
| **D2. Uso de capacidades o sensores** | El sensor de golpes del Smart Tag (acelerómetro: estatus 0/1). En el teléfono: radio Bluetooth LE, vibrador (aviso del resultado), cámara/galería (foto de evidencia en soporte) y almacenamiento local |
| **D3. Intercambio y procesamiento de información** | `telemetria.ts` / `Telemetria.kt`: decodifica el formato texto (`SEPPRO-42;0`, con separadores `;` `,` `\|`) o binario (5 bytes: uint32 LE + estatus). Valida el rango y comprueba que el paquete sea del pedido. Luego llama a la RPC `verificar_entrega`, que cambia el estado, crea la garantía y difunde el cambio por Realtime |
| **D4. Permisos y compatibilidad** | Android: `BLUETOOTH_SCAN`/`CONNECT` (12+) y `ACCESS_FINE_LOCATION` (≤11), pedidos en tiempo de ejecución, más `VIBRATE`. Web: selector de permiso del navegador, `getAvailability()` y aviso de "Bluetooth apagado". Si el navegador no lo soporta (Safari), confirmación manual. La vibración se omite donde no existe |

## E. Seguridad y calidad

| Criterio | Evidencia |
|---|---|
| **E1. Protección de información y autenticación** | Supabase Auth (bcrypt + JWT). RLS en todas las tablas. Las RPC solo con sesión. El servidor calcula precio, estado y total (triggers). Los perfiles son privados. El rol y el correo están protegidos. Contraseña oculta y mensajes genéricos (Práctica 5). Sin tokens ni contraseñas en el almacenamiento propio ni en los logs. CSP, HSTS, `X-Frame-Options` y `nosniff` (`vercel.json`). Solo HTTPS en Android. URL firmada de 10 minutos para las fotos. `npm audit`: 0 vulnerabilidades |
| **E2. Pruebas y validación** | Caja negra (Práctica 8), caja blanca (Práctica 11), y unitarias, de integración y de persistencia (Prácticas 13 y 14): **114 pruebas automáticas** (80 Kotlin + 34 Angular), que GitHub Actions corre en cada push |
| **E3. Rendimiento, estabilidad y recuperación** | Práctica 12: paginación de 20, índices, pruebas de estrés y de concurrencia, envío del chat con reintento sin duplicar (`id_cliente`) y bloqueo optimista. Modo sin conexión. Tiempo límite en la lectura Bluetooth |

## F. Demostración (guion de 5 minutos)

1. **Instalación:** en el iPhone, abrir `/app` → Agregar a pantalla de inicio. Se abre a pantalla completa (C2, C4).
2. **Login** con `cliente2@seppro.com`. Mostrar que la contraseña se ve oculta (E1).
3. **Pedidos:** abrir uno "En puerta" → Verificar paquete.
4. **Smart Tag real:** con Chrome en Android o en la laptop, Buscar → `SEPPRO-Tag` (el teléfono con nRF Connect) → Leer (D1-D3).
   - Valor `;0`: verificado, garantía y vibración.
   - Cambiar a `;1` y usar otro pedido: alerta → Rechazar → se crea un reporte.
5. **Tienda web abierta al lado:** Mis pedidos cambia sola (Realtime) → Ver certificado de garantía.
6. **Soporte:** el chat entre el cliente y el agente en tiempo real.
7. **Sin conexión:** activar el modo avión y abrir la app. Aparecen los pedidos guardados con el aviso (C3).
8. **Calidad:** mostrar GitHub Actions en verde (E2).

## Preguntas probables y respuesta corta

- **¿Qué guarda el service worker?** La app completa (HTML, JS, CSS, manifest e íconos). Los datos vienen de Supabase; sin red se usa la última copia guardada en `localStorage`, sin tokens propios.
- **¿Por qué no funciona el Bluetooth en iPhone?** Apple no implementa Web Bluetooth en Safari ni en ningún navegador de iOS. Para eso está la app Android o Chrome en PC/Android, y en iPhone la confirmación manual.
- **¿Qué pasa si alguien manda un estado falso a la API?** RLS solo deja tocar los pedidos propios. `verificar_entrega` comprueba que el pedido sea del usuario y esté "En puerta", y que el paquete coincida. Los triggers ignoran el estado y los precios que manda el cliente.
- **¿Qué es GATT?** Es el perfil de Bluetooth LE que organiza los datos en servicios y características con UUID. La app lee la característica `5e1a0002-…` del servicio `5e1a0001-…`.
- **¿Cómo te recuperas de una caída de red?** El chat reintenta con espera progresiva usando el mismo `id_cliente`, para no duplicar. La lista sin conexión usa la copia guardada, y la lectura del Smart Tag tiene un tiempo límite de 15 s.
