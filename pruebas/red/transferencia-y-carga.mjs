// Práctica 11 — Fase 5: tasa de transferencia (NET-03/NET-04), comparación de 10 solicitudes (NET-02)
// y prueba de carga controlada (NET-07) contra el proyecto Supabase de SEPPRO.
//
// Uso (desde la raíz del repo): SEPPRO_PASS=<contraseña> node pruebas/red/transferencia-y-carga.mjs
//
// - Crea un reporte temporal de cliente1 para escribir mensajes y subir archivos; al final el admin
//   lo borra (los mensajes se van en cascada). Los 3 archivos de 1 MB quedan en
//   Storage > evidencias/<id>/ porque el bucket no tiene política de borrado (se eliminan desde el panel).
// - Escenarios de carga: 1, 5 y 10 usuarios concurrentes, 20 solicitudes cada uno (lectura y escritura).
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import os from 'node:os';

const URL = 'https://sbhkqjueesxsjkfootuq.supabase.co';
const ANON = readFileSync('seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt', 'utf8').match(/ANON_KEY = "([^"]+)"/)[1];
const PASS = process.env.SEPPRO_PASS;
if (!PASS) { console.error('Falta SEPPRO_PASS'); process.exit(1); }

const mbps = (bytes, ms) => +((bytes * 8) / ((ms / 1000) * 1_000_000)).toFixed(3);
const stats = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const prom = s.reduce((a, b) => a + b, 0) / s.length;
  const de = Math.sqrt(s.reduce((a, b) => a + (b - prom) ** 2, 0) / s.length);
  const p95 = s[Math.min(s.length - 1, Math.ceil(0.95 * s.length) - 1)];
  return { min: +s[0].toFixed(1), prom: +prom.toFixed(1), max: +s.at(-1).toFixed(1), de: +de.toFixed(1), p95: +p95.toFixed(1) };
};

async function pedir(metodo, ruta, { token, body, headers = {}, bin } = {}) {
  const h = { apikey: ANON, ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const t0 = performance.now();
  try {
    const r = await fetch(ruta.startsWith('http') ? ruta : URL + ruta,
      { method: metodo, headers: h, body: bin ?? (body === undefined ? undefined : JSON.stringify(body)) });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, ms: performance.now() - t0, bytes: buf.length, buf };
  } catch (e) {
    return { status: 0, ms: performance.now() - t0, bytes: 0, error: e.cause?.code ?? e.message };
  }
}

const token = async (c) => (JSON.parse((await pedir('POST', '/auth/v1/token?grant_type=password',
  { body: { email: `${c}@seppro.com`, password: PASS } })).buf)).access_token;
const tCliente = await token('cliente1');
const tAdmin = await token('admin');

// Reporte temporal (sin pedido) para escribir
const rep = JSON.parse((await pedir('POST', '/rest/v1/reportes?select=id,folio', { token: tCliente,
  headers: { Prefer: 'return=representation' },
  body: { tipo: 'seguimiento', descripcion: 'Reporte temporal de la prueba de carga (Práctica 11)', prioridad: 'baja', para_admin: true } })).buf)[0];
console.log('Reporte temporal:', rep.folio);

const salida = { fecha: new Date().toISOString(), cliente: `${os.hostname()} · ${os.type()} ${os.release()} · Node ${process.version}`, servidor: URL };

// ---------- NET-02: 10 solicitudes consecutivas (conexión reutilizada, como la app con OkHttp) ----------
const diez = [];
for (let i = 1; i <= 10; i++) {
  const r = await pedir('GET', '/rest/v1/pedidos?select=*,pedido_items(*)&order=created_at.desc', { token: tCliente });
  diez.push({ solicitud: i, codigo: r.status, ms: +r.ms.toFixed(1), bytes: r.bytes });
}
console.log('\nNET-02 — 10 solicitudes consecutivas (keep-alive):'); console.table(diez);
salida.diezSolicitudes = { filas: diez, resumen: stats(diez.map((x) => x.ms)) };
console.log('Resumen:', salida.diezSolicitudes.resumen);

// ---------- NET-03 / NET-04: subida y descarga de 1 MB (Storage) ----------
const MB = 1024 * 1024;
const transfer = [];
for (let i = 1; i <= 3; i++) {
  const archivo = randomBytes(MB);
  const ruta = `${rep.id}/carga-${Date.now()}-${i}.jpg`;
  const sub = await pedir('POST', `/storage/v1/object/evidencias/${ruta}`, { token: tCliente, bin: archivo, headers: { 'Content-Type': 'image/jpeg' } });
  const firma = JSON.parse((await pedir('POST', `/storage/v1/object/sign/evidencias/${ruta}`, { token: tCliente, body: { expiresIn: 600 } })).buf);
  const urlFirmada = URL + '/storage/v1' + (firma.signedURL ?? firma.signedUrl);
  const baj = await pedir('GET', urlFirmada, {});
  transfer.push({
    medicion: i,
    subida_codigo: sub.status, subida_s: +(sub.ms / 1000).toFixed(3), subida_Mbps: mbps(MB, sub.ms),
    bajada_codigo: baj.status, bajada_s: +(baj.ms / 1000).toFixed(3), bajada_Mbps: mbps(baj.bytes, baj.ms),
    integro: Buffer.compare(baj.buf ?? Buffer.alloc(0), archivo) === 0,
  });
}
console.log('\nNET-03/NET-04 — Transferencia de 1 MB (1 048 576 bytes):'); console.table(transfer);
salida.transferencia = transfer;

// URL firmada vencida / alterada (seguridad de la evidencia)
const firmaMala = await pedir('GET', `${URL}/storage/v1/object/sign/evidencias/${rep.id}/x.jpg?token=alterado`, {});
salida.urlFirmadaAlterada = firmaMala.status;

// ---------- NET-07: carga controlada ----------
async function escenario(nombre, usuarios, total, crear) {
  const lat = []; let ok = 0, fallo = 0, bytes = 0; const codigos = {};
  let siguiente = 0;
  const t0 = performance.now();
  await Promise.all(Array.from({ length: usuarios }, async () => {
    while (siguiente < total) {
      const n = siguiente++;
      const r = await crear(n);
      lat.push(r.ms); bytes += r.bytes;
      codigos[r.status] = (codigos[r.status] ?? 0) + 1;
      (r.status >= 200 && r.status < 300) ? ok++ : fallo++;
    }
  }));
  const dur = performance.now() - t0;
  const s = stats(lat);
  return { endpoint: nombre, usuarios, solicitudes: total, exitosas: ok, fallidas: fallo, codigos: JSON.stringify(codigos),
    prom_ms: s.prom, p95_ms: s.p95, max_ms: s.max, duracion_s: +(dur / 1000).toFixed(2),
    req_por_s: +(total / (dur / 1000)).toFixed(2), transfer_Mbps: mbps(bytes, dur) };
}

const carga = [];
for (const u of [1, 5, 10]) {
  carga.push(await escenario('GET pedidos (lectura)', u, 20, () =>
    pedir('GET', '/rest/v1/pedidos?select=*,pedido_items(*)&order=created_at.desc', { token: tCliente })));
  carga.push(await escenario('POST mensajes (escritura)', u, 20, (n) =>
    pedir('POST', '/rest/v1/mensajes', { token: tCliente, body: { reporte_id: rep.id, texto: `Carga u${u} #${n}`, dispositivo: 'k6-alternativa Node' } })));
}
console.log('\nNET-07 — Carga controlada (20 solicitudes por escenario):'); console.table(carga);
salida.carga = carga;

// Consistencia tras la carga: deben existir exactamente 60 mensajes
const cuenta = await pedir('GET', `/rest/v1/mensajes?reporte_id=eq.${rep.id}&select=id`, { token: tCliente, headers: { Prefer: 'count=exact', Range: '0-0' } });
salida.mensajesTrasCarga = JSON.parse(cuenta.buf).length >= 0 ? cuenta : null;
const total = (await pedir('GET', `/rest/v1/mensajes?reporte_id=eq.${rep.id}&select=id`, { token: tCliente })).buf;
salida.mensajesGuardados = JSON.parse(total).length;
console.log(`\nMensajes guardados tras la carga: ${salida.mensajesGuardados} (esperados 60)`);

// Limpieza: el admin borra el reporte (y sus mensajes en cascada)
const borrado = await pedir('DELETE', `/rest/v1/reportes?id=eq.${rep.id}`, { token: tAdmin, headers: { Prefer: 'return=representation' } });
console.log('Limpieza del reporte temporal:', borrado.status, `(${JSON.parse(borrado.buf).length} fila)`);

mkdirSync('pruebas/evidencias', { recursive: true });
delete salida.mensajesTrasCarga;
writeFileSync('pruebas/evidencias/transferencia-y-carga.json', JSON.stringify(salida, null, 2));
