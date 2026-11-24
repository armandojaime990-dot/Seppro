import { Injectable } from '@angular/core';
import type { RealtimeChannel, SupabaseClient } from '@supabase/supabase-js';
import { SupabaseService } from '../services/supabase.service';
import { CERRADOS, Rol } from './nucleo/estados';
import { MENSAJE_GENERICO } from './nucleo/validacion-login';
import { conReintento, ErrorDelServidor, ErrorDeRed } from './nucleo/reintento';
import { nombreDispositivo } from './nucleo/fechas';
import { Telemetria } from './nucleo/telemetria';
import { RepositorioVerificacion, ResultadoAlerta, ResultadoVerificacion } from './verificacion.store';

export interface Perfil { uid: string; nombre: string; rol: Rol; email: string; }
export interface PedidoResumen {
  id: number; total: number; estado: string; created_at: string;
  pedido_items: { nombre_producto: string }[] | null;
}
export interface Reporte {
  id: number; folio: string; tipo: string; pedido_id: number | null; equipo: string | null;
  descripcion: string; prioridad: string; estado: string; cliente_id: string; agente_id: string | null;
  para_admin: boolean; created_at: string; updated_at: string;
  cliente?: { nombre: string } | null; agente?: { nombre: string } | null;
}
export interface Mensaje {
  id: number; reporte_id: number; emisor_id: string; emisor_nombre: string | null; emisor_rol: string | null;
  dispositivo: string | null; texto: string; adjunto_path: string | null; created_at: string;
}
export interface AgenteEstado { id: string; nombre: string; rol: string; en_linea: boolean; reportes_activos: number; }
export interface Garantia { folio: string; pedido_id: number; inicio: string; vence: string; correo_enviado: boolean; producto: string | null; }

/** El token ya no es válido: hay que volver a iniciar sesión. */
export class SesionExpiradaError extends Error {
  constructor() { super('Tu sesión expiró. Inicia sesión de nuevo.'); this.name = 'SesionExpiradaError'; }
}
/** Otra persona modificó el reporte entre que se leyó y se guardó (bloqueo optimista, Práctica 12). */
export class ConflictoError extends Error {
  constructor() { super('Otra persona modificó el reporte'); this.name = 'ConflictoError'; }
}

export const TAMANO_PAGINA = 20;
const CAMPOS_PEDIDO = 'id,total,estado,created_at,pedido_items(nombre_producto)';
const SELECT_REPORTE =
  '*,cliente:perfiles!reportes_cliente_id_fkey(nombre),agente:perfiles!reportes_agente_id_fkey(nombre)';
export const BUCKET = 'evidencias';
export const URL_FIRMADA_SEGUNDOS = 600;

/** Traduce la respuesta de supabase-js a datos o a un error tipado (red / servidor / sesión). */
export function interpretar<T>(r: { data: T | null; error: { message?: string; code?: string } | null; status?: number }): T {
  if (!r.error) return r.data as T;
  const msg = r.error.message ?? '';
  const status = r.status ?? 0;
  if (status === 401 || /jwt.*expired/i.test(msg)) throw new SesionExpiradaError();
  if (status === 0 || /failed to fetch|network|load failed|fetch failed/i.test(msg)) throw new ErrorDeRed();
  throw new ErrorDelServidor(status || 400, msg || `Error del servidor (${status})`);
}

/**
 * Acceso a Supabase para la app móvil web (PWA). Mismas consultas que la app Kotlin.
 * Lo que cada rol puede ver lo decide el servidor (RLS), no la app.
 */
@Injectable({ providedIn: 'root' })
export class MovilApi implements RepositorioVerificacion {
  perfil: Perfil | null = null;

  constructor(private readonly sb: SupabaseService) {}

  get client(): SupabaseClient { return this.sb.client; }
  get uid(): string { return this.perfil?.uid ?? ''; }

  // ===== Sesión =====

  async iniciarSesion(email: string, password: string): Promise<Perfil> {
    const { data, error } = await this.client.auth.signInWithPassword({ email, password });
    if (error) {
      if (/fetch|network/i.test(error.message)) throw new ErrorDeRed();
      throw new Error(MENSAJE_GENERICO); // no se revela si falló el correo o la contraseña
    }
    const perfil = await this.cargarPerfil(data.user.id, data.user.email ?? email);
    if (!perfil) {
      await this.client.auth.signOut();
      throw new Error('Tu cuenta está deshabilitada. Contacta al administrador.');
    }
    return perfil;
  }

  /** Lee nombre, rol y habilitado. Devuelve null si la cuenta está deshabilitada. */
  async cargarPerfil(uid?: string, email?: string): Promise<Perfil | null> {
    if (!uid) {
      const { data } = await this.client.auth.getSession();
      if (!data.session) return (this.perfil = null);
      uid = data.session.user.id;
      email = data.session.user.email ?? '';
    }
    const filas = interpretar(await this.client.from('perfiles').select('nombre,rol,habilitado').eq('id', uid).limit(1));
    const p = (filas as { nombre: string; rol: Rol; habilitado: boolean }[])[0];
    if (p && p.habilitado === false) return (this.perfil = null);
    return (this.perfil = { uid, nombre: p?.nombre ?? '', rol: p?.rol ?? 'usuario', email: email ?? '' });
  }

  async cerrarSesion(): Promise<void> {
    try { await this.client.rpc('desconectar'); } catch { /* sin conexión: se cierra igual */ }
    await this.client.auth.signOut();
    this.perfil = null;
  }

  async recuperarAcceso(email: string): Promise<void> {
    await this.client.auth.resetPasswordForEmail(email); // mismo mensaje exista o no la cuenta
  }

  async latido(): Promise<void> { await this.client.rpc('latido'); }

  // ===== Pedidos (Práctica 12 O1: páginas de 20, filtro en el servidor) =====

  async paginaPedidos(filtro: 'activos' | 'historial', offset: number, limite = TAMANO_PAGINA)
    : Promise<{ pedidos: PedidoResumen[]; total: number | null }> {
    const lista = `(${CERRADOS.join(',')})`;
    let q = this.client.from('pedidos').select(CAMPOS_PEDIDO, { count: 'exact' }).eq('usuario_id', this.uid);
    q = filtro === 'activos' ? q.not('estado', 'in', lista) : q.in('estado', [...CERRADOS]);
    const r = await q.order('created_at', { ascending: false }).order('id', { ascending: false })
      .range(offset, offset + limite - 1);
    return { pedidos: interpretar(r) as PedidoResumen[], total: r.count ?? null };
  }

  async pedidosParaSelector(): Promise<PedidoResumen[]> {
    return interpretar(await this.client.from('pedidos').select(CAMPOS_PEDIDO).eq('usuario_id', this.uid)
      .order('created_at', { ascending: false }).limit(100)) as PedidoResumen[];
  }

  async pedido(id: number): Promise<PedidoResumen | null> {
    const filas = interpretar(await this.client.from('pedidos').select(CAMPOS_PEDIDO).eq('id', id).limit(1)) as PedidoResumen[];
    return filas[0] ?? null;
  }

  async garantia(pedidoId: number): Promise<Garantia | null> {
    const filas = interpretar(await this.client.from('garantias')
      .select('folio,pedido_id,inicio,vence,correo_enviado,producto').eq('pedido_id', pedidoId).limit(1)) as Garantia[];
    return filas[0] ?? null;
  }

  // ===== Verificación (RepositorioVerificacion) =====

  async verificar(pedidoId: number, t: Telemetria, sensor: string | null): Promise<ResultadoVerificacion> {
    const r = interpretar(await this.client.rpc('verificar_entrega',
      { p_pedido: pedidoId, p_paquete: t.paqueteId, p_estatus: t.estatus, p_sensor: sensor })) as Record<string, unknown>;
    return {
      pedidoId: Number(r['pedido_id']), estado: String(r['estado']), golpe: Boolean(r['golpe']),
      garantiaFolio: (r['garantia_folio'] as string) ?? null, garantiaVence: (r['garantia_vence'] as string) ?? null,
      repetido: Boolean(r['repetido']),
    };
  }

  async resolverAlerta(pedidoId: number, accion: 'aceptar' | 'rechazar', motivo: string | null): Promise<ResultadoAlerta> {
    const r = interpretar(await this.client.rpc('resolver_alerta',
      { p_pedido: pedidoId, p_accion: accion, p_motivo: motivo })) as Record<string, unknown>;
    return {
      pedidoId: Number(r['pedido_id']), estado: String(r['estado']),
      reporteId: (r['reporte_id'] as number) ?? null, reporteFolio: (r['reporte_folio'] as string) ?? null,
    };
  }

  async enviarCertificado(pedidoId: number): Promise<boolean> {
    try {
      const { error } = await this.client.functions.invoke('enviar-certificado', { body: { pedido_id: pedidoId } });
      return !error;
    } catch {
      return false; // función no desplegada o sin correo configurado: la garantía ya existe
    }
  }

  // ===== Soporte (Práctica 7) =====

  async reportes(): Promise<Reporte[]> {
    return interpretar(await this.client.from('reportes').select(SELECT_REPORTE).order('updated_at', { ascending: false })) as Reporte[];
  }

  async reporte(id: number): Promise<Reporte | null> {
    const filas = interpretar(await this.client.from('reportes').select(SELECT_REPORTE).eq('id', id).limit(1)) as Reporte[];
    return filas[0] ?? null;
  }

  async crearReporte(d: { tipo: string; pedidoId: number | null; equipo: string; descripcion: string; prioridad: string; paraAdmin: boolean }): Promise<Reporte> {
    const filas = interpretar(await this.client.from('reportes').insert({
      tipo: d.tipo, pedido_id: d.pedidoId, equipo: d.equipo.trim() || null, descripcion: d.descripcion.trim(),
      prioridad: d.prioridad, para_admin: d.paraAdmin,
    }).select('id')) as { id: number }[];
    const creado = await this.reporte(filas[0].id);
    if (!creado) throw new ErrorDelServidor(404, 'No se pudo leer el reporte creado');
    return creado;
  }

  /** Bloqueo optimista: solo guarda si updated_at sigue siendo el leído. */
  async actualizarReporte(id: number, cambios: Partial<Reporte>, updatedAtLeido: string): Promise<void> {
    const filas = interpretar(await this.client.from('reportes').update(cambios)
      .eq('id', id).eq('updated_at', updatedAtLeido).select('id')) as unknown[];
    if (filas.length === 0) throw new ConflictoError();
  }

  async agentes(): Promise<AgenteEstado[]> {
    return interpretar(await this.client.rpc('agentes_estado')) as AgenteEstado[];
  }

  async reasignar(reporteId: number, agenteId: string | null): Promise<void> {
    interpretar(await this.client.rpc('reasignar_reporte', { p_reporte: reporteId, p_agente: agenteId }));
  }

  async mensajes(reporteId: number): Promise<Mensaje[]> {
    return interpretar(await this.client.from('mensajes').select('*').eq('reporte_id', reporteId)
      .order('id', { ascending: true })) as Mensaje[];
  }

  /** Práctica 12 (O3): envío idempotente con reintento y espera progresiva. */
  async enviarMensaje(reporteId: number, texto: string, adjunto: string | null = null,
                      idCliente: string = crypto.randomUUID()): Promise<Mensaje> {
    const cuerpo = { reporte_id: reporteId, texto, dispositivo: nombreDispositivo(), adjunto_path: adjunto, id_cliente: idCliente };
    return conReintento<Mensaje>(
      async () => (interpretar(await this.client.from('mensajes').insert(cuerpo).select('*')) as Mensaje[])[0],
      async () => (interpretar(await this.client.from('mensajes').select('*').eq('id_cliente', idCliente).limit(1)) as Mensaje[])[0] ?? null,
    );
  }

  async subirEvidencia(reporteId: number, jpeg: Blob): Promise<string> {
    const ruta = `${reporteId}/${crypto.randomUUID()}.jpg`;
    const { error } = await this.client.storage.from(BUCKET).upload(ruta, jpeg, { contentType: 'image/jpeg', upsert: false });
    if (error) throw new ErrorDelServidor(400, error.message);
    return ruta;
  }

  async urlFirmada(ruta: string): Promise<string> {
    const { data, error } = await this.client.storage.from(BUCKET).createSignedUrl(ruta, URL_FIRMADA_SEGUNDOS);
    if (error || !data?.signedUrl) throw new ErrorDelServidor(400, 'No se pudo generar el enlace');
    return data.signedUrl;
  }

  /** Chat en tiempo real (Supabase Realtime), igual que RealtimeCanal.kt. Devuelve cómo cerrarlo. */
  escucharReporte(reporteId: number, eventos: {
    mensaje: (m: Mensaje) => void; reporte: () => void; conexion: (conectado: boolean) => void;
  }): () => void {
    const canal: RealtimeChannel = this.client.channel(`reporte-${reporteId}-${Date.now()}`)
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'mensajes', filter: `reporte_id=eq.${reporteId}` },
        (p) => eventos.mensaje(p.new as Mensaje))
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'reportes', filter: `id=eq.${reporteId}` },
        () => eventos.reporte())
      .subscribe((estado) => eventos.conexion(estado === 'SUBSCRIBED'));
    return () => { void this.client.removeChannel(canal); };
  }

  /** Cambios en mis pedidos (sincronización con la tienda web y el reloj). */
  escucharPedidos(alCambiar: () => void): () => void {
    const canal = this.client.channel(`pedidos-${this.uid}-${Date.now()}`)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'pedidos', filter: `usuario_id=eq.${this.uid}` }, () => alCambiar())
      .subscribe();
    return () => { void this.client.removeChannel(canal); };
  }
}
