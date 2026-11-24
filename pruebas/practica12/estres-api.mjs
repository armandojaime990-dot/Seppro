// Práctica 12 — Fase 3: prueba de estrés reproducible de la API (Supabase PostgREST) y
// medición de la consulta crítica de la app (lista de pedidos de un cliente con 3 005 pedidos).
//
// Uso: SEPPRO_PASS=<contraseña> node pruebas/practica12/estres-api.mjs <antes|despues>
//
// Escenarios: 1, 5, 10, 25 y 50 usuarios concurrentes, 100 solicitudes cada uno,
//   - LECTURA:   GET /rest/v1/pedidos (la consulta de "Mis pedidos" de la app, cliente1)
//   - ESCRITURA: POST /rest/v1/mensajes en un reporte temporal (se borra al final con sus mensajes)
// Consulta crítica: 10 solicitudes secuenciales de la lista de pedidos de cliente2
//   - antes:   la consulta original de la app (select=*, sin paginar)
//   - despues: la consulta optimizada (campos necesarios, filtro en servidor, página de 20)
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import os from 'node:os';

const etq = process.argv[2];
if (!['antes', 'despues'].includes(etq)) { console.error('Uso: estres-api.mjs antes|despues'); process.exit(1); }
const URL = 'https://sbhkqjueesxsjkfootuq.supabase.co';
const ANON = readFileSync('seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt', 'utf8').match(/ANON_KEY = "([^"]+)"/)[1];
const PASS = process.env.SEPPRO_PASS;
if (!PASS) { console.error('Falta SEPPRO_PASS'); process.exit(1); }
const USUARIOS = [1, 5, 10, 25, 50];
const POR_ESCENARIO = 100;

const est = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const p = (q) => s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)];
  return { prom: +(s.reduce((a, b) => a + b, 0) / s.length).toFixed(1), p50: +p(0.5).toFixed(1), p90: +p(0.9).toFixed(1), p95: +p(0.95).toFixed(1), max: +s.at(-1).toFixed(1), min: +s[0].toFixed(1) };
};

async function pedir(metodo, ruta, token, body, extra = {}) {
  const h = { apikey: ANON, Authorization: `Bearer ${token}`, ...extra };
  if (body) h['Content-Type'] = 'application/json';
  const t0 = performance.now();
  try {
    const r = await fetch(URL + ruta, { method: metodo, headers: h, body: body ? JSON.stringify(body) : undefined });
    const buf = await r.arrayBuffer();
    return { status: r.status, ms: performance.now() - t0, bytes: buf.byteLength, rango: r.headers.get('content-range'), buf };
  } catch (e) {
    return { status: 0, ms: performance.now() - t0, bytes: 0, error: e.cause?.code ?? e.message };
  }
}
const token = async (c) => (await (await fetch(`${URL}/auth/v1/token?grant_type=password`, {
  method: 'POST', headers: { apikey: ANON, 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: `${c}@seppro.com`, password: PASS }) })).json());

const s1 = await token('cliente1'); const s2 = await token('cliente2'); const sa = await token('admin');

async function escenario(nombre, usuarios, crear) {
  const lat = []; const codigos = {}; let bytes = 0, err = 0, sig = 0;
  const t0 = performance.now();
  await Promise.all(Array.from({ length: usuarios }, async () => {
    while (sig < POR_ESCENARIO) {
      const n = sig++;
      const r = await crear(n);
      lat.push(r.ms); bytes += r.bytes;
      const c = r.status || `red:${r.error}`; codigos[c] = (codigos[c] ?? 0) + 1;
      if (!(r.status >= 200 && r.status < 300)) err++;
    }
  }));
  const dur = (performance.now() - t0) / 1000;
  return { endpoint: nombre, usuarios, solicitudes: POR_ESCENARIO, rps: +(POR_ESCENARIO / dur).toFixed(2), ...est(lat),
    errores_pct: +(100 * err / POR_ESCENARIO).toFixed(1), codigos: JSON.stringify(codigos), mbps: +((bytes * 8) / (dur * 1e6)).toFixed(3) };
}

// Reporte temporal para las escrituras
const rep = JSON.parse(Buffer.from((await pedir('POST', '/rest/v1/reportes?select=id,folio', s1.access_token,
  { tipo: 'seguimiento', descripcion: 'Reporte temporal de la prueba de estrés (Práctica 12)', para_admin: true },
  { Prefer: 'return=representation' })).buf).toString())[0];

const salida = { etiqueta: etq, fecha: new Date().toISOString(), cliente: `${os.type()} ${os.release()} · Node ${process.version} · ${os.cpus().length} CPU`, servidor: URL };

// ---------- Estrés ----------
salida.estres = [];
for (const u of USUARIOS) {
  salida.estres.push(await escenario('LECTURA GET pedidos', u, () =>
    pedir('GET', `/rest/v1/pedidos?select=*,pedido_items(*)&usuario_id=eq.${s1.user.id}&order=created_at.desc`, s1.access_token)));
}
for (const u of USUARIOS) {
  salida.estres.push(await escenario('ESCRITURA POST mensajes', u, (n) =>
    pedir('POST', '/rest/v1/mensajes', s1.access_token, { reporte_id: rep.id, texto: `Estrés ${etq} u${u} #${n}`, dispositivo: 'estres-api.mjs' })));
}
console.table(salida.estres.map(({ codigos, ...x }) => x));

// Integridad: deben existir exactamente 5 x 100 mensajes
const cuenta = await pedir('GET', `/rest/v1/mensajes?reporte_id=eq.${rep.id}&select=id`, s1.access_token, null, { Prefer: 'count=exact', Range: '0-0' });
salida.mensajesGuardados = cuenta.rango;
console.log('Mensajes guardados (esperados 500):', cuenta.rango);
await pedir('DELETE', `/rest/v1/reportes?id=eq.${rep.id}`, sa.access_token);

// ---------- Consulta crítica: lista de pedidos de cliente2 (3 005 pedidos) ----------
const rutaCritica = etq === 'antes'
  ? `/rest/v1/pedidos?select=*,pedido_items(*)&usuario_id=eq.${s2.user.id}&order=created_at.desc`
  : `/rest/v1/pedidos?select=id,total,estado,created_at,pedido_items(nombre_producto)&usuario_id=eq.${s2.user.id}` +
    `&estado=not.in.(entregado,entregado_verificado,entregado_con_danos)&order=created_at.desc,id.desc&limit=20&offset=0`;
const critica = [];
for (let i = 1; i <= 10; i++) {
  const r = await pedir('GET', rutaCritica, s2.access_token, null, { Prefer: 'count=exact' });
  const filas = JSON.parse(Buffer.from(r.buf).toString()).length;
  critica.push({ solicitud: i, codigo: r.status, ms: +r.ms.toFixed(1), bytes: r.bytes, filas, total_en_bd: r.rango });
}
console.log('\nConsulta crítica (pedidos de cliente2):'); console.table(critica);
salida.consultaCritica = { ruta: rutaCritica.replace(s2.user.id, '<uid>'), filas: critica, resumen: est(critica.map((x) => x.ms)) };
console.log('Resumen:', salida.consultaCritica.resumen);

mkdirSync('pruebas/practica12/evidencias', { recursive: true });
writeFileSync(`pruebas/practica12/evidencias/estres-api-${etq}.json`, JSON.stringify(salida, null, 2));
