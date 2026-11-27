-- =========================================================================
-- SEPPRO — Reinicio total de usuarios y datos de demostración
-- Archivo: supabase-reinicio-usuarios.sql
--
-- ⚠️ BORRA TODOS LOS USUARIOS de Supabase Auth (incluidas cuentas reales) y,
--    en cascada, sus perfiles, pedidos, artículos, garantías, carrito, lista
--    de deseos, reportes y mensajes. El catálogo (productos y categorías) NO
--    se toca.
--
-- Después crea 6 cuentas nuevas, ya confirmadas, con datos simulados.
-- Ninguna queda vacía en lo que su rol puede ver:
--   clientes e invitado -> pedidos, historial, garantía, reportes con chat
--   agentes             -> reportes asignados y consultas al administrador
--   administrador       -> todos los reportes y las consultas de los agentes
--   todas               -> compras en la tienda web, carrito y lista de deseos
--   admin@seppro.com     -> admin       (Administrador SEPPRO)
--   agente@seppro.com    -> agente      (Agente Laura)
--   agente2@seppro.com   -> agente      (Agente Carlos)
--   cliente1@seppro.com  -> usuario     (Cliente Ana)
--   cliente2@seppro.com  -> usuario     (Cliente Luis)
--   invitado@seppro.com  -> invitado    (Invitado SEPPRO)
--
-- CONTRASEÑA de todas: la que pongas en v_password (línea marcada abajo).
--   Mínimo 6 caracteres. No uses 123456 en la presentación.
--
-- Requisitos (ya ejecutados en el proyecto): supabase-schema.sql,
-- supabase-update.sql, supabase-bluetooth.sql, supabase-soporte.sql,
-- supabase-entregable3.sql y supabase-permisos-roles.sql (en ese orden).
-- Ejecutar en: Supabase > SQL Editor > New query > Run.
-- Las fotos que se hayan subido al bucket "evidencias" se pueden borrar desde
-- Storage > evidencias (la base de datos ya no las referencia).
-- =========================================================================

-- Crea un pedido con un producto del catálogo y devuelve su id.
--   p_dias  = hace cuántos días se hizo el pedido
--   p_golpe = NULL sin verificar · FALSE viaje sin incidentes · TRUE golpe fuerte
-- Si el pedido quedó "entregado_verificado" también crea su garantía.
CREATE OR REPLACE FUNCTION pg_temp.crear_pedido(
  p_usuario UUID, p_estado TEXT, p_producto TEXT, p_cantidad INTEGER,
  p_dias INTEGER, p_golpe BOOLEAN DEFAULT NULL, p_motivo TEXT DEFAULT NULL
) RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
  v_prod_id INTEGER; v_precio NUMERIC(10, 2); v_pedido INTEGER;
  v_nombre TEXT; v_correo TEXT;
BEGIN
  SELECT id, precio INTO v_prod_id, v_precio FROM productos WHERE nombre = p_producto LIMIT 1;
  IF v_prod_id IS NULL THEN -- si el catálogo cambió, usa el primer producto
    SELECT id, precio, nombre INTO v_prod_id, v_precio, p_producto FROM productos ORDER BY id LIMIT 1;
  END IF;
  SELECT nombre, email INTO v_nombre, v_correo FROM perfiles WHERE id = p_usuario;

  INSERT INTO pedidos (
    usuario_id, total, estado, metodo_pago, nombre_envio, direccion_envio, telefono_envio,
    created_at, sensor_id, paquete_id, golpe_detectado, fecha_verificacion, motivo_rechazo
  ) VALUES (
    p_usuario, v_precio * p_cantidad, p_estado,
    CASE WHEN p_dias % 2 = 0 THEN 'tarjeta' ELSE 'transferencia' END,
    v_nombre, 'Av. Universidad ' || (100 + p_dias) || ', Col. Centro, Querétaro, Qro.', '4421234567',
    now() - make_interval(days => p_dias),
    -- El Smart Tag se asigna cuando el pedido sale a ruta
    CASE WHEN p_estado IN ('enviado', 'en_camino', 'en_puerta', 'entregado_verificado',
                           'entregado_con_danos', 'alerta_integridad', 'rechazado')
         THEN 'SEPPRO-Tag' END,
    NULL, p_golpe,
    CASE WHEN p_golpe IS NOT NULL THEN now() - make_interval(days => GREATEST(p_dias - 1, 0)) END,
    p_motivo
  ) RETURNING id INTO v_pedido;

  INSERT INTO pedido_items (pedido_id, producto_id, nombre_producto, cantidad, precio_unitario)
  VALUES (v_pedido, v_prod_id, p_producto, p_cantidad, v_precio);

  IF p_golpe IS NOT NULL THEN -- identificador que reportó el Smart Tag (SEPPRO-<pedido>)
    UPDATE pedidos SET paquete_id = 'SEPPRO-' || v_pedido WHERE id = v_pedido;
  END IF;

  IF p_estado = 'entregado_verificado' THEN
    INSERT INTO garantias (pedido_id, usuario_id, correo, cliente_nombre, producto, inicio, vence)
    VALUES (v_pedido, p_usuario, v_correo, v_nombre, p_producto,
            (now() - make_interval(days => GREATEST(p_dias - 1, 0)))::date,
            ((now() - make_interval(days => GREATEST(p_dias - 1, 0))) + interval '12 months')::date);
  END IF;
  RETURN v_pedido;
END;
$$;

DO $$
DECLARE
  v_password CONSTANT TEXT := 'Seppro2026';   -- <<< CAMBIA AQUÍ LA CONTRASEÑA DE LAS CUENTAS
  v_cuenta RECORD;
  v_id UUID;
  v_admin UUID; v_laura UUID; v_carlos UUID; v_c1 UUID; v_c2 UUID; v_inv UUID;
  v_p_danos1 INTEGER; v_p_camino1 INTEGER; v_p_verif1 INTEGER;
  v_p_alerta2 INTEGER; v_p_danos2 INTEGER; v_p_rechazo2 INTEGER;
  v_p_camino_inv INTEGER; v_p_verif_inv INTEGER;
  v_r INTEGER;
  v_usuario UUID; v_n INTEGER := 0;
BEGIN
  IF length(v_password) < 6 THEN
    RAISE EXCEPTION 'La contraseña debe tener al menos 6 caracteres';
  END IF;
  IF to_regprocedure('public.rol_actual()') IS NULL THEN
    RAISE EXCEPTION 'Ejecuta primero supabase-permisos-roles.sql';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM productos) THEN
    RAISE EXCEPTION 'No hay productos: ejecuta primero supabase-schema.sql';
  END IF;

  -- -----------------------------------------------------------------------
  -- 1) Borrar TODOS los usuarios (todo lo demás se borra en cascada)
  -- -----------------------------------------------------------------------
  DELETE FROM auth.users;
  RAISE NOTICE 'Usuarios eliminados.';

  -- Los folios vuelven a empezar (pedido #1, REP001, GAR-00001)
  PERFORM setval(pg_get_serial_sequence('public.pedidos', 'id'), 1, false);
  PERFORM setval(pg_get_serial_sequence('public.pedido_items', 'id'), 1, false);
  PERFORM setval(pg_get_serial_sequence('public.reportes', 'id'), 1, false);
  PERFORM setval(pg_get_serial_sequence('public.mensajes', 'id'), 1, false);
  PERFORM setval(pg_get_serial_sequence('public.garantias', 'id'), 1, false);

  -- -----------------------------------------------------------------------
  -- 2) Cuentas nuevas (correo confirmado, contraseña cifrada con bcrypt)
  -- -----------------------------------------------------------------------
  FOR v_cuenta IN
    SELECT * FROM (VALUES
      ('admin@seppro.com',    'Administrador SEPPRO', 'admin'),
      ('agente@seppro.com',   'Agente Laura',         'agente'),
      ('agente2@seppro.com',  'Agente Carlos',        'agente'),
      ('cliente1@seppro.com', 'Cliente Ana',          'usuario'),
      ('cliente2@seppro.com', 'Cliente Luis',         'usuario'),
      ('invitado@seppro.com', 'Invitado SEPPRO',      'invitado')
    ) AS t(email, nombre, rol)
  LOOP
    v_id := gen_random_uuid();
    INSERT INTO auth.users (
      instance_id, id, aud, role, email, encrypted_password,
      email_confirmed_at, raw_app_meta_data, raw_user_meta_data,
      created_at, updated_at, confirmation_token, recovery_token, email_change, email_change_token_new
    ) VALUES (
      '00000000-0000-0000-0000-000000000000', v_id, 'authenticated', 'authenticated',
      v_cuenta.email, extensions.crypt(v_password, extensions.gen_salt('bf')),
      now(), '{"provider":"email","providers":["email"]}'::jsonb,
      jsonb_build_object('nombre', v_cuenta.nombre),
      now(), now(), '', '', '', ''
    );
    INSERT INTO auth.identities (id, user_id, provider_id, provider, identity_data,
                                 last_sign_in_at, created_at, updated_at)
    VALUES (gen_random_uuid(), v_id, v_id::text, 'email',
            jsonb_build_object('sub', v_id::text, 'email', v_cuenta.email, 'email_verified', true),
            now(), now(), now());

    -- El trigger crea el perfil como 'usuario'; aquí se fija el rol
    -- (en el SQL Editor auth.uid() es NULL y el trigger de protección lo permite)
    INSERT INTO perfiles (id, nombre, email, rol, habilitado)
    VALUES (v_id, v_cuenta.nombre, v_cuenta.email, v_cuenta.rol, true)
    ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre, email = EXCLUDED.email,
                                   rol = EXCLUDED.rol, habilitado = true;

    CASE v_cuenta.email
      WHEN 'admin@seppro.com'    THEN v_admin  := v_id;
      WHEN 'agente@seppro.com'   THEN v_laura  := v_id;
      WHEN 'agente2@seppro.com'  THEN v_carlos := v_id;
      WHEN 'cliente1@seppro.com' THEN v_c1     := v_id;
      WHEN 'cliente2@seppro.com' THEN v_c2     := v_id;
      WHEN 'invitado@seppro.com' THEN v_inv    := v_id;
      ELSE NULL;
    END CASE;
  END LOOP;

  -- -----------------------------------------------------------------------
  -- 3) Pedidos simulados
  -- -----------------------------------------------------------------------
  -- Cliente Ana: un pedido en cada estado
  PERFORM pg_temp.crear_pedido(v_c1, 'pendiente',  'Válvula Check 6in',      1, 0);
  PERFORM pg_temp.crear_pedido(v_c1, 'pagado',     'Cable 3x8 AWG (100m)',   2, 1);
  PERFORM pg_temp.crear_pedido(v_c1, 'enviado',    'Arrancador Suave 15HP',  1, 2);
  v_p_camino1 := pg_temp.crear_pedido(v_c1, 'en_camino', 'Motor Franklin 5HP', 1, 3);
  PERFORM pg_temp.crear_pedido(v_c1, 'en_puerta',  'Bomba Altamira KOR10',   1, 4);   -- "Verificar paquete"
  PERFORM pg_temp.crear_pedido(v_c1, 'entregado',  'Tubería Acero 4in (6m)', 3, 20);
  v_p_verif1 := pg_temp.crear_pedido(v_c1, 'entregado_verificado', 'Bomba KSB UPA 150', 1, 12, FALSE); -- con garantía
  v_p_danos1 := pg_temp.crear_pedido(v_c1, 'entregado_con_danos', 'Bomba Grundfos SP 30-5', 1, 6, TRUE);
  PERFORM pg_temp.crear_pedido(v_c1, 'alerta_integridad', 'Motor Neumann 10HP', 1, 5, TRUE);

  -- Cliente Luis
  PERFORM pg_temp.crear_pedido(v_c2, 'pagado',     'Válvula Check 6in',      2, 1);
  PERFORM pg_temp.crear_pedido(v_c2, 'en_camino',  'Motor BAMSA 7.5HP',      1, 2);
  PERFORM pg_temp.crear_pedido(v_c2, 'en_puerta',  'Bomba KSB UPA 150',      1, 3);   -- "Verificar paquete"
  PERFORM pg_temp.crear_pedido(v_c2, 'en_puerta',  'Arrancador Suave 15HP',  1, 3);   -- segundo para repetir la demo
  v_p_alerta2  := pg_temp.crear_pedido(v_c2, 'alerta_integridad', 'Bomba Altamira KOR10', 1, 4, TRUE);
  v_p_danos2   := pg_temp.crear_pedido(v_c2, 'entregado_con_danos', 'Motor Franklin 5HP', 1, 9, TRUE);
  v_p_rechazo2 := pg_temp.crear_pedido(v_c2, 'rechazado', 'Bomba Grundfos SP 30-5', 1, 11, TRUE,
                                       'La carcasa llegó abollada y el eje no gira.');
  PERFORM pg_temp.crear_pedido(v_c2, 'entregado_verificado', 'Cable 3x10 AWG (100m)', 2, 15, FALSE);

  -- -----------------------------------------------------------------------
  -- 4) Reportes de soporte y conversaciones
  -- -----------------------------------------------------------------------
  -- A) Falla de Ana, atendida por Laura (en proceso)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c1, v_p_danos1, 'falla', 'Bomba Grundfos SP 30-5',
          'La caja llegó abollada de una esquina y el Smart Tag marcó golpe fuerte. La bomba hace un ruido metálico al girar el eje a mano.',
          'alta', 'en_proceso', v_laura)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_c1,    'Hola, recibí mi bomba pero la caja venía golpeada y la app marcó Entregado con daños.'),
    (v_r, v_laura, 'Hola Ana, soy Laura de soporte SEPPRO. ¿Nos puedes enviar una foto de la caja y del equipo?'),
    (v_r, v_c1,    'Claro, en un momento la adjunto. El eje suena raro al girarlo.'),
    (v_r, v_laura, 'Gracias. Por el registro del sensor el golpe fue durante el traslado; vamos a gestionar el cambio del equipo.');
  UPDATE mensajes m SET created_at = now() - make_interval(mins => (90 - 20 * x.n)::INTEGER)
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS n FROM mensajes WHERE reporte_id = v_r) x
  WHERE m.id = x.id;

  -- B) Seguimiento de Ana, sin asignar (cola del administrador)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado)
  VALUES (v_c1, v_p_camino1, 'seguimiento', 'Motor Franklin 5HP',
          'Mi pedido lleva 3 días En camino y no ha cambiado de estado. ¿Me pueden confirmar la fecha de entrega?',
          'media', 'abierto');

  -- C) Reporte resuelto de Ana (historial)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c1, v_p_verif1, 'seguimiento', 'Bomba KSB UPA 150',
          'Quiero confirmar que la garantía de mi bomba quedó activada después de la verificación.',
          'baja', 'resuelto', v_laura)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_c1,    '¿Mi garantía ya está activa?'),
    (v_r, v_laura, 'Sí, Ana. El sensor no registró golpes y la entrega quedó verificada. Tu certificado está en Mis pedidos.');

  -- D) Alerta de integridad de Luis, asignada a Carlos (desconectado: el admin puede reasignar)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c2, v_p_alerta2, 'falla', 'Bomba Altamira KOR10',
          'La app mostró Alerta de integridad al verificar el paquete. Todavía no abro la caja, ¿la rechazo?',
          'alta', 'abierto', v_carlos)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_c2, 'Buenas tardes, el repartidor sigue aquí. ¿Qué hago con la caja?');

  -- E) Devolución por equipo rechazado de Luis (como la crea resolver_alerta)
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c2, v_p_rechazo2, 'falla', 'Bomba Grundfos SP 30-5',
          'Equipo rechazado al recibir: el Smart Tag registró un golpe fuerte durante el traslado. Comentario del cliente: La carcasa llegó abollada y el eje no gira.',
          'alta', 'en_proceso', v_laura)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_laura, 'Hola Luis, recibimos tu rechazo. Mañana pasa la paquetería por el equipo y te enviamos uno nuevo.'),
    (v_r, v_c2,    'Perfecto, gracias. Aquí estaré.');

  -- F) Consulta de Laura al administrador (los clientes no escriben directo al admin)
  INSERT INTO reportes (cliente_id, tipo, descripcion, prioridad, estado, para_admin)
  VALUES (v_laura, 'consulta',
          'Luis rechazó la Grundfos SP 30-5 del pedido #16 por golpe en el traslado. ¿Autorizo el envío de un equipo nuevo?',
          'alta', 'en_proceso', TRUE)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_laura, 'Hola, el Smart Tag registró golpe fuerte y el cliente ya rechazó el equipo. ¿Procedo con el reemplazo?'),
    (v_r, v_admin, 'Sí, autorizado. Genera la guía de recolección y avísale al cliente.');

  -- G) Falla de Luis cerrada por el cliente
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_c2, v_p_danos2, 'falla', 'Motor Franklin 5HP',
          'El motor llegó con la carcasa rayada, pero funciona. Solo quiero dejar constancia.',
          'baja', 'cerrado', v_laura);

  -- -----------------------------------------------------------------------
  -- 5) Pedidos e historial para el resto de las cuentas (ninguna queda vacía)
  --    Van después de los de Ana y Luis: los pedidos 12 y 13 de la demo no cambian.
  -- -----------------------------------------------------------------------
  -- Personal (admin y agentes): compras en la tienda web. No verifican paquetes,
  -- así que sus pedidos no pasan por el Smart Tag.
  PERFORM pg_temp.crear_pedido(v_admin,  'pagado',    'Motor Neumann 10HP',     1, 1);
  PERFORM pg_temp.crear_pedido(v_admin,  'en_camino', 'Cable 3x8 AWG (100m)',   1, 2);
  PERFORM pg_temp.crear_pedido(v_admin,  'entregado', 'Tubería Acero 6in (6m)', 4, 40);
  PERFORM pg_temp.crear_pedido(v_laura,  'pagado',    'Arrancador Suave 15HP',  1, 1);
  PERFORM pg_temp.crear_pedido(v_laura,  'entregado', 'Cable 3x10 AWG (100m)',  1, 18);
  PERFORM pg_temp.crear_pedido(v_carlos, 'enviado',   'Motor BAMSA 7.5HP',      1, 2);
  PERFORM pg_temp.crear_pedido(v_carlos, 'entregado', 'Válvula Check 6in',      2, 22);

  -- Invitado
  PERFORM pg_temp.crear_pedido(v_inv, 'pendiente', 'Tubería Acero 4in (6m)',   2, 0);
  v_p_camino_inv := pg_temp.crear_pedido(v_inv, 'en_camino', 'Bomba Altamira KOR10', 1, 3);
  PERFORM pg_temp.crear_pedido(v_inv, 'en_puerta', 'Cable 3x8 AWG (100m)',     1, 2);
  v_p_verif_inv := pg_temp.crear_pedido(v_inv, 'entregado_verificado', 'Motor BAMSA 7.5HP', 1, 16, FALSE);
  PERFORM pg_temp.crear_pedido(v_inv, 'entregado', 'Válvula Check 6in',        1, 28);

  -- Reportes del invitado (también llenan "Resueltos y cerrados" de Carlos)
  -- H) Seguimiento activo, atendido por Carlos
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_inv, v_p_camino_inv, 'seguimiento', 'Bomba Altamira KOR10',
          '¿Me pueden decir a qué hora llega mi bomba? Necesito que alguien esté en el domicilio.',
          'media', 'en_proceso', v_carlos)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_inv,    'Hola, ¿mi pedido llega hoy?'),
    (v_r, v_carlos, 'Hola, soy Carlos de soporte SEPPRO. Llega mañana entre 10:00 y 14:00.');

  -- I) Duda de garantía resuelta por Carlos
  INSERT INTO reportes (cliente_id, pedido_id, tipo, equipo, descripcion, prioridad, estado, agente_id)
  VALUES (v_inv, v_p_verif_inv, 'seguimiento', 'Motor BAMSA 7.5HP',
          '¿Dónde descargo el certificado de garantía de mi motor?',
          'baja', 'resuelto', v_carlos)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_inv,    '¿Dónde veo mi garantía?'),
    (v_r, v_carlos, 'En la tienda web, en Mis pedidos, toca Ver certificado. Vence en 12 meses.');

  -- J) Consulta de Carlos al administrador, resuelta
  INSERT INTO reportes (cliente_id, tipo, descripcion, prioridad, estado, para_admin)
  VALUES (v_carlos, 'consulta',
          '¿Cuánto tiempo tiene el cliente para reportar un daño después de la entrega?',
          'baja', 'resuelto', TRUE)
  RETURNING id INTO v_r;
  INSERT INTO mensajes (reporte_id, emisor_id, texto) VALUES
    (v_r, v_carlos, '¿Cuál es el plazo para reportar daños?'),
    (v_r, v_admin,  'Se acepta en el momento de la entrega con la lectura del Smart Tag; después aplica la garantía de 12 meses.');

  -- Carrito y lista de deseos para todas las cuentas (tienda web)
  FOREACH v_usuario IN ARRAY ARRAY[v_admin, v_laura, v_carlos, v_c1, v_c2, v_inv] LOOP
    INSERT INTO lista_deseos (usuario_id, producto_id)
    SELECT v_usuario, id FROM productos ORDER BY id OFFSET (v_n * 2) % 12 LIMIT 2
    ON CONFLICT DO NOTHING;
    INSERT INTO carrito (usuario_id, producto_id, cantidad)
    SELECT v_usuario, id, 1 FROM productos ORDER BY id OFFSET (v_n * 2 + 5) % 12 LIMIT 1
    ON CONFLICT DO NOTHING;
    v_n := v_n + 1;
  END LOOP;

  -- Agentes desconectados al inicio (se conectan al abrir la app)
  UPDATE perfiles SET ultima_conexion = NULL WHERE id IN (v_admin, v_laura, v_carlos);

  RAISE NOTICE 'Listo: 6 cuentas nuevas con pedidos, garantías, reportes, consultas y conversaciones.';
END $$;

-- =========================================================================
-- Verificación
-- =========================================================================
SELECT p.email, p.nombre, p.rol, (u.email_confirmed_at IS NOT NULL) AS confirmado,
       (SELECT count(*) FROM pedidos x WHERE x.usuario_id = p.id
          AND x.estado NOT IN ('entregado', 'entregado_verificado', 'entregado_con_danos', 'rechazado')) AS activos,
       (SELECT count(*) FROM pedidos x WHERE x.usuario_id = p.id
          AND x.estado IN ('entregado', 'entregado_verificado', 'entregado_con_danos', 'rechazado')) AS historial,
       (SELECT count(*) FROM garantias g WHERE g.usuario_id = p.id) AS garantias,
       (SELECT count(*) FROM reportes r WHERE r.cliente_id = p.id OR r.agente_id = p.id
          OR (p.rol = 'admin')) AS reportes_visibles,
       (SELECT count(*) FROM carrito c WHERE c.usuario_id = p.id) AS carrito,
       (SELECT count(*) FROM lista_deseos d WHERE d.usuario_id = p.id) AS deseos
FROM perfiles p JOIN auth.users u ON u.id = p.id
ORDER BY CASE p.rol WHEN 'admin' THEN 1 WHEN 'agente' THEN 2 WHEN 'usuario' THEN 3 ELSE 4 END, p.email;

SELECT r.folio, c.nombre AS cliente, r.tipo, r.estado, r.prioridad,
       COALESCE(a.nombre, CASE WHEN r.para_admin THEN 'Administrador' ELSE 'Sin asignar' END) AS atiende,
       (SELECT count(*) FROM mensajes m WHERE m.reporte_id = r.id) AS mensajes
FROM reportes r JOIN perfiles c ON c.id = r.cliente_id LEFT JOIN perfiles a ON a.id = r.agente_id
ORDER BY r.id;
