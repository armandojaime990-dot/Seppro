import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { SupabaseService, Pedido } from '../../services/supabase.service';

@Component({
  selector: 'app-mis-pedidos',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <section class="pedidos-hero">
      <div class="container">
        <h1 class="section-title">📦 Mis Pedidos</h1>
        <p class="section-subtitle">Consulta el estado de tus compras</p>
      </div>
    </section>

    <section class="section">
      <div class="container">
        <div *ngIf="pedidos.length === 0 && !loading" class="empty-state">
          <span class="empty-icon">📦</span>
          <h3>No tienes pedidos aún</h3>
          <p>Cuando realices una compra, aparecerá aquí</p>
          <a routerLink="/catalogo" class="btn-primary">Explorar Catálogo</a>
        </div>

        <div class="pedidos-list">
          <div *ngFor="let pedido of pedidos; let i = index" class="pedido-card glass-card" (click)="togglePedido(pedido.id)" [style.animation-delay]="(i * 150) + 'ms'">
            <div class="pedido-header">
              <div class="pedido-info">
                <span class="pedido-id">{{ getPedidoTitle(pedido) }}</span>
                <span class="pedido-date">{{ pedido.created_at | date:'dd/MM/yyyy HH:mm' }}</span>
                <span class="pedido-eta">{{ getETA(pedido.estado) }}</span>
              </div>
              <div class="pedido-right">
                <span class="pedido-total">\${{ pedido.total | number:'1.2-2' }} MXN</span>
                <span class="badge" [ngClass]="getEstadoClass(pedido.estado)">
                  {{ getEstadoIcon(pedido.estado) }} {{ getEstadoLabel(pedido.estado) }}
                </span>
              </div>
            </div>

            <!-- Progress Bar (5 pasos: Pagado, Enviado, En camino, En puerta, Entregado) -->
            <div class="progress-bar">
              <div class="progress-step" [class.active]="getProgressLevel(pedido.estado) >= 1" [class.current]="getProgressLevel(pedido.estado) === 1">
                <div class="step-dot"></div>
                <span>Pagado</span>
              </div>
              <div class="progress-line" [class.active]="getProgressLevel(pedido.estado) >= 2"></div>
              <div class="progress-step" [class.active]="getProgressLevel(pedido.estado) >= 2" [class.current]="getProgressLevel(pedido.estado) === 2">
                <div class="step-dot"></div>
                <span>Enviado</span>
              </div>
              <div class="progress-line" [class.active]="getProgressLevel(pedido.estado) >= 3"></div>
              <div class="progress-step" [class.active]="getProgressLevel(pedido.estado) >= 3" [class.current]="getProgressLevel(pedido.estado) === 3">
                <div class="step-dot"></div>
                <span>En camino</span>
              </div>
              <div class="progress-line" [class.active]="getProgressLevel(pedido.estado) >= 4"></div>
              <div class="progress-step" [class.active]="getProgressLevel(pedido.estado) >= 4" [class.current]="getProgressLevel(pedido.estado) === 4">
                <div class="step-dot"></div>
                <span>En puerta</span>
              </div>
              <div class="progress-line" [class.active]="getProgressLevel(pedido.estado) >= 5"></div>
              <div class="progress-step" [class.active]="getProgressLevel(pedido.estado) >= 5" [class.current]="getProgressLevel(pedido.estado) === 5">
                <div class="step-dot"></div>
                <span>Entregado</span>
              </div>
            </div>

            <!-- Expanded Items -->
            <div class="pedido-items" *ngIf="expandedPedido === pedido.id">
              <div *ngFor="let item of pedido.pedido_items" class="pedido-item">
                <span class="pi-name">{{ item.nombre_producto }}</span>
                <span class="pi-qty">× {{ item.cantidad }}</span>
                <span class="pi-price">\${{ (item.precio_unitario * item.cantidad) | number:'1.2-2' }}</span>
              </div>
              <div class="pedido-shipping" *ngIf="pedido.nombre_envio">
                <strong>Envío a:</strong> {{ pedido.nombre_envio }} · {{ pedido.direccion_envio }}
              </div>
              <!-- Entregable 3: resultado de la verificación con el Smart Tag (sincronizado con el teléfono) -->
              <div class="pedido-verificacion" *ngIf="pedido.fecha_verificacion">
                <strong>Verificación Smart Tag:</strong>
                {{ pedido.golpe_detectado ? '🚨 golpe fuerte registrado' : '✅ viaje sin incidentes' }}
                · {{ pedido.fecha_verificacion | date:'dd/MM/yyyy HH:mm' }}
                <span *ngIf="pedido.sensor_id"> · sensor {{ pedido.sensor_id }}</span>
              </div>
              <div class="pedido-garantia" *ngIf="garantias[pedido.id] as g">
                🛡️ <strong>Garantía {{ g.folio }}</strong> activa hasta {{ g.vence | date:'dd/MM/yyyy' }}
                · {{ g.correo_enviado ? 'certificado enviado a tu correo' : 'certificado disponible aquí' }}
                <a [routerLink]="['/garantia', pedido.id]" (click)="$event.stopPropagation()">Ver certificado</a>
              </div>
              <a *ngIf="pedido.estado === 'en_puerta' || pedido.estado === 'alerta_integridad'" class="btn-primary btn-verificar"
                 [routerLink]="['/app/verificar']" [queryParams]="{ pedido: pedido.id }" (click)="$event.stopPropagation()">
                {{ pedido.estado === 'en_puerta' ? 'Verificar con el Smart Tag' : 'Resolver alerta de integridad' }}
              </a>
            </div>
            <div class="expand-hint">{{ expandedPedido === pedido.id ? '▲ Ocultar detalles' : '▼ Ver detalles' }}</div>
          </div>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .pedidos-hero { padding: 140px 0 40px; text-align: center; background: radial-gradient(ellipse at 50% 0%, rgba(0,61,122,0.12), transparent 60%); }
    .empty-state { text-align: center; padding: 80px 20px; }
    .empty-icon { font-size: 4rem; display: block; margin-bottom: 16px; }
    .empty-state h3 { color: #0F172A; margin-bottom: 8px; }
    .empty-state p { color: #64748B; margin-bottom: 24px; }

    .pedidos-list { display: flex; flex-direction: column; gap: 16px; max-width: 800px; margin: 0 auto; }
    
    @keyframes slideUpFadeIn {
      from { opacity: 0; transform: translateY(30px); }
      to { opacity: 1; transform: translateY(0); }
    }
    
    .pedido-card { padding: 24px; cursor: pointer; transition: transform 0.3s, box-shadow 0.3s; opacity: 0; animation: slideUpFadeIn 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
    .pedido-card:hover { transform: translateY(-3px); box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1); }
    .pedido-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
    .pedido-info { display: flex; flex-direction: column; gap: 4px; }
    .pedido-id { font-weight: 700; color: #0F172A; font-size: 1.1rem; }
    .pedido-date { color: #64748B; font-size: 0.8rem; }
    .pedido-eta { color: #0072c6; font-size: 0.85rem; font-style: italic; font-weight: 600; margin-top: 4px; }
    .pedido-right { display: flex; align-items: center; gap: 12px; }
    .pedido-total { font-weight: 700; color: #0072c6; font-size: 1.1rem; }

    .badge-pagado { background: rgba(0,114,198,0.1); color: #0072c6; }
    .badge-enviado { background: rgba(255,152,0,0.1); color: #FF9800; }
    .badge-en-camino { background: rgba(156,39,176,0.1); color: #9C27B0; }
    .badge-entregado { background: rgba(76,175,80,0.1); color: #4CAF50; }
    .badge-pendiente { background: rgba(158,158,158,0.1); color: #9E9E9E; }
    /* Estados de verificación BLE */
    .badge-en-puerta { background: rgba(0,150,136,0.1); color: #009688; }
    .badge-entregado-verificado { background: rgba(46,125,50,0.12); color: #2E7D32; }
    .badge-entregado-con-danos { background: rgba(255,152,0,0.15); color: #E65100; } /* advertencia */
    .badge-alerta-integridad { background: rgba(239,83,80,0.15); color: #D32F2F; } /* error */
    .badge-rechazado { background: rgba(109,76,65,0.15); color: #6D4C41; }
    .pedido-verificacion, .pedido-garantia { margin-top: 10px; padding: 10px 14px; border-radius: 8px; font-size: 0.85rem; color: #334155; background: #F1F5F9; }
    .pedido-garantia { background: rgba(46,125,50,0.08); }
    .pedido-garantia a { margin-left: 8px; color: #0072c6; font-weight: 600; }
    .btn-verificar { display: inline-block; margin-top: 12px; }

    .progress-bar { display: flex; align-items: center; margin: 20px 0 12px; padding: 0 8px; }
    .progress-step { display: flex; flex-direction: column; align-items: center; gap: 6px; flex-shrink: 0; }
    .step-dot {
      width: 14px; height: 14px; border-radius: 50%; background: #E2E8F0;
      border: 2px solid #CBD5E1; transition: all 0.3s;
    }
    .progress-step.active .step-dot { background: #0072c6; border-color: #0072c6; }
    .progress-step.current .step-dot { box-shadow: 0 0 0 4px rgba(0,114,198,0.2); }
    .progress-step span { font-size: 0.65rem; color: #94A3B8; font-weight: 500; }
    .progress-step.active span { color: #0072c6; }
    .progress-line { flex: 1; height: 2px; background: #E2E8F0; margin: 0 4px; margin-bottom: 18px; }
    .progress-line.active { background: #0072c6; }

    .pedido-items { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--border); }
    .pedido-item { display: flex; justify-content: space-between; padding: 8px 0; font-size: 0.85rem; }
    .pi-name { color: #334155; flex: 1; }
    .pi-qty { color: #64748B; margin: 0 16px; }
    .pi-price { color: #0F172A; font-weight: 600; }
    .pedido-shipping { margin-top: 12px; padding: 10px 14px; background: var(--bg-surface); border-radius: 8px; font-size: 0.8rem; color: #334155; }
    .expand-hint { text-align: center; color: #64748B; font-size: 0.75rem; margin-top: 8px; }

    @media (max-width: 768px) {
      .pedido-header { flex-direction: column; align-items: flex-start; }
      .pedido-right { flex-direction: column; align-items: flex-start; }
    }
    /* 5 pasos en pantallas de celular: textos y separaciones más compactos */
    @media (max-width: 480px) {
      .progress-bar { padding: 0; }
      .progress-step span { font-size: 0.55rem; white-space: nowrap; }
      .progress-line { margin: 0 2px; margin-bottom: 18px; }
    }
  `]
})
export class MisPedidosComponent implements OnInit, OnDestroy {
  pedidos: Pedido[] = [];
  loading = true;
  expandedPedido: number | null = null;
  garantias: Record<number, { folio: string; vence: string; correo_enviado: boolean }> = {};
  private canal?: ReturnType<SupabaseService['client']['channel']>;

  constructor(private supabase: SupabaseService) {}

  async ngOnInit() {
    await this.cargar();
    this.loading = false;
    // Sincronización bidireccional: lo que se verifica en el teléfono/PWA aparece aquí al instante
    const uid = (await this.supabase.getSession())?.user.id;
    if (uid) {
      this.canal = this.supabase.client.channel(`mis-pedidos-${uid}`)
        .on('postgres_changes', { event: '*', schema: 'public', table: 'pedidos', filter: `usuario_id=eq.${uid}` },
          () => void this.cargar())
        .subscribe();
    }
  }

  ngOnDestroy() {
    if (this.canal) void this.supabase.client.removeChannel(this.canal);
  }

  private async cargar() {
    this.pedidos = await this.supabase.getPedidos();
    const { data } = await this.supabase.client.from('garantias').select('pedido_id, folio, vence, correo_enviado');
    this.garantias = Object.fromEntries((data ?? []).map((g: any) => [g.pedido_id, g]));
  }

  togglePedido(id: number) {
    this.expandedPedido = this.expandedPedido === id ? null : id;
  }

  getProgressLevel(estado: string): number {
    const levels: Record<string, number> = {
      pendiente: 0, pagado: 1, enviado: 2, en_camino: 3,
      en_puerta: 4, alerta_integridad: 4, rechazado: 5,
      entregado: 5, entregado_verificado: 5, entregado_con_danos: 5
    };
    return levels[estado] || 0;
  }

  getEstadoClass(estado: string): string {
    // Reemplaza TODOS los guiones bajos (p. ej. entregado_con_danos -> badge-entregado-con-danos)
    return 'badge-' + estado.replace(/_/g, '-');
  }

  getEstadoIcon(estado: string): string {
    const icons: Record<string, string> = {
      pendiente: '⏳', pagado: '✅', enviado: '📤', en_camino: '🚚', entregado: '📬',
      en_puerta: '📍', entregado_verificado: '✅', entregado_con_danos: '⚠️', alerta_integridad: '🚨', rechazado: '⛔'
    };
    return icons[estado] || '📦';
  }

  getEstadoLabel(estado: string): string {
    const labels: Record<string, string> = {
      pendiente: 'Pendiente', pagado: 'Pagado', enviado: 'Enviado', en_camino: 'En Camino', entregado: 'Entregado',
      en_puerta: 'En puerta (listo para verificar)',
      entregado_verificado: 'Entregado y verificado',
      entregado_con_danos: 'Entregado con daños',
      alerta_integridad: 'Alerta de integridad',
      rechazado: 'Equipo rechazado'
    };
    return labels[estado] || estado;
  }

  getPedidoTitle(pedido: any): string {
    if (!pedido.pedido_items || pedido.pedido_items.length === 0) return `Pedido #${pedido.id}`;
    const firstItem = pedido.pedido_items[0].nombre_producto;
    if (pedido.pedido_items.length > 1) {
      return `${firstItem} y ${pedido.pedido_items.length - 1} más`;
    }
    return firstItem;
  }

  getETA(estado: string): string {
    switch (estado.toLowerCase()) {
      case 'pagado': return 'Estimado: En 3 a 5 días';
      case 'enviado': return 'Estimado: En 2 días';
      case 'en_camino': return 'Llega mañana';
      case 'entregado': return 'Entregado';
      case 'en_puerta': return 'Listo para verificar';
      case 'entregado_verificado': return 'Entregado y verificado';
      case 'entregado_con_danos': return 'Entregado con daños';
      case 'alerta_integridad': return 'Alerta de integridad: revisión pendiente';
      case 'rechazado': return 'Equipo rechazado: devolución en proceso';
      default: return 'Procesando pago...';
    }
  }
}
