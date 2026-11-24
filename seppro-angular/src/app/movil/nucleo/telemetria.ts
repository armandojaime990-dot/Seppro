/**
 * Protocolo GATT del Smart Tag SEPPRO — mismo que TelemetriaTag.kt del teléfono.
 *   Servicio:       5e1a0001-7c3b-4b8e-9a51-5e9970000001
 *   Característica: 5e1a0002-7c3b-4b8e-9a51-5e9970000001
 * Valor: texto "SEPPRO-42;0" (también "," o "|") o binario de 5 bytes
 * (uint32 little-endian con el pedido + 1 byte de estatus). 0 = sin incidentes, 1 = golpe fuerte.
 */
export const SERVICIO_TAG = '5e1a0001-7c3b-4b8e-9a51-5e9970000001';
export const CARACTERISTICA_TAG = '5e1a0002-7c3b-4b8e-9a51-5e9970000001';
export const ESTATUS_SIN_INCIDENTES = 0;
export const ESTATUS_GOLPE = 1;
export const MAX_BYTES = 64;

export interface Telemetria { paqueteId: string; estatus: number; }

export class TelemetriaInvalidaError extends Error {
  constructor(mensaje: string) {
    super(mensaje);
    this.name = 'TelemetriaInvalidaError';
  }
}

const PATRON_PAQUETE = /^(SEPPRO-?)?\d{1,9}$/i;

export const esGolpe = (t: Telemetria) => t.estatus === ESTATUS_GOLPE;

function validarEstatus(estatus: number): number {
  if (estatus !== ESTATUS_SIN_INCIDENTES && estatus !== ESTATUS_GOLPE) {
    throw new TelemetriaInvalidaError(`Estatus fuera de rango (${estatus}): debe ser 0 o 1`);
  }
  return estatus;
}

/** Convierte los bytes leídos por GATT en Telemetria o lanza TelemetriaInvalidaError. */
export function leerTelemetria(valor: Uint8Array | DataView | null | undefined): Telemetria {
  const bytes = valor instanceof DataView
    ? new Uint8Array(valor.buffer, valor.byteOffset, valor.byteLength)
    : valor;
  if (!bytes || bytes.length === 0) throw new TelemetriaInvalidaError('El Smart Tag no envió datos');
  if (bytes.length > MAX_BYTES) throw new TelemetriaInvalidaError('La lectura del Smart Tag es demasiado larga');

  // Texto = solo ASCII imprimible (en binario el byte de estatus 0/1 nunca lo es)
  const esTexto = bytes.every((b) => (b >= 0x20 && b <= 0x7e) || b === 0x0a || b === 0x0d);
  if (esTexto) {
    const texto = String.fromCharCode(...bytes).trim();
    if (!/[;,|]/.test(texto)) throw new TelemetriaInvalidaError('Formato inválido: se esperaba PAQUETE;ESTATUS');
    const partes = texto.split(/[;,|]/).map((p) => p.trim());
    if (partes.length !== 2) throw new TelemetriaInvalidaError('Formato inválido: se esperaba PAQUETE;ESTATUS');
    if (!PATRON_PAQUETE.test(partes[0])) throw new TelemetriaInvalidaError('Identificador de paquete inválido');
    if (!/^-?\d+$/.test(partes[1])) throw new TelemetriaInvalidaError('El estatus no es un número');
    return { paqueteId: partes[0].toUpperCase(), estatus: validarEstatus(Number(partes[1])) };
  }

  if (bytes.length !== 5) throw new TelemetriaInvalidaError('Formato binario inválido: se esperaban 5 bytes');
  const pedido = new DataView(bytes.buffer, bytes.byteOffset, 5).getInt32(0, true);
  if (pedido <= 0) throw new TelemetriaInvalidaError('Identificador de paquete fuera de rango');
  return { paqueteId: `SEPPRO-${pedido}`, estatus: validarEstatus(bytes[4]) };
}

/** "SEPPRO-0042" / "seppro42" / "42" -> 42 (igual que normalizar_paquete en Supabase). */
export function numeroDePaquete(paqueteId: string): number | null {
  const limpio = paqueteId.trim().toUpperCase().replace(/^SEPPRO-?/, '').replace(/^0+/, '');
  return /^\d{1,9}$/.test(limpio) ? Number(limpio) : null;
}

/** ¿El Smart Tag leído pertenece a este pedido? (evita verificar la caja equivocada) */
export function correspondeAPedido(t: Telemetria, pedidoId: number): boolean {
  return pedidoId > 0 && numeroDePaquete(t.paqueteId) === pedidoId;
}

export function codificarTelemetria(t: Telemetria): Uint8Array {
  return new TextEncoder().encode(`${t.paqueteId};${t.estatus}`);
}
