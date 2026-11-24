// Práctica 12 — Fase 4 (pruebas 5 y 6): escrituras concurrentes sobre el MISMO registro.
//
// 20 clientes leen el mismo reporte, agregan su marca a la descripción y guardan, todos a la vez
// (patrón leer-modificar-escribir, igual que "Editar" en ReporteActivity).
//   modo "antes":   PATCH ?id=eq.X                      (la app actual)  -> se pierden escrituras sin aviso
//   modo "despues": PATCH ?id=eq.X&updated_at=eq.<leído> (bloqueo optimista, SoporteApi optimizado)
//                   si responde 0 filas = conflicto -> se vuelve a leer y se reintenta (máx. 25: con 20 escritores el último puede necesitar 20 rondas)
// Al final se cuenta cuántas de las 20 marcas quedaron guardadas.
//
// Uso: SEPPRO_PASS=<contraseña> node pruebas/practica12/concurrencia.mjs <antes|despues>
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const modo = process.argv[2];
const URL = 'https://sbhkqjueesxsjkfootuq.supabase.co';
const ANON = readFileSync('seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt', 'utf8').match(/ANON_KEY = "([^"]+)"/)[1];
const PASS = process.env.SEPPRO_PASS;
const N = 20;
if (!PASS || !['antes', 'despues'].includes(modo)) { console.error('Uso: SEPPRO_PASS=... node concurrencia.mjs antes|despues'); process.exit(1); }

const sesion = async (c) => (await (await fetch(`${URL}/auth/v1/token?grant_type=password`, {
  method: 'POST', headers: { apikey: ANON, 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: `${c}@seppro.com`, password: PASS }) })).json());
const s1 = await sesion('cliente1'); const sa = await sesion('admin');
const H = { apikey: ANON, Authorization: `Bearer ${s1.access_token}`, 'Content-Type': 'application/json' };

// Reporte de prueba
const rep = (await (await fetch(`${URL}/rest/v1/reportes?select=id,folio,updated_at`, { method: 'POST',
  headers: { ...H, Prefer: 'return=representation' },
  body: JSON.stringify({ tipo: 'seguimiento', descripcion: 'Concurrencia:', para_admin: true }) })).json())[0];

const leer = async () => (await (await fetch(`${URL}/rest/v1/reportes?id=eq.${rep.id}&select=descripcion,updated_at`, { headers: H })).json())[0];

let conflictos = 0, reintentos = 0, exitosas = 0, fallidas = 0;
const lat = [];
const t0 = performance.now();
await Promise.all(Array.from({ length: N }, async (_, i) => {
  const ini = performance.now();
  for (let intento = 0; intento < 25; intento++) {
    const actual = await leer();
    const filtro = modo === 'despues' ? `&updated_at=eq.${encodeURIComponent(actual.updated_at)}` : '';
    const r = await fetch(`${URL}/rest/v1/reportes?id=eq.${rep.id}${filtro}`, {
      method: 'PATCH', headers: { ...H, Prefer: 'return=representation' },
      body: JSON.stringify({ descripcion: `${actual.descripcion} W${String(i).padStart(2, '0')}` }) });
    const filas = r.ok ? (await r.json()).length : 0;
    if (filas === 1) { exitosas++; lat.push(performance.now() - ini); return; }
    if (modo === 'despues' && r.ok && filas === 0) { conflictos++; reintentos++; await new Promise((ok) => setTimeout(ok, 50 * 2 ** Math.min(intento, 4) + Math.random() * 50)); continue; }
    fallidas++; return;
  }
  fallidas++;
}));
const dur = performance.now() - t0;

const final = await leer();
const marcas = (final.descripcion.match(/W\d\d/g) ?? []);
const unicas = new Set(marcas).size;
const resultado = {
  modo, escritores: N, respuestas_exitosas: exitosas, fallidas, conflictos_detectados: conflictos, reintentos,
  marcas_guardadas: unicas, escrituras_perdidas: N - unicas, perdidas_sin_aviso: exitosas - unicas, duplicadas: marcas.length - unicas,
  duracion_ms: +dur.toFixed(0), latencia_prom_ms: lat.length ? +(lat.reduce((a, b) => a + b, 0) / lat.length).toFixed(0) : null,
  descripcion_final: final.descripcion,
};
console.log(resultado);

await fetch(`${URL}/rest/v1/reportes?id=eq.${rep.id}`, { method: 'DELETE', headers: { ...H, Authorization: `Bearer ${sa.access_token}` } });
mkdirSync('pruebas/practica12/evidencias', { recursive: true });
writeFileSync(`pruebas/practica12/evidencias/concurrencia-${modo}.json`, JSON.stringify(resultado, null, 2));
