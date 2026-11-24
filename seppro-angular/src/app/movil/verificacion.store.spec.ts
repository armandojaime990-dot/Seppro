import { ErrorDelServidor, ErrorDeRed } from './nucleo/reintento';
import { Telemetria, TelemetriaInvalidaError } from './nucleo/telemetria';
import { FUNCION_NO_INSTALADA, LectorTelemetria, RepositorioVerificacion, ResultadoAlerta, ResultadoVerificacion, VerificacionStore } from './verificacion.store';

class RepoFalso implements RepositorioVerificacion {
  llamadas = 0;
  error: Error | null = null;
  correo = true;
  async verificar(pedidoId: number, t: Telemetria): Promise<ResultadoVerificacion> {
    this.llamadas++;
    if (this.error) throw this.error;
    const golpe = t.estatus === 1;
    return { pedidoId, estado: golpe ? 'alerta_integridad' : 'entregado_verificado', golpe,
      garantiaFolio: golpe ? null : 'GAR-00001', garantiaVence: golpe ? null : '2027-11-24', repetido: false };
  }
  async resolverAlerta(pedidoId: number, accion: 'aceptar' | 'rechazar'): Promise<ResultadoAlerta> {
    return accion === 'rechazar' ? { pedidoId, estado: 'rechazado', reporteId: 9, reporteFolio: 'REP009' }
      : { pedidoId, estado: 'entregado_con_danos', reporteId: null, reporteFolio: null };
  }
  async enviarCertificado(): Promise<boolean> { return this.correo; }
}

const lector = (t: Telemetria | Error): LectorTelemetria => ({
  leer: async () => { if (t instanceof Error) throw t; return t; },
});

/** Prácticas 13 y 14 — AUT-02 (unitarias): flujo de verificación de la PWA (igual que el ViewModel Kotlin). */
describe('VerificacionStore (AUT-02)', () => {
  let repo: RepoFalso;
  let store: VerificacionStore;
  beforeEach(() => { repo = new RepoFalso(); store = new VerificacionStore(repo); });

  it('sin golpe -> verificado con garantía y certificado enviado', async () => {
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    const e = store.estado();
    expect(e.tipo).toBe('verificado');
    if (e.tipo === 'verificado') {
      expect(e.resultado.garantiaFolio).toBe('GAR-00001');
      expect(e.correoEnviado).toBeTrue();
    }
  });

  it('golpe fuerte -> alerta de integridad -> rechazar abre reporte', async () => {
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 1 }), 'Tag');
    expect(store.estado().tipo).toBe('alerta');
    await store.rechazarEquipo(42, '  caja abollada  ');
    const e = store.estado();
    expect(e.tipo === 'resuelto' && e.resultado.reporteFolio).toBe('REP009');
  });

  it('entrada inválida: sin pedido o sensor de otro paquete -> no llama al servidor', async () => {
    await store.verificar(0, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    expect(store.estado().tipo).toBe('error');
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-43', estatus: 0 }), 'Tag');
    const e = store.estado();
    expect(e.tipo === 'error' && e.mensaje).toContain('SEPPRO-43');
    expect(repo.llamadas).toBe(0);
  });

  it('manejo de excepciones: lectura inválida, servidor, red y sesión', async () => {
    await store.verificar(42, lector(new TelemetriaInvalidaError('Estatus fuera de rango (7): debe ser 0 o 1')), 'Tag');
    expect(store.estado()).toEqual({ tipo: 'error', mensaje: 'Lectura inválida del Smart Tag: Estatus fuera de rango (7): debe ser 0 o 1' });

    repo.error = new ErrorDelServidor(400, 'El pedido no está listo para verificar (estado actual: pagado)');
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    expect(store.estado()).toEqual({ tipo: 'error', mensaje: 'El pedido no está listo para verificar (estado actual: pagado)' });

    repo.error = new ErrorDelServidor(404, 'Could not find the function public.verificar_entrega');
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    expect(store.estado()).toEqual({ tipo: 'error', mensaje: FUNCION_NO_INSTALADA });

    repo.error = new ErrorDeRed();
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    expect(store.estado()).toEqual({ tipo: 'error', mensaje: 'Sin conexión. Revisa tu Internet e intenta de nuevo.' });

    const sesion = new Error('x'); sesion.name = 'SesionExpiradaError';
    repo.error = sesion;
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    const e = store.estado();
    expect(e.tipo === 'error' && e.sesionExpirada).toBeTrue();
  });

  it('correo no disponible -> la garantía sigue activa', async () => {
    repo.correo = false;
    await store.verificar(42, lector({ paqueteId: 'SEPPRO-42', estatus: 0 }), 'Tag');
    const e = store.estado();
    expect(e.tipo === 'verificado' && e.correoEnviado).toBeFalse();
  });
});
