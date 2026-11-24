import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MovilApi } from './movil-api.service';
import { AlmacenLocal } from './nucleo/almacen-local';
import { correoValido, MAX_EMAIL, MAX_PASSWORD, validarLogin } from './nucleo/validacion-login';
import { ErrorDeRed } from './nucleo/reintento';

/** Login de la app (mismo diseño y reglas que LoginActivity.kt). */
@Component({
  selector: 'app-movil-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="m-contenido login">
      <form (ngSubmit)="entrar()" autocomplete="on">
        <h1 class="logo">SEPPRO</h1>
        <p class="sub">Verificación de entregas</p>
        <input class="m-campo" type="email" name="email" placeholder="Correo" autocomplete="username"
               [maxlength]="MAX_EMAIL" [(ngModel)]="email" inputmode="email" autocapitalize="off">
        <input class="m-campo" type="password" name="password" placeholder="Contraseña" autocomplete="current-password"
               [maxlength]="MAX_PASSWORD" [(ngModel)]="password" style="margin-top:12px">
        <p class="estado" [style.color]="colorEstado">{{ estado }}</p>
        <button class="m-btn" type="submit" [disabled]="cargando" style="min-height:52px">Entrar</button>
        <button class="olvido" type="button" (click)="recuperar()">¿Olvidaste tu contraseña?</button>
      </form>
    </div>
  `,
  styles: [`
    .login { display: flex; flex-direction: column; justify-content: center; padding: 48px 32px calc(env(safe-area-inset-bottom) + 24px); }
    .logo { color: var(--m-primary); font-size: 32px; font-weight: 700; text-align: center; margin: 0; }
    .sub { color: var(--m-suave); text-align: center; margin: 4px 0 32px; font-size: 14px; }
    .estado { text-align: center; min-height: 20px; font-size: 14px; margin: 16px 0 0; }
    .olvido { display: block; margin: 16px auto 8px; background: none; border: 0; color: var(--m-accent); font-size: 14px; cursor: pointer; }
  `],
})
export class MovilLoginComponent {
  private readonly api = inject(MovilApi);
  private readonly router = inject(Router);
  private readonly almacen = new AlmacenLocal();

  readonly MAX_EMAIL = MAX_EMAIL;
  readonly MAX_PASSWORD = MAX_PASSWORD;
  email = this.almacen.ultimoEmail ?? '';
  password = '';
  estado = '';
  colorEstado = 'var(--m-error)';
  cargando = false;

  async entrar(): Promise<void> {
    const email = this.email.trim();
    const error = validarLogin(email, this.password);
    if (error) { this.mostrar(error, true); return; }
    this.cargando = true;
    this.mostrar('Iniciando sesión...', false);
    try {
      await this.api.iniciarSesion(email, this.password);
      this.almacen.ultimoEmail = email; // nunca la contraseña
      this.mostrar('¡Conectado!', false);
      await this.router.navigateByUrl('/app/pedidos', { replaceUrl: true });
    } catch (e) {
      this.password = '';
      this.mostrar(e instanceof ErrorDeRed ? 'Error de conexión' : (e as Error).message, true);
    } finally {
      this.cargando = false;
    }
  }

  async recuperar(): Promise<void> {
    const email = this.email.trim();
    if (!email || email.length > MAX_EMAIL || !correoValido(email)) {
      this.mostrar('Escribe tu correo para recuperar el acceso', true);
      return;
    }
    this.mostrar('Enviando...', false);
    try {
      await this.api.recuperarAcceso(email);
      this.estado = 'Si el correo está registrado, recibirás un enlace para restablecer tu contraseña.';
      this.colorEstado = '#2E7D32';
    } catch {
      this.mostrar('Error de conexión', true);
    }
  }

  private mostrar(texto: string, esError: boolean) {
    this.estado = texto;
    this.colorEstado = esError ? 'var(--m-error)' : '#4CAF50';
  }
}
