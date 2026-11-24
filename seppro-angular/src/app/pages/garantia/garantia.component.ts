import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { SupabaseService } from '../../services/supabase.service';

interface GarantiaVista {
  folio: string; pedido_id: number; cliente_nombre: string | null; producto: string | null;
  correo: string; inicio: string; vence: string; correo_enviado: boolean;
}

/**
 * Entregable 3: certificado de garantía (imprimible / guardar como PDF).
 * Solo lo ve el dueño o el administrador (RLS de la tabla garantias).
 */
@Component({
  selector: 'app-garantia',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <section class="section garantia-pagina">
      <div class="container">
        <p *ngIf="cargando" class="nota">Cargando certificado...</p>
        <p *ngIf="!cargando && !g" class="nota">No hay una garantía activa para este pedido. <a routerLink="/mis-pedidos">Volver a Mis pedidos</a></p>

        <article *ngIf="g" class="certificado">
          <header>
            <h1>SEPPRO</h1>
            <p>Certificado de garantía</p>
          </header>
          <p>Se certifica que el equipo del pedido <b>#{{ g.pedido_id }}</b> de <b>{{ g.cliente_nombre || 'cliente' }}</b>
             llegó sin golpes según el sensor Smart Tag del paquete, por lo que su garantía quedó
             <b>activada automáticamente</b>.</p>
          <dl>
            <dt>Folio</dt><dd>{{ g.folio }}</dd>
            <dt>Equipo</dt><dd>{{ g.producto || '—' }}</dd>
            <dt>Vigencia</dt><dd>{{ g.inicio | date:'dd/MM/yyyy' }} al {{ g.vence | date:'dd/MM/yyyy' }}</dd>
            <dt>Correo</dt><dd>{{ g.correo }} · {{ g.correo_enviado ? 'certificado enviado' : 'envío pendiente' }}</dd>
          </dl>
          <p class="nota">Para hacer válida la garantía abre un reporte en la app SEPPRO (Soporte) indicando el folio.</p>
          <div class="acciones">
            <button type="button" class="btn-primary" (click)="imprimir()">Imprimir / guardar PDF</button>
            <a routerLink="/mis-pedidos" class="btn-secondary">Mis pedidos</a>
          </div>
        </article>
      </div>
    </section>
  `,
  styles: [`
    .garantia-pagina { padding-top: 140px; }
    .certificado { max-width: 620px; margin: 0 auto; background: #fff; border-radius: 12px; padding: 32px;
      border-top: 6px solid #003D7A; box-shadow: 0 10px 30px rgba(15,23,42,.08); color: #0F172A; }
    .certificado header h1 { color: #003D7A; margin: 0; }
    .certificado header p { color: #64748B; margin: 4px 0 20px; }
    dl { display: grid; grid-template-columns: 110px 1fr; gap: 8px 12px; margin: 20px 0; }
    dt { color: #64748B; }
    dd { margin: 0; font-weight: 600; }
    .nota { color: #64748B; font-size: .9rem; text-align: center; }
    .acciones { display: flex; gap: 12px; justify-content: center; margin-top: 20px; flex-wrap: wrap; }
    @media print {
      .acciones { display: none; }
      .garantia-pagina { padding-top: 0; }
      .certificado { box-shadow: none; }
    }
  `],
})
export class GarantiaComponent implements OnInit {
  private readonly supabase = inject(SupabaseService);
  private readonly ruta = inject(ActivatedRoute);
  g: GarantiaVista | null = null;
  cargando = true;

  async ngOnInit() {
    const pedido = Number(this.ruta.snapshot.paramMap.get('pedido'));
    if (Number.isInteger(pedido) && pedido > 0) {
      const { data } = await this.supabase.client.from('garantias')
        .select('folio, pedido_id, cliente_nombre, producto, correo, inicio, vence, correo_enviado')
        .eq('pedido_id', pedido).maybeSingle();
      this.g = data as GarantiaVista | null;
    }
    this.cargando = false;
  }

  imprimir() { window.print(); }
}
