-- =========================================================================
-- SEPPRO — Entregable 2 / Práctica 7: módulo de Soporte con roles
-- Archivo: supabase-soporte.sql
--
-- Roles: admin, agente, usuario (cliente) e invitado (columna perfiles.rol).
-- Tablas nuevas: reportes (folio REP001...) y mensajes (chat en tiempo real).
-- Archivos: bucket privado "evidencias" de Supabase Storage (URL firmada).
--
-- Requisitos: supabase-update.sql, fix-rls.sql (is_admin) y supabase-bluetooth.sql.
-- Script IDEMPOTENTE: se puede ejecutar varias veces sin error.
-- Ejecutar en: Supabase > SQL Editor > New query > Run.
-- =========================================================================

BEGIN;

-- -------------------------------------------------------------------------
-- 1) Roles: se agrega 'agente' y la marca de conexión (latido de la app)
-- -------------------------------------------------------------------------
ALTER TABLE perfiles DROP CONSTRAINT IF EXISTS perfiles_rol_check;
ALTER TABLE perfiles ADD CONSTRAINT perfiles_rol_check
  CHECK (rol IN ('admin', 'agente', 'usuario', 'invitado'));

-- Última vez que la app de un agente/admin avisó que sigue abierta.
-- Un agente se considera DESCONECTADO si no hay latido en 2 minutos.
ALTER TABLE perfiles ADD COLUMN IF NOT EXISTS ultima_conexion TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION public.is_agente()
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT EXISTS (
    SELECT 1 FROM perfiles WHERE id = auth.uid() AND rol = 'agente' AND habilitado
  );
$$;

-- -------------------------------------------------------------------------
-- 2) Seguridad: solo el administrador puede cambiar roles o habilitar cuentas.
--    Antes, la política perfiles_own_update permitía que un usuario se
--    pusiera rol = 'admin' a sí mismo.
--    (auth.uid() es NULL en el SQL Editor, así que ahí sí se permite.)
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.perfiles_proteger_rol()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF auth.uid() IS NOT NULL AND NOT public.is_admin() THEN
    IF NEW.rol IS DISTINCT FROM OLD.rol OR NEW.habilitado IS DISTINCT FROM OLD.habilitado THEN
      RAISE EXCEPTION 'Solo el administrador puede cambiar roles' USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS perfiles_proteger_rol ON perfiles;
CREATE TRIGGER perfiles_proteger_rol
  BEFORE UPDATE ON perfiles
  FOR EACH ROW EXECUTE PROCEDURE public.perfiles_proteger_rol();

-- El registro ya no confía en el rol que manda el cliente (raw_user_meta_data):
-- toda cuenta nueva nace como 'usuario'. El admin asigna 'agente' o 'admin'.
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS trigger AS $$
BEGIN
  INSERT INTO public.perfiles (id, email, nombre, rol, pin_hash, habilitado)
  VALUES (
    new.id,
    new.email,
    COALESCE(new.raw_user_meta_data->>'nombre', 'Usuario Nuevo'),
    'usuario',
    new.raw_user_meta_data->>'pin_hash',
    true
  )
  ON CONFLICT (id) DO NOTHING;
  RETURN new;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- -------------------------------------------------------------------------
-- 3) Tabla reportes
--    tipo: falla (bomba dañada / alerta de integridad), seguimiento (entrega)
--          o acceso (recuperación de acceso perdido).
--    para_admin = TRUE: solicitud directa al administrador (sin agente).
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reportes (
  id          SERIAL PRIMARY KEY,
  folio       TEXT GENERATED ALWAYS AS (
                'REP' || CASE WHEN id < 1000 THEN lpad(id::text, 3, '0') ELSE id::text END
              ) STORED,
  cliente_id  UUID NOT NULL DEFAULT auth.uid() REFERENCES perfiles(id) ON DELETE CASCADE,
  pedido_id   INTEGER REFERENCES pedidos(id) ON DELETE SET NULL,
  tipo        VARCHAR(20) NOT NULL CHECK (tipo IN ('falla', 'seguimiento', 'acceso')),
  equipo      VARCHAR(120),
  descripcion TEXT NOT NULL CHECK (char_length(btrim(descripcion)) BETWEEN 10 AND 1000),
  prioridad   VARCHAR(10) NOT NULL DEFAULT 'media' CHECK (prioridad IN ('baja', 'media', 'alta')),
  estado      VARCHAR(15) NOT NULL DEFAULT 'abierto'
              CHECK (estado IN ('abierto', 'en_proceso', 'resuelto', 'cerrado')),
  agente_id   UUID REFERENCES perfiles(id) ON DELETE SET NULL,
  para_admin  BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS reportes_cliente_idx ON reportes (cliente_id);
CREATE INDEX IF NOT EXISTS reportes_agente_idx  ON reportes (agente_id);

-- ¿El usuario actual puede ver este reporte? (cliente dueño, agente asignado o admin)
CREATE OR REPLACE FUNCTION public.puede_ver_reporte(p_reporte INTEGER)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT EXISTS (
    SELECT 1 FROM reportes r
    WHERE r.id = p_reporte
      AND (r.cliente_id = auth.uid() OR r.agente_id = auth.uid() OR public.is_admin())
  );
$$;

-- Al crear: fija el cliente, valida el pedido y asigna un agente conectado
CREATE OR REPLACE FUNCTION public.reportes_al_crear()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_estado_pedido TEXT;
BEGIN
  IF auth.uid() IS NOT NULL AND NOT public.is_admin() THEN
    NEW.cliente_id := auth.uid();
    NEW.estado     := 'abierto';
    NEW.agente_id  := NULL;
  END IF;
  NEW.created_at := now();
  NEW.updated_at := now();

  -- El pedido debe ser del cliente; si el Smart Tag marcó daño, prioridad alta
  IF NEW.pedido_id IS NOT NULL THEN
    SELECT estado INTO v_estado_pedido
    FROM pedidos WHERE id = NEW.pedido_id AND usuario_id = NEW.cliente_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'El pedido no pertenece al cliente' USING ERRCODE = '42501';
    END IF;
    IF v_estado_pedido IN ('entregado_con_danos', 'alerta_integridad') THEN
      NEW.prioridad := 'alta';
    END IF;
  END IF;

  -- Asignación automática: agente conectado con menos reportes activos.
  -- Si no hay ninguno conectado queda sin asignar y lo ve el administrador.
  IF NOT NEW.para_admin AND NEW.agente_id IS NULL THEN
    SELECT p.id INTO NEW.agente_id
    FROM perfiles p
    WHERE p.rol = 'agente' AND p.habilitado
      AND p.ultima_conexion > now() - interval '2 minutes'
    ORDER BY (SELECT count(*) FROM reportes r
              WHERE r.agente_id = p.id AND r.estado IN ('abierto', 'en_proceso')),
             p.ultima_conexion DESC
    LIMIT 1;
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS reportes_al_crear ON reportes;
CREATE TRIGGER reportes_al_crear
  BEFORE INSERT ON reportes
  FOR EACH ROW EXECUTE PROCEDURE public.reportes_al_crear();

-- Al editar: cada rol solo puede cambiar lo que le corresponde
CREATE OR REPLACE FUNCTION public.reportes_al_editar()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  -- Datos que nunca cambian
  NEW.cliente_id := OLD.cliente_id;
  NEW.pedido_id  := OLD.pedido_id;
  NEW.tipo       := OLD.tipo;
  NEW.created_at := OLD.created_at;
  NEW.updated_at := now();

  -- Administrador (o SQL Editor): puede todo
  IF auth.uid() IS NULL OR public.is_admin() THEN
    RETURN NEW;
  END IF;

  -- Solo el admin reasigna
  IF NEW.agente_id IS DISTINCT FROM OLD.agente_id OR NEW.para_admin IS DISTINCT FROM OLD.para_admin THEN
    RAISE EXCEPTION 'Solo el administrador puede reasignar reportes' USING ERRCODE = '42501';
  END IF;

  IF OLD.agente_id = auth.uid() THEN
    -- Agente asignado: cambia estado y prioridad, no el contenido del cliente
    IF NEW.descripcion IS DISTINCT FROM OLD.descripcion OR NEW.equipo IS DISTINCT FROM OLD.equipo THEN
      RAISE EXCEPTION 'El agente no puede editar la descripción del cliente' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
  END IF;

  -- Cliente dueño: edita descripción, equipo y prioridad mientras siga activo,
  -- y solo puede cambiar el estado a 'cerrado'
  IF OLD.estado IN ('resuelto', 'cerrado') THEN
    RAISE EXCEPTION 'El reporte ya está cerrado' USING ERRCODE = '42501';
  END IF;
  IF NEW.estado IS DISTINCT FROM OLD.estado AND NEW.estado <> 'cerrado' THEN
    RAISE EXCEPTION 'El cliente solo puede cerrar su reporte' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS reportes_al_editar ON reportes;
CREATE TRIGGER reportes_al_editar
  BEFORE UPDATE ON reportes
  FOR EACH ROW EXECUTE PROCEDURE public.reportes_al_editar();

ALTER TABLE reportes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "reportes_select" ON reportes;
CREATE POLICY "reportes_select" ON reportes FOR SELECT
  USING (cliente_id = auth.uid() OR agente_id = auth.uid() OR public.is_admin());

DROP POLICY IF EXISTS "reportes_insert" ON reportes;
CREATE POLICY "reportes_insert" ON reportes FOR INSERT
  WITH CHECK (cliente_id = auth.uid() OR public.is_admin());

DROP POLICY IF EXISTS "reportes_update" ON reportes;
CREATE POLICY "reportes_update" ON reportes FOR UPDATE
  USING (cliente_id = auth.uid() OR agente_id = auth.uid() OR public.is_admin());

DROP POLICY IF EXISTS "reportes_delete" ON reportes;
CREATE POLICY "reportes_delete" ON reportes FOR DELETE
  USING (public.is_admin());

-- -------------------------------------------------------------------------
-- 4) Tabla mensajes (conversación de cada reporte)
--    No hay UPDATE ni DELETE: el historial se conserva como evidencia.
--    "Limpiar historial" en la app solo oculta los mensajes en ese teléfono.
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mensajes (
  id            BIGSERIAL PRIMARY KEY,
  reporte_id    INTEGER NOT NULL REFERENCES reportes(id) ON DELETE CASCADE,
  emisor_id     UUID NOT NULL DEFAULT auth.uid() REFERENCES perfiles(id) ON DELETE CASCADE,
  emisor_nombre VARCHAR(150),
  emisor_rol    VARCHAR(20),
  dispositivo   VARCHAR(80),
  texto         TEXT NOT NULL CHECK (char_length(btrim(texto)) BETWEEN 1 AND 1000),
  adjunto_path  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS mensajes_reporte_idx ON mensajes (reporte_id, id);

-- El emisor, su nombre, su rol y la hora los pone el servidor (no se confía en la app)
CREATE OR REPLACE FUNCTION public.mensajes_al_crear()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF auth.uid() IS NOT NULL THEN
    NEW.emisor_id := auth.uid();
  END IF;
  SELECT nombre, rol INTO NEW.emisor_nombre, NEW.emisor_rol
  FROM perfiles WHERE id = NEW.emisor_id;
  NEW.texto      := btrim(NEW.texto);
  NEW.created_at := now();

  -- El adjunto debe estar en la carpeta del propio reporte: evidencias/<reporte_id>/...
  IF NEW.adjunto_path IS NOT NULL AND NEW.adjunto_path NOT LIKE NEW.reporte_id || '/%' THEN
    RAISE EXCEPTION 'Adjunto inválido' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS mensajes_al_crear ON mensajes;
CREATE TRIGGER mensajes_al_crear
  BEFORE INSERT ON mensajes
  FOR EACH ROW EXECUTE PROCEDURE public.mensajes_al_crear();

ALTER TABLE mensajes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "mensajes_select" ON mensajes;
CREATE POLICY "mensajes_select" ON mensajes FOR SELECT
  USING (public.puede_ver_reporte(reporte_id));

DROP POLICY IF EXISTS "mensajes_insert" ON mensajes;
CREATE POLICY "mensajes_insert" ON mensajes FOR INSERT
  WITH CHECK (emisor_id = auth.uid() AND public.puede_ver_reporte(reporte_id));

-- -------------------------------------------------------------------------
-- 5) Supabase Realtime: avisar en vivo de mensajes nuevos y cambios de reporte
-- -------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables
                 WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'mensajes') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.mensajes;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables
                 WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'reportes') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.reportes;
  END IF;
END $$;

-- -------------------------------------------------------------------------
-- 6) Supabase Storage: bucket PRIVADO "evidencias" (fotos de la caja/equipo)
--    Ruta: evidencias/<reporte_id>/<archivo>.jpg
--    Se comparte con URL firmada (preasignada) que expira; nunca es pública.
-- -------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('evidencias', 'evidencias', false, 5242880, ARRAY['image/jpeg', 'image/png', 'image/webp'])
ON CONFLICT (id) DO UPDATE
  SET public = false,
      file_size_limit = EXCLUDED.file_size_limit,
      allowed_mime_types = EXCLUDED.allowed_mime_types;

CREATE OR REPLACE FUNCTION public.puede_ver_carpeta_reporte(p_carpeta TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF p_carpeta IS NULL OR p_carpeta !~ '^[0-9]{1,9}$' THEN
    RETURN FALSE;
  END IF;
  RETURN public.puede_ver_reporte(p_carpeta::INTEGER);
END;
$$;

DROP POLICY IF EXISTS "evidencias_insert" ON storage.objects;
CREATE POLICY "evidencias_insert" ON storage.objects FOR INSERT TO authenticated
  WITH CHECK (bucket_id = 'evidencias'
              AND public.puede_ver_carpeta_reporte((storage.foldername(name))[1]));

-- Firmar la URL requiere poder leer el objeto
DROP POLICY IF EXISTS "evidencias_select" ON storage.objects;
CREATE POLICY "evidencias_select" ON storage.objects FOR SELECT TO authenticated
  USING (bucket_id = 'evidencias'
         AND public.puede_ver_carpeta_reporte((storage.foldername(name))[1]));

-- -------------------------------------------------------------------------
-- 7) Funciones RPC que llama la app (POST /rest/v1/rpc/<nombre>)
-- -------------------------------------------------------------------------

-- Latido: la app del agente/admin lo llama cada minuto mientras está abierta
CREATE OR REPLACE FUNCTION public.latido()
RETURNS VOID LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  UPDATE perfiles SET ultima_conexion = now() WHERE id = auth.uid();
$$;

-- Al cerrar sesión: queda desconectado de inmediato
CREATE OR REPLACE FUNCTION public.desconectar()
RETURNS VOID LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  UPDATE perfiles SET ultima_conexion = NULL WHERE id = auth.uid();
$$;

-- Lista de agentes con su estado de conexión (solo admin)
CREATE OR REPLACE FUNCTION public.agentes_estado()
RETURNS TABLE (id UUID, nombre TEXT, email TEXT, rol TEXT, en_linea BOOLEAN, reportes_activos INTEGER)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NOT public.is_admin() THEN
    RAISE EXCEPTION 'Solo el administrador' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY
    SELECT p.id, p.nombre::TEXT, p.email::TEXT, p.rol::TEXT,
           COALESCE(p.ultima_conexion > now() - interval '2 minutes', FALSE),
           (SELECT count(*)::INTEGER FROM reportes r
             WHERE r.agente_id = p.id AND r.estado IN ('abierto', 'en_proceso'))
    FROM perfiles p
    WHERE p.rol IN ('agente', 'admin') AND p.habilitado
    ORDER BY p.rol DESC, p.nombre;
END;
$$;

-- Reasignar (solo admin). p_agente NULL = lo atiende el propio administrador.
CREATE OR REPLACE FUNCTION public.reasignar_reporte(p_reporte INTEGER, p_agente UUID DEFAULT NULL)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_destino UUID := COALESCE(p_agente, auth.uid());
BEGIN
  IF NOT public.is_admin() THEN
    RAISE EXCEPTION 'Solo el administrador puede reasignar reportes' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM perfiles
                 WHERE id = v_destino AND rol IN ('agente', 'admin') AND habilitado) THEN
    RAISE EXCEPTION 'El destino no es un agente válido';
  END IF;
  UPDATE reportes
     SET agente_id  = v_destino,
         para_admin = (SELECT rol = 'admin' FROM perfiles WHERE id = v_destino)
   WHERE id = p_reporte;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'El reporte no existe';
  END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.latido(), public.desconectar(), public.agentes_estado(),
  public.reasignar_reporte(INTEGER, UUID) FROM anon;

COMMIT;

-- =========================================================================
-- Asignar roles para las pruebas (TC01–TC08), después de crear las cuentas:
--   UPDATE perfiles SET rol = 'admin'  WHERE email = 'admin@seppro.com';
--   UPDATE perfiles SET rol = 'agente' WHERE email = 'agente@seppro.com';
--   (los dos clientes se quedan con rol 'usuario')
-- También se puede hacer desde la web: Admin > Usuarios > columna Rol.
-- =========================================================================
