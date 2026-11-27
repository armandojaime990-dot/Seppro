import { Component, ElementRef, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AgenteEstado, ConflictoError, Mensaje, MovilApi, Reporte, SesionExpiradaError, URL_FIRMADA_SEGUNDOS } from './movil-api.service';
import { Toast } from './movil-root.component';
import { AlmacenLocal } from './nucleo/almacen-local';
import {
  colorEstadoReporte, colorPrioridad, ESTADOS_REPORTE, etiquetaEstadoReporte, etiquetaPrioridad,
  etiquetaTipo, fondoSuave, MAX_DESC, MIN_DESC, PRIORIDADES, reporteActivo,
} from './nucleo/estados';
import { horaLocal } from './nucleo/fechas';
import { ErrorDelServidor } from './nucleo/reintento';

type Hoja = null | 'menu' | 'estado' | 'reasignar' | 'editar' | 'cerrar' | 'limpiar';
const MAX_MENSAJE = 1000;

/**
 * Detalle del reporte y chat en tiempo real (igual que ReporteActivity.kt):
 * Realtime, Toast al enviar/recibir, "Limpiar historial" local,
 * fotos con URL firmada que expira, envío idempotente y acciones según el rol.
 */
@Component({
  selector: 'app-movil-reporte',
  standalone: true,
  imports: [FormsModule],
  template: `
    <header class="m-barra">
      <button class="m-icono-btn" type="button" aria-label="Regresar" (click)="regresar()">←</button>
      <div class="m-titulos"><h1>{{ reporte?.folio ?? 'Reporte' }}</h1></div>
      <button class="m-icono-btn" type="button" aria-label="Menú" (click)="hoja = 'menu'">⋮</button>
    </header>

    <div style="padding:12px 12px 0;flex-shrink:0">
      @if (reporte; as r) {
        <div class="m-tarjeta" style="margin-bottom:0;padding:12px 14px">
          <span class="m-insignia" [style.color]="colorEst(r.estado)" [style.background]="fondo(colorEst(r.estado))">{{ estadoTxt(r.estado) }}</span>
          <span class="m-insignia" [style.color]="colorPri(r.prioridad)" [style.background]="fondo(colorPri(r.prioridad))">Prioridad {{ prioridadTxt(r.prioridad) }}</span>
          <h3 style="margin-top:8px">{{ r.folio }} · {{ tipo(r.tipo) }}</h3>
          <p class="m-suave" style="white-space:pre-line;margin:2px 0">{{ detalles(r) }}</p>
          <p style="margin:6px 0 0;font-size:14px">{{ r.descripcion }}</p>
        </div>
        @if (acciones.length) {
          <div class="m-fila" style="margin-top:8px">
            @for (a of acciones; track a.texto) {
              <button class="m-btn m-sec m-chico" type="button" (click)="a.accion()">{{ a.texto }}</button>
            }
          </div>
        }
      }
      <p style="font-size:12px;margin:8px 0 0" [style.color]="conectado ? '#2E7D32' : '#E65100'">
        {{ conectado ? '● En vivo' : '○ Conectando al chat en tiempo real...' }}
      </p>
    </div>

    <div class="m-contenido" #scroll style="padding:4px 12px 12px">
      @for (m of visibles; track m.id) {
        <div class="fila" [class.propio]="m.emisor_id === uid">
          <div class="burbuja" [class.propio]="m.emisor_id === uid" (click)="m.adjunto_path && abrirAdjunto(m.adjunto_path)">
            <small class="quien">{{ m.emisor_id === uid ? 'Tú' : (m.emisor_nombre ?? 'Usuario') }}</small>
            <span>{{ m.texto }}</span>
            @if (m.adjunto_path) { <u class="foto">Ver foto</u> }
            <small class="hora">{{ hora(m.created_at) }}</small>
          </div>
        </div>
      } @empty {
        <p class="m-vacio">{{ limpiadoHasta > 0 ? 'Historial limpiado en este dispositivo.' : 'Aún no hay mensajes. Escribe el primero.' }}</p>
      }
    </div>

    @if (errorEntrada) { <p class="m-error" style="margin:0;padding:0 12px">{{ errorEntrada }}</p> }
    <form class="escritura" (ngSubmit)="enviar()">
      <label class="clip" [class.deshabilitado]="subiendo" aria-label="Adjuntar foto">📎
        <input type="file" accept="image/*" hidden (change)="elegirFoto($event)" [disabled]="subiendo">
      </label>
      <textarea class="m-campo" name="texto" rows="1" [maxlength]="MAX_MENSAJE" placeholder="Escribe un mensaje" [(ngModel)]="texto"></textarea>
      <button class="m-btn m-chico" type="submit" [disabled]="enviando" style="min-height:48px">Enviar</button>
    </form>

    @if (hoja) {
      <div class="m-velo" (click)="hoja = null">
        <div class="m-hoja" (click)="$event.stopPropagation()">
          @switch (hoja) {
            @case ('menu') {
              <button class="m-opcion" type="button" (click)="hoja = null; cargarTodo()">Actualizar</button>
              <button class="m-opcion" type="button" (click)="hoja = 'limpiar'">Limpiar historial</button>
            }
            @case ('limpiar') {
              <h2>Limpiar historial</h2>
              <p class="m-suave">Se ocultarán los mensajes en este dispositivo. En el servidor se conservan como evidencia.</p>
              <div class="m-fila"><button class="m-btn m-sec" type="button" (click)="hoja = null">Cancelar</button>
                <button class="m-btn" type="button" (click)="limpiar()">Limpiar</button></div>
            }
            @case ('estado') {
              <h2>Cambiar estado</h2>
              @for (e of estadosReporte; track e) {
                <button class="m-opcion" type="button" (click)="guardar({ estado: e }, 'Estado actualizado')">{{ estadoTxt(e) }}</button>
              }
            }
            @case ('reasignar') {
              <h2>Reasignar {{ reporte?.folio }}</h2>
              <button class="m-opcion" type="button" (click)="reasignar(null)">👤 Atenderlo yo (administrador)</button>
              @for (a of agentes; track a.id) {
                <button class="m-opcion" type="button" (click)="reasignar(a)">
                  {{ a.en_linea ? '🟢 Conectado' : '⚪ Desconectado' }} · {{ a.nombre }} ({{ a.reportes_activos }} activos)
                </button>
              } @empty { <p class="m-suave">Cargando agentes...</p> }
            }
            @case ('editar') {
              <h2>Editar {{ reporte?.folio }}</h2>
              <label class="m-etiqueta">Descripción</label>
              <textarea class="m-campo" [maxlength]="MAX_DESC" [(ngModel)]="edDescripcion"></textarea>
              <label class="m-etiqueta">Prioridad</label>
              <select class="m-campo" [(ngModel)]="edPrioridad">
                @for (p of prioridades; track p) { <option [value]="p">{{ prioridadTxt(p) }}</option> }
              </select>
              @if (edError) { <p class="m-error">{{ edError }}</p> }
              <div class="m-fila"><button class="m-btn m-sec" type="button" (click)="hoja = null">Cancelar</button>
                <button class="m-btn" type="button" (click)="guardarEdicion()">Guardar</button></div>
            }
            @case ('cerrar') {
              <h2>Cerrar reporte</h2>
              <p class="m-suave">¿Tu problema quedó resuelto? Ya no podrás editar el reporte.</p>
              <div class="m-fila"><button class="m-btn m-sec" type="button" (click)="hoja = null">Cancelar</button>
                <button class="m-btn m-peligro" type="button" (click)="guardar({ estado: 'cerrado' }, 'Reporte cerrado')">Cerrar reporte</button></div>
            }
          }
        </div>
      </div>
    }
  `,
  styles: [`
    .fila { display: flex; justify-content: flex-start; padding: 4px 48px 4px 0; }
    .fila.propio { justify-content: flex-end; padding: 4px 0 4px 48px; }
    .burbuja { background: var(--m-gris); color: var(--m-texto); border-radius: 12px; padding: 8px 12px; display: flex; flex-direction: column; max-width: 100%; word-break: break-word; }
    .burbuja.propio { background: var(--m-accent); color: #fff; }
    .quien { font-size: 11px; font-weight: 700; color: var(--m-suave); }
    .burbuja.propio .quien, .burbuja.propio .hora { color: #DBEAFE; }
    .hora { font-size: 11px; color: var(--m-suave); text-align: right; }
    .foto { font-size: 13px; margin-top: 4px; cursor: pointer; }
    .escritura { display: flex; gap: 6px; align-items: center; padding: 6px 8px calc(env(safe-area-inset-bottom) + 8px); background: #fff; box-shadow: 0 -2px 8px rgba(15,23,42,.08); flex-shrink: 0; }
    .escritura textarea { flex: 1; min-height: 44px; max-height: 120px; resize: none; padding: 10px 12px; }
    .clip { width: 48px; height: 48px; display: grid; place-items: center; background: var(--m-gris); border-radius: 8px; font-size: 20px; cursor: pointer; flex-shrink: 0; }
    .clip.deshabilitado { opacity: .5; }
  `],
})
export class MovilReporteComponent implements OnInit, OnDestroy {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);
  private readonly ruta = inject(ActivatedRoute);
  private readonly toast = inject(Toast);
  private readonly almacen = new AlmacenLocal();
  @ViewChild('scroll') scroll?: ElementRef<HTMLDivElement>;

  readonly uid = this.api.uid;
  readonly estadosReporte = ESTADOS_REPORTE;
  readonly prioridades = PRIORIDADES;
  readonly MAX_MENSAJE = MAX_MENSAJE;
  readonly MAX_DESC = MAX_DESC;
  readonly tipo = etiquetaTipo;
  readonly estadoTxt = etiquetaEstadoReporte;
  readonly colorEst = colorEstadoReporte;
  readonly prioridadTxt = etiquetaPrioridad;
  readonly colorPri = colorPrioridad;
  readonly fondo = fondoSuave;
  readonly hora = (iso: string) => horaLocal(iso);

  reporteId = 0;
  reporte: Reporte | null = null;
  mensajes: Mensaje[] = [];
  limpiadoHasta = 0;
  conectado = false;
  texto = '';
  errorEntrada = '';
  enviando = false;
  subiendo = false;
  hoja: Hoja = null;
  agentes: AgenteEstado[] = [];
  edDescripcion = '';
  edPrioridad = 'media';
  edError = '';
  /** Último envío que falló: se reintenta con el MISMO id_cliente para no duplicar (Práctica 12). */
  private envioPendiente: { texto: string; id: string } | null = null;
  private cerrarCanal?: () => void;

  get visibles(): Mensaje[] { return this.mensajes.filter((m) => m.id > this.limpiadoHasta); }


  get acciones(): { texto: string; accion: () => void }[] {
    const r = this.reporte;
    if (!r) return [];
    const esAdmin = this.api.perfil?.rol === 'admin';
    const lista: { texto: string; accion: () => void }[] = [];
    if (esAdmin || r.agente_id === this.uid) lista.push({ texto: 'Cambiar estado', accion: () => (this.hoja = 'estado') });
    // Las consultas de los agentes las atiende el administrador: no se reasignan
    if (esAdmin && !r.para_admin) lista.push({ texto: 'Reasignar', accion: () => this.abrirReasignar() });
    if (r.cliente_id === this.uid && !esAdmin && reporteActivo(r.estado)) {
      lista.push({ texto: 'Editar', accion: () => this.abrirEditar() });
      lista.push({ texto: 'Cerrar reporte', accion: () => (this.hoja = 'cerrar') });
    }
    return lista;
  }

  ngOnInit(): void {
    this.reporteId = Number(this.ruta.snapshot.paramMap.get('id')) || 0;
    this.limpiadoHasta = this.almacen.limpiadoHasta(this.uid, this.reporteId);
    void this.cargarTodo();
    this.cerrarCanal = this.api.escucharReporte(this.reporteId, {
      mensaje: (m) => { if (this.agregar(m) && m.emisor_id !== this.uid) this.toast.mostrar(`Nuevo mensaje de ${m.emisor_nombre ?? 'Usuario'}`); },
      reporte: () => void this.recargarReporte(),
      conexion: (si) => {
        this.conectado = si;
        if (si && this.reporte) void this.api.mensajes(this.reporteId).then((l) => l.forEach((m) => this.agregar(m))).catch(() => undefined);
      },
    });
  }

  ngOnDestroy(): void { this.cerrarCanal?.(); }

  async cargarTodo(): Promise<void> {
    try {
      const r = await this.api.reporte(this.reporteId);
      if (!r) { this.toast.mostrar('Ya no tienes acceso a este reporte', true); this.regresar(); return; }
      this.reporte = r;
      this.mensajes = await this.api.mensajes(this.reporteId);
      this.bajar();
    } catch (e) {
      this.error(e, 'Sin conexión. Toca Actualizar en el menú');
    }
  }

  private async recargarReporte(): Promise<void> {
    try {
      const anterior = this.reporte;
      const r = await this.api.reporte(this.reporteId);
      if (!r) { this.toast.mostrar('El reporte fue reasignado. Ya no tienes acceso.', true); this.regresar(); return; }
      this.reporte = r;
      if (anterior && anterior.estado !== r.estado) this.toast.mostrar(`Estado: ${etiquetaEstadoReporte(r.estado)}`);
    } catch { /* se reintenta con Actualizar */ }
  }

  detalles(r: Reporte): string {
    const d: string[] = [];
    if (r.pedido_id) d.push(`Pedido #${r.pedido_id}`);
    if (r.equipo) d.push(`Equipo: ${r.equipo}`);
    if (r.para_admin) {
      d.push(`De: ${r.cliente?.nombre || '—'}`);
      d.push('Atiende: Administrador');
    } else {
      d.push(`Cliente: ${r.cliente?.nombre || '—'}`);
      d.push('Atiende: ' + (r.agente?.nombre ?? 'Sin asignar'));
    }
    d.push(`Creado: ${horaLocal(r.created_at, true)}`);
    return d.join('\n');
  }

  /** Agrega sin duplicar (el mensaje propio llega por la respuesta y también por Realtime). */
  private agregar(m: Mensaje): boolean {
    if (this.mensajes.some((x) => x.id === m.id)) return false;
    this.mensajes = [...this.mensajes, m].sort((a, b) => a.id - b.id);
    this.bajar();
    return true;
  }

  private bajar(): void {
    setTimeout(() => { const el = this.scroll?.nativeElement; if (el) el.scrollTop = el.scrollHeight; });
  }

  async enviar(): Promise<void> {
    const texto = this.texto.trim();
    if (!texto) { this.errorEntrada = 'El mensaje no puede estar vacío'; return; }
    this.errorEntrada = '';
    this.enviando = true;
    const id = this.envioPendiente?.texto === texto ? this.envioPendiente.id : crypto.randomUUID();
    this.envioPendiente = { texto, id };
    try {
      const m = await this.api.enviarMensaje(this.reporteId, texto, null, id);
      this.envioPendiente = null;
      this.texto = '';
      this.agregar(m);
      this.toast.mostrar('Mensaje enviado');
    } catch (e) {
      this.error(e, 'No se pudo enviar el mensaje');
    } finally {
      this.enviando = false;
    }
  }

  async elegirFoto(evento: Event): Promise<void> {
    const entrada = evento.target as HTMLInputElement;
    const archivo = entrada.files?.[0];
    entrada.value = '';
    if (!archivo) return;
    this.subiendo = true;
    this.toast.mostrar('Subiendo foto...');
    try {
      const jpeg = await comprimirImagen(archivo);
      const ruta = await this.api.subirEvidencia(this.reporteId, jpeg);
      this.agregar(await this.api.enviarMensaje(this.reporteId, '📷 Foto de evidencia', ruta));
      this.toast.mostrar('Foto enviada');
    } catch (e) {
      this.error(e, 'No se pudo subir la foto');
    } finally {
      this.subiendo = false;
    }
  }

  async abrirAdjunto(ruta: string): Promise<void> {
    // La ventana se abre antes del await: Safari en iPhone bloquea window.open después de una espera
    const ventana = window.open('', '_blank');
    try {
      const url = await this.api.urlFirmada(ruta);
      this.toast.mostrar(`Enlace temporal: expira en ${URL_FIRMADA_SEGUNDOS / 60} minutos`);
      if (ventana) { ventana.opener = null; ventana.location.href = url; } else window.location.href = url;
    } catch (e) {
      ventana?.close();
      this.error(e, 'No se pudo abrir la foto');
    }
  }

  limpiar(): void {
    this.limpiadoHasta = this.mensajes.reduce((max, m) => Math.max(max, m.id), 0);
    this.almacen.marcarLimpiado(this.uid, this.reporteId, this.limpiadoHasta);
    this.hoja = null;
    this.toast.mostrar('Historial limpiado');
  }

  private async abrirReasignar(): Promise<void> {
    this.agentes = [];
    this.hoja = 'reasignar';
    try {
      this.agentes = (await this.api.agentes()).filter((a) => a.rol === 'agente');
    } catch (e) {
      this.hoja = null;
      this.error(e, 'No se pudo cargar la lista de agentes');
    }
  }

  async reasignar(destino: AgenteEstado | null): Promise<void> {
    this.hoja = null;
    if (destino && !destino.en_linea) this.toast.mostrar(`${destino.nombre} está desconectado; lo verá al volver.`, true);
    try {
      await this.api.reasignar(this.reporteId, destino?.id ?? null);
      this.toast.mostrar('Reporte reasignado');
      await this.recargarReporte();
    } catch (e) {
      this.error(e, 'No se pudo reasignar');
    }
  }

  private abrirEditar(): void {
    this.edDescripcion = this.reporte?.descripcion ?? '';
    this.edPrioridad = this.reporte?.prioridad ?? 'media';
    this.edError = '';
    this.hoja = 'editar';
  }

  guardarEdicion(): void {
    const texto = this.edDescripcion.trim();
    if (texto.length < MIN_DESC) { this.edError = `Mínimo ${MIN_DESC} caracteres`; return; }
    void this.guardar({ descripcion: texto, prioridad: this.edPrioridad }, 'Reporte actualizado');
  }

  /** Práctica 12 (O4): bloqueo optimista por updated_at. */
  async guardar(cambios: Partial<Reporte>, aviso: string): Promise<void> {
    this.hoja = null;
    const leido = this.reporte?.updated_at;
    if (!leido) return;
    try {
      await this.api.actualizarReporte(this.reporteId, cambios, leido);
      this.toast.mostrar(aviso);
      await this.recargarReporte();
    } catch (e) {
      if (e instanceof ConflictoError) {
        this.toast.mostrar('Otra persona modificó el reporte. Se cargó la versión más reciente; revisa y vuelve a intentarlo.', true);
        await this.recargarReporte();
      } else {
        this.error(e, 'No se pudo guardar');
      }
    }
  }

  private error(e: unknown, prefijo: string): void {
    if (e instanceof SesionExpiradaError) { void this.router.navigateByUrl('/app/login'); return; }
    this.toast.mostrar(e instanceof ErrorDelServidor ? `${prefijo}: ${e.message}` : `${prefijo}. Revisa tu conexión a Internet.`, true);
  }

  regresar(): void { void this.router.navigateByUrl('/app/soporte'); }
}

/** Reduce la foto a 1600 px máx. y JPEG (límite de 5 MB del bucket), igual que en Android. */
export async function comprimirImagen(archivo: Blob, maxLado = 1600): Promise<Blob> {
  const bitmap = await createImageBitmap(archivo);
  const escala = Math.min(1, maxLado / Math.max(bitmap.width, bitmap.height));
  const lienzo = document.createElement('canvas');
  lienzo.width = Math.round(bitmap.width * escala);
  lienzo.height = Math.round(bitmap.height * escala);
  lienzo.getContext('2d')!.drawImage(bitmap, 0, 0, lienzo.width, lienzo.height);
  bitmap.close?.();
  for (let calidad = 0.85; ; calidad -= 0.15) {
    const blob = await new Promise<Blob | null>((ok) => lienzo.toBlob(ok, 'image/jpeg', calidad));
    if (!blob) throw new ErrorDelServidor(400, 'No se pudo leer la imagen');
    if (blob.size <= 4_500_000 || calidad <= 0.25) return blob;
  }
}
