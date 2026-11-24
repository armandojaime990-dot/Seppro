/** "2026-11-10T18:04:05.123456+00:00" -> "12:04" (o "10/11/2026 12:04" con fecha), hora local. */
export function horaLocal(iso: string | null | undefined, conFecha = false): string {
  if (!iso) return '';
  const fecha = new Date(iso.replace(/(\.\d{3})\d+/, '$1'));
  if (Number.isNaN(fecha.getTime())) return iso.slice(0, 16).replace('T', ' ');
  const dos = (n: number) => String(n).padStart(2, '0');
  const hora = `${dos(fecha.getHours())}:${dos(fecha.getMinutes())}`;
  return conFecha ? `${dos(fecha.getDate())}/${dos(fecha.getMonth() + 1)}/${fecha.getFullYear()} ${hora}` : hora;
}

/** Nombre del dispositivo para el chat (equivale a Build.MANUFACTURER + MODEL en Android). */
export function nombreDispositivo(ua: string = typeof navigator !== 'undefined' ? navigator.userAgent : ''): string {
  if (/iPhone/.test(ua)) return 'iPhone (PWA)';
  if (/iPad/.test(ua)) return 'iPad (PWA)';
  const android = /Android[^;]*;\s*([^;)]+?)(?:\sBuild|\))/.exec(ua);
  if (android) return `${android[1].trim()} (PWA)`.slice(0, 80);
  if (/Macintosh/.test(ua)) return 'Mac (PWA)';
  if (/Windows/.test(ua)) return 'Windows (PWA)';
  return 'Navegador (PWA)';
}
