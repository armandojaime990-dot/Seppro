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
