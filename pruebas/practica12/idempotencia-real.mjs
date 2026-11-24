// Práctica 12 — RES-07/RES-08 contra Supabase real: el mismo id_cliente enviado 2 veces => 1 registro.
// Uso: SEPPRO_PASS=<contraseña> node pruebas/practica12/idempotencia-real.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
const URL = 'https://sbhkqjueesxsjkfootuq.supabase.co';
const ANON = readFileSync('seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt', 'utf8').match(/ANON_KEY = "([^"]+)"/)[1];
const ses = async (c) => (await (await fetch(`${URL}/auth/v1/token?grant_type=password`, { method: 'POST',
  headers: { apikey: ANON, 'Content-Type': 'application/json' }, body: JSON.stringify({ email: `${c}@seppro.com`, password: process.env.SEPPRO_PASS }) })).json());
const s1 = await ses('cliente1'), sa = await ses('admin');
const H = (t) => ({ apikey: ANON, Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', Prefer: 'return=representation' });
const rep = (await (await fetch(`${URL}/rest/v1/reportes?select=id`, { method: 'POST', headers: H(s1.access_token),
  body: JSON.stringify({ tipo: 'seguimiento', descripcion: 'Prueba idempotencia P12', para_admin: true }) })).json())[0];
const id = randomUUID();
const envio = async () => { const r = await fetch(`${URL}/rest/v1/mensajes?select=id`, { method: 'POST', headers: H(s1.access_token),
  body: JSON.stringify({ reporte_id: rep.id, texto: 'mensaje idempotente', dispositivo: 'script', id_cliente: id }) });
  return { codigo: r.status, cuerpo: (await r.text()).slice(0, 120) }; };
const e1 = await envio(), e2 = await envio(), e3 = await envio();
const guardados = (await (await fetch(`${URL}/rest/v1/mensajes?select=id&id_cliente=eq.${id}`, { headers: H(s1.access_token) })).json()).length;
const res = { id_cliente: id, envio1: e1, envio2: e2, envio3: e3, registros_guardados: guardados };
console.log(res);
await fetch(`${URL}/rest/v1/reportes?id=eq.${rep.id}`, { method: 'DELETE', headers: H(sa.access_token) });
writeFileSync('pruebas/practica12/evidencias/idempotencia-real.json', JSON.stringify(res, null, 2));
