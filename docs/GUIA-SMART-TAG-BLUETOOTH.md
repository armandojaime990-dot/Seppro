# Guía: Smart Tag real con ESP32 + MPU6050

El Smart Tag es un **ESP32** con un **acelerómetro MPU6050** pegado a la caja.
1. Durante el viaje mide la aceleración.
2. Si detecta un golpe fuerte, lo guarda en su memoria flash y enciende su LED.
3. Al entregar, la app se conecta por **Bluetooth LE (GATT)** y lee el resultado.

El programa está en `smart-tag-esp32/SEPPRO_SmartTag/SEPPRO_SmartTag.ino`.

## 1. Material (aprox. $250 MXN)

| Pieza | Nota |
|---|---|
| ESP32 DevKit V1 (ESP-WROOM-32) | Tiene Bluetooth LE y LED en GPIO2 |
| Módulo MPU6050 (GY-521) | Acelerómetro de 3 ejes, I2C |
| 4 cables dupont hembra-hembra | |
| Cable USB de datos | Algunos cables solo cargan |
| Power bank pequeño | Para que viaje en la caja sin la computadora |

## 2. Conexiones

| MPU6050 | ESP32 |
|---|---|
| VCC | 3V3 |
| GND | GND |
| SDA | GPIO21 |
| SCL | GPIO22 |

## 3. Cargar el programa (Arduino IDE)

1. Si aún no tienes el núcleo de ESP32 (ya está instalado en esta computadora, versión 3.0.7):
   - en Archivo → Preferencias → URLs adicionales, agrega `https://espressif.github.io/arduino-esp32/package_esp32_index.json`;
   - en el Gestor de placas, instala **esp32 de Espressif**.
2. Abre `SEPPRO_SmartTag.ino`. No necesita bibliotecas externas.
3. Configura la placa y el puerto:
   - Placa: **ESP32 Dev Module**;
   - Puerto: el COM del ESP32;
   - Partition Scheme: si al compilar sale "Sketch too big", elige **Huge APP (3MB No OTA)**.
4. Pulsa **Subir**. Si se queda en "Connecting…", mantén presionado el botón **BOOT** de la placa hasta que empiece a cargar.
5. Abre el **Monitor Serie** a **115200** baudios con **Nueva línea**. Debe aparecer:
   ```
   === SEPPRO Smart Tag listo (Bluetooth: SEPPRO-Tag) ===
   [SEPPRO] Pedido 1 | estatus 0 (sin incidentes) | ... | acelerometro OK | app desconectada
   ```

## 4. Comandos del Monitor Serie

| Comando | Qué hace |
|---|---|
| `PEDIDO 12` | Asigna la caja al pedido 12 (se guarda aunque se apague) |
| `RESET` | Viaje nuevo: estatus 0 y borra el golpe. También se hace manteniendo **BOOT** 2 s |
| `GOLPE` | Registra un golpe a mano (solo si no tienes el acelerómetro) |
| `UMBRAL 2.5` | Cambia la fuerza mínima del golpe, en g (1.5 a 8). Con 2.5 g, mover la caja no cuenta, pero un golpe seco sí |
| `ESTADO` | Muestra el pedido, el estatus, el golpe máximo y si la app está conectada |

## 5. Demostración

Antes de empezar:
- En Supabase ya se ejecutó `supabase-reinicio-usuarios.sql`.
- Después del reinicio, **los pedidos 12 y 13 de `cliente2@seppro.com` ya están "En puerta"**: el 12 para la entrega sin golpes y el 13 para la de golpe. El pedido 5 de `cliente1` también está en puerta. Para más pedidos, el **admin** cambia uno a "En puerta" en el Dashboard de la web.

**Entrega sin golpes:**
1. En el Monitor Serie escribe `PEDIDO 12` y luego `RESET`. El LED queda apagado.
2. En el lector (Chrome en Android o en la laptop, en `/app`; o el APK en Android), con `cliente2@seppro.com`:
   - Pedidos → **Verificar paquete** en el pedido 12;
   - **Buscar Smart Tag**: elige `SEPPRO-Tag` (en el APK es **Escanear** y luego tocarlo);
   - **Leer Smart Tag seleccionado**.
3. Resultado: **Entrega verificada**, garantía GAR-xxxxx y vibración corta. En el Monitor Serie aparece `Lectura de la app: SEPPRO-12;0`.
4. En la tienda web, **Mis pedidos** cambia sola y muestra **Ver certificado**.

**Entrega con golpe:**
1. En el Monitor Serie escribe `PEDIDO 13` y `RESET`.
2. **Golpea la caja (o el ESP32) contra la mesa.** Se enciende el LED y el Monitor muestra `*** GOLPE FUERTE: 3.4 g ***`.
3. En la app, verifica el pedido 13. Aparece **ALERTA DE INTEGRIDAD** con vibración larga.
4. Toca **Rechazar equipo**: se crea un reporte de falla con prioridad alta.
5. Desconecta el ESP32 y vuelve a conectarlo: el golpe **sigue registrado**, porque está guardado en la flash. Así se demuestra que el sensor guarda lo que pasó en el viaje.

**Casos de error (para la pregunta "¿y si falla?"):**

| Caso | Cómo provocarlo | Resultado en la app |
|---|---|---|
| Caja de otro pedido | `PEDIDO 99` y verificar el pedido 12 | "Este Smart Tag pertenece al paquete SEPPRO-99, no al pedido #12". No se registra nada |
| Sensor apagado o lejos | Desconectar el ESP32 después de elegirlo | "No se pudo conectar con el Smart Tag. Verifica que esté encendido y cerca." o "El Smart Tag no respondió" (15 s) |
| Bluetooth del lector apagado | Apagar el Bluetooth | "El Bluetooth está apagado. Enciéndelo…". La app no se cierra |
| iPhone | Abrir `/app` en Safari | Safari no tiene Web Bluetooth: aparece la confirmación manual |

## 6. Qué decir si preguntan

- **¿Qué es GATT?** Es la forma en que Bluetooth LE organiza los datos: un **servicio** (`5e1a0001-…`) contiene **características** (`5e1a0002-…`). La app se conecta, busca el servicio y lee la característica, que contiene el texto `SEPPRO-12;0`.
- **¿Cómo detecta el golpe?** Calcula la magnitud de la aceleración √(x² + y² + z²). En reposo vale 1 g por la gravedad, y si supera 2.5 g se considera golpe fuerte. Mide 200 veces por segundo para no perder un impacto corto.
- **¿Por qué se guarda en la flash?** Para que el registro no se pierda si se acaba la batería durante el viaje. Se guarda solo el primer golpe y los mayores, como máximo cada 500 ms, para no desgastar la memoria.
- **¿Quién decide el estado del pedido?** El servidor. La función `verificar_entrega` comprueba que el pedido sea del cliente, que esté "En puerta" y que el paquete coincida. El ESP32 solo reporta lo que midió.

## 7. Alternativa sin hardware: nRF Connect

Si el ESP32 falla el día de la presentación, otro teléfono **Android** con **nRF Connect for Mobile** puede anunciar el mismo servidor GATT:
1. Ponle a ese teléfono el nombre Bluetooth `SEPPRO-Tag`.
2. En **Configure GATT server**, crea el servicio `5e1a0001-7c3b-4b8e-9a51-5e9970000001`.
3. Dentro, crea la característica `5e1a0002-7c3b-4b8e-9a51-5e9970000001`, de tipo Read, con el texto `SEPPRO-12;0`.
4. En **Advertiser**, crea un anuncio que incluya el nombre y actívalo.

Desde la app se lee igual que el ESP32.
