-- =========================================================================
-- SEPPRO — Datos de prueba: pedidos/entregas en todos los estados,
--          reportes de soporte y conversaciones (Prácticas 7 y 8)
-- Archivo: supabase-datos-prueba.sql
--
-- ORDEN: supabase-bluetooth.sql -> supabase-soporte.sql
--        -> supabase-usuarios-prueba.sql -> ESTE archivo.
-- Supabase > SQL Editor > New query > Run.
--
-- ⚠️ REINICIA los datos de las cuentas de prueba: borra TODOS los pedidos y
--    reportes de cliente1@seppro.com y cliente2@seppro.com y los vuelve a crear.
--    No toca a ningún otro usuario. Se puede ejecutar cada vez que quieras
--    dejar la demo como nueva.
-- =========================================================================

-- Crea un pedido con un producto y devuelve su id.
-- p_dias = hace cuántos días se hizo el pedido.
-- p_golpe: NULL = sin verificar, FALSE = sin golpes, TRUE = golpe fuerte.
CREATE OR REPLACE FUNCTION pg_temp.crear_pedido(
  p_usuario UUID, p_estado TEXT, p_producto TEXT, p_cantidad INTEGER,
  p_dias INTEGER, p_golpe BOOLEAN DEFAULT NULL
) RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
  v_prod_id  INTEGER;
  v_precio   NUMERIC(10, 2);
  v_pedido   INTEGER;
  v_nombre   TEXT;
BEGIN
  SELECT id, precio INTO v_prod_id, v_precio FROM productos WHERE nombre = p_producto LIMIT 1;
  IF v_prod_id IS NULL THEN
    -- Si el catálogo cambió, usa el primer producto que exista
    SELECT id, precio, nombre INTO v_prod_id, v_precio, p_producto FROM productos ORDER BY id LIMIT 1;
  END IF;
  SELECT nombre INTO v_nombre FROM perfiles WHERE id = p_usuario;

  INSERT INTO pedidos (
    usuario_id, total, estado, metodo_pago, nombre_envio, direccion_envio, telefono_envio,
    created_at, sensor_id, golpe_detectado, fecha_verificacion
  ) VALUES (
    p_usuario, v_precio * p_cantidad, p_estado,
    CASE WHEN p_dias % 2 = 0 THEN 'tarjeta' ELSE 'transferencia' END,
    v_nombre, 'Av. Universidad ' || (100 + p_dias) || ', Col. Centro, Querétaro, Qro.', '4421234567',
    now() - make_interval(days => p_dias),
    -- El Smart Tag se asigna cuando el pedido sale a ruta
    CASE WHEN p_estado IN ('enviado', 'en_camino', 'en_puerta', 'entregado_verificado',
                           'entregado_con_danos', 'alerta_integridad')
         THEN 'TAG-' || lpad((abs(hashtext(p_usuario::text || p_dias)) % 10000)::text, 4, '0') END,
    p_golpe,
    CASE WHEN p_golpe IS NOT NULL THEN now() - make_interval(days => GREATEST(p_dias - 1, 0)) END
  ) RETURNING id INTO v_pedido;

  INSERT INTO pedido_items (pedido_id, producto_id, nombre_producto, cantidad, precio_unitario)
  VALUES (v_pedido, v_prod_id, p_producto, p_cantidad, v_precio);
  RETURN v_pedido;
END;
$$;

DO $$
DECLARE
  v_c1 UUID; v_c2 UUID; v_admin UUID; v_laura UUID; v_carlos UUID;
  v_p_danos1 INTEGER; v_p_camino1 INTEGER; v_p_verif1 INTEGER;
  v_p_alerta2 INTEGER; v_p_danos2 INTEGER;
  v_r INTEGER;
BEGIN
  SELECT id INTO v_c1     FROM perfiles WHERE email = 'cliente1@seppro.com';
  SELECT id INTO v_c2     FROM perfiles WHERE email = 'cliente2@seppro.com';
  SELECT id INTO v_admin  FROM perfiles WHERE email = 'admin@seppro.com';
  SELECT id INTO v_laura  FROM perfiles WHERE email = 'agente@seppro.com';
  SELECT id INTO v_carlos FROM perfiles WHERE email = 'agente2@seppro.com';

  IF v_c1 IS NULL OR v_c2 IS NULL OR v_admin IS NULL OR v_laura IS NULL OR v_carlos IS NULL THEN
    RAISE EXCEPTION 'Faltan cuentas de prueba: ejecuta primero supabase-usuarios-prueba.sql';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM productos) THEN
    RAISE EXCEPTION 'No hay productos: ejecuta primero supabase-schema.sql';
  END IF;

  -- ---------------------------------------------------------------------
  -- 0) Reinicio: borrar los datos anteriores de los clientes de prueba
  --    (mensajes se borran en cascada con sus reportes)
  -- ---------------------------------------------------------------------
  DELETE FROM reportes WHERE cliente_id IN (v_c1, v_c2);
  DELETE FROM pedidos  WHERE usuario_id IN (v_c1, v_c2);

  -- ---------------------------------------------------------------------
  -- 1) Pedidos de cliente1 (Ana): un pedido en CADA estado
  -- ---------------------------------------------------------------------
  PERFORM pg_temp.crear_pedido(v_c1, 'pendiente',  'Válvula Check 6in',        1, 0);
  PERFORM pg_temp.crear_pedido(v_c1, 'pagado',     'Cable 3x8 AWG (100m)',     2, 1);
  PERFORM pg_temp.crear_pedido(v_c1, 'enviado',    'Arrancador Suave 15HP',    1, 2);
  v_p_camino1 := pg_temp.crear_pedido(v_c1, 'en_camino', 'Motor Franklin 5HP', 1, 3);
  -- En puerta: aparece "Verificar paquete" (escaneo BLE)
  PERFORM pg_temp.crear_pedido(v_c1, 'en_puerta',  'Bomba Altamira KOR10',     1, 4);
  PERFORM pg_temp.crear_pedido(v_c1, 'entregado',  'Tubería Acero 4in (6m)',   3, 20);
  -- Verificado sin golpes (golpe_detectado = FALSE)
  v_p_verif1 := pg_temp.crear_pedido(v_c1, 'entregado_verificado', 'Bomba KSB UPA 150', 1, 12, FALSE);
  -- Sensor registró golpe fuerte: aparece "Reportar falla"
  v_p_danos1 := pg_temp.crear_pedido(v_c1, 'entregado_con_danos', 'Bomba Grundfos SP 30-5', 1, 6, TRUE);
  PERFORM pg_temp.crear_pedido(v_c1, 'alerta_integridad', 'Motor Neumann 10HP', 1, 5, TRUE);

  -- ---------------------------------------------------------------------
  -- 2) Pedidos de cliente2 (Luis)
  -- ---------------------------------------------------------------------
  PERFORM pg_temp.crear_pedido(v_c2, 'en_camino',  'Motor BAMSA 7.5HP',        1, 2);
  PERFORM pg_temp.crear_pedido(v_c2, 'en_puerta',  'Bomba KSB UPA 150',        1, 3);
  v_p_alerta2 := pg_temp.crear_pedido(v_c2, 'alerta_integridad', 'Bomba Altamira KOR10', 1, 4, TRUE);
  v_p_danos2  := pg_temp.crear_pedido(v_c2, 'entregado_con_danos', 'Motor Franklin 5HP', 1, 9, TRUE);
  PERFORM pg_temp.crear_pedido(v_c2, 'entregado_verificado', 'Cable 3x10 AWG (100m)', 2, 15, FALSE);

  -- ---------------------------------------------------------------------
  -- 3) Reportes de soporte y conversaciones
  --    (en el SQL Editor auth.uid() es NULL: los triggers respetan los
  --     valores que se mandan aquí)
  -- ---------------------------------------------------------------------

  -- Agentes desconectados al inicio (se conectan al abrir la app). Se hace ANTES
  -- de crear los reportes para que la asignación automática no tome a nadie.
  UPDATE perfiles SET ultima_conexion = NULL WHERE id IN (v_laura, v_carlos, v_admin);

  -- A) Falla de cliente1, atendida por Laura (en proceso) — TC01/TC02/TC04
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c1, v_p_danos1, 'falla', 'Bomba Grundfos SP 30-5',
          'La caja llegó abollada de una esquina y el Smart Tag marcó golpe fuerte. La bomba hace un ruido metálico al girar el eje a mano.',
          'alta', 'en_proceso', v_laura)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, dispositivo, texto) VALUES
    (v_r, v_c1,    'Google Pixel 8a',  'Hola, recibí mi bomba pero la caja venía golpeada y la app marcó Entregado con daños.'),
    (v_r, v_laura, 'Samsung Galaxy S23','Hola Ana, soy Laura de soporte SEPPRO. ¿Nos puedes enviar una foto de la caja y del equipo?'),
    (v_r, v_c1,    'Google Pixel 8a',  'Claro, en un momento la adjunto. El eje suena raro al girarlo.'),
    (v_r, v_laura, 'Samsung Galaxy S23','Gracias. Por el registro del sensor el golpe fue durante el traslado; vamos a gestionar el cambio del equipo.');
  -- Horas escalonadas para que el chat se vea natural
  UPDATE mensajes m SET created_at = now() - make_interval(mins => (90 - 20 * x.n)::INTEGER)
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS n FROM mensajes WHERE reporte_id = v_r) x
  WHERE m.id = x.id;

  -- B) Seguimiento de cliente1, SIN asignar (cola del administrador)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado)
  VALUES (v_c1, v_p_camino1, 'seguimiento', 'Motor Franklin 5HP',
          'Mi pedido lleva 3 días En camino y no ha cambiado de estado. ¿Me pueden confirmar la fecha de entrega?',
          'media', 'abierto');

  -- C) Reporte YA RESUELTO de cliente1 (historial)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c1, v_p_verif1, 'seguimiento', 'Bomba KSB UPA 150',
          'Quiero confirmar que la garantía de mi bomba quedó activada después de la verificación.',
          'baja', 'resuelto', v_laura)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, dispositivo, texto) VALUES
    (v_r, v_c1,    'Google Pixel 8a',   '¿Mi garantía ya está activa?'),
    (v_r, v_laura, 'Samsung Galaxy S23','Sí, Ana. El sensor no registró golpes y la entrega quedó verificada. ¡Saludos!');

  -- D) Alerta de integridad de cliente2, asignada a Carlos — TC06
  --    (Carlos no tiene latido = aparece DESCONECTADO y el admin puede reasignar)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c2, v_p_alerta2, 'falla', 'Bomba Altamira KOR10',
          'La app mostró Alerta de integridad al verificar el paquete. Todavía no abro la caja, ¿la rechazo?',
          'alta', 'abierto', v_carlos)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, dispositivo, texto) VALUES
    (v_r, v_c2, 'Xiaomi Redmi Note 13', 'Buenas tardes, el repartidor sigue aquí. ¿Qué hago con la caja?');

  -- E) Solicitud DIRECTA al administrador de cliente2 (recuperación de acceso) — TC07
  INSERT INTO reportes (cliente_id, tipo, descripcion, prioridad, estado, para_admin)
  VALUES (v_c2, 'acceso',
          'Cambié de teléfono y en el reloj no puedo iniciar sesión con mi cuenta. Necesito ayuda para recuperar el acceso.',
          'media', 'abierto', TRUE);

  -- F) Falla de cliente2 ya CERRADA por el cliente
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c2, v_p_danos2, 'falla', 'Motor Franklin 5HP',
          'El motor llegó con la carcasa rayada, pero funciona. Solo quiero dejar constancia.',
          'baja', 'cerrado', v_laura);

  RAISE NOTICE 'Datos de prueba listos.';
END $$;

-- =========================================================================
-- Verificación
-- =========================================================================
SELECT pf.email, p.id AS pedido, p.estado, p.total, p.sensor_id, p.golpe_detectado,
       to_char(p.created_at, 'DD/MM/YYYY') AS fecha
FROM pedidos p JOIN perfiles pf ON pf.id = p.usuario_id
WHERE pf.email IN ('cliente1@seppro.com', 'cliente2@seppro.com')
ORDER BY pf.email, p.created_at DESC;

SELECT r.folio, c.email AS cliente, r.tipo, r.estado, r.prioridad,
       COALESCE(a.nombre, CASE WHEN r.para_admin THEN 'Administrador' ELSE 'Sin asignar' END) AS atiende,
       (SELECT count(*) FROM mensajes m WHERE m.reporte_id = r.id) AS mensajes
FROM reportes r
JOIN perfiles c ON c.id = r.cliente_id
LEFT JOIN perfiles a ON a.id = r.agente_id
ORDER BY r.id;
