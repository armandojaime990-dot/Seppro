# Práctica 8: pruebas de caja negra (SEPPRO)

**Objeto de prueba:** app del teléfono `seppro-movil` (debug APK), en el emulador de Android Studio (API 34).
**Técnica:** caja negra. Solo se observan entradas y salidas; no se revisa el código.
**Clasificación:** Aprobado (el resultado coincide con el criterio) / Fallido (no coincide) / No verificable (depende de una función pendiente).

## 1. Requisitos verificables

| ID | Categoría | Requisito | Criterio de aceptación (medible) |
|---|---|---|---|
| RQ01 | Operación principal | Iniciar sesión con una cuenta válida | Con correo y contraseña correctos, la pantalla principal abre en ≤ 5 s y la barra superior muestra el nombre y el rol. |
| RQ02 | Operación principal | Consultar pedidos | La pestaña Pedidos muestra solo los pedidos del usuario, con número, total y estado. Los pedidos entregados aparecen en Historial. |
| RQ03 | Operación principal | Escanear dispositivos BLE | Con Bluetooth y permisos activos, el escaneo dura 10 s y lista los dispositivos sin duplicados, con nombre, dirección y RSSI. |
| RQ04 | Operación principal | Registrar un reporte | Con datos válidos, se crea un reporte con folio `REP` + 3 dígitos y se abre su conversación. |
| RQ05 | Validación | Correo y contraseña | Con campos vacíos, correo sin formato o contraseña de menos de 6 caracteres no se envía la petición y se muestra un mensaje. Con credenciales incorrectas el mensaje es genérico ("Correo o contraseña incorrectos"). |
| RQ06 | Validación | Campos del reporte | Si la descripción tiene menos de 10 caracteres, o en una falla falta el equipo o el pedido, no se crea el reporte y se indica el campo. |
| RQ07 | Validación | Mensajes vacíos | Un mensaje vacío o de solo espacios no se envía y aparece "El mensaje no puede estar vacío". |
| RQ08 | Persistencia | Reporte modificado | Al editar la descripción o la prioridad y volver a abrir el reporte (o verlo en otro dispositivo), se conservan los cambios. |
| RQ09 | Persistencia | Datos al cerrar la app | Después de cerrar la app por completo e iniciar sesión otra vez, los reportes y mensajes siguen ahí, y el último correo usado aparece escrito en el login. |
| RQ10 | Roles | Un cliente no ve reportes ajenos | El cliente 1 no ve en Soporte los reportes del cliente 2. El agente solo ve los que tiene asignados. |
| RQ11 | Navegación | Barra inferior | Pedidos, Verificar, Historial y Soporte abren su pantalla correspondiente al primer toque. |
| RQ12 | Manejo de errores | Sin Internet / Bluetooth apagado | En modo avión, las pantallas muestran "Sin conexión..." o "Error de red" y la app no se cierra. Con el Bluetooth apagado, Verificar muestra "Bluetooth apagado" y el botón "Activar Bluetooth". |

## 2. Casos de prueba

| Caso | RQ | Entrada / pasos | Resultado esperado | Resultado obtenido | Evidencia (captura) | Estado |
|---|---|---|---|---|---|---|
| CP01 | RQ01 | cliente1@seppro.com + contraseña correcta → Entrar | Abre la pantalla principal con "Nombre · Cliente" | | | |
| CP02 | RQ02 | Pestaña Pedidos y luego Historial | Solo los pedidos propios, separados por estado | | | |
| CP03 | RQ03 | Verificar → Escanear (BT y ubicación encendidos) | Lista de dispositivos en 10 s, ordenada por señal | | | |
| CP04 | RQ04 | Soporte → Nuevo reporte → falla, pedido, equipo y descripción válidos → Crear | Toast con folio REP00X y apertura del chat | | | |
| CP05 | RQ05 | Correo "abc" + contraseña "123" | "Escribe un correo válido" y no hay petición | | | |
| CP06 | RQ05 | Correo válido + contraseña incorrecta | "Correo o contraseña incorrectos" (no dice cuál falló) | | | |
| CP07 | RQ06 | Descripción "falla" (5 caracteres) | "La descripción debe tener al menos 10 caracteres" | | | |
| CP08 | RQ07 | Mensaje "   " → Enviar | "El mensaje no puede estar vacío" y no se envía | | | |
| CP09 | RQ08 | Editar → prioridad Alta y nueva descripción → Guardar → salir y volver a entrar | Se mantienen los cambios | | | |
| CP10 | RQ09 | Cerrar la app desde recientes, abrir e iniciar sesión | Reportes y mensajes presentes; correo prellenado | | | |
| CP11 | RQ10 | Con cliente2, revisar Soporte | No aparecen reportes de cliente1 | | | |
| CP12 | RQ11 | Tocar cada pestaña | Abre la pantalla correcta | | | |
| CP13 | RQ12 | Modo avión → Pedidos → Actualizar; Soporte → Actualizar | Mensaje de error de red y la app sigue abierta | | | |
| CP14 | RQ12 | Bluetooth apagado → Verificar → Escanear | "Bluetooth apagado" + botón "Activar Bluetooth" | | | |

## 3. Requisitos que aún no pueden verificarse

| Requisito | Motivo | Se verificará en |
|---|---|---|
| Lectura GATT del Smart Tag (ID del paquete y estatus 0/1) | Conexión GATT pendiente | Entregable 2 |
| Actualización del pedido con `verificar_entrega` | Función pendiente | Entregable 2 |
| Notificación del resultado al reloj | Pendiente | Entregable 2 |
| Garantía automática / certificado por correo | Pendiente | Entregable 3 |

## 4. Resumen de resultados

| Total de casos | Aprobados | Fallidos | No verificables |
|---|---|---|---|
| 14 | | | 4 |

**Defectos encontrados:** (anotar ID del caso, descripción, severidad y corrección)
