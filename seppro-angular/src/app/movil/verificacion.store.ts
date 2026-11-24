import { signal } from '@angular/core';
import {
  CARACTERISTICA_TAG, codificarTelemetria, correspondeAPedido, esGolpe, leerTelemetria, SERVICIO_TAG,
  Telemetria, TelemetriaInvalidaError,
} from './nucleo/telemetria';
import { ErrorDelServidor, ErrorDeRed } from './nucleo/reintento';

/** Respuesta de verificar_entrega. */
export interface ResultadoVerificacion {
  pedidoId: number; estado: string; golpe: boolean;
  garantiaFolio: string | null; garantiaVence: string | null; repetido: boolean;
}
/** Respuesta de resolver_alerta. */
export interface ResultadoAlerta { pedidoId: number; estado: string; reporteId: number | null; reporteFolio: string | null; }

export interface RepositorioVerificacion {
  verificar(pedidoId: number, t: Telemetria, sensor: string | null): Promise<ResultadoVerificacion>;
  resolverAlerta(pedidoId: number, accion: 'aceptar' | 'rechazar', motivo: string | null): Promise<ResultadoAlerta>;
  enviarCertificado(pedidoId: number): Promise<boolean>;
}

export interface LectorTelemetria {
  leer(): Promise<Telemetria>;
}

export type EstadoVerificacion =
  | { tipo: 'inactivo' }
  | { tipo: 'conectando'; dispositivo: string }
  | { tipo: 'registrando'; telemetria: Telemetria }
  | { tipo: 'verificado'; resultado: ResultadoVerificacion; correoEnviado: boolean | null }
  | { tipo: 'alerta'; pedidoId: number; telemetria: Telemetria }
  | { tipo: 'resolviendo'; pedidoId: number; accion: 'aceptar' | 'rechazar' }
  | { tipo: 'resuelto'; resultado: ResultadoAlerta }
  | { tipo: 'error'; mensaje: string; sesionExpirada?: boolean };

/**
 * Equivalente web de VerificacionViewModel (Kotlin, StateFlow): mismo flujo y mismos mensajes.
 *   leer Smart Tag -> validar paquete -> verificar_entrega -> (garantía + certificado) | alerta
 */
export class VerificacionStore {
  readonly estado = signal<EstadoVerificacion>({ tipo: 'inactivo' });

  constructor(private readonly repo: RepositorioVerificacion) {}

  get ocupado(): boolean {
    const t = this.estado().tipo;
    return t === 'conectando' || t === 'registrando' || t === 'resolviendo';
  }

  async verificar(pedidoId: number, lector: LectorTelemetria, dispositivo: string): Promise<void> {
    if (this.ocupado) return; // evita dobles toques
    if (!pedidoId || pedidoId <= 0) {
      this.estado.set({ tipo: 'error', mensaje: 'Elige un pedido "En puerta" en la pestaña Pedidos' });
      return;
    }
    this.estado.set({ tipo: 'conectando', dispositivo });
    try {
      const t = await lector.leer();
      if (!correspondeAPedido(t, pedidoId)) {
        this.estado.set({ tipo: 'error', mensaje: `Este Smart Tag pertenece al paquete ${t.paqueteId}, no al pedido #${pedidoId}` });
        return;
      }
      this.estado.set({ tipo: 'registrando', telemetria: t });
      const r = await this.repo.verificar(pedidoId, t, dispositivo);
      if (r.golpe || esGolpe(t)) {
        this.estado.set({ tipo: 'alerta', pedidoId, telemetria: t });
        return;
      }
      this.estado.set({ tipo: 'verificado', resultado: r, correoEnviado: null });
      const enviado = await this.repo.enviarCertificado(pedidoId).catch(() => false);
      this.estado.set({ tipo: 'verificado', resultado: r, correoEnviado: enviado });
    } catch (e) {
      this.estado.set(VerificacionStore.error(e));
    }
  }

  async aceptarConDanos(pedidoId: number): Promise<void> {
    await this.resolver(pedidoId, 'aceptar', null);
  }

  async rechazarEquipo(pedidoId: number, motivo: string | null): Promise<void> {
    await this.resolver(pedidoId, 'rechazar', motivo?.trim().slice(0, 500) || null);
  }

  reiniciar(): void {
    this.estado.set({ tipo: 'inactivo' });
  }

  private async resolver(pedidoId: number, accion: 'aceptar' | 'rechazar', motivo: string | null) {
    if (this.ocupado) return;
    this.estado.set({ tipo: 'resolviendo', pedidoId, accion });
    try {
      this.estado.set({ tipo: 'resuelto', resultado: await this.repo.resolverAlerta(pedidoId, accion, motivo) });
    } catch (e) {
      this.estado.set(VerificacionStore.error(e));
    }
  }

  static error(e: unknown): EstadoVerificacion {
    if (e instanceof Error && e.name === 'SesionExpiradaError') {
      return { tipo: 'error', mensaje: 'Tu sesión expiró. Inicia sesión de nuevo.', sesionExpirada: true };
    }
    if (e instanceof TelemetriaInvalidaError) return { tipo: 'error', mensaje: `Lectura inválida del Smart Tag: ${e.message}` };
    if (e instanceof ErrorDelServidor) return { tipo: 'error', mensaje: e.message };
    if (e instanceof ErrorDeRed) return { tipo: 'error', mensaje: 'Sin conexión. Revisa tu Internet e intenta de nuevo.' };
    if (e instanceof Error && e.name === 'TimeoutError') {
      return { tipo: 'error', mensaje: 'El Smart Tag no respondió. Acércate a la caja e intenta de nuevo.' };
    }
    if (e instanceof Error && e.name === 'NotFoundError') return { tipo: 'error', mensaje: 'No elegiste ningún Smart Tag' };
    return { tipo: 'error', mensaje: e instanceof Error && e.message ? e.message : 'Algo salió mal' };
  }
}

/** Simulador del Smart Tag (modo demostración, sin hardware). */
export class SimuladorTag implements LectorTelemetria {
  constructor(private readonly pedidoId: number, private readonly estatus: number, private readonly esperaMs = 1200) {}
  async leer(): Promise<Telemetria> {
    await new Promise((ok) => setTimeout(ok, this.esperaMs));
    return leerTelemetria(codificarTelemetria({ paqueteId: `SEPPRO-${this.pedidoId}`, estatus: this.estatus }));
  }
}

// ===== Web Bluetooth (Chrome en Android / escritorio). Safari en iPhone NO lo soporta. =====

interface CaracteristicaBt { readValue(): Promise<DataView>; }
interface ServidorGatt {
  connected: boolean;
  connect(): Promise<ServidorGatt>;
  disconnect(): void;
  getPrimaryService(uuid: string): Promise<{ getCharacteristic(uuid: string): Promise<CaracteristicaBt> }>;
}
export interface DispositivoBt { id: string; name?: string; gatt?: ServidorGatt; }
interface BluetoothNavegador {
  getAvailability?(): Promise<boolean>;
  requestDevice(opciones: unknown): Promise<DispositivoBt>;
}

function bluetooth(): BluetoothNavegador | null {
  return typeof navigator !== 'undefined' ? ((navigator as unknown as { bluetooth?: BluetoothNavegador }).bluetooth ?? null) : null;
}

export async function webBluetoothDisponible(): Promise<boolean> {
  const bt = bluetooth();
  if (!bt) return false;
  try { return bt.getAvailability ? await bt.getAvailability() : true; } catch { return true; }
}

/** Abre el selector del navegador (filtra por el servicio del Smart Tag o el prefijo SEPPRO). */
export async function elegirSmartTag(): Promise<DispositivoBt> {
  const bt = bluetooth();
  if (!bt) throw new Error('Este navegador no tiene Bluetooth');
  return bt.requestDevice({
    filters: [{ services: [SERVICIO_TAG] }, { namePrefix: 'SEPPRO' }],
    optionalServices: [SERVICIO_TAG],
  });
}

/** Conexión GATT + lectura de la característica, con tiempo límite de 15 s. */
export class LectorWebBluetooth implements LectorTelemetria {
  constructor(private readonly dispositivo: DispositivoBt, private readonly limiteMs = 15_000) {}

  async leer(): Promise<Telemetria> {
    const gatt = this.dispositivo.gatt;
    if (!gatt) throw new Error('El dispositivo no admite GATT');
    let reloj: ReturnType<typeof setTimeout> | undefined;
    const limite = new Promise<never>((_, falla) => {
      reloj = setTimeout(() => {
        const e = new Error('El Smart Tag no respondió');
        e.name = 'TimeoutError';
        falla(e);
      }, this.limiteMs);
    });
    try {
      const lectura = (async () => {
        const servidor = await gatt.connect();
        const servicio = await servidor.getPrimaryService(SERVICIO_TAG)
          .catch(() => { throw new Error('Este dispositivo no es un Smart Tag SEPPRO'); });
        const caracteristica = await servicio.getCharacteristic(CARACTERISTICA_TAG);
        return leerTelemetria(await caracteristica.readValue());
      })();
      return await Promise.race([lectura, limite]);
    } finally {
      clearTimeout(reloj);
      try { if (gatt.connected) gatt.disconnect(); } catch { /* ya desconectado */ }
    }
  }
}
