-- =========================================================================
-- SEPPRO — Entregable 1: soporte de verificación por Bluetooth (BLE)
-- Archivo: supabase-bluetooth.sql
--
-- Prepara la tabla `pedidos` para registrar la verificación de integridad
-- del equipo (Smart Tag / sensor de carga) al momento de la entrega.
--
-- Este script es IDEMPOTENTE: se puede ejecutar varias veces sin error.
-- NO modifica las políticas RLS existentes.
-- Ejecutar en: Supabase > SQL Editor > New query > Run.
-- =========================================================================

BEGIN;

-- -------------------------------------------------------------------------
-- 1) Ampliar la columna `estado` a VARCHAR(30)
--    El estado más largo nuevo es 'entregado_verificado' (20 caracteres);
--    se amplía a 30 para dejar margen. Ampliar un VARCHAR no pierde datos
--    y repetir el ALTER con el mismo tipo no genera error.
-- -------------------------------------------------------------------------
ALTER TABLE pedidos
  ALTER COLUMN estado TYPE VARCHAR(30);

-- -------------------------------------------------------------------------
-- 2) Reemplazar la restricción `pedidos_estado_check`
--    Se elimina (si existe) y se vuelve a crear con los estados originales
--    más los 4 estados nuevos del flujo de verificación BLE:
--      - en_puerta             : el repartidor llegó; listo para verificar.
--      - entregado_verificado  : entregado y el sensor no reportó golpes.
--      - entregado_con_danos   : entregado, pero el sensor reportó golpes.
--      - alerta_integridad     : anomalía detectada; requiere revisión.
--    Valores en minúsculas, sin acentos (deben coincidir con Angular y Kotlin).
-- -------------------------------------------------------------------------
ALTER TABLE pedidos
  DROP CONSTRAINT IF EXISTS pedidos_estado_check;

ALTER TABLE pedidos
  ADD CONSTRAINT pedidos_estado_check CHECK (estado IN (
    'pendiente',
    'pagado',
    'enviado',
    'en_camino',
    'entregado',
    'en_puerta',
    'entregado_verificado',
    'entregado_con_danos',
    'alerta_integridad'
  ));

-- -------------------------------------------------------------------------
-- 3) Columnas nuevas para la verificación
--    ADD COLUMN IF NOT EXISTS evita errores si el script se repite.
-- -------------------------------------------------------------------------

-- Identificador del sensor (Smart Tag) pegado a la caja. Opcional:
-- se asigna cuando se vincula un sensor al pedido.
ALTER TABLE pedidos
  ADD COLUMN IF NOT EXISTS sensor_id VARCHAR(50);

-- Resultado de la verificación: TRUE = hubo golpe, FALSE = sin golpe.
-- Permanece NULL hasta que el pedido se verifica (sin DEFAULT a propósito).
ALTER TABLE pedidos
  ADD COLUMN IF NOT EXISTS golpe_detectado BOOLEAN;

-- Fecha y hora (con zona horaria) en que se realizó la verificación. Opcional.
ALTER TABLE pedidos
  ADD COLUMN IF NOT EXISTS fecha_verificacion TIMESTAMPTZ;

-- -------------------------------------------------------------------------
-- 4) Documentación de las columnas dentro de la base de datos
--    COMMENT ON sobrescribe el comentario anterior, por lo que es idempotente.
-- -------------------------------------------------------------------------
COMMENT ON COLUMN pedidos.estado IS
  'Estado del pedido: pendiente, pagado, enviado, en_camino, entregado, en_puerta, entregado_verificado, entregado_con_danos, alerta_integridad';
COMMENT ON COLUMN pedidos.sensor_id IS
  'Identificador del sensor BLE (Smart Tag) asociado a la caja del pedido';
COMMENT ON COLUMN pedidos.golpe_detectado IS
  'Resultado de la verificación BLE: TRUE si el sensor registró golpe; NULL hasta verificar';
COMMENT ON COLUMN pedidos.fecha_verificacion IS
  'Fecha y hora en que se verificó la integridad del paquete';

COMMIT;
