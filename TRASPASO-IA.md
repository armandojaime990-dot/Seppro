# SEPPRO: estado del proyecto (nota de traspaso)

Fecha: 2026-11-10. Alumno: Armando Jaime Ramírez (IDGS14, Aplicaciones Móviles).

## Carpetas
- `seppro-angular/`: tienda web Angular 18 + scripts SQL de Supabase (`*.sql`).
- `seppro-movil/`: app del teléfono (Kotlin, Views por código, sin XML de layouts ni Compose). Paquete `com.seppro.movil`.
- `seppro-wearos/`: app del reloj Wear OS (Kotlin). Paquete `com.seppro.wearos`.
- `docs/`: matrices de prueba de las Prácticas 7 y 8.
- `seppro/`: versión PHP anterior (no se usa).

Backend: Supabase (PostgreSQL + Auth + REST + Realtime + Storage).
- Teléfono: HTTP con OkHttp a través de `Api.kt`, con el JWT en memoria (`SupabaseClient`).
- Reloj: `HttpURLConnection`.

Orden de los scripts SQL:
1. `supabase-schema.sql`
2. `supabase-update.sql`
3. `supabase-trigger.sql`
4. `fix-rls.sql`
5. `supabase-bluetooth.sql`
6. `supabase-soporte.sql`

## Entregable 1: HECHO
- Escaneo BLE (`BleScanner.kt`, `VerificarFragment.kt`), permisos en tiempo de ejecución y barra inferior.
- Estados nuevos de pedido en web, teléfono y reloj (`supabase-bluetooth.sql`).
- Práctica 5 en los logins del teléfono y del reloj: `ValidacionLogin.kt` (contraseña de 6 a 72 caracteres, porque la web registra con mínimo 6), mensaje genérico y Logcat sin credenciales ni JWT.

## Entregable 2: Práctica 7 (Soporte con roles): HECHO en código, falta probar en dispositivos
- **SQL** (`supabase-soporte.sql`, AÚN NO EJECUTADO en Supabase):
  - roles admin, agente, usuario e invitado
  - tablas `reportes` (folio REP001) y `mensajes`
  - RLS y triggers por rol
  - Realtime
  - bucket privado `evidencias`
  - RPC `latido`, `desconectar`, `agentes_estado` y `reasignar_reporte`
  - corrección de seguridad: un usuario ya no puede cambiarse su propio rol
- **Teléfono**:
  - `SoporteFragment` (4.ª pestaña, según el rol)
  - `NuevoReporteActivity`
  - `ReporteActivity`: chat en vivo, contador por dispositivo, Toast, limpiar historial local, fotos con URL firmada de 10 minutos y acciones por rol
  - `RealtimeCanal.kt` (WebSocket Phoenix)
  - `SoporteApi.kt`, con `Presencia` (latido cada 60 s; desconectado tras 2 minutos sin latido)
  - botón "Reportar falla" en los pedidos con daño
  - en el login: perfil y rol, cuenta deshabilitada y "¿Olvidaste tu contraseña?"
  - "Cerrar sesión" en la Toolbar
  - `PedidosApi` filtra por `usuario_id`
- **Web**: selector de rol en Admin > Usuarios.
- **Docs**: `docs/PRACTICA7-roles-y-casos-de-prueba.md` (TC01–TC08).

## Entregable 2: Práctica 8 (caja negra): DOCUMENTADA
`docs/PRACTICA8-pruebas-caja-negra.md`: RQ01–RQ12 y CP01–CP14. Faltan los resultados obtenidos y las capturas del emulador.

## Pendiente (Entregable 2 y 3, "Aún nada" en el reporte)
- GATT del Smart Tag (estatus 0/1)
- función `verificar_entrega`
- ViewModel con StateFlow
- aviso al reloj
- garantía automática
- rechazo del equipo
- APK final

## Compilar
- Teléfono y reloj: `gradlew.bat :app:assembleDebug` (AGP 8.2, Kotlin 1.9.22, JDK 17). Los dos compilan.
- Web: `npm install` y después `npx ng build`.
