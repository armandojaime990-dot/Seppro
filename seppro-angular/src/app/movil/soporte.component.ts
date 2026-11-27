import { Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AgenteEstado, MovilApi, Reporte, SesionExpiradaError } from './movil-api.service';
import {
  colorEstadoReporte, colorPrioridad, esStaff, etiquetaEstadoReporte, etiquetaPrioridad, etiquetaTipo,
  fondoSuave, reporteActivo,
} from './nucleo/estados';
import { ErrorDelServidor } from './nucleo/reintento';

interface Seccion { titulo: string; reportes: Reporte[]; }

/** Pestaña Soporte por rol (igual que SoporteFragment.kt). Qué reportes llegan lo decide RLS. */
@Component({
  selector: 'app-movil-soporte',
  standalone: true,
  template: `
    <div class="m-encabezado">
      <h2 class="m-titulo">{{ titulo }}</h2>
      <button class="m-btn m-sec m-chico" type="button" (click)="cargar()">Actualizar</button>
    </div>
    <!-- Cliente: reporta a un agente. Agente: consulta al administrador. Administrador: no crea reportes. -->
    @if (!staff) {
      <button class="m-btn" type="button" style="margin-top:12px" (click)="nuevo(false)">Nuevo reporte</button>
    } @else if (rol === 'agente') {
      <button class="m-btn" type="button" style="margin-top:12px" (click)="nuevo(true)">Consultar al administrador</button>
    }

    @if (mensaje) { <p class="m-vacio">{{ mensaje }}</p> }

    @if (rol === 'admin' && !mensaje) {
      <span class="m-etiqueta">Agentes de atención</span>
      @for (a of soloAgentes; track a.id) {
        <p style="margin:2px 0;font-size:14px">{{ a.en_linea ? '🟢' : '⚪' }} {{ a.nombre }} · {{ a.en_linea ? 'Conectado' : 'Desconectado' }} · {{ a.reportes_activos }} activos</p>
      } @empty {
        <p class="m-suave">No hay usuarios con rol agente. Asígnalo en la web (Admin > Usuarios).</p>
      }
    }

    @if (!mensaje && !staff && reportesCliente.length === 0) {
      <p class="m-suave" style="margin-top:12px">No tienes reportes. Si tu bomba llegó dañada o tienes dudas de tu entrega, crea uno.</p>
    }

    @for (s of secciones; track s.titulo) {
      <span class="m-etiqueta" style="font-weight:700;margin-top:16px">{{ s.titulo }} ({{ s.reportes.length }})</span>
      @for (r of s.reportes; track r.id) {
        <article class="m-tarjeta m-click" (click)="abrir(r.id)">
          <h3 style="font-size:15px">{{ r.folio }} · {{ tipo(r.tipo) }}</h3>
          <p class="m-suave" style="margin:2px 0 6px">{{ detalles(r) }}</p>
          <span class="m-insignia" [style.color]="colorEst(r.estado)" [style.background]="fondo(colorEst(r.estado))">{{ estadoTxt(r.estado) }}</span>
          <span class="m-insignia" [style.color]="colorPri(r.prioridad)" [style.background]="fondo(colorPri(r.prioridad))">{{ prioridad(r.prioridad) }}</span>
        </article>
      } @empty {
        <p class="m-suave">Nada por aquí.</p>
      }
    }
  `,
})
export class MovilSoporteComponent implements OnInit {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);

  readonly rol = this.api.perfil?.rol ?? 'usuario';
  readonly staff = esStaff(this.rol);
  private readonly uid = this.api.uid;
  readonly titulo = this.rol === 'admin' ? 'Soporte · Administración' : this.rol === 'agente' ? 'Soporte · Mis reportes' : 'Soporte';
  mensaje = '';
  secciones: Seccion[] = [];
  soloAgentes: AgenteEstado[] = [];
  reportesCliente: Reporte[] = [];

  readonly tipo = etiquetaTipo;
  readonly estadoTxt = etiquetaEstadoReporte;
  readonly colorEst = colorEstadoReporte;
  readonly prioridad = etiquetaPrioridad;
  readonly colorPri = colorPrioridad;
  readonly fondo = fondoSuave;

  ngOnInit(): void { void this.cargar(); }

  async cargar(): Promise<void> {
    this.mensaje = 'Cargando...';
    try {
      const reportes = await this.api.reportes();
      const agentes = this.rol === 'admin' ? await this.api.agentes() : [];
      this.mostrar(reportes, agentes);
      this.mensaje = '';
    } catch (e) {
      if (e instanceof SesionExpiradaError) { await this.router.navigateByUrl('/app/login'); return; }
      this.secciones = [];
      this.mensaje = e instanceof ErrorDelServidor
        ? (e.codigo === 404 ? 'Soporte no está disponible por el momento. Intenta más tarde.'
          : 'Error del servidor. Toca Actualizar.')
        : 'Sin conexión a Internet. Toca Actualizar.';
    }
  }

  private mostrar(reportes: Reporte[], agentes: AgenteEstado[]) {
    if (this.rol === 'admin') {
      this.soloAgentes = agentes.filter((a) => a.rol === 'agente');
      const desconectados = new Set(this.soloAgentes.filter((a) => !a.en_linea).map((a) => a.id));
      this.secciones = [
        { titulo: 'Sin asignar o con agente desconectado', reportes: reportes.filter((r) =>
          reporteActivo(r.estado) && !r.para_admin && (!r.agente_id || desconectados.has(r.agente_id))) },
        { titulo: 'Consultas de los agentes', reportes: reportes.filter((r) => r.para_admin && reporteActivo(r.estado)) },
        { titulo: 'Todos los reportes', reportes },
      ];
    } else if (this.rol === 'agente') {
      const asignados = reportes.filter((r) => r.agente_id === this.uid);
      this.secciones = [
        { titulo: 'Activos', reportes: asignados.filter((r) => reporteActivo(r.estado)) },
        { titulo: 'Resueltos y cerrados', reportes: asignados.filter((r) => !reporteActivo(r.estado)) },
        { titulo: 'Mis consultas al administrador', reportes: reportes.filter((r) => r.cliente_id === this.uid) },
      ];
    } else {
      this.reportesCliente = reportes;
      this.secciones = reportes.length ? [{ titulo: 'Mis reportes', reportes }] : [];
    }
  }

  detalles(r: Reporte): string {
    const partes: string[] = [];
    if (r.pedido_id) partes.push(`Pedido #${r.pedido_id}`);
    if (r.equipo) partes.push(r.equipo);
    if (r.tipo === 'consulta') {
      if (this.rol === 'admin') partes.push(`De: ${r.cliente?.nombre || '—'}`);
      return partes.join(' · ');
    }
    if (this.staff) partes.push(`Cliente: ${r.cliente?.nombre || '—'}`);
    partes.push('Atiende: ' + (r.agente?.nombre ?? 'Sin asignar'));
    return partes.join(' · ');
  }

  abrir(id: number): void { void this.router.navigate(['/app/reporte', id]); }

  nuevo(paraAdmin: boolean): void {
    void this.router.navigate(['/app/reporte/nuevo'], {
      queryParams: paraAdmin ? { tipo: 'consulta', admin: 1 } : { tipo: 'falla' },
    });
  }
}
