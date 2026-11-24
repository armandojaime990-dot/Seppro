/**
 * Práctica 12 (O3) en la PWA: envío idempotente con reintento controlado.
 *  - Mismo id_cliente (UUID) en todos los reintentos: el índice UNIQUE de mensajes.id_cliente
 *    responde 409 si el servidor ya lo había guardado, y se recupera el existente.
 *  - Solo se reintenta ante fallas de RED; los errores del servidor se muestran.
 *  - Espera progresiva (exponential backoff): 500 ms, 1 s, 2 s... (tope 4 s) + 0–250 ms al azar.
 */
export const MAX_INTENTOS = 3;

export function esperaReintentoMs(intento: number, azar = Math.floor(Math.random() * 250)): number {
  const n = Math.min(Math.max(intento - 1, 0), 3);
  return (500 << n) + azar;
}

/** Error de red (sin respuesta del servidor): se puede reintentar. */
export class ErrorDeRed extends Error {
  constructor(mensaje = 'Sin conexión') {
    super(mensaje);
    this.name = 'ErrorDeRed';
  }
}

/** Error que respondió el servidor (RLS, trigger, validación): no se reintenta. */
export class ErrorDelServidor extends Error {
  constructor(public readonly codigo: number, mensaje: string) {
    super(mensaje);
    this.name = 'ErrorDelServidor';
  }
}

export interface OpcionesReintento {
  intentos?: number;
  dormir?: (ms: number) => Promise<void>;
  alFallar?: (intento: number, error: unknown) => void;
}

/**
 * Ejecuta `operacion` hasta `intentos` veces mientras falle por red.
 * Si responde 409 (ya existía) llama a `recuperar` en lugar de duplicar.
 */
export async function conReintento<T>(
  operacion: () => Promise<T>,
  recuperar: () => Promise<T | null>,
  opciones: OpcionesReintento = {},
): Promise<T> {
  const intentos = opciones.intentos ?? MAX_INTENTOS;
  const dormir = opciones.dormir ?? ((ms) => new Promise<void>((ok) => setTimeout(ok, ms)));
  let ultimo: unknown = new ErrorDeRed();
  for (let intento = 1; intento <= intentos; intento++) {
    try {
      return await operacion();
    } catch (e) {
      if (e instanceof ErrorDelServidor) {
        if (e.codigo === 409) {
          const existente = await recuperar();
          if (existente) return existente;
        }
        throw e;
      }
      ultimo = e;
      opciones.alFallar?.(intento, e);
      if (intento < intentos) await dormir(esperaReintentoMs(intento));
    }
  }
  throw ultimo;
}
