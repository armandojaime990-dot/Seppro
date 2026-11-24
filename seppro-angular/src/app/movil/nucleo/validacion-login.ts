/**
 * Práctica 5 (código seguro) — mismas reglas que ValidacionLogin.kt del teléfono.
 * Valida ANTES de enviar a Supabase Auth; quien decide si las credenciales son correctas es el servidor.
 */
export const MAX_EMAIL = 254;      // RFC 5321
export const MIN_PASSWORD = 6;     // mínimo de Supabase Auth
export const MAX_PASSWORD = 72;    // bcrypt solo usa 72 caracteres
export const MENSAJE_GENERICO = 'Correo o contraseña incorrectos'; // no revela qué campo falló

const PATRON_EMAIL =
  /^[a-zA-Z0-9+._%\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\-]{0,64}(\.[a-zA-Z0-9][a-zA-Z0-9\-]{0,25})+$/;

export function correoValido(email: string): boolean {
  return PATRON_EMAIL.test(email);
}

/** Devuelve el mensaje de error para mostrar, o null si los datos son válidos. */
export function validarLogin(email: string | null | undefined, password: string | null | undefined): string | null {
  const e = email ?? '';
  const p = password ?? '';
  if (e === '' || p === '') return 'Llena todos los campos';
  if (p.trim() === '') return 'Llena todos los campos';
  if (e.length > MAX_EMAIL || p.length > MAX_PASSWORD) return MENSAJE_GENERICO;
  if (/\s/.test(e) || !correoValido(e)) return 'Escribe un correo válido';
  if (p.length < MIN_PASSWORD) return MENSAJE_GENERICO;
  return null;
}
