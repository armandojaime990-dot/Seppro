import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MovilApi, PedidoResumen, SesionExpiradaError } from './movil-api.service';
import { Toast } from './movil-root.component';
import {
  etiquetaEstado, etiquetaPrioridad, etiquetaTipo, MAX_DESC, MIN_DESC, nombrePedido, PRIORIDADES, tieneDano,
  TIPOS_REPORTE, validarReporte,
} from './nucleo/estados';
import { ErrorDelServidor } from './nucleo/reintento';

/** Formulario para abrir un reporte (igual que NuevoReporteActivity.kt). */
@Component({
  selector: 'app-movil-nuevo-reporte',
  standalone: true,
  imports: [FormsModule],
  template: `
    <header class="m-barra">
      <button class="m-icono-btn" type="button" aria-label="Regresar" (click)="regresar()">←</button>
      <div class="m-titulos"><h1>{{ paraAdmin ? 'Consulta al administrador' : 'Nuevo reporte' }}</h1></div>
    </header>
    <form class="m-contenido" (ngSubmit)="enviar()">
      <p class="m-suave" style="font-size:14px;margin-top:0">
        {{ paraAdmin ? 'Tu consulta llegará directamente al administrador y te responderá en el chat.'
                     : 'Un agente de atención revisará tu reporte y te responderá en el chat.' }}
      </p>
      @if (!paraAdmin) {
      <label class="m-etiqueta" for="tipo">Tipo de reporte</label>
      <select id="tipo" class="m-campo" name="tipo" [(ngModel)]="tipo">
        @for (t of tipos; track t) { <option [value]="t">{{ etiquetaTipo(t) }}</option> }
      </select>

      <label class="m-etiqueta" for="pedido">Pedido relacionado</label>
      <select id="pedido" class="m-campo" name="pedido" [(ngModel)]="pedidoId" (ngModelChange)="alElegirPedido($event)">
        <option [ngValue]="null">{{ cargandoPedidos ? 'Cargando pedidos...' : 'Sin pedido' }}</option>
        @for (p of pedidos; track p.id) {
          <option [ngValue]="p.id">Pedido #{{ p.id }} · {{ etiquetaEstado(p.estado) }}{{ p.pedido_items?.length ? ' · ' + p.pedido_items![0].nombre_producto : '' }}</option>
        }
      </select>

      <label class="m-etiqueta" for="equipo">Equipo (obligatorio en reportes de falla)</label>
      <input id="equipo" class="m-campo" name="equipo" maxlength="120" placeholder="Ej. Bomba Altamira KOR10" [(ngModel)]="equipo">
      }

      <label class="m-etiqueta" for="desc">{{ paraAdmin ? 'Tu pregunta' : 'Descripción' }} (mínimo {{ MIN_DESC }} caracteres)</label>
      <textarea id="desc" class="m-campo" name="descripcion" [maxlength]="MAX_DESC"
                [placeholder]="paraAdmin ? 'Ej. ¿Autorizo el cambio de equipo del pedido #16?' : 'Describe qué pasó: estado de la caja, ruidos, golpes visibles...'"
                [(ngModel)]="descripcion"></textarea>

      <label class="m-etiqueta" for="prioridad">Prioridad</label>
      <select id="prioridad" class="m-campo" name="prioridad" [(ngModel)]="prioridad">
        @for (p of prioridades; track p) { <option [value]="p">{{ etiquetaPrioridad(p) }}</option> }
      </select>

      @if (error) { <p class="m-error">{{ error }}</p> }
      <button class="m-btn" type="submit" [disabled]="enviando" style="min-height:52px">
        {{ paraAdmin ? 'Enviar consulta' : 'Crear reporte' }}
      </button>
    </form>
  `,
})
export class MovilNuevoReporteComponent implements OnInit {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);
  private readonly ruta = inject(ActivatedRoute);
  private readonly toast = inject(Toast);

  readonly tipos = TIPOS_REPORTE;
  readonly prioridades = PRIORIDADES;
  readonly etiquetaTipo = etiquetaTipo;
  readonly etiquetaEstado = etiquetaEstado;
  readonly etiquetaPrioridad = etiquetaPrioridad;
  readonly MIN_DESC = MIN_DESC;
  readonly MAX_DESC = MAX_DESC;

  paraAdmin = false;
  tipo = 'falla';
  pedidoId: number | null = null;
  equipo = '';
  descripcion = '';
  prioridad = 'media';
  pedidos: PedidoResumen[] = [];
  cargandoPedidos = true;
  error = '';
  enviando = false;

  async ngOnInit(): Promise<void> {
    const q = this.ruta.snapshot.queryParamMap;
    const rol = this.api.perfil?.rol ?? 'usuario';
    // El administrador no crea reportes; el agente solo consulta al administrador; el cliente reporta a un agente
    if (rol === 'admin') { await this.router.navigateByUrl('/app/soporte'); return; }
    this.paraAdmin = rol === 'agente';
    if (this.paraAdmin) { this.tipo = 'consulta'; this.cargandoPedidos = false; return; }
    const tipo = q.get('tipo');
    if (tipo && (TIPOS_REPORTE as readonly string[]).includes(tipo)) this.tipo = tipo;
    const preseleccion = Number(q.get('pedido')) || null;
    try {
      this.pedidos = await this.api.pedidosParaSelector();
    } catch (e) {
      if (e instanceof SesionExpiradaError) { await this.router.navigateByUrl('/app/login'); return; }
    } finally {
      this.cargandoPedidos = false;
    }
    if (preseleccion && this.pedidos.some((p) => p.id === preseleccion)) {
      this.pedidoId = preseleccion;
      this.alElegirPedido(preseleccion);
    }
  }

  /** Si el sensor detectó daño: prioridad alta y el equipo se llena con el producto. */
  alElegirPedido(id: number | null): void {
    const p = this.pedidos.find((x) => x.id === id);
    if (!p) return;
    if (tieneDano(p.estado)) this.prioridad = 'alta';
    if (!this.equipo.trim() && p.pedido_items?.length) this.equipo = nombrePedido({ id: p.id, pedido_items: [p.pedido_items[0]] }).slice(0, 120);
  }

  async enviar(): Promise<void> {
    this.error = validarReporte({ tipo: this.tipo, descripcion: this.descripcion, equipo: this.equipo, pedidoId: this.pedidoId }) ?? '';
    if (this.paraAdmin) { this.pedidoId = null; this.equipo = ''; }
    if (this.error) return;
    this.enviando = true;
    try {
      const r = await this.api.crearReporte({
        tipo: this.tipo, pedidoId: this.pedidoId, equipo: this.equipo, descripcion: this.descripcion,
        prioridad: this.prioridad, paraAdmin: this.paraAdmin,
      });
      this.toast.mostrar(this.paraAdmin ? `Consulta ${r.folio} enviada al administrador`
        : !r.agente_id ? `Reporte ${r.folio} creado. No hay agentes conectados: el administrador lo asignará.`
        : `Reporte ${r.folio} creado y asignado a ${r.agente?.nombre ?? 'un agente'}`, true);
      await this.router.navigate(['/app/reporte', r.id], { replaceUrl: true });
    } catch (e) {
      if (e instanceof SesionExpiradaError) { await this.router.navigateByUrl('/app/login'); return; }
      this.error = e instanceof ErrorDelServidor ? `No se pudo crear el reporte: ${e.message}`
        : 'Sin conexión. Revisa tu Internet e intenta de nuevo.';
    } finally {
      this.enviando = false;
    }
  }

  regresar(): void { void this.router.navigateByUrl('/app/soporte'); }
}
