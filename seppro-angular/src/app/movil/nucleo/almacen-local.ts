/**
 * Persistencia local de la PWA (equivalente a SharedPreferences "seppro_prefs" del teléfono).
 * Nunca se guarda la contraseña ni el token: solo preferencias.
 *  - último correo usado
 *  - último Smart Tag elegido
 *  - "Limpiar historial" del chat (solo oculta mensajes en este dispositivo)
 */
export interface Almacen {
  getItem(clave: string): string | null;
  setItem(clave: string, valor: string): void;
  removeItem(clave: string): void;
}

const PREFIJO = 'seppro_prefs.';

/** localStorage puede lanzar en modo privado de Safari: se trata como vacío. */
function seguro<T>(f: () => T, porDefecto: T): T {
  try { return f(); } catch { return porDefecto; }
}

export class AlmacenLocal {
  constructor(private readonly almacen: Almacen | null = typeof localStorage !== 'undefined' ? localStorage : null) {}

  private leer(clave: string): string | null {
    return seguro(() => this.almacen?.getItem(PREFIJO + clave) ?? null, null);
  }

  private escribir(clave: string, valor: string | null): void {
    seguro(() => {
      if (valor === null) this.almacen?.removeItem(PREFIJO + clave);
      else this.almacen?.setItem(PREFIJO + clave, valor);
    }, undefined);
  }

  get ultimoEmail(): string | null { return this.leer('ultimo_email'); }
  set ultimoEmail(email: string | null) { this.escribir('ultimo_email', email ? email.slice(0, 254) : null); }

  get ultimoDispositivo(): { nombre: string; id: string } | null {
    const crudo = this.leer('ultimo_dispositivo');
    if (!crudo) return null;
    try {
      const j = JSON.parse(crudo);
      return typeof j?.nombre === 'string' && typeof j?.id === 'string' ? { nombre: j.nombre, id: j.id } : null;
    } catch {
      return null; // dato dañado: se ignora
    }
  }
  set ultimoDispositivo(d: { nombre: string; id: string } | null) {
    this.escribir('ultimo_dispositivo', d ? JSON.stringify({ nombre: d.nombre, id: d.id }) : null);
  }

  /** Último id de mensaje oculto con "Limpiar historial" (por usuario y reporte). */
  limpiadoHasta(uid: string, reporteId: number): number {
    const n = Number(this.leer(`limpiado_${uid}_${reporteId}`));
    return Number.isFinite(n) && n > 0 ? n : 0;
  }
  marcarLimpiado(uid: string, reporteId: number, hastaId: number): void {
    this.escribir(`limpiado_${uid}_${reporteId}`, hastaId > 0 ? String(hastaId) : null);
  }
}
