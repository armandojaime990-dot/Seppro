-- =========================================================================
-- Práctica 12 — Fase 4: pruebas avanzadas de base de datos + optimización (antes / después)
-- Ejecutar en Supabase > SQL Editor > New query > Run.  (Una sola ejecución.)
--
-- Consulta crítica: "Mis pedidos" de un cliente con 3 005 pedidos (cliente2), con RLS, tal como
-- la ejecuta PostgREST para la app.
--   1) individual por id     2) conjunto pequeño (20)     3) conjunto grande (todos)
--   4) con filtros (estado activo, página de 20 = la consulta nueva de la app)
-- Mide cada una ANTES, aplica la optimización y la mide DESPUÉS:
--   O2: índices pedidos(usuario_id, created_at DESC, id DESC) y pedido_items(pedido_id)
--   O3: columna mensajes.id_cliente + índice UNIQUE (idempotencia de los mensajes)
-- Además: consultas repetidas (pg_stat_statements) y bloqueos/esperas actuales.
-- Copia TODO el resultado (puedes usar Export > CSV) y pásalo para el reporte.
-- =========================================================================

CREATE OR REPLACE FUNCTION pg_temp.medir(fase TEXT, etiqueta TEXT, email TEXT, consulta TEXT)
RETURNS TABLE (fase_ TEXT, prueba TEXT, resultado TEXT) LANGUAGE plpgsql AS $$
DECLARE r RECORD; v_uid UUID;
BEGIN
  SELECT id INTO v_uid FROM perfiles WHERE perfiles.email = medir.email;
  PERFORM set_config('request.jwt.claims', json_build_object('sub', v_uid, 'role', 'authenticated')::TEXT, TRUE);
  EXECUTE 'SET LOCAL ROLE authenticated';
  FOR r IN EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, SUMMARY) ' || consulta LOOP
    -- Solo las líneas relevantes: nodos de escaneo, filas, tiempos
    IF r."QUERY PLAN" ~ '(Scan|Sort|Limit|Execution Time|Planning Time|Rows Removed|actual time=.*rows=|Buffers: shared)' THEN
      fase_ := fase; prueba := etiqueta; resultado := r."QUERY PLAN"; RETURN NEXT;
    END IF;
  END LOOP;
  EXECUTE 'RESET ROLE';
END $$;

CREATE OR REPLACE FUNCTION pg_temp.bateria(fase TEXT)
RETURNS TABLE (fase_ TEXT, prueba TEXT, resultado TEXT) LANGUAGE plpgsql AS $$
DECLARE v_c2 UUID; v_id INT; v_filtro TEXT := $f$estado NOT IN ('entregado','entregado_verificado','entregado_con_danos')$f$;
BEGIN
  SELECT id INTO v_c2 FROM perfiles WHERE email = 'cliente2@seppro.com';
  SELECT max(id) INTO v_id FROM pedidos WHERE usuario_id = v_c2;
  RETURN QUERY SELECT * FROM pg_temp.medir(fase, '1 individual por id', 'cliente2@seppro.com',
    format('SELECT p.*, (SELECT json_agg(i) FROM pedido_items i WHERE i.pedido_id = p.id) FROM pedidos p WHERE p.id = %s', v_id));
  RETURN QUERY SELECT * FROM pg_temp.medir(fase, '2 conjunto pequeño (20)', 'cliente2@seppro.com',
    format('SELECT p.id, p.total, p.estado, p.created_at, (SELECT json_agg(i.nombre_producto) FROM pedido_items i WHERE i.pedido_id = p.id) FROM pedidos p WHERE p.usuario_id = %L ORDER BY p.created_at DESC, p.id DESC LIMIT 20', v_c2));
  RETURN QUERY SELECT * FROM pg_temp.medir(fase, '3 conjunto grande (todos, consulta original de la app)', 'cliente2@seppro.com',
    format('SELECT p.*, (SELECT json_agg(i) FROM pedido_items i WHERE i.pedido_id = p.id) FROM pedidos p WHERE p.usuario_id = %L ORDER BY p.created_at DESC', v_c2));
  RETURN QUERY SELECT * FROM pg_temp.medir(fase, '4 con filtros (activos, página 1, consulta nueva)', 'cliente2@seppro.com',
    format('SELECT p.id, p.total, p.estado, p.created_at, (SELECT json_agg(i.nombre_producto) FROM pedido_items i WHERE i.pedido_id = p.id) FROM pedidos p WHERE p.usuario_id = %L AND p.%s ORDER BY p.created_at DESC, p.id DESC LIMIT 20 OFFSET 0', v_c2, v_filtro));
  RETURN QUERY SELECT * FROM pg_temp.medir(fase, '4b conteo para "Mostrando 20 de N"', 'cliente2@seppro.com',
    format('SELECT count(*) FROM pedidos p WHERE p.usuario_id = %L AND p.%s', v_c2, v_filtro));
END $$;

-- Todo se guarda en una tabla temporal y se muestra al final (el SQL Editor solo muestra el último SELECT)
CREATE TEMP TABLE r12 (orden SERIAL, fase TEXT, prueba TEXT, resultado TEXT);

DO $$
DECLARE r RECORD;
BEGIN
  -- Volumen de datos
  INSERT INTO r12 (fase, prueba, resultado)
  SELECT 'datos', 'conteo', format('pedidos=%s · pedido_items=%s · pedidos de cliente2=%s · mensajes=%s',
    (SELECT count(*) FROM pedidos), (SELECT count(*) FROM pedido_items),
    (SELECT count(*) FROM pedidos p JOIN perfiles f ON f.id = p.usuario_id WHERE f.email = 'cliente2@seppro.com'),
    (SELECT count(*) FROM mensajes));

  -- Consultas repetidas (pg_stat_statements, si la extensión está activa)
  BEGIN
    INSERT INTO r12 (fase, prueba, resultado)
    SELECT 'repetidas', left(regexp_replace(query, '\s+', ' ', 'g'), 140),
           format('llamadas=%s · filas=%s · prom=%s ms · total=%s ms', calls, rows,
                  round(mean_exec_time::numeric, 2), round(total_exec_time::numeric, 0))
    FROM extensions.pg_stat_statements
    WHERE query ILIKE '%from "public"."pedidos"%' OR query ILIKE '%"public"."mensajes"%'
    ORDER BY calls DESC LIMIT 6;
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO r12 (fase, prueba, resultado) VALUES ('repetidas', 'pg_stat_statements', 'no disponible: ' || SQLERRM);
  END;

  -- ===================== ANTES =====================
  INSERT INTO r12 (fase, prueba, resultado) SELECT * FROM pg_temp.bateria('ANTES');

  -- ===================== OPTIMIZACIÓN =====================
  CREATE INDEX IF NOT EXISTS pedidos_usuario_fecha_idx ON pedidos (usuario_id, created_at DESC, id DESC);
  CREATE INDEX IF NOT EXISTS pedido_items_pedido_idx   ON pedido_items (pedido_id);
  ALTER TABLE mensajes ADD COLUMN IF NOT EXISTS id_cliente UUID;
  CREATE UNIQUE INDEX IF NOT EXISTS mensajes_id_cliente_uq ON mensajes (id_cliente);
  ANALYZE pedidos;
  ANALYZE pedido_items;
  ANALYZE mensajes;
  INSERT INTO r12 (fase, prueba, resultado) VALUES ('optimizacion', 'índices y columna', 'creados: pedidos_usuario_fecha_idx, pedido_items_pedido_idx, mensajes.id_cliente + mensajes_id_cliente_uq');

  -- ===================== DESPUÉS =====================
  INSERT INTO r12 (fase, prueba, resultado) SELECT * FROM pg_temp.bateria('DESPUES');

  -- Bloqueos / esperas en este momento
  INSERT INTO r12 (fase, prueba, resultado)
  SELECT 'bloqueos', 'pg_stat_activity', format('sesiones esperando lock=%s · conexiones activas=%s',
    (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'),
    (SELECT count(*) FROM pg_stat_activity WHERE state = 'active'));

  INSERT INTO r12 (fase, prueba, resultado)
  SELECT 'indices', tablename, indexname || ' · ' || pg_size_pretty(pg_relation_size(format('public.%I', indexname)::regclass))
  FROM pg_indexes WHERE schemaname = 'public' AND tablename IN ('pedidos', 'pedido_items', 'mensajes');
END $$;

SELECT fase, prueba, resultado FROM r12 ORDER BY orden;
