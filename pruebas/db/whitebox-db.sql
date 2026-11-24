-- =========================================================================
-- Práctica 11 — Fase 4: White Box de la base de datos (Supabase / PostgreSQL)
-- Ejecutar en Supabase > SQL Editor > New query > Run.  NO modifica datos:
-- la prueba de transacción hace ROLLBACK de todo lo que inserta.
--
-- Devuelve UNA sola tabla con:
--   * índices existentes
--   * planes EXPLAIN (ANALYZE, BUFFERS) de las consultas reales de la app
--     (con y sin RLS, simulando la sesión de cliente1/admin como lo hace PostgREST)
--   * prueba de atomicidad de una transacción
-- Copia el resultado completo (Export > CSV o copiar) para el informe.
-- =========================================================================

-- Plan de una consulta como superusuario (sin RLS)
CREATE OR REPLACE FUNCTION pg_temp.plan(etiqueta TEXT, consulta TEXT)
RETURNS TABLE (prueba TEXT, resultado TEXT) LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
  FOR r IN EXECUTE 'EXPLAIN (ANALYZE, BUFFERS) ' || consulta LOOP
    prueba := etiqueta; resultado := r."QUERY PLAN"; RETURN NEXT;
  END LOOP;
END $$;

-- Plan de una consulta con la sesión de un usuario (rol authenticated + JWT), como PostgREST
CREATE OR REPLACE FUNCTION pg_temp.plan_como(etiqueta TEXT, email TEXT, consulta TEXT)
RETURNS TABLE (prueba TEXT, resultado TEXT) LANGUAGE plpgsql AS $$
DECLARE r RECORD; v_uid UUID;
BEGIN
  SELECT id INTO v_uid FROM perfiles WHERE perfiles.email = plan_como.email;
  PERFORM set_config('request.jwt.claims',
    json_build_object('sub', v_uid, 'role', 'authenticated')::TEXT, TRUE);
  EXECUTE 'SET LOCAL ROLE authenticated';
  FOR r IN EXECUTE 'EXPLAIN (ANALYZE, BUFFERS) ' || consulta LOOP
    prueba := etiqueta; resultado := r."QUERY PLAN"; RETURN NEXT;
  END LOOP;
  EXECUTE 'RESET ROLE';
END $$;

-- Atomicidad: un reporte válido + uno inválido en la MISMA transacción
CREATE OR REPLACE FUNCTION pg_temp.prueba_transaccion()
RETURNS TABLE (prueba TEXT, resultado TEXT) LANGUAGE plpgsql AS $$
DECLARE v_c1 UUID; antes INT; despues INT;
BEGIN
  SELECT id INTO v_c1 FROM perfiles WHERE email = 'cliente1@seppro.com';
  SELECT count(*) INTO antes FROM reportes;
  BEGIN
    INSERT INTO reportes (cliente_id, tipo, descripcion) VALUES (v_c1, 'seguimiento', 'Válido dentro de la transacción');
    INSERT INTO reportes (cliente_id, tipo, descripcion) VALUES (v_c1, 'seguimiento', 'corta'); -- viola CHECK
  EXCEPTION WHEN check_violation THEN
    prueba := 'DB-TX atomicidad'; resultado := 'Error capturado: ' || SQLERRM; RETURN NEXT;
  END;
  SELECT count(*) INTO despues FROM reportes;
  prueba := 'DB-TX atomicidad';
  resultado := format('reportes antes=%s, después=%s -> %s', antes, despues,
    CASE WHEN antes = despues THEN 'se revirtió TODO (también el válido)' ELSE 'QUEDÓ UNA OPERACIÓN PARCIAL' END);
  RETURN NEXT;
END $$;

SELECT 'Índices' AS prueba, tablename || ': ' || indexdef AS resultado
FROM pg_indexes
WHERE schemaname = 'public' AND tablename IN ('pedidos', 'pedido_items', 'reportes', 'mensajes', 'perfiles', 'carrito')

UNION ALL
-- Q1: la consulta de "Mis pedidos" (app y reloj) SIN RLS
SELECT * FROM pg_temp.plan('Q1 pedidos+items de cliente1 (sin RLS)', $q$
  SELECT p.*, (SELECT json_agg(i) FROM pedido_items i WHERE i.pedido_id = p.id) AS pedido_items
  FROM pedidos p
  WHERE p.usuario_id = (SELECT id FROM perfiles WHERE email = 'cliente1@seppro.com')
  ORDER BY p.created_at DESC $q$)

UNION ALL
-- Q2: la MISMA consulta como la ejecuta PostgREST para cliente1 (con RLS)
SELECT * FROM pg_temp.plan_como('Q2 pedidos+items como cliente1 (con RLS)', 'cliente1@seppro.com', $q$
  SELECT p.*, (SELECT json_agg(i) FROM pedido_items i WHERE i.pedido_id = p.id) AS pedido_items
  FROM pedidos p ORDER BY p.created_at DESC $q$)

UNION ALL
-- Q3: mensajes de un reporte (chat) como cliente1: RLS llama puede_ver_reporte() por fila
SELECT * FROM pg_temp.plan_como('Q3 mensajes de REP002 como cliente1 (con RLS)', 'cliente1@seppro.com', $q$
  SELECT * FROM mensajes
  WHERE reporte_id = (SELECT id FROM reportes WHERE folio = 'REP002') ORDER BY id $q$)

UNION ALL
-- Q4: lista de reportes del administrador (pestaña Soporte)
SELECT * FROM pg_temp.plan_como('Q4 reportes como admin (con RLS)', 'admin@seppro.com', $q$
  SELECT r.*, c.nombre, a.nombre FROM reportes r
  JOIN perfiles c ON c.id = r.cliente_id LEFT JOIN perfiles a ON a.id = r.agente_id
  ORDER BY r.updated_at DESC $q$)

UNION ALL
SELECT * FROM pg_temp.prueba_transaccion();
