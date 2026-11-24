// Práctica 12 — Resume una carpeta de evidencias de perfil-app.sh (antes / despues).
// Uso: node pruebas/practica12/resumen-perfil.mjs antes   (o despues)
import { readFileSync, existsSync, writeFileSync } from 'node:fs';

const etq = process.argv[2] ?? 'antes';
const dir = `pruebas/practica12/evidencias/${etq}`;
const leer = (f) => (existsSync(`${dir}/${f}`) ? readFileSync(`${dir}/${f}`, 'utf8') : '');
const est = (xs) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const prom = s.reduce((a, b) => a + b, 0) / s.length;
  const p = (q) => s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)];
  return { n: s.length, min: +s[0].toFixed(1), prom: +prom.toFixed(1), p90: +p(0.9).toFixed(1), max: +s.at(-1).toFixed(1) };
};

// CPU (top del dispositivo, %CPU sobre 400 % = 4 núcleos)
const cpu = leer('top_cpu.txt').split('\n').map((l) => l.trim().match(/^(\d+(?:\.\d+)?)\s+\S+$/)).filter(Boolean).map((m) => +m[1]);
const cpuEst = est(cpu);
const activos = cpu.filter((x) => x >= 1);

// Memoria PSS (kB) muestreada cada 2 s
const pss = leer('memoria_pss_kb.txt').trim().split('\n').map((l) => +l.split(' ')[1]).filter((x) => x > 0);

// Operaciones instrumentadas
const ops = leer('logcat.txt').split('\n').filter((l) => l.includes('SEPPRO_Perf')).map((l) => {
  const g = (k) => +(l.match(new RegExp(`${k}=(\\d+)`))?.[1] ?? NaN);
  return { op: l.match(/op=(.+?) red_ms/)?.[1], red: g('red_ms'), ui: g('ui_hilo_principal_ms'), total: g('total_hasta_dibujo_ms'), filas: g('filas'), bytes: g('bytes'), vistas: g('vistas') };
});

// Congelamientos y cuadros
const saltos = [...leer('choreographer.txt').matchAll(/Skipped (\d+) frames/g)].map((m) => +m[1]);
const gfx = leer('gfxinfo.txt');
const gv = (re) => gfx.match(re)?.[1];
const errores = leer('logcat.txt').split('\n').filter((l) => /SEPPRO_(Pedidos|Chat).*(Error|No se pudo)|FATAL|ANR in/.test(l));

const r = {
  etiqueta: etq,
  cpu_pct_de_400: cpuEst, cpu_mientras_trabaja: est(activos),
  ram_pss_mb: pss.length ? { inicial: +(pss[0] / 1024).toFixed(1), max: +(Math.max(...pss) / 1024).toFixed(1), final: +(pss.at(-1) / 1024).toFixed(1), muestras: pss.length } : null,
  operaciones: ops,
  tiempo_total_ms: est(ops.map((o) => o.total)), tiempo_hilo_principal_ms: est(ops.map((o) => o.ui)), tiempo_red_ms: est(ops.map((o) => o.red)),
  congelamientos: { eventos: saltos.length, cuadros_saltados: saltos.reduce((a, b) => a + b, 0), max: saltos.length ? Math.max(...saltos) : 0 },
  gfx: { cuadros: gv(/Total frames rendered: (\d+)/), lentos: gv(/Janky frames: \d+ \(([\d.]+%)\)/), p50: gv(/50th percentile: (\d+ms)/), p90: gv(/90th percentile: (\d+ms)/), p95: gv(/95th percentile: (\d+ms)/) },
  errores: errores.length,
};
console.log(JSON.stringify(r, null, 2));
writeFileSync(`${dir}/resumen.json`, JSON.stringify(r, null, 2));
