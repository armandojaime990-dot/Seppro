# Guía: Smart Tag real por Bluetooth sin ESP32

Para la demostración, un **segundo teléfono Android** hace de Smart Tag con la app gratuita **nRF Connect for Mobile**, de Nordic Semiconductor. Ese teléfono anuncia el servicio Bluetooth (BLE) y la característica GATT de SEPPRO. Es la misma comunicación que tendría el sensor físico.

## Qué se necesita

| Rol | Dispositivo | Navegador o app |
|---|---|---|
| **Smart Tag** | Cualquier Android con Bluetooth 4.0+ | nRF Connect for Mobile (Play Store) |
| **Lector, opción A** | Laptop Windows o Mac con Bluetooth | Google Chrome → `https://<tu-sitio>/app` |
| **Lector, opción B** | Teléfono Android | Chrome → `/app` (PWA), o el APK de SEPPRO |
| iPhone | No sirve como lector | Safari no permite Bluetooth en páginas web. Ahí se usa la confirmación manual |

La PWA usa la API **Web Bluetooth**. Funciona en Chrome y Edge de Android, Windows, macOS y ChromeOS, y exige HTTPS o `localhost`.

## 1. Preparar el Smart Tag (teléfono con nRF Connect)

1. Instala **nRF Connect for Mobile** y dale los permisos de Bluetooth y ubicación.
2. Cambia el nombre Bluetooth del teléfono a **`SEPPRO-Tag`**, en Ajustes → Bluetooth → Nombre del dispositivo. La app filtra por el prefijo `SEPPRO` o por el servicio.
3. Abre el menú ☰ y entra a **Configure GATT server**. Agrega una configuración y, dentro de ella:
   1. Crea un servicio con UUID `5e1a0001-7c3b-4b8e-9a51-5e9970000001`. Si no lo deja escribir completo, elige *Server Configuration → Add service → custom* y pega el UUID.
   2. Dentro del servicio, crea una característica con:
      - UUID `5e1a0002-7c3b-4b8e-9a51-5e9970000001`;
      - propiedad **Read**;
      - permiso **Read**;
      - valor inicial en **texto (UTF-8)** igual a `SEPPRO-<número de pedido>;0`, por ejemplo `SEPPRO-12;0`.
4. Ve a la pestaña **Advertiser** y crea un anuncio nuevo:
   - activa **Complete local name** para que anuncie `SEPPRO-Tag`;
   - opcionalmente, agrega el **Service UUID** `5e1a0001-...`;
   - marca **Connectable** y actívalo.

**Significado del valor:**
- `SEPPRO-12;0` indica que el paquete del pedido 12 viajó **sin golpes**.
- `SEPPRO-12;1` indica que el sensor registró un **golpe fuerte**.

## 2. Leerlo desde la app (PWA)

1. En la web, con la cuenta del administrador, pon el pedido 12 del cliente en **En puerta**. Usa Dashboard → cambiar estado.
2. En el lector, abre `/app` en Chrome y entra con el cliente.
3. En **Pedidos**, toca **Verificar paquete** en el pedido 12.
4. Toca **Buscar Smart Tag**. Chrome muestra la ventana de permiso con `SEPPRO-Tag`: elígelo.
5. Toca **Leer Smart Tag seleccionado**. La app hace lo siguiente:
   - se conecta por GATT;
   - lee la característica;
   - valida el formato y que el paquete corresponda al pedido;
   - llama a `verificar_entrega` en Supabase.
6. El resultado depende del valor:
   - con `;0` el pedido queda en **Entregado y verificado**, con su **garantía** y una vibración corta;
   - con `;1` aparece la **ALERTA DE INTEGRIDAD** con Aceptar/Rechazar y una vibración larga.
7. En otra ventana, la tienda web (Mis pedidos) cambia sola: es la sincronización en tiempo real.

Con la **app Android (APK)** el flujo es el mismo. Ahí se usa **Escanear**, se toca `SEPPRO-Tag` en la lista y luego **Leer Smart Tag seleccionado**.

## 3. Pruebas de error para la demo

| Caso | Valor en nRF Connect | Resultado esperado |
|---|---|---|
| Paquete equivocado | `SEPPRO-99;0` con el pedido 12 | "Este Smart Tag pertenece al paquete SEPPRO-99, no al pedido #12". No se manda nada al servidor |
| Lectura dañada | `SEPPRO-12;7` | "Lectura inválida del Smart Tag: estatus fuera de rango" |
| Sensor apagado | Detener el Advertiser | "No se pudo conectar con el Smart Tag. Verifica que esté encendido y cerca." o "El Smart Tag no respondió" (límite de 15 s) |
| Bluetooth apagado en el lector | — | Aviso "El Bluetooth está apagado. Enciéndelo…". La app no se cierra |

## 4. ¿Y el ESP32?

No hace falta para la demo. Si se quisiera un sensor físico, un ESP32 con un acelerómetro MPU6050 tendría que hacer tres cosas:
1. Anunciar los mismos UUID.
2. Cambiar el estatus a `1` cuando la aceleración supere unos 4 g.
3. Guardarlo hasta la entrega.

La app no cambia: solo lee la característica.
