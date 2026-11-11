# Práctica 7: asignación de roles e interacción de situaciones (SEPPRO)

Módulo de **Soporte** de la app del teléfono (`seppro-movil`, pestaña "Soporte").
Backend: Supabase (PostgreSQL + Realtime + Storage). Script: `seppro-angular/supabase-soporte.sql`.

## 1. Preparación

1. En Supabase > SQL Editor, ejecutar `supabase-soporte.sql`. Antes deben haberse ejecutado `supabase-update.sql`, `fix-rls.sql` y `supabase-bluetooth.sql`.
2. Crear 4 cuentas: desde el registro de la tienda web o en Supabase > Authentication.
3. Asignar los roles desde la web (**Admin > Usuarios > columna Rol**) o con SQL:

| Dispositivo | Cuenta (ejemplo) | Rol (`perfiles.rol`) |
|---|---|---|
| Emulador 1 | admin@seppro.com | `admin` |
| Emulador 2 | agente@seppro.com | `agente` |
| Emulador 3 | cliente1@seppro.com | `usuario` |
| Emulador 4 / teléfono | cliente2@seppro.com | `usuario` |

4. Para el caso de falla, poner un pedido de cliente1 en `entregado_con_danos` o `alerta_integridad` desde el panel de administración de la web.

## 2. Qué ve cada rol

| Rol | Opciones en la pestaña Soporte |
|---|---|
| Cliente | Nuevo reporte, Solicitud directa al administrador y Mis reportes. En el chat: Editar y Cerrar reporte. |
| Agente | Reportes asignados (Activos / Resueltos y cerrados). En el chat: Cambiar estado. |
| Administrador | Agentes conectados o desconectados, reportes sin asignar o con agente desconectado, solicitudes directas y todos los reportes. En el chat: Cambiar estado y Reasignar (a otro agente o "Atenderlo yo"). |

La seguridad no depende de la app. RLS (`puede_ver_reporte`, `is_admin`) y los triggers `reportes_al_crear` y `reportes_al_editar` hacen que cada usuario solo vea y modifique lo que le corresponde.

## 3. Casos de SEPPRO

| Caso | Cómo se abre | Tipo |
|---|---|---|
| Reporte de falla | Botón **Reportar falla** en un pedido *Entregado con daños* o *Alerta de integridad*, o Soporte > Nuevo reporte | `falla` (la prioridad pasa a alta automáticamente si el sensor marcó daño) |
| Seguimiento de la entrega | Soporte > Nuevo reporte | `seguimiento` |
| Recuperación de acceso perdido | Login > **¿Olvidaste tu contraseña?** (Supabase envía el enlace), o un reporte de tipo acceso | `acceso` |

## 4. Matriz de pruebas TC01–TC08 (roles de administrador y agente)

| ID | Rol | Pasos | Resultado esperado | Resultado obtenido | Evidencia | Estado |
|---|---|---|---|---|---|---|
| TC01 | Agente | El agente inicia sesión y deja la app abierta. Cliente1 crea un reporte de falla de un pedido con daño. | El reporte aparece con folio REP00X, prioridad Alta y asignado al agente (pestaña Soporte del agente > Activos). | | | |
| TC02 | Agente | El agente abre el reporte y escribe un mensaje. El cliente tiene el chat abierto en otro emulador. | El mensaje llega al cliente sin recargar ("● En vivo"), con emisor, rol, dispositivo y hora. Toast "Mensaje enviado" en el agente y "Nuevo mensaje de..." en el cliente. El contador por dispositivo aumenta. | | | |
| TC03 | Agente | Intentar enviar un mensaje vacío o de solo espacios. | No se envía y aparece "El mensaje no puede estar vacío". | | | |
| TC04 | Agente | Cambiar estado → En proceso y luego → Resuelto. | El estado cambia en la cabecera del agente y del cliente en vivo. En Supabase, `reportes.estado` y `updated_at` se actualizan. | | | |
| TC05 | Agente | Adjuntar una foto con 📎 y abrir "Ver foto" desde el otro rol. | La foto se sube a `evidencias/<reporte>/...jpg` (bucket privado) y se abre con una URL firmada. Después de 10 minutos la misma URL ya no funciona. | | | |
| TC06 | Administrador | El agente cierra sesión o cierra la app por más de 2 minutos. El admin abre Soporte. | El agente aparece como "⚪ Desconectado" y su reporte en "Sin asignar o con agente desconectado". El admin usa Reasignar (a otro agente o "Atenderlo yo") y el reporte cambia de responsable. El agente anterior ya no puede abrirlo. | | | |
| TC07 | Administrador | Cliente2 envía "Solicitud directa al administrador". | La solicitud aparece en "Solicitudes directas" del admin, sin agente. El admin responde en el chat y cliente2 lo recibe en vivo. | | | |
| TC08 | Administrador | Revisar en Supabase > Table Editor las tablas `reportes` y `mensajes`, y en Storage el bucket `evidencias`. Cliente1 intenta ver un reporte de cliente2. | Todos los datos persisten en PostgreSQL. El cliente no ve reportes ajenos (RLS). "Limpiar historial" oculta los mensajes solo en ese teléfono; en la base siguen existiendo. | | | |

> La práctica plantea persistencia en Firestore. En SEPPRO se comprueba en PostgreSQL (Supabase), que es el backend del proyecto.

## 5. Notas técnicas

- **Tiempo real:** WebSocket de Supabase Realtime (`RealtimeCanal.kt`, protocolo Phoenix) con `postgres_changes` en `mensajes` (INSERT) y `reportes` (UPDATE). El JWT va en el join, así que Realtime respeta RLS. Si la conexión se cae, reintenta en 2, 4, 8... hasta 30 s y recupera los mensajes perdidos.
- **Agente desconectado:** la app del agente o admin llama a `latido()` cada 60 s. Sin latido en 2 minutos, el agente cuenta como desconectado. Al crear un reporte se asigna al agente conectado con menos reportes activos; si no hay ninguno, queda para el administrador.
- **Seguridad:** solo el administrador cambia roles (trigger `perfiles_proteger_rol`). Las cuentas nuevas siempre nacen como `usuario`.
