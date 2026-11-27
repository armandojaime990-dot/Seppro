import { Component, Injectable, ViewEncapsulation, inject, signal } from '@angular/core';
import { CanActivateFn, Router, RouterOutlet } from '@angular/router';
import { MovilApi } from './movil-api.service';
import { inicioPorRol, puedeVerificar } from './nucleo/estados';

/** Aviso corto en la parte inferior, como el Toast de Android. */
@Injectable({ providedIn: 'root' })
export class Toast {
  readonly texto = signal<string | null>(null);
  private reloj?: ReturnType<typeof setTimeout>;

  mostrar(texto: string, largo = false): void {
    clearTimeout(this.reloj);
    this.texto.set(texto);
    this.reloj = setTimeout(() => this.texto.set(null), largo ? 3500 : 2000);
  }
}

/** Solo con sesión iniciada (y cuenta habilitada). Si no, al login de la app. */
export const sesionGuard: CanActivateFn = async () => {
  const api = inject(MovilApi);
  const router = inject(Router);
  try {
    if (api.perfil || (await api.cargarPerfil())) return true;
  } catch { /* sin conexión: se pide iniciar sesión */ }
  return router.parseUrl('/app/login');
};

/** Pedidos, Verificar e Historial son solo para clientes; el personal va a Soporte. */
export const soloClientesGuard: CanActivateFn = () => {
  const rol = inject(MovilApi).perfil?.rol ?? 'usuario';
  return puedeVerificar(rol) ? true : inject(Router).parseUrl(inicioPorRol(rol));
};

/**
 * Contenedor de la app móvil web (PWA para iPhone). Misma interfaz que la app Kotlin:
 * colores de la marca, tarjetas grises redondeadas, botones y barra inferior.
 */
@Component({
  selector: 'app-movil-root',
  standalone: true,
  imports: [RouterOutlet],
  encapsulation: ViewEncapsulation.None,
  template: `
    <div class="m-app">
      <router-outlet></router-outlet>
      @if (toast.texto(); as t) {
        <div class="m-toast" role="status" aria-live="polite">{{ t }}</div>
      }
    </div>
  `,
  styles: [`
    .m-app {
      --m-primary: #003D7A; --m-accent: #0072C6; --m-texto: #0F172A; --m-suave: #64748B;
      --m-gris: #F1F5F9; --m-error: #EF5350; --m-tenue: #94A3B8;
      position: fixed; inset: 0; display: flex; flex-direction: column; background: #fff; color: var(--m-texto);
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
      font-size: 15px; -webkit-tap-highlight-color: transparent; overflow: hidden;
    }
    .m-app *, .m-app *::before, .m-app *::after { box-sizing: border-box; }
    /* Cada pantalla ocupa el alto completo: barra arriba, contenido con scroll, barra/caja abajo */
    .m-app > :not(router-outlet):not(.m-toast) {
      display: flex; flex-direction: column; flex: 1; min-height: 0;
    }
    .m-barra {
      background: var(--m-primary); color: #fff; display: flex; align-items: center; gap: 8px;
      padding: calc(env(safe-area-inset-top) + 10px) 12px 10px 16px; min-height: 56px; flex-shrink: 0;
    }
    .m-barra h1 { font-size: 20px; margin: 0; font-weight: 600; line-height: 1.2; }
    .m-barra small { display: block; color: #DBEAFE; font-size: 13px; }
    .m-barra .m-titulos { flex: 1; min-width: 0; }
    .m-barra .m-titulos small, .m-barra .m-titulos h1 { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .m-icono-btn {
      background: none; border: 0; color: #fff; font-size: 22px; width: 40px; height: 40px; border-radius: 20px;
      cursor: pointer; display: grid; place-items: center;
    }
    .m-icono-btn:active { background: rgba(255,255,255,.15); }
    .m-contenido { flex: 1; overflow-y: auto; -webkit-overflow-scrolling: touch; padding: 16px; }
    .m-titulo { color: var(--m-primary); font-size: 20px; font-weight: 700; margin: 0; }
    .m-encabezado { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
    .m-encabezado .m-titulo { flex: 1; }
    .m-suave { color: var(--m-suave); font-size: 13px; }
    .m-etiqueta { color: var(--m-suave); font-size: 13px; margin: 12px 0 4px; display: block; }
    .m-vacio { color: var(--m-tenue); font-size: 14px; text-align: center; padding: 24px 0; }
    .m-tarjeta { background: var(--m-gris); border-radius: 12px; padding: 14px 16px; margin-bottom: 12px; }
    .m-tarjeta.m-click { cursor: pointer; }
    .m-tarjeta.m-click:active { filter: brightness(.96); }
    .m-tarjeta h3 { font-size: 16px; margin: 0; font-weight: 700; color: var(--m-texto); }
    .m-insignia {
      display: inline-block; font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 16px; margin-right: 6px;
    }
    .m-btn {
      width: 100%; min-height: 48px; border: 0; border-radius: 8px; font-size: 15px; cursor: pointer;
      background: var(--m-accent); color: #fff; padding: 0 14px; font-family: inherit; margin-top: 12px;
    }
    .m-btn:disabled { opacity: .5; cursor: default; }
    .m-btn.m-sec { background: var(--m-gris); color: var(--m-accent); font-size: 14px; }
    .m-btn.m-peligro { background: var(--m-error); }
    .m-btn.m-chico { width: auto; min-height: 40px; margin-top: 0; }
    .m-campo {
      width: 100%; background: var(--m-gris); border: 0; border-radius: 8px; padding: 12px 14px; font-size: 16px;
      color: var(--m-texto); font-family: inherit; outline-color: var(--m-accent);
    }
    textarea.m-campo { min-height: 96px; resize: vertical; }
    .m-error { color: var(--m-error); font-size: 14px; margin-top: 8px; }
    .m-estado { border-radius: 8px; padding: 10px 12px; background: var(--m-gris); font-weight: 600; white-space: pre-line; }
    .m-nav {
      display: flex; background: #fff; box-shadow: 0 -2px 8px rgba(15,23,42,.08); flex-shrink: 0;
      padding-bottom: env(safe-area-inset-bottom);
    }
    .m-nav a {
      flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 8px 0 6px;
      color: var(--m-tenue); text-decoration: none; font-size: 12px;
    }
    .m-nav a svg { width: 24px; height: 24px; fill: currentColor; }
    .m-nav a.activo { color: var(--m-accent); font-weight: 600; }
    .m-toast {
      position: fixed; left: 50%; transform: translateX(-50%); bottom: calc(env(safe-area-inset-bottom) + 84px);
      background: rgba(30,41,59,.92); color: #fff; padding: 10px 18px; border-radius: 22px; font-size: 14px;
      max-width: 88vw; text-align: center; z-index: 1000; box-shadow: 0 4px 14px rgba(0,0,0,.2);
    }
    .m-velo { position: fixed; inset: 0; background: rgba(15,23,42,.45); display: flex; align-items: flex-end; z-index: 900; }
    .m-hoja {
      background: #fff; width: 100%; border-radius: 16px 16px 0 0; padding: 18px 20px calc(env(safe-area-inset-bottom) + 18px);
      max-height: 85vh; overflow-y: auto;
    }
    .m-hoja h2 { font-size: 18px; margin: 0 0 6px; color: var(--m-texto); }
    .m-opcion {
      display: block; width: 100%; text-align: left; background: none; border: 0; border-bottom: 1px solid #E2E8F0;
      padding: 14px 4px; font-size: 15px; color: var(--m-texto); cursor: pointer; font-family: inherit;
    }
    .m-sin-red { background: #FFF3E0; color: #E65100; font-size: 13px; text-align: center; padding: 6px 12px; }
    .m-fila { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .enlace { background: none; border: 0; padding: 16px 0 4px; color: var(--m-primary); font-size: 14px; text-decoration: underline; cursor: pointer; }
    .m-fila > .m-btn { flex: 1; }
    @media (min-width: 640px) {
      .m-app { max-width: 480px; margin: 0 auto; box-shadow: 0 0 24px rgba(0,0,0,.12); }
    }
  `],
})
export class MovilRootComponent {
  readonly toast = inject(Toast);
}
