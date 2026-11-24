// Práctica 12 — Genera volumen de datos de prueba: N pedidos (con 1 producto cada uno) para cliente2.
// Usa la API con la sesión de cliente2 (RLS permite insertar pedidos propios). Inserción por lotes.
// Uso: SEPPRO_PASS=<contraseña> node pruebas/practica12/generar-volumen.mjs [N=3000]
// Limpieza: ejecutar supabase-datos-prueba.sql (borra y recrea los pedidos de cliente1 y cliente2).
import { readFileSync } from 'node:fs';

const URL = 'https://sbhkqjueesxsjkfootuq.supabase.co';
const ANON = readFileSync('seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt', 'utf8').match(/ANON_KEY = "([^"]+)"/)[1];
const PASS = process.env.SEPPRO_PASS;
const N = +(process.argv[2] ?? 3000);
const LOTE = 500;
if (!PASS) { console.error('Falta SEPPRO_PASS'); process.exit(1); }

const login = await (await fetch(`${URL}/auth/v1/token?grant_type=password`, {
  method: 'POST', headers: { apikey: ANON, 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'cliente2@seppro.com', password: PASS }),
})).json();
const H = { apikey: ANON, Authorization: `Bearer ${login.access_token}`, 'Content-Type': 'application/json' };
const uid = login.user.id;

const productos = await (await fetch(`${URL}/rest/v1/productos?select=id,nombre,precio`, { headers: H })).json();
const ESTADOS = ['pendiente', 'pagado', 'enviado', 'en_camino', 'en_puerta', 'entregado',
  'entregado_verificado', 'entregado_con_danos', 'alerta_integridad'];

// Pseudoaleatorio con semilla: los mismos datos en cada ejecución (reproducible)
let semilla = 12;
const azar = () => ((semilla = (semilla * 1103515245 + 12345) % 2147483648) / 2147483648);

const t0 = performance.now();
let creados = 0;
for (let inicio = 0; inicio < N; inicio += LOTE) {
  const n = Math.min(LOTE, N - inicio);
  const elegidos = Array.from({ length: n }, () => productos[Math.floor(azar() * productos.length)]);
  const pedidos = elegidos.map((p, k) => {
    const cantidad = 1 + Math.floor(azar() * 3);
    const estado = ESTADOS[Math.floor(azar() * ESTADOS.length)];
    const dias = Math.floor(azar() * 730);
    return {
      usuario_id: uid, total: +(p.precio * cantidad).toFixed(2), estado, metodo_pago: 'tarjeta',
      nombre_envio: 'Cliente Luis', direccion_envio: `[CARGA-P12] Calle ${inicio + k}, Querétaro`,
      telefono_envio: '4420000000', created_at: new Date(Date.now() - dias * 86400000).toISOString(),
      _p: p, _c: cantidad,
    };
  });
  const r = await fetch(`${URL}/rest/v1/pedidos?select=id`, {
    method: 'POST', headers: { ...H, Prefer: 'return=representation' },
    body: JSON.stringify(pedidos.map(({ _p, _c, ...x }) => x)),
  });
  if (r.status !== 201) { console.error('Error pedidos', r.status, await r.text()); process.exit(1); }
  const ids = await r.json();
  const items = ids.map((x, k) => ({
    pedido_id: x.id, producto_id: pedidos[k]._p.id, nombre_producto: pedidos[k]._p.nombre,
    cantidad: pedidos[k]._c, precio_unitario: pedidos[k]._p.precio,
  }));
  const r2 = await fetch(`${URL}/rest/v1/pedido_items`, { method: 'POST', headers: H, body: JSON.stringify(items) });
  if (r2.status !== 201) { console.error('Error items', r2.status, await r2.text()); process.exit(1); }
  creados += n;
  console.log(`lote ${inicio / LOTE + 1}: ${creados}/${N} pedidos`);
}
const total = await fetch(`${URL}/rest/v1/pedidos?select=id`, { headers: { ...H, Prefer: 'count=exact', Range: '0-0' } });
console.log(`Listo en ${((performance.now() - t0) / 1000).toFixed(1)} s. Pedidos de cliente2: ${total.headers.get('content-range')}`);
