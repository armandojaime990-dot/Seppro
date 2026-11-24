import { Component, inject } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { NavbarComponent } from './components/navbar/navbar.component';
import { FooterComponent } from './components/footer/footer.component';
import { SearchModalComponent } from './components/search-modal/search-modal.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, NavbarComponent, FooterComponent, SearchModalComponent],
  template: `
    @if (esAppMovil) {
      <!-- /app: app móvil (PWA) a pantalla completa, sin la barra ni el pie de la tienda -->
      <router-outlet></router-outlet>
    } @else {
      <app-navbar></app-navbar>
      <main>
        <router-outlet></router-outlet>
      </main>
      <app-footer></app-footer>
      <app-search-modal></app-search-modal>
    }
  `,
  styles: [`
    main {
      min-height: calc(100vh - 160px);
    }
  `]
})
export class AppComponent {
  esAppMovil = AppComponent.esRutaMovil(typeof location !== 'undefined' ? location.pathname : '');

  constructor() {
    inject(Router).events.pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => (this.esAppMovil = AppComponent.esRutaMovil(e.urlAfterRedirects)));
  }

  static esRutaMovil(url: string): boolean {
    return url === '/app' || url.startsWith('/app/') || url.startsWith('/app?');
  }
}
