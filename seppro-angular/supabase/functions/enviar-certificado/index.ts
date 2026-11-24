// SEPPRO — Entregable 3: envío del certificado de garantía al correo del cliente.
//
// Supabase Edge Function (Deno). La llaman el teléfono y la PWA justo después de que
// verificar_entrega activó la garantía (viaje sin golpes):
//   POST /functions/v1/enviar-certificado   { "pedido_id": 42 }   (con el JWT del cliente)
//
// - Con el JWT del cliente lee la garantía (RLS: solo la suya).
// - Envía el certificado en HTML con Resend (https://resend.com) si existe el secreto
//   RESEND_API_KEY; si no, responde 503 y la app muestra el certificado en pantalla.
// - Marca garantias.correo_enviado con la llave de servicio (el cliente no puede).
//
// Despliegue (una sola vez):
//   npx supabase login
//   npx supabase link --project-ref sbhkqjueesxsjkfootuq
//   npx supabase secrets set RESEND_API_KEY=re_xxx CORREO_REMITENTE="SEPPRO <garantias@tu-dominio.com>"
//   npx supabase functions deploy enviar-certificado
import { createClient } from 'npm:@supabase/supabase-js@2.45.4';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const json = (cuerpo: unknown, status = 200) =>
  new Response(JSON.stringify(cuerpo), { status, headers: { ...CORS, 'Content-Type': 'application/json' } });

/** Escapa texto para insertarlo en HTML (el nombre o el producto podrían traer etiquetas). */
export function escaparHtml(texto: string): string {
  return texto.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

export function certificadoHtml(g: {
  folio: string; cliente_nombre: string | null; producto: string | null;
  pedido_id: number; inicio: string; vence: string;
}): string {
  const e = (t: unknown) => escaparHtml(String(t ?? ''));
  return `<!doctype html><html lang="es"><body style="font-family:Arial,sans-serif;background:#f1f5f9;padding:24px">
  <div style="max-width:560px;margin:auto;background:#fff;border-radius:12px;padding:28px;border-top:6px solid #003D7A">
    <h1 style="color:#003D7A;margin:0">SEPPRO</h1>
    <p style="color:#64748b;margin:4px 0 20px">Certificado de garantía</p>
    <p>Hola <b>${e(g.cliente_nombre || 'cliente')}</b>, tu equipo llegó sin golpes según el sensor
       Smart Tag del paquete, así que tu garantía quedó <b>activada automáticamente</b>.</p>
    <table style="width:100%;border-collapse:collapse;margin:16px 0">
      <tr><td style="color:#64748b;padding:6px 0">Folio</td><td><b>${e(g.folio)}</b></td></tr>
      <tr><td style="color:#64748b;padding:6px 0">Pedido</td><td>#${e(g.pedido_id)}</td></tr>
      <tr><td style="color:#64748b;padding:6px 0">Equipo</td><td>${e(g.producto || '—')}</td></tr>
      <tr><td style="color:#64748b;padding:6px 0">Vigencia</td><td>${e(g.inicio)} al ${e(g.vence)}</td></tr>
    </table>
    <p style="color:#64748b;font-size:13px">Conserva este correo. Para hacer válida la garantía abre un
       reporte en la app SEPPRO (pestaña Soporte) indicando el folio.</p>
  </div></body></html>`;
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: CORS });
  if (req.method !== 'POST') return json({ error: 'Método no permitido' }, 405);

  const auth = req.headers.get('Authorization');
  if (!auth?.startsWith('Bearer ')) return json({ error: 'Inicia sesión' }, 401);

  let pedidoId: number;
  try {
    pedidoId = Number((await req.json()).pedido_id);
  } catch {
    return json({ error: 'Cuerpo inválido' }, 400);
  }
  if (!Number.isInteger(pedidoId) || pedidoId <= 0) return json({ error: 'pedido_id inválido' }, 400);

  const url = Deno.env.get('SUPABASE_URL')!;
  // Cliente con el JWT del usuario: RLS decide qué garantía puede leer
  const comoUsuario = createClient(url, Deno.env.get('SUPABASE_ANON_KEY')!, {
    global: { headers: { Authorization: auth } },
  });
  const { data: g, error } = await comoUsuario.from('garantias')
    .select('folio, pedido_id, correo, cliente_nombre, producto, inicio, vence, correo_enviado')
    .eq('pedido_id', pedidoId).maybeSingle();
  if (error) return json({ error: 'No se pudo leer la garantía' }, 500);
  if (!g) return json({ error: 'No hay garantía activa para ese pedido' }, 404);
  if (g.correo_enviado) return json({ enviado: true, repetido: true, folio: g.folio });

  const llave = Deno.env.get('RESEND_API_KEY');
  if (!llave) {
    return json({ enviado: false, folio: g.folio,
      motivo: 'El servicio de correo no está configurado (RESEND_API_KEY)' }, 503);
  }

  const resp = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: { Authorization: `Bearer ${llave}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      from: Deno.env.get('CORREO_REMITENTE') ?? 'SEPPRO <onboarding@resend.dev>',
      to: [g.correo],
      subject: `Tu garantía SEPPRO ${g.folio} está activa`,
      html: certificadoHtml(g),
    }),
  });
  if (!resp.ok) return json({ enviado: false, folio: g.folio, motivo: `Proveedor de correo: HTTP ${resp.status}` }, 502);

  // Solo el servidor marca el envío (la tabla no tiene política UPDATE para clientes)
  const servicio = createClient(url, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  await servicio.from('garantias').update({ correo_enviado: true, correo_enviado_at: new Date().toISOString() })
    .eq('pedido_id', pedidoId);

  return json({ enviado: true, folio: g.folio });
});
