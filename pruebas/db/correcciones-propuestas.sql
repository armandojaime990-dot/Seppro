-- =========================================================================
-- Práctica 11 — Correcciones propuestas en la base de datos (Fase 6)
-- Revisa cada bloque antes de ejecutarlo en Supabase > SQL Editor.
-- =========================================================================

BEGIN;

-- -------------------------------------------------------------------------
-- DEF-01 (Crítico): cualquiera con la llave pública puede leer correo y rol
-- de TODOS los usuarios (política perfiles_own_read = USING (true), sin TO).
-- Corrección: cada quien ve su perfil; el admin ve todos; y se pueden ver los
-- perfiles de las personas con las que compartes un reporte (nombre del agente
-- o del cliente en el chat).
-- -------------------------------------------------------------------------
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

-- -------------------------------------------------------------------------
-- DEF-06 (Bajo): las RPC de administración se pueden invocar sin sesión
-- (la función se protege sola, pero no debería ejecutarse para anon).
-- En Postgres EXECUTE se concede a PUBLIC por defecto; revocarlo de anon no basta.
-- -------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION public.agentes_estado(), public.reasignar_reporte(INTEGER, UUID),
  public.latido(), public.desconectar() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agentes_estado(), public.reasignar_reporte(INTEGER, UUID),
  public.latido(), public.desconectar() TO authenticated;

-- -------------------------------------------------------------------------
-- DEF-07 (Medio, rendimiento a futuro): las llaves foráneas que filtran las
-- consultas más frecuentes no tienen índice (Postgres no los crea solo).
-- -------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS pedidos_usuario_fecha_idx ON pedidos (usuario_id, created_at DESC);
CREATE INDEX IF NOT EXISTS pedido_items_pedido_idx  ON pedido_items (pedido_id);

-- -------------------------------------------------------------------------
-- DEF-03 (Medio): un reintento crea mensajes duplicados.
-- Corrección: la app genera un UUID por mensaje y lo manda en "id_cliente";
-- si el POST se repite, la restricción UNIQUE responde 409 en vez de duplicar.
-- (Requiere también el cambio en SoporteApi.enviarMensaje.)
-- -------------------------------------------------------------------------
ALTER TABLE mensajes ADD COLUMN IF NOT EXISTS id_cliente UUID;
CREATE UNIQUE INDEX IF NOT EXISTS mensajes_id_cliente_uq ON mensajes (id_cliente) WHERE id_cliente IS NOT NULL;

COMMIT;
