import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { MovilApi, PedidoResumen, SesionExpiradaError } from './movil-api.service';
import { ErrorDeRed } from './nucleo/reintento';
import { colorEstado, etiquetaEstado, fondoSuave, iconoEstado, nombrePedido, tieneDano } from './nucleo/estados';

/**
 * Pestañas Pedidos (activos) e Historial (cerrados). Igual que ListaPedidosFragment.kt:
 * páginas de 20 con el filtro aplicado en el servidor, "Cargar más" y "Mostrando X de N".
 * Además se actualiza sola cuando cambia un pedido (Realtime: web o verificación).
 * Sin conexión muestra la última lista descargada (modo sin conexión, rúbrica C3).
 */
@Component({
  selector: 'app-movil-lista-pedidos',
  standalone: true,
  imports: [CurrencyPipe],
  template: `
    <div class="m-encabezado">
      <h2 class="m-titulo">{{ titulo }}</h2>
      <button class="m-btn m-sec m-chico" type="button" (click)="cargar(true)">Actualizar</button>
    </div>
    <p class="m-suave" style="margin:0 0 12px">{{ contador }}</p>
    @if (copiaDe) {
      <p class="m-estado" style="color:#E65100;margin:0 0 12px">📴 Sin conexión · mostrando tus pedidos guardados el {{ copiaDe }}</p>
    }

    @if (mensaje) { <p class="m-vacio">{{ mensaje }}</p> }

    @for (p of pedidos; track p.id) {
      <article class="m-tarjeta">
        <h3>{{ nombre(p) }}</h3>
        <p class="m-suave" style="margin:2px 0 8px">Pedido #{{ p.id }} · {{ p.total | currency:'MXN':'symbol-narrow':'1.2-2' }}</p>
        <span class="m-insignia" [style.color]="color(p.estado)" [style.background]="fondo(p.estado)">
          {{ icono(p.estado) }}&nbsp; {{ etiqueta(p.estado) }}
        </span>
        @if (p.estado === 'en_puerta' && filtro === 'activos') {
          <button class="m-btn" type="button" (click)="verificar(p.id)">Verificar paquete</button>
        }
        @if (p.estado === 'alerta_integridad') {
          <button class="m-btn" type="button" style="background:#E65100" (click)="verificar(p.id)">Resolver alerta de integridad</button>
        }
        @if (conDano(p.estado)) {
          <button class="m-btn m-peligro" type="button" (click)="reportar(p.id)">Reportar falla</button>
        }
      </article>
    }

    @if (hayMas) {
      <button class="m-btn m-sec" type="button" [disabled]="cargando" (click)="cargar(false)">
        {{ cargando ? 'Cargando...' : errorPagina ? 'Reintentar cargar más' : 'Cargar más' }}
      </button>
    }
  `,
})
export class MovilListaPedidosComponent implements OnInit, OnDestroy {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);
  private readonly ruta = inject(ActivatedRoute);

  filtro: 'activos' | 'historial' = 'activos';
  titulo = '';
  textoVacio = '';
  pedidos: PedidoResumen[] = [];
  total: number | null = null;
  mensaje = '';
  cargando = false;
  errorPagina = false;
  copiaDe = ''; // fecha de la copia guardada que se está mostrando (modo sin conexión)
  private generacion = 0; // descarta respuestas tardías de cargas anteriores
  private dejarDeEscuchar?: () => void;
  private recarga?: ReturnType<typeof setTimeout>;

  readonly nombre = nombrePedido;
  readonly etiqueta = etiquetaEstado;
  readonly icono = iconoEstado;
  readonly color = colorEstado;
  readonly conDano = tieneDano;
  fondo = (e: string) => fondoSuave(colorEstado(e));

  get contador(): string {
    if (this.pedidos.length === 0) return '';
    return this.total !== null ? `Mostrando ${this.pedidos.length} de ${this.total} pedidos` : `Mostrando ${this.pedidos.length} pedidos`;
  }
  get hayMas(): boolean {
    return this.pedidos.length > 0 && (this.total !== null ? this.pedidos.length < this.total : false);
  }

  ngOnInit(): void {
    this.filtro = this.ruta.snapshot.data['filtro'] ?? 'activos';
    this.titulo = this.filtro === 'activos' ? 'Mis pedidos' : 'Historial';
    this.textoVacio = this.filtro === 'activos' ? 'No tienes pedidos activos.' : 'Aún no tienes pedidos entregados.';
    void this.cargar(true);
    // Cambios hechos en la web, el dashboard del admin o la verificación llegan solos
    this.dejarDeEscuchar = this.api.escucharPedidos(() => {
      clearTimeout(this.recarga);
      this.recarga = setTimeout(() => void this.cargar(true), 600);
    });
  }

  ngOnDestroy(): void {
    this.generacion++;
    clearTimeout(this.recarga);
    this.dejarDeEscuchar?.();
  }

  async cargar(reiniciar: boolean): Promise<void> {
    if (this.cargando && !reiniciar) return;
    if (reiniciar) {
      this.generacion++;
      this.pedidos = [];
      this.total = null;
      this.mensaje = 'Cargando...';
    }
    const mia = this.generacion;
    this.cargando = true;
    this.errorPagina = false;
    try {
      const pagina = await this.api.paginaPedidos(this.filtro, this.pedidos.length);
      if (mia !== this.generacion) return; // respuesta vieja: se ignora
      this.pedidos = [...this.pedidos, ...pagina.pedidos];
      this.total = pagina.total;
      this.copiaDe = '';
      this.mensaje = this.pedidos.length === 0 ? this.textoVacio : '';
      if (reiniciar) this.api.almacen.guardarPedidos(this.api.uid, this.filtro, this.pedidos, this.total);
    } catch (e) {
      if (mia !== this.generacion) return;
      if (e instanceof SesionExpiradaError) { await this.router.navigateByUrl('/app/login'); return; }
      const copia = e instanceof ErrorDeRed && this.pedidos.length === 0
        ? this.api.almacen.pedidosGuardados<PedidoResumen>(this.api.uid, this.filtro) : null;
      if (copia) {
        this.pedidos = copia.pedidos;
        this.total = copia.pedidos.length; // sin "Cargar más" mientras no haya conexión
        this.copiaDe = new Date(copia.fecha).toLocaleString('es-MX', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
        this.mensaje = this.pedidos.length === 0 ? this.textoVacio : '';
      } else if (this.pedidos.length === 0) this.mensaje = 'Sin conexión. Revisa tu Internet y toca Actualizar.';
      else this.errorPagina = true;
    } finally {
      if (mia === this.generacion) this.cargando = false;
    }
  }

  verificar(id: number): void {
    void this.router.navigate(['/app/verificar'], { queryParams: { pedido: id } });
  }

  reportar(id: number): void {
    void this.router.navigate(['/app/reporte/nuevo'], { queryParams: { pedido: id, tipo: 'falla' } });
  }
}
