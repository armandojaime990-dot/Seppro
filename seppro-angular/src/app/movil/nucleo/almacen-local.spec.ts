import { Almacen, AlmacenLocal } from './almacen-local';

/**
 * Prácticas 13 y 14 — AUT-06 (persistencia): preferencias guardadas en el almacenamiento REAL del
 * navegador (localStorage), igual que SharedPreferences en el teléfono.
 */
describe('AlmacenLocal (AUT-06, persistencia)', () => {
  beforeEach(() => localStorage.clear());
  afterAll(() => localStorage.clear());

  it('lo guardado sobrevive a una instancia nueva (como al reabrir la app)', () => {
    const a = new AlmacenLocal();
    a.ultimoEmail = 'cliente1@seppro.com';
    a.ultimoDispositivo = { nombre: 'SEPPRO-Tag-01', id: 'abc123' };
    a.marcarLimpiado('uid-1', 5, 120);

    const b = new AlmacenLocal(); // nueva instancia: lee del almacenamiento real
    expect(b.ultimoEmail).toBe('cliente1@seppro.com');
    expect(b.ultimoDispositivo).toEqual({ nombre: 'SEPPRO-Tag-01', id: 'abc123' });
    expect(b.limpiadoHasta('uid-1', 5)).toBe(120);
    expect(b.limpiadoHasta('uid-2', 5)).toBe(0); // otro usuario no hereda el "limpiado"
  });

  it('nunca guarda la contraseña ni el token: solo claves con prefijo seppro_prefs.', () => {
    new AlmacenLocal().ultimoEmail = 'a@b.com';
    const claves = Object.keys(localStorage);
    expect(claves).toEqual(['seppro_prefs.ultimo_email']);
    expect(JSON.stringify(localStorage)).not.toContain('password');
  });

  it('dato dañado o vacío -> se ignora sin romper la app', () => {
    localStorage.setItem('seppro_prefs.ultimo_dispositivo', '{no es json');
    localStorage.setItem('seppro_prefs.limpiado_u_1', 'abc');
    const a = new AlmacenLocal();
    expect(a.ultimoDispositivo).toBeNull();
    expect(a.limpiadoHasta('u', 1)).toBe(0);
    a.ultimoEmail = null;
    expect(a.ultimoEmail).toBeNull();
  });

  it('almacenamiento bloqueado (Safari privado) -> no lanza excepción', () => {
    const bloqueado: Almacen = {
      getItem: () => { throw new Error('SecurityError'); },
      setItem: () => { throw new Error('QuotaExceededError'); },
      removeItem: () => { throw new Error('SecurityError'); },
    };
    const a = new AlmacenLocal(bloqueado);
    expect(() => (a.ultimoEmail = 'a@b.com')).not.toThrow();
    expect(a.ultimoEmail).toBeNull();
  });
});

/** Rúbrica C3 — modo sin conexión: última lista de pedidos y perfil guardados en el dispositivo. */
describe('AlmacenLocal (modo sin conexión)', () => {
  beforeEach(() => localStorage.clear());
  afterAll(() => localStorage.clear());
  const pedido = { id: 7, total: 1500, estado: 'en_puerta', created_at: '2026-11-24', pedido_items: null };

  it('guarda la lista y la devuelve solo al mismo usuario y filtro', () => {
    const a = new AlmacenLocal();
    a.guardarPedidos('uid-1', 'activos', [pedido], 1, 1_000);
    expect(new AlmacenLocal().pedidosGuardados('uid-1', 'activos')).toEqual({ pedidos: [pedido], total: 1, fecha: 1_000 });
    expect(a.pedidosGuardados('uid-2', 'activos')).toBeNull();     // otro usuario no ve la copia
    expect(a.pedidosGuardados('uid-1', 'historial')).toBeNull();
  });

  it('guarda como máximo 50 pedidos', () => {
    const a = new AlmacenLocal();
    a.guardarPedidos('u', 'activos', Array.from({ length: 80 }, (_, i) => ({ ...pedido, id: i })), 80);
    expect(a.pedidosGuardados('u', 'activos')!.pedidos.length).toBe(50);
  });

  it('dato dañado -> se ignora sin fallar', () => {
    localStorage.setItem('seppro_prefs.cache_pedidos_activos', '{roto');
    localStorage.setItem('seppro_prefs.perfil', '[1,2]');
    const a = new AlmacenLocal();
    expect(a.pedidosGuardados('u', 'activos')).toBeNull();
    expect(a.perfilGuardado).toBeNull();
  });

  it('cerrar sesión borra el perfil y las copias, pero conserva el último correo', () => {
    const a = new AlmacenLocal();
    a.ultimoEmail = 'cliente@seppro.com';
    a.perfilGuardado = { uid: 'u', nombre: 'Luis', rol: 'usuario', email: 'cliente@seppro.com' };
    a.guardarPedidos('u', 'activos', [pedido], 1);
    a.guardarPedidos('u', 'historial', [pedido], 1);
    a.borrarDatosDeSesion();
    expect(a.perfilGuardado).toBeNull();
    expect(a.pedidosGuardados('u', 'activos')).toBeNull();
    expect(a.pedidosGuardados('u', 'historial')).toBeNull();
    expect(a.ultimoEmail).toBe('cliente@seppro.com');
    expect(Object.keys(localStorage).some((k) => k.includes('token') || k.includes('password'))).toBeFalse();
  });
});
