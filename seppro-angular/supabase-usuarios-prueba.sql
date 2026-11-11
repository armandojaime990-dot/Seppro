-- =========================================================================
-- SEPPRO — Usuarios de prueba de TODOS los roles (Prácticas 7 y 8)
-- Archivo: supabase-usuarios-prueba.sql
--
-- EJECUTAR DESPUÉS de supabase-soporte.sql (ahí se crea el rol 'agente').
-- Supabase > SQL Editor > New query > Run.
--
-- Crea las cuentas ya confirmadas (no piden verificar correo):
--   admin@seppro.com     -> admin
--   agente@seppro.com    -> agente
--   agente2@seppro.com   -> agente   (para probar la reasignación, TC06)
--   cliente1@seppro.com  -> usuario
--   cliente2@seppro.com  -> usuario
--   invitado@seppro.com  -> invitado
-- Contraseña de TODAS:  123456
-- ¡Son cuentas de prueba! Cambia la contraseña o bórralas antes de publicar.
--
-- Si una cuenta ya existe, no la duplica: solo actualiza su nombre y su rol.
-- También crea pedidos de prueba para cliente1 (uno "en_puerta" y uno
-- "entregado_con_danos") para probar "Verificar paquete" y "Reportar falla".
-- =========================================================================

DO $$
DECLARE
  v_password CONSTANT TEXT := '123456';
  v_cuenta   RECORD;
  v_id       UUID;
  v_cliente1 UUID;
  v_pedido   INTEGER;
BEGIN
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
    SELECT id INTO v_id FROM auth.users WHERE email = v_cuenta.email;

    IF v_id IS NULL THEN
      v_id := gen_random_uuid();

      -- Usuario de Supabase Auth (correo confirmado, contraseña con bcrypt)
      INSERT INTO auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, raw_app_meta_data, raw_user_meta_data,
        created_at, updated_at,
        confirmation_token, recovery_token, email_change, email_change_token_new
      ) VALUES (
        '00000000-0000-0000-0000-000000000000', v_id, 'authenticated', 'authenticated',
        v_cuenta.email, extensions.crypt(v_password, extensions.gen_salt('bf')),
        now(), '{"provider":"email","providers":["email"]}'::jsonb,
        jsonb_build_object('nombre', v_cuenta.nombre),
        now(), now(),
        '', '', '', ''
      );

      -- Identidad de correo (necesaria para iniciar sesión con email/contraseña)
      INSERT INTO auth.identities (
        id, user_id, provider_id, provider, identity_data,
        last_sign_in_at, created_at, updated_at
      ) VALUES (
        gen_random_uuid(), v_id, v_id::text, 'email',
        jsonb_build_object('sub', v_id::text, 'email', v_cuenta.email, 'email_verified', true),
        now(), now(), now()
      );
      RAISE NOTICE 'Creado: % (%)', v_cuenta.email, v_cuenta.rol;
    ELSE
      RAISE NOTICE 'Ya existía: % -> se actualiza el rol a %', v_cuenta.email, v_cuenta.rol;
    END IF;

    -- Perfil (el trigger handle_new_user lo crea como 'usuario'; aquí se fija el rol).
    -- En el SQL Editor auth.uid() es NULL, así que el trigger de protección lo permite.
    INSERT INTO perfiles (id, nombre, email, rol, habilitado)
    VALUES (v_id, v_cuenta.nombre, v_cuenta.email, v_cuenta.rol, true)
    ON CONFLICT (id) DO UPDATE
      SET nombre = EXCLUDED.nombre, rol = EXCLUDED.rol, habilitado = true;

    IF v_cuenta.email = 'cliente1@seppro.com' THEN
      v_cliente1 := v_id;
    END IF;
  END LOOP;

  -- ---------------------------------------------------------------------
  -- Pedidos de prueba para cliente1 (solo si todavía no tiene pedidos)
  -- ---------------------------------------------------------------------
  IF NOT EXISTS (SELECT 1 FROM pedidos WHERE usuario_id = v_cliente1)
     AND EXISTS (SELECT 1 FROM productos) THEN
    -- 1) En puerta: aparece "Verificar paquete" (escaneo BLE)
    INSERT INTO pedidos (usuario_id, total, estado, metodo_pago, nombre_envio, direccion_envio, telefono_envio)
    VALUES (v_cliente1, 15000.00, 'en_puerta', 'tarjeta', 'Cliente Ana',
            'Av. Universidad 100, Querétaro, Qro.', '4421234567')
    RETURNING id INTO v_pedido;
    INSERT INTO pedido_items (pedido_id, producto_id, nombre_producto, cantidad, precio_unitario)
    VALUES (v_pedido,
            COALESCE((SELECT id FROM productos WHERE nombre = 'Bomba Altamira KOR10' LIMIT 1), (SELECT min(id) FROM productos)),
            'Bomba Altamira KOR10', 1, 15000.00);

    -- 2) Entregado con daños: aparece "Reportar falla" (Práctica 7)
    INSERT INTO pedidos (usuario_id, total, estado, metodo_pago, nombre_envio, direccion_envio,
                         telefono_envio, sensor_id, golpe_detectado, fecha_verificacion)
    VALUES (v_cliente1, 22000.00, 'entregado_con_danos', 'tarjeta', 'Cliente Ana',
            'Av. Universidad 100, Querétaro, Qro.', '4421234567', 'TAG-0001', true, now())
    RETURNING id INTO v_pedido;
    INSERT INTO pedido_items (pedido_id, producto_id, nombre_producto, cantidad, precio_unitario)
    VALUES (v_pedido,
            COALESCE((SELECT id FROM productos WHERE nombre = 'Bomba Grundfos SP 30-5' LIMIT 1), (SELECT min(id) FROM productos)),
            'Bomba Grundfos SP 30-5', 1, 22000.00);

    -- 3) En camino: pedido activo normal
    INSERT INTO pedidos (usuario_id, total, estado, metodo_pago, nombre_envio, direccion_envio, telefono_envio)
    VALUES (v_cliente1, 8500.00, 'en_camino', 'tarjeta', 'Cliente Ana',
            'Av. Universidad 100, Querétaro, Qro.', '4421234567')
    RETURNING id INTO v_pedido;
    INSERT INTO pedido_items (pedido_id, producto_id, nombre_producto, cantidad, precio_unitario)
    VALUES (v_pedido,
            COALESCE((SELECT id FROM productos WHERE nombre = 'Motor Franklin 5HP' LIMIT 1), (SELECT min(id) FROM productos)),
            'Motor Franklin 5HP', 1, 8500.00);

    RAISE NOTICE 'Pedidos de prueba creados para cliente1';
  END IF;
END $$;

-- Verificación: debe mostrar los 6 usuarios con su rol
SELECT p.email, p.nombre, p.rol, p.habilitado,
       (u.email_confirmed_at IS NOT NULL) AS correo_confirmado
FROM perfiles p
JOIN auth.users u ON u.id = p.id
WHERE p.email LIKE '%@seppro.com'
ORDER BY CASE p.rol WHEN 'admin' THEN 1 WHEN 'agente' THEN 2 WHEN 'usuario' THEN 3 ELSE 4 END, p.email;
