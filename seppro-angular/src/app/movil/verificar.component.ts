import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MovilApi } from './movil-api.service';
import { Toast } from './movil-root.component';
import { AlmacenLocal } from './nucleo/almacen-local';
import { colorEstado, COLOR_ACCENT, COLOR_ERROR } from './nucleo/estados';
import { ESTATUS_GOLPE, ESTATUS_SIN_INCIDENTES } from './nucleo/telemetria';
import {
  DispositivoBt, elegirSmartTag, EstadoVerificacion, LectorWebBluetooth, SimuladorTag, VerificacionStore,
  webBluetoothDisponible,
} from './verificacion.store';

/**
 * Pestaña Verificar (igual que VerificarFragment.kt):
 *  - Smart Tag por Web Bluetooth (GATT) cuando el navegador lo permite (Chrome en Android/PC).
 *  - Safari en iPhone no permite Bluetooth en páginas web: se explica y queda el modo demostración.
 *  - verificar_entrega -> garantía + certificado, o Alerta de integridad -> aceptar / rechazar.
 */
@Component({
  selector: 'app-movil-verificar',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h2 class="m-titulo">Verificar paquete</h2>
    <p [style.color]="pedidoId > 0 ? colorPuerta : 'var(--m-suave)'" style="margin:4px 0 16px;font-size:14px">
      {{ pedidoId > 0 ? '📍 Pedido #' + pedidoId + ' seleccionado' : 'Sin pedido seleccionado. Usa "Verificar paquete" en un pedido En puerta.' }}
    </p>

    <div class="m-estado" [style.color]="btDisponible ? 'var(--m-suave)' : '#E65100'">
      {{ btDisponible === null ? 'Revisando Bluetooth...' : btDisponible
          ? 'Bluetooth disponible. Toca Buscar para elegir el Smart Tag de la caja.'
          : 'Este navegador no permite Bluetooth en páginas web (Safari en iPhone no lo soporta). ' +
            'Usa la app Android, el navegador Bluefy en iPhone, o el modo demostración de abajo.' }}
    </div>
    <button class="m-btn" type="button" [disabled]="!btDisponible || store.ocupado" (click)="buscar()" style="min-height:52px">
      Buscar Smart Tag
    </button>
    <p style="margin:16px 0 8px;font-size:14px">Seleccionado: {{ seleccionado ?? 'ninguno' }}</p>

    <h3 class="m-titulo" style="font-size:17px;margin-top:24px">Lectura del Smart Tag (GATT)</h3>
    <div class="m-estado" style="margin-top:8px" [style.color]="color">{{ texto }}</div>
    <button class="m-btn" type="button" [disabled]="!dispositivo || store.ocupado" (click)="leer()" style="min-height:52px">
      Leer Smart Tag seleccionado
    </button>

    @switch (estado.tipo) {
      @case ('alerta') {
        <button class="m-btn" type="button" style="background:#E65100" (click)="aceptar()">Aceptar equipo con daños</button>
        <button class="m-btn m-peligro" type="button" (click)="rechazando = true">Rechazar equipo</button>
      }
      @case ('resuelto') {
        @if (reporteId) {
          <button class="m-btn m-sec" type="button" (click)="verReporte()">Ver reporte {{ reporteFolio }}</button>
        }
      }
      @case ('error') {
        <button class="m-btn m-sec" type="button" (click)="store.reiniciar()">Intentar de nuevo</button>
      }
    }

    <p class="m-suave" style="margin:20px 0 6px;font-size:12px">
      Modo demostración (sin hardware): simula la lectura del sensor del pedido elegido.
    </p>
    <div class="m-fila">
      <button class="m-btn m-sec" type="button" style="margin-top:0" [disabled]="store.ocupado" (click)="simular(0)">Simular sin golpes</button>
      <button class="m-btn m-sec" type="button" style="margin-top:0" [disabled]="store.ocupado" (click)="simular(1)">Simular golpe fuerte</button>
    </div>

    @if (rechazando) {
      <div class="m-velo" (click)="rechazando = false">
        <form class="m-hoja" (click)="$event.stopPropagation()" (ngSubmit)="rechazar()">
          <h2>Rechazar equipo del pedido #{{ pedidoId }}</h2>
          <p class="m-suave">Se registrará la devolución y se abrirá un reporte de falla con prioridad alta.</p>
          <textarea class="m-campo" name="motivo" maxlength="500" placeholder="Ej. la carcasa llegó abollada" [(ngModel)]="motivo"></textarea>
          <div class="m-fila">
            <button class="m-btn m-sec" type="button" (click)="rechazando = false">Cancelar</button>
            <button class="m-btn m-peligro" type="submit">Rechazar</button>
          </div>
        </form>
      </div>
    }
  `,
})
export class MovilVerificarComponent implements OnInit {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);
  private readonly ruta = inject(ActivatedRoute);
  private readonly toast = inject(Toast);
  private readonly almacen = new AlmacenLocal();

  readonly store = new VerificacionStore(this.api);
  readonly colorPuerta = colorEstado('en_puerta');
  pedidoId = 0;
  btDisponible: boolean | null = null;
  dispositivo: DispositivoBt | null = null;
  seleccionado: string | null = this.almacen.ultimoDispositivo?.nombre ?? null;
  rechazando = false;
  motivo = '';

  get estado(): EstadoVerificacion { return this.store.estado(); }

  ngOnInit(): void {
    this.pedidoId = Number(this.ruta.snapshot.queryParamMap.get('pedido')) || 0;
    void webBluetoothDisponible().then((si) => (this.btDisponible = si));
    // Si el pedido ya tiene una alerta pendiente, se puede resolver sin volver a leer el sensor
    if (this.pedidoId > 0) {
      void this.api.pedido(this.pedidoId).then((p) => {
        if (p?.estado === 'alerta_integridad' && this.estado.tipo === 'inactivo') {
          this.store.estado.set({ tipo: 'alerta', pedidoId: p.id, telemetria: { paqueteId: `SEPPRO-${p.id}`, estatus: 1 } });
        }
      }).catch(() => undefined);
    }
  }

  async buscar(): Promise<void> {
    try {
      this.dispositivo = await elegirSmartTag();
      this.seleccionado = this.dispositivo.name || 'Sin nombre';
      this.almacen.ultimoDispositivo = { nombre: this.seleccionado, id: this.dispositivo.id };
    } catch (e) {
      if ((e as Error).name !== 'NotFoundError') this.toast.mostrar('No se pudo abrir el selector de Bluetooth');
    }
  }

  leer(): void {
    if (!this.dispositivo) return;
    void this.store.verificar(this.pedidoId, new LectorWebBluetooth(this.dispositivo), this.seleccionado ?? this.dispositivo.id);
  }

  simular(estatus: number): void {
    void this.store.verificar(this.pedidoId,
      new SimuladorTag(this.pedidoId, estatus === 1 ? ESTATUS_GOLPE : ESTATUS_SIN_INCIDENTES), 'Smart Tag (simulado)');
  }

  aceptar(): void {
    const e = this.estado;
    if (e.tipo === 'alerta') void this.store.aceptarConDanos(e.pedidoId);
  }

  rechazar(): void {
    const e = this.estado;
    this.rechazando = false;
    if (e.tipo === 'alerta') void this.store.rechazarEquipo(e.pedidoId, this.motivo);
  }

  get reporteId(): number | null { const e = this.estado; return e.tipo === 'resuelto' ? e.resultado.reporteId : null; }
  get reporteFolio(): string { const e = this.estado; return e.tipo === 'resuelto' ? (e.resultado.reporteFolio ?? '') : ''; }

  verReporte(): void {
    if (this.reporteId) void this.router.navigate(['/app/reporte', this.reporteId]);
  }

  get texto(): string {
    const e = this.estado;
    switch (e.tipo) {
      case 'inactivo':
        return this.pedidoId > 0 ? `Elige el Smart Tag de la caja del pedido #${this.pedidoId} y léelo.`
          : 'Elige un pedido "En puerta" en la pestaña Pedidos para verificarlo.';
      case 'conectando': return `🔗 Conectando con ${e.dispositivo} y leyendo la telemetría...`;
      case 'registrando':
        return `📡 Paquete ${e.telemetria.paqueteId} · estatus ${e.telemetria.estatus} ` +
          `(${e.telemetria.estatus === 1 ? 'golpe fuerte' : 'sin incidentes'}). Registrando en el servidor...`;
      case 'verificado': {
        const r = e.resultado;
        const correo = e.correoEnviado === null ? 'Enviando certificado a tu correo...'
          : e.correoEnviado ? '📧 Certificado enviado a tu correo.'
          : '📧 El correo se enviará cuando el servicio esté disponible; el certificado ya está en Mis pedidos.';
        return `✅ Entrega verificada: el viaje fue sin incidentes.\n🛡️ Garantía ${r.garantiaFolio ?? 'activada'}` +
          `${r.garantiaVence ? ' · vence ' + r.garantiaVence : ''}\n${correo}`;
      }
      case 'alerta':
        return '🚨 ALERTA DE INTEGRIDAD\nEl Smart Tag registró un golpe fuerte durante el traslado. ' +
          'Revisa el equipo frente al repartidor: puedes aceptarlo con daños o rechazarlo.';
      case 'resolviendo': return e.accion === 'rechazar' ? 'Registrando el rechazo del equipo...' : 'Registrando la aceptación...';
      case 'resuelto':
        return e.resultado.estado === 'rechazado'
          ? `⛔ Equipo rechazado. Se abrió el reporte ${e.resultado.reporteFolio ?? ''} con prioridad alta para la devolución.`
          : '⚠️ Equipo aceptado con daños. Si encuentras fallas, abre un reporte en Soporte.';
      case 'error':
        if (e.sesionExpirada) void this.router.navigateByUrl('/app/login');
        return `Error: ${e.mensaje}`;
    }
  }

  get color(): string {
    const e = this.estado;
    switch (e.tipo) {
      case 'inactivo': return 'var(--m-suave)';
      case 'verificado': return '#2E7D32';
      case 'alerta': case 'error': return COLOR_ERROR;
      case 'resuelto': return colorEstado(e.resultado.estado);
      default: return COLOR_ACCENT;
    }
  }
}
