/**
 * Estados del pedido y del soporte. Mismos valores, textos, íconos y colores que la app Kotlin
 * (Estados.kt / SoporteApi.kt), el reloj y Supabase (pedidos_estado_check).
 */
export const COLOR_PRIMARY = '#003D7A';
export const COLOR_ACCENT = '#0072C6';
export const COLOR_ERROR = '#EF5350';

/** Pedidos cerrados: van al Historial; el resto se muestra en Pedidos. */
export const CERRADOS = ['entregado', 'entregado_verificado', 'entregado_con_danos', 'rechazado'] as const;

/** Estados en los que el sensor detectó un problema: se puede abrir un reporte de falla. */
export const CON_DANO = ['entregado_con_danos', 'alerta_integridad'] as const;

const ETIQUETAS: Record<string, string> = {
  pendiente: 'Pendiente', pagado: 'Pagado', enviado: 'Enviado', en_camino: 'En Camino', entregado: 'Entregado',
  en_puerta: 'En puerta (listo para verificar)', entregado_verificado: 'Entregado y verificado',
  entregado_con_danos: 'Entregado con daños', alerta_integridad: 'Alerta de integridad', rechazado: 'Equipo rechazado',
};
const ICONOS: Record<string, string> = {
  pagado: '💳', enviado: '📦', en_camino: '🚚', entregado: '✅', en_puerta: '📍',
  entregado_verificado: '✅', entregado_con_danos: '⚠️', alerta_integridad: '🚨', rechazado: '⛔',
};
const COLORES: Record<string, string> = {
  pagado: '#4CAF50', enviado: '#2196F3', en_camino: '#9C27B0', entregado: '#0072C6', en_puerta: '#009688',
  entregado_verificado: '#2E7D32', entregado_con_danos: '#EF5350', alerta_integridad: '#EF5350', rechazado: '#6D4C41',
};

export const etiquetaEstado = (e: string) => ETIQUETAS[e] ?? e;
export const iconoEstado = (e: string) => ICONOS[e] ?? '🕒';
export const colorEstado = (e: string) => COLORES[e] ?? '#FFA000';
export const esCerrado = (e: string) => (CERRADOS as readonly string[]).includes(e);
export const tieneDano = (e: string) => (CON_DANO as readonly string[]).includes(e);

/** Fondo translúcido del color (igual que fondoRedondeado con alfa 0x1F en Kotlin). */
export const fondoSuave = (color: string) => color + '1F';

/** "Bomba X" o "Bomba X y 2 más" (igual que la tarjeta del teléfono y el reloj). */
export function nombrePedido(p: { id: number; pedido_items?: { nombre_producto: string }[] | null }): string {
  const items = p.pedido_items ?? [];
  if (items.length === 0) return `Pedido #${p.id}`;
  return items.length > 1 ? `${items[0].nombre_producto} y ${items.length - 1} más` : items[0].nombre_producto;
}

// ===== Roles (perfiles.rol) =====
export type Rol = 'admin' | 'agente' | 'usuario' | 'invitado';
export const esStaff = (rol: string) => rol === 'admin' || rol === 'agente';
export function etiquetaRol(rol: string): string {
  return rol === 'admin' ? 'Administrador' : rol === 'agente' ? 'Agente' : rol === 'invitado' ? 'Invitado' : 'Cliente';
}

// ===== Soporte (reportes) =====
export const TIPOS_REPORTE = ['falla', 'seguimiento', 'acceso'] as const;
export const PRIORIDADES = ['baja', 'media', 'alta'] as const;
export const ESTADOS_REPORTE = ['abierto', 'en_proceso', 'resuelto', 'cerrado'] as const;

export function etiquetaTipo(t: string): string {
  return t === 'falla' ? 'Reporte de falla' : t === 'seguimiento' ? 'Seguimiento de entrega'
    : t === 'acceso' ? 'Recuperación de acceso' : t;
}
export function etiquetaEstadoReporte(e: string): string {
  return ({ abierto: 'Abierto', en_proceso: 'En proceso', resuelto: 'Resuelto', cerrado: 'Cerrado' } as Record<string, string>)[e] ?? e;
}
export function colorEstadoReporte(e: string): string {
  return ({ abierto: '#FFA000', en_proceso: '#2196F3', resuelto: '#2E7D32' } as Record<string, string>)[e] ?? '#64748B';
}
export const etiquetaPrioridad = (p: string) => (p === 'alta' ? 'Alta' : p === 'baja' ? 'Baja' : 'Media');
export const colorPrioridad = (p: string) => (p === 'alta' ? COLOR_ERROR : p === 'baja' ? '#64748B' : '#FFA000');
export const reporteActivo = (estado: string) => estado === 'abierto' || estado === 'en_proceso';

/** Validación del formulario de reporte (mismas reglas que NuevoReporteActivity y el CHECK de la BD). */
export const MIN_DESC = 10;
export const MAX_DESC = 1000;
export function validarReporte(d: { tipo: string; descripcion: string; equipo: string; pedidoId: number | null }): string | null {
  const desc = (d.descripcion ?? '').trim();
  if (!desc) return 'Escribe una descripción';
  if (desc.length < MIN_DESC) return `La descripción debe tener al menos ${MIN_DESC} caracteres`;
  if (desc.length > MAX_DESC) return `La descripción no puede pasar de ${MAX_DESC} caracteres`;
  if (d.tipo === 'falla' && !(d.equipo ?? '').trim()) return 'Indica el equipo dañado';
  if (d.tipo === 'falla' && !d.pedidoId) return 'Elige el pedido del equipo dañado';
  return null;
}
