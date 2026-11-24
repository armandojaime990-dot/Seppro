# SEPPRO — Entregables 2 y 3: cierre del proyecto

**Alumno:** Armando Jaime Ramírez · **Matrícula:** 2022348004 · **Grupo:** IDGS14 · **Materia:** Aplicaciones Móviles
**Fecha:** 24 de noviembre de 2026
**Repositorio:** https://github.com/armandojaime990-dot/Seppro

## Estado de cada punto

| # | Punto pedido | Estado | Dónde está |
|---|---|---|---|
| 2.1 | Conexión del teléfono con el Smart Tag y lectura de telemetría por GATT (identificador del paquete + estatus 0/1) | ✅ Hecho | `seppro-movil/.../GattLector.kt`, `Telemetria.kt`; PWA: `verificacion.store.ts` (Web Bluetooth) |
| 2.2 | Actualización del estado del pedido por la API REST de Supabase (`verificar_entrega`) | ✅ Hecho | `seppro-angular/supabase-entregable3.sql` + `Verificacion.kt` (`VerificacionApi`) |
| 2.3 | Manejo de estado con ViewModel + StateFlow y aviso del resultado al reloj | ✅ Hecho | `VerificacionViewModel` (Kotlin); reloj: `VerificacionListenerService.kt` (Wear OS Data Layer) |
| 3.1 | Sincronización bidireccional con el e-commerce y garantía automática con certificado por correo | ✅ Hecho (el correo requiere configurar una llave, ver abajo) | Realtime en `pedidos`; tabla `garantias`; Edge Function `enviar-certificado`; página `/garantia/:pedido` |
| 3.2 | Excepciones: estado "Alerta de integridad" y opción de rechazar el equipo | ✅ Hecho | `resolver_alerta` (SQL) + botones en Kotlin y PWA; estado nuevo `rechazado` |
| 3.3 | Pruebas de integración, rendimiento y usabilidad en dispositivos físicos | ⚠️ Parcial | Integración y rendimiento automatizados (Prácticas 12 a 14). Falta la prueba con el Smart Tag y el teléfono físicos, porque no hay hardware disponible (checklist abajo) |
| 3.4 | Refinamiento de la interfaz y la experiencia de usuario | ✅ Hecho | Paginación, mensajes claros, estados con color e ícono, íconos de la marca, PWA para iPhone |
| 3.5 | Generación del ejecutable (APK) para teléfono y reloj | ✅ Hecho | `entregables/apk/SEPPRO-telefono-v3.0.apk` y `SEPPRO-reloj-v3.0.apk` (firmados) |
| Extra | App para iPhone | ✅ Hecho | PWA en `/app` de la web Angular (misma interfaz y funciones que la app Kotlin) |

---

## 1. Protocolo del Smart Tag (GATT)

| Elemento | Valor |
|---|---|
| Servicio | `5e1a0001-7c3b-4b8e-9a51-5e9970000001` |
| Característica (READ) | `5e1a0002-7c3b-4b8e-9a51-5e9970000001` |
| Formato texto | `SEPPRO-42;0` (paquete ; estatus). También acepta `,` o `\|` |
| Formato binario | 5 bytes: número de pedido (uint32 little-endian) + 1 byte de estatus |
| Estatus | `0` = viaje sin incidentes, `1` = golpe fuerte. Cualquier otro valor se rechaza |

**Flujo en el teléfono:**
1. Escanear (BLE, entregable 1).
2. Tocar el Smart Tag.
3. **Leer Smart Tag seleccionado**, que ejecuta `connectGatt` → `discoverServices` → `readCharacteristic` → cierra la conexión, con un tiempo límite de 15 s.

**Verificaciones antes de registrar algo:**
- La lectura debe tener formato válido.
- El paquete leído debe corresponder al pedido elegido. Así se evita verificar la caja equivocada.

Para presentar sin hardware hay un **modo demostración** con los botones "Simular sin golpes" y "Simular golpe fuerte". Usa exactamente el mismo flujo y la misma API.

## 2. Backend (Supabase): `supabase-entregable3.sql`

Hay que ejecutarlo **una vez** en el SQL Editor. Es idempotente, así que repetirlo no rompe nada.

| Función o tabla | Qué hace |
|---|---|
| `verificar_entrega(p_pedido, p_paquete, p_estatus, p_sensor)` | Comprueba que el pedido sea del usuario, que esté "En puerta" y que el paquete coincida. Si el estatus es 0, pasa el pedido a `entregado_verificado` y crea la garantía. Si es 1, lo pasa a `alerta_integridad`. Es idempotente: repetir la misma lectura no duplica nada |
| `resolver_alerta(p_pedido, 'aceptar' \| 'rechazar', motivo)` | Con "aceptar" el pedido pasa a `entregado_con_danos`. Con "rechazar" pasa a `rechazado` y se crea un reporte de falla con prioridad alta, que se asigna solo a un agente conectado |
| `garantias` | Folio `GAR-00001`, vigencia de 12 meses y correo de la cuenta. Solo la leen el dueño y el admin; nadie puede editarla por la API |
| Realtime en `pedidos` y `garantias` | La tienda web (Mis pedidos), la PWA y el reloj ven el cambio al instante |

## 3. ViewModel + StateFlow y aviso al reloj

- `VerificacionViewModel` publica los estados `Inactivo → Conectando → Registrando → Verificado | Alerta → Resolviendo → Resuelto | Error`.
- La pantalla los observa con `repeatOnLifecycle(STARTED)`. Si se rota el teléfono, la lectura no se repite.
- El resultado se envía al reloj por **Wear OS Data Layer** (`MessageClient`, ruta `/seppro/verificacion`) y el reloj muestra una notificación con vibración.
- Data Layer exige que el teléfono y el reloj tengan **el mismo applicationId y la misma firma**. Por eso el reloj ahora usa `com.seppro.movil` y los dos APK se firman con la misma llave.

## 4. Garantía y certificado por correo

1. `verificar_entrega` crea la garantía en la base. Esto **siempre funciona**.
2. La app llama a la Edge Function `enviar-certificado`, que manda el certificado en HTML con Resend.
3. El certificado también se puede ver e imprimir en la web: **Mis pedidos → Ver certificado** (`/garantia/:pedido`).

**Para activar el envío de correo**, que es lo único que no se pudo dejar funcionando porque requiere una cuenta de correo propia:

```bash
cd seppro-angular
npx supabase login
npx supabase link --project-ref sbhkqjueesxsjkfootuq
npx supabase secrets set RESEND_API_KEY=re_xxx            # cuenta gratuita en resend.com
npx supabase functions deploy enviar-certificado
```

Sin la llave, la app muestra: "El correo se enviará cuando el servicio esté disponible; el certificado ya está en Mis pedidos".

## 5. Alerta de integridad y rechazo del equipo

- Si el sensor registró un golpe, aparece "🚨 ALERTA DE INTEGRIDAD" con dos botones: **Aceptar equipo con daños** y **Rechazar equipo**.
- Al rechazar se pide un comentario opcional.
- Después del rechazo hay un botón para abrir el reporte creado. Desde ahí se sigue la devolución en el chat de soporte.

## 6. App para iPhone (PWA)

- **Cómo se abre:** en la web desplegada (Vercel) entra a `https://<tu-dominio>/app`.
- **Cómo se instala:** en Safari, **Compartir → Agregar a pantalla de inicio**. Se abre a pantalla completa, con el ícono de SEPPRO, como una app.
- **Funciones** (las mismas que la app Kotlin):
  - Login.
  - Pedidos paginados.
  - Verificar.
  - Historial.
  - Soporte por rol: chat en tiempo real, fotos de evidencia con enlace temporal, cambiar estado, reasignar, editar y cerrar reportes.
  - Garantía.
- **Base de datos:** la misma de Supabase.
- **Limitación de iOS:** Safari en iPhone **no permite Bluetooth en páginas web**. Por eso la PWA:
  - usa Web Bluetooth (GATT) donde existe (Chrome en Android y en PC);
  - en iPhone muestra el aviso y deja el modo demostración. Otra opción es abrir `/app` en el navegador **Bluefy**, que sí tiene Web Bluetooth.

## 7. Seguridad (revisada para que las pruebas de seguridad no fallen)

| Hallazgo | Corrección |
|---|---|
| `npm audit`: 10 vulnerabilidades en producción (5 altas, XSS de Angular 18) y 76 en total (4 críticas) | Actualización a Angular 21 y `overrides` de dependencias transitivas: **0 vulnerabilidades** |
| DEF-01 de la Práctica 11: cualquiera con la llave pública podía leer correo y rol de todos los perfiles | Política `perfiles_own_read`: solo tu perfil, el admin, o quien comparte un reporte contigo |
| Un cliente podía insertar por la API un pedido ya "entregado_verificado" o con precio de $0.01 | Triggers `pedidos_al_crear` y `pedido_items_al_crear`: el estado, los precios y el total los pone el servidor |
| Las RPC se podían invocar sin sesión | `REVOKE EXECUTE ... FROM PUBLIC, anon` |
| Un usuario podía cambiar el correo de su perfil (y desviar el certificado) | `perfiles_proteger_rol` bloquea el cambio de correo; el certificado usa el correo de `auth.users` |
| El reloj permitía tráfico HTTP sin cifrar | `usesCleartextTraffic=false` + `network_security_config` (solo HTTPS) en teléfono y reloj |
| Copias de seguridad de Android con preferencias del usuario | `allowBackup=false` y reglas de extracción de datos |
| Rutas profundas de la web en blanco (sin `<base href>`) | Corregido en `index.html` |
| Llave de firma de los APK | En `firma/`, fuera de Git (`.gitignore`) |

**Pendientes que dependen del dueño de la cuenta de Supabase:**
- Ejecutar `supabase-entregable3.sql`.
- Cambiar la contraseña `123456` de las cuentas de prueba antes de una presentación pública.
- Activar *Leaked password protection* en Auth.

## 8. Prueba en dispositivos físicos (checklist para la presentación)

1. Ejecutar `supabase-entregable3.sql` en Supabase.
2. Instalar `SEPPRO-telefono-v3.0.apk` en el teléfono Android y `SEPPRO-reloj-v3.0.apk` en el reloj. Los dos usan la misma firma.
3. En la web (Dashboard del admin), poner un pedido del cliente en **En puerta**.
4. En el teléfono: Pedidos → Verificar paquete → Escanear → tocar el Smart Tag → Leer. Sin Smart Tag, usar "Simular...".
5. Comprobar:
   - la notificación en el reloj;
   - el estado nuevo en la web (Mis pedidos), sin recargar;
   - la garantía y el certificado.
6. Repetir con "golpe fuerte" y rechazar: debe aparecer un reporte nuevo en Soporte, con el agente asignado.
7. En el iPhone: abrir `/app`, agregarla a la pantalla de inicio y revisar Pedidos, Soporte y el chat.

**Programa sugerido para hacer de Smart Tag** (si se usa un ESP32 o la app *nRF Connect* como servidor GATT):
- Servicio y característica con los UUID de la sección 1.
- Valor `SEPPRO-<número de pedido>;0` o `;1`.
- Nombre anunciado `SEPPRO-Tag`.
