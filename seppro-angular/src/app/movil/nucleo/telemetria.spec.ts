import {
  codificarTelemetria, correspondeAPedido, leerTelemetria, numeroDePaquete, TelemetriaInvalidaError,
} from './telemetria';

const txt = (s: string) => new TextEncoder().encode(s);

/** Prácticas 13 y 14 — AUT-01 (unitarias): telemetría del Smart Tag en la PWA (mismo protocolo que Kotlin). */
describe('leerTelemetria (AUT-01)', () => {
  it('entrada válida en texto y binario -> resultado esperado', () => {
    expect(leerTelemetria(txt('SEPPRO-42;0'))).toEqual({ paqueteId: 'SEPPRO-42', estatus: 0 });
    expect(leerTelemetria(txt(' seppro-42 , 1 \n'))).toEqual({ paqueteId: 'SEPPRO-42', estatus: 1 });
    // binario: pedido 59 (0x3B = ';') no debe confundirse con texto
    expect(leerTelemetria(new Uint8Array([0x3b, 0, 0, 0, 1]))).toEqual({ paqueteId: 'SEPPRO-59', estatus: 1 });
    // también acepta el DataView que devuelve Web Bluetooth (readValue)
    expect(leerTelemetria(new DataView(new Uint8Array([0x2c, 0x01, 0, 0, 0]).buffer))).toEqual({ paqueteId: 'SEPPRO-300', estatus: 0 });
  });

  it('entrada vacía o nula -> TelemetriaInvalidaError', () => {
    expect(() => leerTelemetria(null)).toThrowError(TelemetriaInvalidaError, 'El Smart Tag no envió datos');
    expect(() => leerTelemetria(undefined)).toThrowError(TelemetriaInvalidaError);
    expect(() => leerTelemetria(new Uint8Array())).toThrowError(TelemetriaInvalidaError);
    expect(() => leerTelemetria(txt(';'))).toThrowError(TelemetriaInvalidaError);
  });

  it('valores fuera de rango -> TelemetriaInvalidaError', () => {
    expect(() => leerTelemetria(txt('SEPPRO-4;2'))).toThrowError(/fuera de rango/);
    expect(() => leerTelemetria(txt('SEPPRO-4;-1'))).toThrowError(/fuera de rango/);
    expect(() => leerTelemetria(new Uint8Array([0, 0, 0, 0, 0]))).toThrowError(/fuera de rango/); // pedido 0
    expect(() => leerTelemetria(new Uint8Array(65).fill(65))).toThrowError(/demasiado larga/);
  });

  it('formatos inválidos -> excepción con mensaje', () => {
    expect(() => leerTelemetria(txt('hola'))).toThrowError(/PAQUETE;ESTATUS/);
    expect(() => leerTelemetria(txt('<script>;0'))).toThrowError('Identificador de paquete inválido');
    expect(() => leerTelemetria(txt('SEPPRO-4;x'))).toThrowError('El estatus no es un número');
    expect(() => leerTelemetria(new Uint8Array([1, 2, 3]))).toThrowError(/5 bytes/);
  });

  it('el sensor debe ser el del pedido y codificar es reversible', () => {
    expect(correspondeAPedido({ paqueteId: 'SEPPRO-0042', estatus: 0 }, 42)).toBeTrue();
    expect(correspondeAPedido({ paqueteId: 'SEPPRO-43', estatus: 0 }, 42)).toBeFalse();
    expect(correspondeAPedido({ paqueteId: 'SEPPRO-42', estatus: 0 }, 0)).toBeFalse();
    expect(numeroDePaquete('SEPPRO-')).toBeNull();
    const t = { paqueteId: 'SEPPRO-3005', estatus: 1 };
    expect(leerTelemetria(codificarTelemetria(t))).toEqual(t);
  });
});
