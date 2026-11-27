-- =========================================================================
-- SEPPRO — Permisos por rol (servidor)
-- Archivo: supabase-permisos-roles.sql
--
-- Las apps ya ocultan lo que cada rol no usa; aquí el servidor lo hace cumplir
-- aunque alguien llame a la API directamente:
--
--   Cliente / invitado : verifica SUS pedidos con el Smart Tag y abre reportes
--                        que atiende un agente. Ya NO escribe directo al admin.
--   Agente             : atiende los reportes asignados y puede abrir
--                        CONSULTAS al administrador (tipo 'consulta').
--                        No verifica paquetes.
--   Administrador      : asigna y reasigna reportes, responde consultas y
--                        avanza los pedidos en la web. No verifica paquetes.
--
-- Requisitos: supabase-soporte.sql y supabase-entregable3.sql.
-- Ejecutar en: Supabase > SQL Editor > New query > Run (se puede repetir).
-- Después ejecuta supabase-reinicio-usuarios.sql para tener datos de ejemplo.
-- =========================================================================

-- 1) Nuevo tipo de reporte: consulta del agente al administrador
ALTER TABLE reportes DROP CONSTRAINT IF EXISTS reportes_tipo_check;
ALTER TABLE reportes ADD CONSTRAINT reportes_tipo_check
  CHECK (tipo IN ('falla', 'seguimiento', 'acceso', 'consulta'));

CREATE OR REPLACE FUNCTION public.rol_actual()
RETURNS TEXT LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT rol FROM perfiles WHERE id = auth.uid();
$$;

-- 2) Al crear un reporte, cada rol solo puede abrir lo que le corresponde
CREATE OR REPLACE FUNCTION public.reportes_al_crear()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_estado_pedido TEXT;
  v_rol TEXT := public.rol_actual();
BEGIN
  IF auth.uid() IS NOT NULL AND NOT public.is_admin() THEN
    NEW.cliente_id := auth.uid();
    NEW.estado     := 'abierto';
    NEW.agente_id  := NULL;

    IF v_rol = 'agente' THEN
      -- El agente solo consulta al administrador (sin pedido de cliente)
      NEW.tipo       := 'consulta';
      NEW.para_admin := TRUE;
      NEW.pedido_id  := NULL;
    ELSE
      -- El cliente no escribe directo al administrador: lo atiende un agente
      IF NEW.tipo = 'consulta' THEN
        RAISE EXCEPTION 'Las consultas al administrador son solo para agentes' USING ERRCODE = '42501';
      END IF;
      NEW.para_admin := FALSE;
    END IF;
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

-- 3) Verificar paquete y resolver la alerta: solo clientes e invitados.
--    verificar_entrega y resolver_alerta cambian el estado del pedido; este
--    trigger rechaza ese cambio si lo pide un agente o el administrador.
--    (El administrador sigue pudiendo avanzar pedidos hasta "En puerta" en la web.)
CREATE OR REPLACE FUNCTION public.pedidos_solo_cliente_verifica()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  -- Solo aplica cuando el personal intenta verificar/resolver SU propio pedido
  -- (las RPC solo trabajan con pedidos propios del usuario que llama)
  IF auth.uid() IS NOT NULL
     AND OLD.usuario_id = auth.uid()
     AND public.rol_actual() IN ('admin', 'agente')
     AND ((OLD.estado = 'en_puerta' AND NEW.estado IN ('entregado_verificado', 'alerta_integridad'))
       OR (OLD.estado = 'alerta_integridad' AND NEW.estado IN ('entregado_con_danos', 'rechazado')))
  THEN
    RAISE EXCEPTION 'La verificación del paquete la hace el cliente que lo recibe' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS pedidos_solo_cliente_verifica ON pedidos;
CREATE TRIGGER pedidos_solo_cliente_verifica BEFORE UPDATE ON pedidos
  FOR EACH ROW EXECUTE PROCEDURE public.pedidos_solo_cliente_verifica();

-- 4) Comprobación
SELECT 'Permisos por rol aplicados' AS resultado,
       (SELECT count(*) FROM reportes WHERE tipo = 'consulta') AS consultas_de_agentes,
       (SELECT count(*) FROM reportes r JOIN perfiles p ON p.id = r.cliente_id
         WHERE r.para_admin AND p.rol NOT IN ('agente')) AS solicitudes_de_clientes_antiguas;
