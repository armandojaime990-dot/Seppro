/*
 * SEPPRO — Smart Tag (ESP32 + acelerómetro MPU6050)
 * -------------------------------------------------
 * Sensor que viaja pegado a la caja de la bomba. Durante el traslado mide la
 * aceleración; si detecta un golpe fuerte (más de UMBRAL_G) lo GUARDA en la
 * memoria flash (sobrevive a que se apague) y enciende el LED.
 *
 * Al entregar, la app SEPPRO (PWA en Chrome o APK Android) se conecta por
 * Bluetooth LE y lee la característica GATT:
 *   Servicio        5e1a0001-7c3b-4b8e-9a51-5e9970000001
 *   Característica  5e1a0002-7c3b-4b8e-9a51-5e9970000001  (READ)
 *   Valor (texto)   "SEPPRO-<pedido>;<estatus>"   ej. "SEPPRO-12;0"
 *                   estatus 0 = viaje sin incidentes · 1 = golpe fuerte
 * Nombre anunciado: "SEPPRO-Tag"
 *
 * Conexiones (MPU6050 / GY-521 -> ESP32 DevKit):
 *   VCC -> 3V3   GND -> GND   SDA -> GPIO21   SCL -> GPIO22
 *
 * Comandos por el Monitor Serie (115200 baudios, "Nueva línea"):
 *   PEDIDO 12     asigna el número de pedido de la caja (se guarda)
 *   RESET         borra el golpe registrado (estatus 0) para un viaje nuevo
 *   GOLPE         registra un golpe a mano (si no hay acelerómetro)
 *   UMBRAL 2.5    cambia el umbral de golpe en g (1.5 a 8)
 *   ESTADO        muestra pedido, estatus, golpe máximo y conexión
 * El botón BOOT (GPIO0) mantenido 2 s también hace RESET.
 *
 * Probado con la placa "ESP32 Dev Module" y el núcleo esp32 de Espressif 3.x.
 * No necesita bibliotecas externas (BLE y Wire vienen con el núcleo).
 */
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Preferences.h>
#include <Wire.h>

#define UUID_SERVICIO        "5e1a0001-7c3b-4b8e-9a51-5e9970000001"
#define UUID_CARACTERISTICA  "5e1a0002-7c3b-4b8e-9a51-5e9970000001"
#define NOMBRE_BLE           "SEPPRO-Tag"

const int PIN_LED = 2;      // LED azul de la placa: encendido = golpe registrado
const int PIN_BOTON = 0;    // botón BOOT
const uint8_t MPU_DIR = 0x68;

Preferences memoria;
BLECharacteristic *caracteristica = nullptr;
bool hayAcelerometro = false;
bool clienteConectado = false;

uint32_t pedido = 1;        // número de pedido de la caja (comando PEDIDO)
uint8_t estatus = 0;        // 0 sin incidentes · 1 golpe fuerte
float umbralG = 2.5f;       // un golpe de caída o impacto supera 2.5 g; manipular la caja, no
float maximoG = 0.0f;       // golpe más fuerte registrado en el viaje
unsigned long botonDesde = 0;

// ---------------------------------------------------------------------------
// Valor que lee la app
// ---------------------------------------------------------------------------
void publicarValor() {
  char texto[32];
  snprintf(texto, sizeof(texto), "SEPPRO-%lu;%u", (unsigned long)pedido, estatus);
  caracteristica->setValue((uint8_t *)texto, strlen(texto));
  digitalWrite(PIN_LED, estatus == 1 ? HIGH : LOW);
}

void guardar() {
  memoria.putUInt("pedido", pedido);
  memoria.putUChar("estatus", estatus);
  memoria.putFloat("umbral", umbralG);
  memoria.putFloat("maximo", maximoG);
}

void mostrarEstado() {
  Serial.printf("[SEPPRO] Pedido %lu | estatus %u (%s) | golpe maximo %.2f g | umbral %.2f g | acelerometro %s | app %s\n",
                (unsigned long)pedido, estatus, estatus ? "GOLPE FUERTE" : "sin incidentes", maximoG, umbralG,
                hayAcelerometro ? "OK" : "NO DETECTADO", clienteConectado ? "conectada" : "desconectada");
}

unsigned long ultimoGuardado = 0;

void registrarGolpe(float g) {
  bool nuevo = estatus == 0;
  bool mayor = g > maximoG + 0.25f;
  if (g > maximoG) maximoG = g;
  if (nuevo) {
    estatus = 1;
    Serial.printf("[SEPPRO] *** GOLPE FUERTE: %.2f g *** (queda registrado)\n", g);
    publicarValor();
  }
  // Un impacto dura varias muestras: se guarda en flash solo si es el primero o uno mayor,
  // y como máximo cada 500 ms (cuida la memoria flash)
  if (nuevo || (mayor && millis() - ultimoGuardado > 500)) {
    guardar();
    ultimoGuardado = millis();
  }
}

void reiniciarViaje() {
  estatus = 0;
  maximoG = 0.0f;
  guardar();
  publicarValor();
  Serial.println("[SEPPRO] Viaje nuevo: estatus 0 (sin incidentes)");
}

// ---------------------------------------------------------------------------
// MPU6050 por I2C (registros directos, rango ±8 g)
// ---------------------------------------------------------------------------
bool iniciarAcelerometro() {
  Wire.begin(21, 22);
  Wire.beginTransmission(MPU_DIR);
  if (Wire.endTransmission() != 0) return false;
  Wire.beginTransmission(MPU_DIR); Wire.write(0x6B); Wire.write(0x00); Wire.endTransmission(); // despertar
  Wire.beginTransmission(MPU_DIR); Wire.write(0x1C); Wire.write(0x10); Wire.endTransmission(); // ±8 g
  return true;
}

// Magnitud de la aceleración en g (en reposo ≈ 1 g por la gravedad)
float leerMagnitudG() {
  Wire.beginTransmission(MPU_DIR);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return 1.0f;
  if (Wire.requestFrom((int)MPU_DIR, 6) != 6) return 1.0f;
  int16_t ax = (Wire.read() << 8) | Wire.read();
  int16_t ay = (Wire.read() << 8) | Wire.read();
  int16_t az = (Wire.read() << 8) | Wire.read();
  const float escala = 4096.0f; // LSB/g en ±8 g
  float x = ax / escala, y = ay / escala, z = az / escala;
  return sqrtf(x * x + y * y + z * z);
}

// ---------------------------------------------------------------------------
// Bluetooth LE
// ---------------------------------------------------------------------------
class EventosServidor : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    clienteConectado = true;
    Serial.println("[SEPPRO] App conectada: enviando telemetria");
  }
  void onDisconnect(BLEServer *) override {
    clienteConectado = false;
    Serial.println("[SEPPRO] App desconectada: vuelvo a anunciarme");
    BLEDevice::startAdvertising();
  }
};

class EventosLectura : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic *) override {
    Serial.printf("[SEPPRO] Lectura de la app: SEPPRO-%lu;%u\n", (unsigned long)pedido, estatus);
  }
};

void iniciarBluetooth() {
  BLEDevice::init(NOMBRE_BLE);
  BLEServer *servidor = BLEDevice::createServer();
  servidor->setCallbacks(new EventosServidor());
  BLEService *servicio = servidor->createService(UUID_SERVICIO);
  caracteristica = servicio->createCharacteristic(UUID_CARACTERISTICA, BLECharacteristic::PROPERTY_READ);
  caracteristica->setCallbacks(new EventosLectura());
  publicarValor();
  servicio->start();

  BLEAdvertising *anuncio = BLEDevice::getAdvertising();
  anuncio->addServiceUUID(UUID_SERVICIO);   // la app filtra por este servicio o por el nombre "SEPPRO"
  anuncio->setScanResponse(true);           // el nombre va en la respuesta de escaneo
  BLEDevice::startAdvertising();
}

// ---------------------------------------------------------------------------
// Comandos por el Monitor Serie
// ---------------------------------------------------------------------------
void atenderComando(String linea) {
  linea.trim();
  String cmd = linea;
  cmd.toUpperCase();
  if (cmd.startsWith("PEDIDO")) {
    long n = linea.substring(6).toInt();
    if (n > 0 && n < 1000000000L) {
      pedido = (uint32_t)n;
      guardar();
      publicarValor();
      Serial.printf("[SEPPRO] Pedido asignado: %lu\n", (unsigned long)pedido);
    } else {
      Serial.println("[SEPPRO] Uso: PEDIDO 12");
    }
  } else if (cmd == "RESET") {
    reiniciarViaje();
  } else if (cmd == "GOLPE") {
    registrarGolpe(umbralG + 0.5f);
  } else if (cmd.startsWith("UMBRAL")) {
    float u = linea.substring(6).toFloat();
    if (u >= 1.5f && u <= 8.0f) {
      umbralG = u;
      guardar();
      Serial.printf("[SEPPRO] Umbral: %.2f g\n", umbralG);
    } else {
      Serial.println("[SEPPRO] Uso: UMBRAL 2.5 (entre 1.5 y 8)");
    }
  } else if (cmd == "ESTADO" || cmd.length() == 0) {
    mostrarEstado();
  } else {
    Serial.println("[SEPPRO] Comandos: PEDIDO <n> | RESET | GOLPE | UMBRAL <g> | ESTADO");
  }
}

// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BOTON, INPUT_PULLUP);

  memoria.begin("seppro", false);
  pedido = memoria.getUInt("pedido", 1);
  estatus = memoria.getUChar("estatus", 0) == 1 ? 1 : 0;
  umbralG = memoria.getFloat("umbral", 2.5f);
  maximoG = memoria.getFloat("maximo", 0.0f);

  hayAcelerometro = iniciarAcelerometro();
  iniciarBluetooth();

  Serial.println();
  Serial.println("=== SEPPRO Smart Tag listo (Bluetooth: SEPPRO-Tag) ===");
  if (!hayAcelerometro) Serial.println("[SEPPRO] No se detecto el MPU6050: usa el comando GOLPE para simular un golpe");
  mostrarEstado();
  Serial.println("[SEPPRO] Comandos: PEDIDO <n> | RESET | GOLPE | UMBRAL <g> | ESTADO");
}

void loop() {
  // 1) Golpes: se muestrea cada 5 ms (200 Hz) para no perder un impacto corto
  if (hayAcelerometro) {
    float g = leerMagnitudG();
    if (g >= umbralG) registrarGolpe(g);
  }

  // 2) Comandos del Monitor Serie
  if (Serial.available()) atenderComando(Serial.readStringUntil('\n'));

  // 3) Botón BOOT mantenido 2 s = viaje nuevo
  if (digitalRead(PIN_BOTON) == LOW) {
    if (botonDesde == 0) botonDesde = millis();
    if (millis() - botonDesde > 2000) { reiniciarViaje(); botonDesde = 0; delay(500); }
  } else {
    botonDesde = 0;
  }

  delay(5);
}
