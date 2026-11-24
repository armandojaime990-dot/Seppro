// Práctica 11 — Fases 3 y 4: pruebas White Box de la API (Supabase PostgREST/Auth) y de la base de datos.
//
// Ejecuta casos reales contra el proyecto de Supabase de SEPPRO usando las cuentas de prueba
// (supabase-usuarios-prueba.sql) y compara el código HTTP esperado con el obtenido.
//
// Uso (desde la raíz del repo):
//   SEPPRO_PASS=<contraseña de las cuentas de prueba> node pruebas/api/whitebox-api.mjs
//
// Deja la evidencia en pruebas/evidencias/api-whitebox.json
// Los registros que crea se eliminan al final (excepto los mensajes, que por diseño no se pueden borrar).
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

const URL = 'https://sbhkqjueesxsjkfootuq.supabase.co';
const ANON = readFileSync('seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt', 'utf8')
  .match(/ANON_KEY = "([^"]+)"/)[1];
const PASS = process.env.SEPPRO_PASS;
if (!PASS) { console.error('Falta la variable SEPPRO_PASS'); process.exit(1); }

const resultados = [];
const tokens = {};
const ids = {};

async function http(metodo, ruta, { token, body, headers = {}, raw } = {}) {
  const h = { apikey: ANON, ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  if (body !== undefined) h['Content-Type'] = 'application/json';
  const t0 = performance.now();
  const r = await fetch(URL + ruta, { method: metodo, headers: h, body: raw ?? (body === undefined ? undefined : JSON.stringify(body)) });
  const texto = await r.text();
  const ms = performance.now() - t0;
  let json = null;
  try { json = texto ? JSON.parse(texto) : null; } catch { /* no JSON */ }
  return { status: r.status, ms: +ms.toFixed(1), bytes: Buffer.byteLength(texto), json, texto };
}

function registrar(id, descripcion, esperado, r, extra = '') {
  const ok = Array.isArray(esperado) ? esperado.includes(r.status) : r.status === esperado;
  const detalle = (r.json?.code ? `${r.json.code} ` : '') + (r.json?.message ?? r.json?.msg ?? r.json?.error_code ?? '');
  resultados.push({ id, descripcion, esperado: String(esperado), obtenido: r.status, ok: ok ? 'APROBADO' : 'FALLIDO',
    ms: r.ms, bytes: r.bytes, detalle: (extra || detalle).slice(0, 90) });
  return ok;
}

async function login(cuenta) {
  const r = await http('POST', '/auth/v1/token?grant_type=password', { body: { email: `${cuenta}@seppro.com`, password: PASS } });
  tokens[cuenta] = r.json?.access_token;
  ids[cuenta] = r.json?.user?.id;
  return r;
}

// ===================== FASE 3: sitio web / servicios =====================

// --- Autenticación (Supabase Auth) ---
let r = await login('cliente1');
const sinSecretos = !/encrypted_password|"password"/.test(r.texto) && !r.texto.includes(PASS);
registrar('WEB-01a', 'Login válido (POST /auth/v1/token)', 200, r,
  `access_token=${!!r.json?.access_token} expira_en=${r.json?.expires_in}s sin_contraseña_en_respuesta=${sinSecretos}`);
await login('cliente2'); await login('agente'); await login('admin');

r = await http('POST', '/auth/v1/token?grant_type=password', { body: { email: 'cliente1@seppro.com', password: 'incorrecta' } });
registrar('WEB-02a', 'Login con contraseña incorrecta', 400, r);

r = await http('POST', '/auth/v1/token?grant_type=password', { raw: '{"email": "cliente1@seppro.com", ' , headers: { 'Content-Type': 'application/json' } });
registrar('WEB-02b', 'Login con JSON mal formado', 400, r);

r = await http('POST', '/auth/v1/signup', { body: { email: `prueba.${Date.now()}@example.com`, password: '12' } });
registrar('WEB-03a', 'Registro con contraseña débil (validación semántica)', 422, r);

// --- Autenticación vs autorización ---
r = await http('GET', '/rest/v1/pedidos?select=id', { token: 'token.invalido.x' });
registrar('WEB-04a', 'Token inválido -> no autenticado', 401, r);

r = await http('POST', '/rest/v1/rpc/agentes_estado', { body: {} }); // solo apikey (rol anon)
registrar('WEB-04b', 'RPC solo-admin sin sesión (anon) -> 401', 401, r);

r = await http('POST', '/rest/v1/rpc/agentes_estado', { token: tokens.cliente1, body: {} });
registrar('WEB-04c', 'RPC solo-admin con sesión de cliente -> 403', 403, r);

r = await http('POST', '/rest/v1/rpc/agentes_estado', { token: tokens.admin, body: {} });
registrar('WEB-04d', 'RPC solo-admin con sesión de admin', 200, r, `agentes=${r.json?.length}`);

r = await http('GET', '/rest/v1/pedidos?select=id', {}); // solo apikey
registrar('WEB-04e', 'Pedidos sin sesión: RLS oculta todo (200 y lista vacía)', 200, r, `filas=${r.json?.length}`);

// --- Consulta exitosa y consistencia de la estructura JSON ---
r = await http('GET', '/rest/v1/pedidos?select=*,pedido_items(*)&order=created_at.desc', { token: tokens.cliente1 });
const llaves = ['id', 'usuario_id', 'total', 'estado', 'created_at', 'pedido_items'];
const consistente = Array.isArray(r.json) && r.json.every((p) => llaves.every((k) => k in p) && p.usuario_id === ids.cliente1);
registrar('WEB-01b', 'Pedidos del cliente (lo que usa la app)', 200, r,
  `filas=${r.json?.length} estructura_consistente=${consistente} solo_suyos=${consistente}`);
const pedidoDanado = r.json?.find((p) => p.estado === 'entregado_con_danos');

r = await http('GET', '/rest/v1/tabla_que_no_existe', { token: tokens.cliente1 });
registrar('WEB-02c', 'Recurso (tabla) inexistente', 404, r);

r = await http('GET', '/rest/v1/reportes?id=eq.999999', { token: tokens.cliente1, headers: { Accept: 'application/vnd.pgrst.object+json' } });
registrar('WEB-02d', 'Un solo registro con id inexistente', 406, r);

// --- Datos sensibles ---
r = await http('GET', '/rest/v1/perfiles?select=email,rol', {}); // ¡sin sesión!
registrar('WEB-05s', 'Perfiles sin sesión: NO deberían verse correos', 200, r,
  `DEFECTO si filas>0 -> filas_expuestas=${r.json?.length}`);
const perfilesExpuestos = r.json?.length ?? 0;

// ===================== FASE 4: base de datos (CRUD vía API, con RLS y triggers) =====================

// DB-01 Inserción válida (trigger asigna cliente, folio y prioridad)
r = await http('POST', '/rest/v1/reportes?select=id,folio,cliente_id,prioridad,estado', {
  token: tokens.cliente1, headers: { Prefer: 'return=representation' },
  body: { tipo: 'falla', pedido_id: pedidoDanado?.id ?? null, equipo: 'Prueba White Box',
          descripcion: 'Registro creado por la prueba DB-01 de la Práctica 11', prioridad: 'baja' },
});
const nuevo = r.json?.[0];
registrar('DB-01', 'Insertar reporte válido', 201, r,
  `folio=${nuevo?.folio} cliente_forzado=${nuevo?.cliente_id === ids.cliente1} prioridad=${nuevo?.prioridad} (pedido con daño -> alta)`);

// DB-02 Integridad (CHECK, FK/trigger, RLS, tipos)
r = await http('POST', '/rest/v1/reportes', { token: tokens.cliente1, body: { tipo: 'falla', descripcion: 'corta' } });
registrar('DB-02a', 'Insertar con descripción < 10 (CHECK)', 400, r);
r = await http('POST', '/rest/v1/reportes', { token: tokens.cliente1, body: { tipo: 'queja', descripcion: 'Tipo que no existe en el CHECK' } });
registrar('DB-02b', 'Insertar con tipo inválido (CHECK)', 400, r);
r = await http('POST', '/rest/v1/reportes', { token: tokens.cliente1, body: { descripcion: 'Falta el campo tipo NOT NULL' } });
registrar('DB-02c', 'Insertar sin campo obligatorio (NOT NULL)', 400, r);
r = await http('POST', '/rest/v1/reportes', { token: tokens.cliente2,
  body: { tipo: 'falla', pedido_id: pedidoDanado?.id, descripcion: 'Intento de usar el pedido de otro cliente' } });
registrar('DB-02d', 'Insertar con pedido de OTRO cliente (trigger)', 403, r);
r = await http('POST', '/rest/v1/reportes', { token: tokens.cliente1, body: { tipo: 'falla', prioridad: 7, descripcion: 'Tipo de dato incorrecto' } });
registrar('DB-02e', 'Insertar con tipo de dato incorrecto', 400, r);

// Duplicado (UNIQUE) y eliminación sin cuerpo (204) usando el carrito
const prod = (await http('GET', '/rest/v1/productos?select=id&limit=1', {})).json?.[0]?.id;
await http('DELETE', `/rest/v1/carrito?usuario_id=eq.${ids.cliente1}&producto_id=eq.${prod}`, { token: tokens.cliente1 });
r = await http('POST', '/rest/v1/carrito', { token: tokens.cliente1, body: { usuario_id: ids.cliente1, producto_id: prod, cantidad: 1 } });
registrar('DB-02f', 'Agregar al carrito (registro creado)', 201, r);
r = await http('POST', '/rest/v1/carrito', { token: tokens.cliente1, body: { usuario_id: ids.cliente1, producto_id: prod, cantidad: 1 } });
registrar('DB-02g', 'Mismo producto otra vez (UNIQUE usuario+producto)', 409, r);
r = await http('DELETE', `/rest/v1/carrito?usuario_id=eq.${ids.cliente1}&producto_id=eq.${prod}`, { token: tokens.cliente1 });
registrar('DB-05a', 'Eliminar del carrito (sin cuerpo)', 204, r);

// DB-03 Consultas
r = await http('GET', `/rest/v1/reportes?id=eq.${nuevo?.id}&select=id,folio`, { token: tokens.cliente1 });
registrar('DB-03a', 'Consulta por id existente', 200, r, `filas=${r.json?.length}`);
r = await http('GET', '/rest/v1/reportes?id=eq.999999&select=id', { token: tokens.cliente1 });
registrar('DB-03b', 'Consulta por id inexistente (lista vacía)', 200, r, `filas=${r.json?.length}`);
r = await http('GET', `/rest/v1/reportes?id=eq.${nuevo?.id}&select=id`, { token: tokens.cliente2 });
registrar('DB-03c', 'Cliente2 consulta reporte de cliente1 (RLS)', 200, r, `filas=${r.json?.length} (debe ser 0)`);

// DB-04 Actualización + persistencia
r = await http('PATCH', `/rest/v1/reportes?id=eq.${nuevo?.id}`, { token: tokens.cliente1,
  headers: { Prefer: 'return=representation' }, body: { descripcion: 'Descripción actualizada por la prueba DB-04' } });
registrar('DB-04a', 'Actualizar descripción (dueño)', 200, r);
r = await http('GET', `/rest/v1/reportes?id=eq.${nuevo?.id}&select=descripcion,updated_at`, { token: tokens.cliente1 });
registrar('DB-04b', 'Consultar después de actualizar (persistencia)', 200, r,
  `persistió=${r.json?.[0]?.descripcion === 'Descripción actualizada por la prueba DB-04'}`);
r = await http('PATCH', `/rest/v1/reportes?id=eq.${nuevo?.id}`, { token: tokens.cliente1, body: { agente_id: ids.agente } });
registrar('DB-04c', 'Cliente intenta reasignar (trigger)', 403, r);

// DB-06 Concurrencia: dos actualizaciones simultáneas sobre el mismo registro
const [a, b] = await Promise.all([
  http('PATCH', `/rest/v1/reportes?id=eq.${nuevo?.id}`, { token: tokens.cliente1, headers: { Prefer: 'return=representation' }, body: { prioridad: 'baja', equipo: 'Escritura A' } }),
  http('PATCH', `/rest/v1/reportes?id=eq.${nuevo?.id}`, { token: tokens.cliente1, headers: { Prefer: 'return=representation' }, body: { prioridad: 'media', equipo: 'Escritura B' } }),
]);
r = await http('GET', `/rest/v1/reportes?id=eq.${nuevo?.id}&select=prioridad,equipo`, { token: tokens.cliente1 });
registrar('DB-06', 'Dos PATCH simultáneos al mismo reporte', 200, r,
  `A=${a.status} B=${b.status} final=${r.json?.[0]?.equipo}/${r.json?.[0]?.prioridad} -> gana la última escritura, la otra se pierde sin aviso`);

// Mensajes: validación del backend + reintento duplicado (idempotencia)
r = await http('POST', '/rest/v1/mensajes', { token: tokens.cliente1, body: { reporte_id: nuevo?.id, texto: '    ' } });
registrar('WEB-03b', 'Mensaje de solo espacios (validación del backend)', 400, r);
const msg = { reporte_id: nuevo?.id, texto: `Reintento ${Date.now()}`, dispositivo: 'Prueba Node' };
const m1 = await http('POST', '/rest/v1/mensajes', { token: tokens.cliente1, body: msg });
const m2 = await http('POST', '/rest/v1/mensajes', { token: tokens.cliente1, body: msg });
r = await http('GET', `/rest/v1/mensajes?reporte_id=eq.${nuevo?.id}&texto=eq.${encodeURIComponent(msg.texto)}&select=id`, { token: tokens.cliente1 });
registrar('NET-06d', 'Mismo mensaje enviado 2 veces (reintento)', 200, r, `POST1=${m1.status} POST2=${m2.status} filas_guardadas=${r.json?.length} (1 sería idempotente)`);
r = await http('POST', '/rest/v1/mensajes', { token: tokens.cliente2, body: { reporte_id: nuevo?.id, texto: 'Mensaje en reporte ajeno' } });
registrar('WEB-04f', 'Cliente2 escribe en reporte de cliente1 (RLS)', 403, r);

// DB-05 Eliminación: cliente (sin política DELETE) vs admin
r = await http('DELETE', `/rest/v1/reportes?id=eq.${nuevo?.id}`, { token: tokens.cliente1, headers: { Prefer: 'return=representation' } });
registrar('DB-05b', 'Cliente intenta borrar su reporte (sin permiso)', 200, r, `filas_borradas=${r.json?.length} (RLS: 0, no lanza error)`);
r = await http('DELETE', `/rest/v1/reportes?id=eq.${nuevo?.id}`, { token: tokens.admin, headers: { Prefer: 'return=representation' } });
registrar('DB-05c', 'Admin borra el reporte de prueba', 200, r, `filas_borradas=${r.json?.length}`);
r = await http('GET', `/rest/v1/mensajes?reporte_id=eq.${nuevo?.id}&select=id`, { token: tokens.admin });
registrar('DB-05d', 'Mensajes del reporte borrado (ON DELETE CASCADE)', 200, r, `filas=${r.json?.length} (debe ser 0)`);

// RPC exitosa sin cuerpo
r = await http('POST', '/rest/v1/rpc/latido', { token: tokens.agente, body: {} });
registrar('WEB-01c', 'RPC latido() (función VOID)', [200, 204], r);

// ===================== Resultado =====================
console.table(resultados.map(({ id, descripcion, esperado, obtenido, ok, ms, detalle }) => ({ id, descripcion: descripcion.slice(0, 48), esperado, obtenido, ok, ms, detalle: detalle.slice(0, 70) })));
const aprob = resultados.filter((x) => x.ok === 'APROBADO').length;
console.log(`\n${aprob}/${resultados.length} casos con el código HTTP esperado. Perfiles expuestos sin sesión: ${perfilesExpuestos}`);
mkdirSync('pruebas/evidencias', { recursive: true });
writeFileSync('pruebas/evidencias/api-whitebox.json', JSON.stringify({
  fecha: new Date().toISOString(), servidor: URL, cliente: `Node ${process.version} / ${process.platform}`, resultados,
}, null, 2));
