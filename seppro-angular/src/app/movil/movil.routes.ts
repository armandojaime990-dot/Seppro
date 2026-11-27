import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { MovilApi } from './movil-api.service';
import { MovilRootComponent, sesionGuard, soloClientesGuard } from './movil-root.component';
import { inicioPorRol } from './nucleo/estados';

/**
 * App móvil web (PWA) en /app: misma app que el teléfono Kotlin para instalarla en iPhone
 * (Safari > Compartir > Agregar a pantalla de inicio). Usa la misma base de Supabase.
 * Pedidos, Verificar e Historial son solo para clientes; agentes y administrador usan Soporte.
 */
export const MOVIL_ROUTES: Routes = [
  {
    path: '',
    component: MovilRootComponent,
    children: [
      { path: 'login', loadComponent: () => import('./login.component').then((m) => m.MovilLoginComponent) },
      {
        path: 'reporte/nuevo', canActivate: [sesionGuard],
        loadComponent: () => import('./nuevo-reporte.component').then((m) => m.MovilNuevoReporteComponent),
      },
      {
        path: 'reporte/:id', canActivate: [sesionGuard],
        loadComponent: () => import('./reporte.component').then((m) => m.MovilReporteComponent),
      },
      {
        path: '', canActivate: [sesionGuard],
        loadComponent: () => import('./shell.component').then((m) => m.MovilShellComponent),
        children: [
          { path: 'pedidos', data: { filtro: 'activos' }, canActivate: [soloClientesGuard],
            loadComponent: () => import('./lista-pedidos.component').then((m) => m.MovilListaPedidosComponent) },
          { path: 'historial', data: { filtro: 'historial' }, canActivate: [soloClientesGuard],
            loadComponent: () => import('./lista-pedidos.component').then((m) => m.MovilListaPedidosComponent) },
          { path: 'verificar', canActivate: [soloClientesGuard],
            loadComponent: () => import('./verificar.component').then((m) => m.MovilVerificarComponent) },
          { path: 'soporte', loadComponent: () => import('./soporte.component').then((m) => m.MovilSoporteComponent) },
          { path: '', pathMatch: 'full', redirectTo: () => inicioPorRol(inject(MovilApi).perfil?.rol ?? 'usuario') },
        ],
      },
    ],
  },
];
