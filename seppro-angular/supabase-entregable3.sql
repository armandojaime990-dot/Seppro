-- =========================================================================
-- SEPPRO — Entregables 2 y 3 + endurecimiento de seguridad
-- Archivo: supabase-entregable3.sql
--
--  1) Estado nuevo 'rechazado' (el cliente rechaza el equipo por golpe fuerte).
--  2) Tabla garantias (certificado de garantía al verificar sin golpes).
--  3) RPC verificar_entrega(pedido, paquete, estatus, sensor): la llama el teléfono
--     (Kotlin y PWA) con la telemetría leída del Smart Tag por GATT.
--       estatus 0 = viaje sin incidentes -> entregado_verificado + garantía
--       estatus 1 = golpe fuerte         -> alerta_integridad
--  4) RPC resolver_alerta(pedido, 'aceptar'|'rechazar', motivo): manejo de la alerta.
--       aceptar  -> entregado_con_danos
--       rechazar -> rechazado + reporte de falla automático (prioridad alta)
--  5) Seguridad: precios y estados de pedidos calculados por el servidor,
--     perfiles privados (DEF-01 de la Práctica 11), RPC solo con sesión,
--     correo del perfil protegido.
--  6) Realtime en pedidos y garantias (sincronización web <-> teléfono <-> reloj).
--
-- IDEMPOTENTE: se puede ejecutar varias veces. Requiere haber ejecutado antes
-- supabase-bluetooth.sql y supabase-soporte.sql.
-- Ejecutar en: Supabase > SQL Editor > New query > Run.
-- =========================================================================

BEGIN;

-- -------------------------------------------------------------------------
-- 1) Estado 'rechazado'
-- -------------------------------------------------------------------------
ALTER TABLE pedidos DROP CONSTRAINT IF EXISTS pedidos_estado_check;
ALTER TABLE pedidos ADD CONSTRAINT pedidos_estado_check CHECK (estado IN (
  'pendiente', 'pagado', 'enviado', 'en_camino', 'entregado',
  'en_puerta', 'entregado_verificado', 'entregado_con_danos', 'alerta_integridad',
  'rechazado'
));

-- Identificador del paquete que reportó el Smart Tag y motivo de rechazo
ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS paquete_id VARCHAR(60);
ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS motivo_rechazo TEXT;

COMMENT ON COLUMN pedidos.estado IS
  'pendiente, pagado, enviado, en_camino, entregado, en_puerta, entregado_verificado, entregado_con_danos, alerta_integridad, rechazado';

-- -------------------------------------------------------------------------
-- 2) Garantías
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS garantias (
  id                 SERIAL PRIMARY KEY,
  folio              TEXT GENERATED ALWAYS AS ('GAR-' || lpad(id::text, 5, '0')) STORED,
  pedido_id          INTEGER NOT NULL UNIQUE REFERENCES pedidos(id) ON DELETE CASCADE,
  usuario_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  correo             TEXT NOT NULL,
  cliente_nombre     TEXT,
  producto           TEXT,
  inicio             DATE NOT NULL DEFAULT current_date,
  vence              DATE NOT NULL DEFAULT (current_date + interval '12 months')::date,
  correo_enviado     BOOLEAN NOT NULL DEFAULT FALSE,
  correo_enviado_at  TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS garantias_usuario_idx ON garantias (usuario_id);

ALTER TABLE garantias ENABLE ROW LEVEL SECURITY;
-- Solo lectura para el dueño y el administrador. Se crean únicamente con
-- verificar_entrega (SECURITY DEFINER); nadie puede insertarlas ni editarlas desde la API.
DROP POLICY IF EXISTS "garantias_select" ON garantias;
CREATE POLICY "garantias_select" ON garantias FOR SELECT TO authenticated
  USING (usuario_id = auth.uid() OR public.is_admin());

-- -------------------------------------------------------------------------
-- 3) verificar_entrega
-- -------------------------------------------------------------------------
-- "SEPPRO-0042", "seppro-42", "42" -> "42"
CREATE OR REPLACE FUNCTION public.normalizar_paquete(p TEXT)
RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
  SELECT NULLIF(ltrim(regexp_replace(upper(btrim(COALESCE(p, ''))), '^SEPPRO-?', ''), '0'), '');
$$;

CREATE OR REPLACE FUNCTION public.verificar_entrega(
  p_pedido INTEGER, p_paquete TEXT, p_estatus INTEGER, p_sensor TEXT DEFAULT NULL)
RETURNS JSON LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_uid      UUID := auth.uid();
  v_pedido   pedidos%ROWTYPE;
  v_nuevo    TEXT;
  v_correo   TEXT;
  v_nombre   TEXT;
  v_producto TEXT;
  v_gar      garantias%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Inicia sesión para verificar la entrega' USING ERRCODE = '42501';
  END IF;
  IF p_estatus IS NULL OR p_estatus NOT IN (0, 1) THEN
    RAISE EXCEPTION 'Estatus de telemetría inválido: debe ser 0 (sin incidentes) o 1 (golpe fuerte)'
      USING ERRCODE = '22023';
  END IF;

  -- FOR UPDATE: dos verificaciones simultáneas del mismo pedido se serializan
  SELECT * INTO v_pedido FROM pedidos
   WHERE id = p_pedido AND usuario_id = v_uid
   FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'El pedido no existe o no es tuyo' USING ERRCODE = '42501';
  END IF;

  -- El Smart Tag debe ser el del paquete de ESTE pedido
  IF public.normalizar_paquete(p_paquete) IS DISTINCT FROM p_pedido::TEXT THEN
    RAISE EXCEPTION 'El sensor pertenece a otro paquete (%), no al pedido #%',
      COALESCE(NULLIF(btrim(p_paquete), ''), 'sin identificador'), p_pedido USING ERRCODE = '22023';
  END IF;

  v_nuevo := CASE p_estatus WHEN 0 THEN 'entregado_verificado' ELSE 'alerta_integridad' END;

  -- Idempotencia: repetir la misma lectura no cambia nada ni duplica la garantía
  IF v_pedido.estado = v_nuevo THEN
    SELECT * INTO v_gar FROM garantias WHERE pedido_id = p_pedido;
    RETURN json_build_object('pedido_id', p_pedido, 'estado', v_nuevo, 'golpe', p_estatus = 1,
      'garantia_folio', v_gar.folio, 'garantia_vence', v_gar.vence, 'repetido', TRUE);
  END IF;

  IF v_pedido.estado <> 'en_puerta' THEN
    RAISE EXCEPTION 'El pedido no está listo para verificar (estado actual: %)', v_pedido.estado
      USING ERRCODE = '55000';
  END IF;

  UPDATE pedidos
     SET estado = v_nuevo,
         golpe_detectado = (p_estatus = 1),
         paquete_id = left(btrim(p_paquete), 60),
         sensor_id = COALESCE(left(NULLIF(btrim(p_sensor), ''), 50), sensor_id),
         fecha_verificacion = now()
   WHERE id = p_pedido;

  -- Sin golpes: se activa la garantía automáticamente
  IF p_estatus = 0 THEN
    SELECT u.email INTO v_correo FROM auth.users u WHERE u.id = v_uid; -- correo real de la cuenta
    SELECT nombre INTO v_nombre FROM perfiles WHERE id = v_uid;
    SELECT string_agg(nombre_producto, ', ' ORDER BY id) INTO v_producto
      FROM pedido_items WHERE pedido_id = p_pedido;
    INSERT INTO garantias (pedido_id, usuario_id, correo, cliente_nombre, producto)
    VALUES (p_pedido, v_uid, v_correo, v_nombre, left(v_producto, 300))
    ON CONFLICT (pedido_id) DO NOTHING;
    SELECT * INTO v_gar FROM garantias WHERE pedido_id = p_pedido;
  END IF;

  RETURN json_build_object('pedido_id', p_pedido, 'estado', v_nuevo, 'golpe', p_estatus = 1,
    'garantia_folio', v_gar.folio, 'garantia_vence', v_gar.vence, 'repetido', FALSE);
END;
$$;

-- -------------------------------------------------------------------------
-- 4) resolver_alerta: el cliente acepta el equipo con daños o lo rechaza
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.resolver_alerta(
  p_pedido INTEGER, p_accion TEXT, p_motivo TEXT DEFAULT NULL)
RETURNS JSON LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_uid     UUID := auth.uid();
  v_pedido  pedidos%ROWTYPE;
  v_nuevo   TEXT;
  v_equipo  TEXT;
  v_rep     reportes%ROWTYPE;
  v_motivo  TEXT := left(btrim(COALESCE(p_motivo, '')), 500);
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Inicia sesión' USING ERRCODE = '42501';
  END IF;
  IF p_accion NOT IN ('aceptar', 'rechazar') THEN
    RAISE EXCEPTION 'Acción inválida: aceptar o rechazar' USING ERRCODE = '22023';
  END IF;

  SELECT * INTO v_pedido FROM pedidos WHERE id = p_pedido AND usuario_id = v_uid FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'El pedido no existe o no es tuyo' USING ERRCODE = '42501';
  END IF;

  v_nuevo := CASE p_accion WHEN 'aceptar' THEN 'entregado_con_danos' ELSE 'rechazado' END;
  IF v_pedido.estado = v_nuevo THEN -- repetido: no se duplica el reporte
    SELECT * INTO v_rep FROM reportes WHERE pedido_id = p_pedido AND tipo = 'falla'
      ORDER BY id DESC LIMIT 1;
    RETURN json_build_object('pedido_id', p_pedido, 'estado', v_nuevo,
      'reporte_id', v_rep.id, 'reporte_folio', v_rep.folio, 'repetido', TRUE);
  END IF;
  IF v_pedido.estado <> 'alerta_integridad' THEN
    RAISE EXCEPTION 'El pedido no tiene una alerta de integridad pendiente (estado: %)', v_pedido.estado
      USING ERRCODE = '55000';
  END IF;

  UPDATE pedidos SET estado = v_nuevo,
         motivo_rechazo = CASE WHEN p_accion = 'rechazar' THEN NULLIF(v_motivo, '') END
   WHERE id = p_pedido;

  IF p_accion = 'rechazar' THEN
    SELECT left(string_agg(nombre_producto, ', ' ORDER BY id), 120) INTO v_equipo
      FROM pedido_items WHERE pedido_id = p_pedido;
    INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad)
    VALUES (v_uid, p_pedido, 'falla', v_equipo,
            'Equipo rechazado al recibir: el Smart Tag registró un golpe fuerte durante el traslado.'
              || CASE WHEN v_motivo <> '' THEN ' Comentario del cliente: ' || v_motivo ELSE '' END,
            'alta')
    RETURNING * INTO v_rep;
  END IF;

  RETURN json_build_object('pedido_id', p_pedido, 'estado', v_nuevo,
    'reporte_id', v_rep.id, 'reporte_folio', v_rep.folio, 'repetido', FALSE);
END;
$$;

-- -------------------------------------------------------------------------
-- 5) Seguridad
-- -------------------------------------------------------------------------

-- 5a) Pedidos: el cliente NO decide el estado, el total ni los precios.
--     Antes un cliente podía insertar un pedido "entregado_verificado" o con
--     precio_unitario = 0.01 directamente por la API REST.
CREATE OR REPLACE FUNCTION public.pedidos_al_crear()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF auth.uid() IS NOT NULL AND NOT public.is_admin() THEN
    NEW.usuario_id := auth.uid();
    NEW.estado := 'pagado';            -- pago simulado de la tienda
    NEW.total := 0;                    -- lo recalculan los artículos
    NEW.sensor_id := NULL;
    NEW.golpe_detectado := NULL;
    NEW.fecha_verificacion := NULL;
    NEW.paquete_id := NULL;
    NEW.motivo_rechazo := NULL;
    NEW.created_at := now();
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS pedidos_al_crear ON pedidos;
CREATE TRIGGER pedidos_al_crear BEFORE INSERT ON pedidos
  FOR EACH ROW EXECUTE PROCEDURE public.pedidos_al_crear();

CREATE OR REPLACE FUNCTION public.pedido_items_al_crear()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_nombre TEXT; v_precio NUMERIC; v_estado TEXT; v_fecha TIMESTAMPTZ;
BEGIN
  IF auth.uid() IS NOT NULL AND NOT public.is_admin() THEN
    SELECT estado, created_at INTO v_estado, v_fecha FROM pedidos
     WHERE id = NEW.pedido_id AND usuario_id = auth.uid();
    IF NOT FOUND THEN
      RAISE EXCEPTION 'El pedido no es tuyo' USING ERRCODE = '42501';
    END IF;
    IF v_estado <> 'pagado' OR v_fecha < now() - interval '10 minutes' THEN
      RAISE EXCEPTION 'Ya no se pueden agregar artículos a este pedido' USING ERRCODE = '42501';
    END IF;
    IF NEW.cantidad IS NULL OR NEW.cantidad NOT BETWEEN 1 AND 99 THEN
      RAISE EXCEPTION 'Cantidad inválida (1 a 99)' USING ERRCODE = '22023';
    END IF;
    SELECT nombre, precio INTO v_nombre, v_precio FROM productos WHERE id = NEW.producto_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'El producto no existe' USING ERRCODE = '22023';
    END IF;
    NEW.nombre_producto := v_nombre;   -- precio y nombre salen del catálogo
    NEW.precio_unitario := v_precio;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS pedido_items_al_crear ON pedido_items;
CREATE TRIGGER pedido_items_al_crear BEFORE INSERT ON pedido_items
  FOR EACH ROW EXECUTE PROCEDURE public.pedido_items_al_crear();

CREATE OR REPLACE FUNCTION public.pedido_items_recalcular_total()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  UPDATE pedidos SET total = (SELECT COALESCE(sum(cantidad * precio_unitario), 0)
                                FROM pedido_items WHERE pedido_id = NEW.pedido_id)
   WHERE id = NEW.pedido_id;
  RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS pedido_items_recalcular_total ON pedido_items;
CREATE TRIGGER pedido_items_recalcular_total AFTER INSERT ON pedido_items
  FOR EACH ROW EXECUTE PROCEDURE public.pedido_items_recalcular_total();

-- 5b) DEF-01 (Práctica 11, crítico): perfiles ya no son públicos.
--     Cada quien ve el suyo; el admin todos; y los participantes de un reporte
--     ven el nombre del otro (chat).
DROP POLICY IF EXISTS "perfiles_own_read" ON perfiles;
CREATE POLICY "perfiles_own_read" ON perfiles FOR SELECT TO authenticated
  USING (
    id = auth.uid()
    OR public.is_admin()
    OR EXISTS (
      SELECT 1 FROM reportes r
      WHERE (r.cliente_id = perfiles.id OR r.agente_id = perfiles.id)
        AND (r.cliente_id = auth.uid() OR r.agente_id = auth.uid())
    )
  );

-- 5c) El usuario no puede cambiar su rol, su habilitación ni su correo
--     (el certificado de garantía se envía al correo de la cuenta).
CREATE OR REPLACE FUNCTION public.perfiles_proteger_rol()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF auth.uid() IS NOT NULL AND NOT public.is_admin() THEN
    IF NEW.rol IS DISTINCT FROM OLD.rol OR NEW.habilitado IS DISTINCT FROM OLD.habilitado THEN
      RAISE EXCEPTION 'Solo el administrador puede cambiar roles' USING ERRCODE = '42501';
    END IF;
    IF NEW.email IS DISTINCT FROM OLD.email OR NEW.id IS DISTINCT FROM OLD.id THEN
      RAISE EXCEPTION 'El correo solo se cambia desde la cuenta' USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- 5d) RPC: solo con sesión (EXECUTE se concede a PUBLIC por defecto)
REVOKE EXECUTE ON FUNCTION public.verificar_entrega(INTEGER, TEXT, INTEGER, TEXT),
  public.resolver_alerta(INTEGER, TEXT, TEXT),
  public.agentes_estado(), public.reasignar_reporte(INTEGER, UUID),
  public.latido(), public.desconectar() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verificar_entrega(INTEGER, TEXT, INTEGER, TEXT),
  public.resolver_alerta(INTEGER, TEXT, TEXT),
  public.agentes_estado(), public.reasignar_reporte(INTEGER, UUID),
  public.latido(), public.desconectar() TO authenticated;
-- Funciones internas de triggers: nadie las llama por la API
REVOKE EXECUTE ON FUNCTION public.pedidos_al_crear(), public.pedido_items_al_crear(),
  public.pedido_items_recalcular_total() FROM PUBLIC, anon, authenticated;

-- 5e) Índices e idempotencia de la Práctica 12 (por si no se habían creado)
CREATE INDEX IF NOT EXISTS pedidos_usuario_fecha_idx ON pedidos (usuario_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS pedido_items_pedido_idx ON pedido_items (pedido_id);
ALTER TABLE mensajes ADD COLUMN IF NOT EXISTS id_cliente UUID;
CREATE UNIQUE INDEX IF NOT EXISTS mensajes_id_cliente_uq ON mensajes (id_cliente);

-- -------------------------------------------------------------------------
-- 6) Realtime: los cambios de estado llegan solos a la web, la PWA y el teléfono
-- -------------------------------------------------------------------------
DO $$
BEGIN
  BEGIN ALTER PUBLICATION supabase_realtime ADD TABLE pedidos;   EXCEPTION WHEN duplicate_object THEN NULL; END;
  BEGIN ALTER PUBLICATION supabase_realtime ADD TABLE garantias; EXCEPTION WHEN duplicate_object THEN NULL; END;
END $$;

COMMIT;

-- =========================================================================
-- Comprobación rápida (opcional, después de ejecutar):
--   SELECT proname FROM pg_proc WHERE proname IN ('verificar_entrega','resolver_alerta');
--   SELECT * FROM garantias ORDER BY id DESC LIMIT 5;
-- Para probar: en la web (Dashboard) pon un pedido en "En puerta" y verifícalo
-- desde el teléfono o la PWA con el Smart Tag (o el simulador).
-- =========================================================================
