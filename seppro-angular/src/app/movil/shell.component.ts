import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MovilApi } from './movil-api.service';
import { esStaff } from './nucleo/estados';

/** Barra superior + contenido + barra inferior (4 pestañas para el cliente; el personal solo ve Soporte). */
@Component({
  selector: 'app-movil-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="m-barra">
      <div class="m-titulos">
        <h1>SEPPRO</h1>
        <small>{{ subtitulo }}</small>
      </div>
      <button class="m-icono-btn" type="button" aria-label="Menú" (click)="menu = true">⋮</button>
    </header>

    @if (sinConexion) {
      <div class="m-sin-red" role="status">📴 Sin conexión. Verás tus últimos datos guardados.</div>
    }
    <div class="m-contenido"><router-outlet></router-outlet></div>

    <!-- Agentes y administrador solo usan Soporte: no se muestra la barra de cliente -->
    @if (cliente) {
    <nav class="m-nav" aria-label="Secciones">
      <a routerLink="pedidos" routerLinkActive="activo">
        <svg viewBox="0 0 24 24"><path d="M19 3h-1V1h-2v2H8V1H6v2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 16H5V8h14v11zM7 10h5v5H7z"/></svg>
        Pedidos
      </a>
      <a routerLink="verificar" routerLinkActive="activo">
        <svg viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>
        Verificar
      </a>
      <a routerLink="historial" routerLinkActive="activo">
        <svg viewBox="0 0 24 24"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>
        Historial
      </a>
      <a routerLink="soporte" routerLinkActive="activo">
        <svg viewBox="0 0 24 24"><path d="M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z"/></svg>
        Soporte
      </a>
    </nav>
    }

    @if (menu) {
      <div class="m-velo" (click)="menu = false">
        <div class="m-hoja" (click)="$event.stopPropagation()">
          <h2>Cerrar sesión</h2>
          <p class="m-suave">¿Quieres salir de tu cuenta?</p>
          <div class="m-fila">
            <button class="m-btn m-sec" type="button" (click)="menu = false">Cancelar</button>
            <button class="m-btn m-peligro" type="button" (click)="salir()">Salir</button>
          </div>
        </div>
      </div>
    }
  `,
})
export class MovilShellComponent implements OnInit, OnDestroy {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);
  private latido?: ReturnType<typeof setInterval>;
  menu = false;
  sinConexion = typeof navigator !== 'undefined' && navigator.onLine === false;
  private readonly cambioDeRed = () => { this.sinConexion = !navigator.onLine; };

  readonly cliente = !esStaff(this.api.perfil?.rol ?? 'usuario');

  get subtitulo(): string { return this.api.perfil?.nombre || 'Mi cuenta'; }

  ngOnInit(): void {
    window.addEventListener('online', this.cambioDeRed);
    window.addEventListener('offline', this.cambioDeRed);
    // Presencia del agente/admin: latido cada minuto mientras la app está abierta (igual que Presencia.kt)
    if (esStaff(this.api.perfil?.rol ?? '')) {
      const latir = () => { if (document.visibilityState === 'visible') void this.api.latido().catch(() => undefined); };
      latir();
      this.latido = setInterval(latir, 60_000);
    }
  }

  ngOnDestroy(): void {
    clearInterval(this.latido);
    window.removeEventListener('online', this.cambioDeRed);
    window.removeEventListener('offline', this.cambioDeRed);
  }

  async salir(): Promise<void> {
    this.menu = false;
    await this.api.cerrarSesion();
    await this.router.navigateByUrl('/app/login', { replaceUrl: true });
  }
}
