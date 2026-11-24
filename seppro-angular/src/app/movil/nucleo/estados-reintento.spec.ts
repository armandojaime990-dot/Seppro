import { esCerrado, etiquetaEstado, nombrePedido, validarReporte } from './estados';
import { conReintento, ErrorDelServidor, ErrorDeRed, esperaReintentoMs } from './reintento';

/** Prácticas 13 y 14 — AUT-02 (unitarias): reglas de negocio de pedidos, reportes y reintentos. */
describe('estados y validarReporte (AUT-02)', () => {
  it('entrada válida -> sin error; etiquetas y nombres como en el teléfono', () => {
    expect(validarReporte({ tipo: 'falla', descripcion: 'La bomba llegó golpeada', equipo: 'Bomba KOR10', pedidoId: 4 })).toBeNull();
    expect(validarReporte({ tipo: 'seguimiento', descripcion: '¿Cuándo llega mi pedido?', equipo: '', pedidoId: null })).toBeNull();
    expect(etiquetaEstado('rechazado')).toBe('Equipo rechazado');
    expect(esCerrado('rechazado')).toBeTrue();
    expect(esCerrado('en_puerta')).toBeFalse();
    expect(nombrePedido({ id: 7, pedido_items: [{ nombre_producto: 'Bomba' }, { nombre_producto: 'Cable' }] })).toBe('Bomba y 1 más');
    expect(nombrePedido({ id: 7, pedido_items: null })).toBe('Pedido #7');
  });

  it('entrada vacía o fuera de rango -> mensaje de error', () => {
    expect(validarReporte({ tipo: 'falla', descripcion: '   ', equipo: 'x', pedidoId: 1 })).toBe('Escribe una descripción');
    expect(validarReporte({ tipo: 'falla', descripcion: 'corta', equipo: 'x', pedidoId: 1 })).toContain('al menos 10');
    expect(validarReporte({ tipo: 'falla', descripcion: 'x'.repeat(1001), equipo: 'x', pedidoId: 1 })).toContain('1000');
    expect(validarReporte({ tipo: 'falla', descripcion: 'Descripción válida', equipo: '', pedidoId: 1 })).toBe('Indica el equipo dañado');
    expect(validarReporte({ tipo: 'falla', descripcion: 'Descripción válida', equipo: 'Bomba', pedidoId: null }))
      .toBe('Elige el pedido del equipo dañado');
  });
});

describe('conReintento: backoff + idempotencia (AUT-02)', () => {
  const sinEspera = { dormir: () => Promise.resolve() };

  it('espera progresiva 500 ms, 1 s, 2 s, tope 4 s', () => {
    expect([1, 2, 3, 4, 9].map((n) => esperaReintentoMs(n, 0))).toEqual([500, 1000, 2000, 4000, 4000]);
  });

  it('reintenta solo fallas de red y se recupera al tercer intento', async () => {
    let llamadas = 0;
    const r = await conReintento(async () => { if (++llamadas < 3) throw new ErrorDeRed(); return 'ok'; },
      async () => null, sinEspera);
    expect(r).toBe('ok');
    expect(llamadas).toBe(3);
  });

  it('409 (ya guardado) -> recupera el existente sin duplicar', async () => {
    const r = await conReintento(async () => { throw new ErrorDelServidor(409, 'duplicate key'); },
      async () => 'mensaje-77', sinEspera);
    expect(r).toBe('mensaje-77');
  });

  it('manejo de excepciones: 500 no se reintenta y red persistente falla tras 3 intentos', async () => {
    let llamadas = 0;
    await expectAsync(conReintento(async () => { llamadas++; throw new ErrorDelServidor(500, 'Internal'); },
      async () => null, sinEspera)).toBeRejectedWithError(ErrorDelServidor, 'Internal');
    expect(llamadas).toBe(1);

    llamadas = 0;
    await expectAsync(conReintento(async () => { llamadas++; throw new ErrorDeRed(); }, async () => null, sinEspera))
      .toBeRejectedWithError(ErrorDeRed);
    expect(llamadas).toBe(3);
  });
});
