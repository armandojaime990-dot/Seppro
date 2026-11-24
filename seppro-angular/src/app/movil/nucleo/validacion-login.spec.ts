import { MAX_EMAIL, MAX_PASSWORD, MENSAJE_GENERICO, correoValido, validarLogin } from './validacion-login';

/** Prácticas 13 y 14 — AUT-03 (unitarias): validación del inicio de sesión de la PWA (igual que Kotlin). */
describe('validarLogin (AUT-03)', () => {
  it('entrada válida -> null (se envía a Supabase)', () => {
    expect(validarLogin('cliente1@seppro.com', '123456')).toBeNull();
    expect(validarLogin('ana.perez+pwa@uteq.edu.mx', 'x'.repeat(MAX_PASSWORD))).toBeNull();
  });

  it('entrada vacía o nula -> "Llena todos los campos"', () => {
    expect(validarLogin('', '123456')).toBe('Llena todos los campos');
    expect(validarLogin('a@b.com', '')).toBe('Llena todos los campos');
    expect(validarLogin(null, undefined)).toBe('Llena todos los campos');
    expect(validarLogin('a@b.com', '      ')).toBe('Llena todos los campos');
  });

  it('valores fuera de rango -> mensaje genérico (no revela qué falló)', () => {
    expect(validarLogin('a@b.com', '12345')).toBe(MENSAJE_GENERICO);
    expect(validarLogin('a@b.com', 'x'.repeat(MAX_PASSWORD + 1))).toBe(MENSAJE_GENERICO);
    expect(validarLogin('a'.repeat(MAX_EMAIL) + '@b.com', '123456')).toBe(MENSAJE_GENERICO);
  });

  it('correo con formato inválido o inyección -> "Escribe un correo válido"', () => {
    for (const malo of ['sin-arroba.com', 'a@b', 'a b@c.com', "' OR 1=1 --@x.com", '<script>@x.com', 'a@.com']) {
      expect(validarLogin(malo, '123456')).withContext(malo).toBe('Escribe un correo válido');
    }
    expect(correoValido('cliente2@seppro.com')).toBeTrue();
  });
});
