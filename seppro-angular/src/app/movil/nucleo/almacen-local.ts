/**
 * Persistencia local de la PWA (equivalente a SharedPreferences "seppro_prefs" del teléfono).
 * Nunca se guarda la contraseña ni el token: solo preferencias.
 *  - último correo usado
 *  - último Smart Tag elegido
 *  - "Limpiar historial" del chat (solo oculta mensajes en este dispositivo)
 *  - Modo sin conexión (rúbrica C3): el perfil (nombre y rol) y la última lista de pedidos
 *    del usuario, para abrir la app sin Internet. Se borran al cerrar sesión.
 */
export interface Almacen {
  getItem(clave: string): string | null;
  setItem(clave: string, valor: string): void;
  removeItem(clave: string): void;
}

const PREFIJO = 'seppro_prefs.';

export interface PerfilGuardado { uid: string; nombre: string; rol: string; email: string }
/** Forma de lo que se lee de localStorage: todo se valida antes de usarse. */
interface DatoGuardado {
  uid?: unknown; nombre?: unknown; rol?: unknown; email?: unknown; pedidos?: unknown; total?: unknown; fecha?: unknown;
}
export interface CopiaPedidos<T> { pedidos: T[]; total: number | null; fecha: number }

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

  // ===== Modo sin conexión =====

  get perfilGuardado(): PerfilGuardado | null {
    const j = this.json(this.leer('perfil'));
    return j && typeof j.uid === 'string' && typeof j.nombre === 'string' && typeof j.rol === 'string'
      ? { uid: j.uid, nombre: j.nombre, rol: j.rol, email: typeof j.email === 'string' ? j.email : '' } : null;
  }
  set perfilGuardado(p: PerfilGuardado | null) {
    this.escribir('perfil', p ? JSON.stringify({ uid: p.uid, nombre: p.nombre, rol: p.rol, email: p.email }) : null);
  }

  /** Última lista de pedidos descargada (solo si es del mismo usuario). */
  pedidosGuardados<T>(uid: string, filtro: string): CopiaPedidos<T> | null {
    const j = this.json(this.leer(`cache_pedidos_${filtro}`));
    if (!j || j.uid !== uid || !Array.isArray(j.pedidos) || typeof j.fecha !== 'number') return null;
    return { pedidos: j.pedidos as T[], total: typeof j.total === 'number' ? j.total : null, fecha: j.fecha as number };
  }
  guardarPedidos<T>(uid: string, filtro: string, pedidos: T[], total: number | null, ahora = Date.now()): void {
    this.escribir(`cache_pedidos_${filtro}`, JSON.stringify({ uid, pedidos: pedidos.slice(0, 50), total, fecha: ahora }));
  }

  /** Al cerrar sesión no queda nada del usuario en el dispositivo. */
  borrarDatosDeSesion(): void {
    for (const clave of ['perfil', 'cache_pedidos_activos', 'cache_pedidos_historial']) this.escribir(clave, null);
  }

  private json(crudo: string | null): DatoGuardado | null {
    if (!crudo) return null;
    try { const j = JSON.parse(crudo); return j && typeof j === 'object' && !Array.isArray(j) ? (j as DatoGuardado) : null; } catch { return null; }
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
