import { createClient } from '@supabase/supabase-js';
import { interpretar, MovilApi, SesionExpiradaError } from './movil-api.service';
import { ErrorDelServidor, ErrorDeRed } from './nucleo/reintento';
import { SupabaseService } from '../services/supabase.service';

interface Peticion { url: string; metodo: string; cuerpo: unknown; headers: Headers; }
type Respuesta = { status: number; body?: unknown; headers?: Record<string, string> } | 'sin-red';

/**
 * Servidor HTTP simulado con las mismas respuestas que Supabase (PostgREST).
 * El cliente es el supabase-js REAL: se prueba la comunicación completa app -> HTTP -> respuesta.
 */
function servidorSimulado(respuestas: Respuesta[]) {
  const peticiones: Peticion[] = [];
  const fetchFalso = async (entrada: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const cuerpo = typeof init?.body === 'string' ? JSON.parse(init.body) : null;
    peticiones.push({ url: String(entrada), metodo: init?.method ?? 'GET', cuerpo, headers: new Headers(init?.headers) });
    const r = respuestas.shift() ?? { status: 500, body: { message: 'sin respuesta configurada' } };
    if (r === 'sin-red') throw new TypeError('Failed to fetch');
    return new Response(r.body === undefined ? null : JSON.stringify(r.body),
      { status: r.status, headers: { 'Content-Type': 'application/json', ...(r.headers ?? {}) } });
  };
  const client = createClient('https://servidor-prueba.supabase.co', 'llave-anon-de-prueba', {
    global: { fetch: fetchFalso as typeof fetch },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const api = new MovilApi({ client } as unknown as SupabaseService);
  api.perfil = { uid: 'uid-cliente-1', nombre: 'Cliente Ana', rol: 'usuario', email: 'cliente1@seppro.com' };
  return { api, peticiones };
}

/** Prácticas 13 y 14 — AUT-04/05/06 (integración) de la PWA con el protocolo HTTP de Supabase. */
describe('MovilApi + supabase-js (integración)', () => {
  it('AUT-04: envía la telemetría a verificar_entrega y procesa la respuesta válida', async () => {
    const { api, peticiones } = servidorSimulado([{ status: 200, body:
      { pedido_id: 42, estado: 'entregado_verificado', golpe: false, garantia_folio: 'GAR-00007', garantia_vence: '2027-11-24', repetido: false } }]);

    const r = await api.verificar(42, { paqueteId: 'SEPPRO-42', estatus: 0 }, 'SmartTag-01');

    const p = peticiones[0];
    expect(p.metodo).toBe('POST');
    expect(p.url).toBe('https://servidor-prueba.supabase.co/rest/v1/rpc/verificar_entrega');
    expect(p.headers.get('apikey')).toBe('llave-anon-de-prueba');
    expect(p.cuerpo).toEqual({ p_pedido: 42, p_paquete: 'SEPPRO-42', p_estatus: 0, p_sensor: 'SmartTag-01' });
    expect(r).toEqual({ pedidoId: 42, estado: 'entregado_verificado', golpe: false, garantiaFolio: 'GAR-00007',
      garantiaVence: '2027-11-24', repetido: false });
  });

  it('AUT-05: la API devuelve el código HTTP esperado y la app lo traduce', async () => {
    const { api } = servidorSimulado([
      { status: 400, body: { code: '55000', message: 'El pedido no está listo para verificar (estado actual: pagado)' } },
      { status: 401, body: { message: 'JWT expired' } },
      'sin-red',
    ]);
    await expectAsync(api.verificar(42, { paqueteId: 'SEPPRO-42', estatus: 0 }, null))
      .toBeRejectedWith(new ErrorDelServidor(400, 'El pedido no está listo para verificar (estado actual: pagado)'));
    await expectAsync(api.verificar(42, { paqueteId: 'SEPPRO-42', estatus: 0 }, null)).toBeRejectedWithError(SesionExpiradaError);
    // El servidor no responde: la app muestra "Sin conexión" en lugar de quedarse colgada
    await expectAsync(api.verificar(42, { paqueteId: 'SEPPRO-42', estatus: 0 }, null)).toBeRejectedWithError(ErrorDeRed);
  });

  it('AUT-05: pedidos paginados con filtro en el servidor y total de Content-Range', async () => {
    const { api, peticiones } = servidorSimulado([{ status: 206, headers: { 'Content-Range': '0-19/2046' },
      body: [{ id: 3005, total: 100, estado: 'en_puerta', created_at: '2026-11-20T10:00:00Z', pedido_items: [{ nombre_producto: 'Bomba' }] }] }]);

    const pagina = await api.paginaPedidos('activos', 0);

    const url = decodeURIComponent(peticiones[0].url);
    expect(url).toContain('usuario_id=eq.uid-cliente-1');
    expect(url).toContain('estado=not.in.(entregado,entregado_verificado,entregado_con_danos,rechazado)');
    expect(url).toContain('order=created_at.desc,id.desc');
    expect(url).toContain('limit=20');
    expect(peticiones[0].headers.get('Prefer')).toContain('count=exact');
    expect(pagina.total).toBe(2046);
    expect(pagina.pedidos[0].id).toBe(3005);
  });

  it('AUT-06 (persistencia): el mensaje se guarda una sola vez aunque se pierda la respuesta (409 -> recupera)', async () => {
    const guardado = { id: 77, reporte_id: 5, emisor_id: 'uid-cliente-1', texto: 'Hola', created_at: '2026-11-24T10:00:00Z' };
    const { api, peticiones } = servidorSimulado([
      'sin-red',                                                         // 1er intento: se perdió la respuesta
      { status: 409, body: { code: '23505', message: 'duplicate key value violates unique constraint "mensajes_id_cliente_uq"' } },
      { status: 200, body: [guardado] },                                 // GET por id_cliente
    ]);

    const m = await api.enviarMensaje(5, 'Hola', null, '11111111-2222-3333-4444-555555555555');

    expect(m.id).toBe(77);
    const posts = peticiones.filter((p) => p.metodo === 'POST');
    expect(posts.length).toBe(2);
    expect(posts.every((p) => (p.cuerpo as { id_cliente: string }).id_cliente === '11111111-2222-3333-4444-555555555555')).toBeTrue();
    expect(decodeURIComponent(peticiones[2].url)).toContain('id_cliente=eq.11111111-2222-3333-4444-555555555555');
  });
});

/** Rúbrica C3: sin red, el service worker de Angular responde 504; se trata como "sin conexión". */
describe('interpretar (errores de red)', () => {
  it('504 del service worker y status 0 -> ErrorDeRed; 500 -> ErrorDelServidor', () => {
    expect(() => interpretar({ data: null, error: { message: 'Gateway Timeout' }, status: 504 })).toThrowError(ErrorDeRed);
    expect(() => interpretar({ data: null, error: { message: 'TypeError: Failed to fetch' }, status: 0 })).toThrowError(ErrorDeRed);
    expect(() => interpretar({ data: null, error: { message: 'boom' }, status: 500 })).toThrowError(ErrorDelServidor);
    expect(interpretar({ data: [1], error: null, status: 200 })).toEqual([1]);
  });
});
