// Práctica 11 — Resume el reporte XML de JaCoCo de seppro-movil.
// Uso (desde la raíz del repo):  node pruebas/cobertura-resumen.mjs
import { readFileSync } from 'node:fs';

const xml = readFileSync('seppro-movil/app/build/reports/coverage/test/debug/report.xml', 'utf8');

// Métodos críticos evaluados en la Fase 2
const CRITICOS = [
  'ValidacionLogin.validar', 'ValidacionLogin.correoValido',
  'SoporteApi.horaLocal', 'RealtimeCanal$Companion.esperaReintento',
  'Reporte$Companion.desdeJson', 'Mensaje$Companion.desdeJson',
  'Api.request', 'Api.mensajeDeError',
];
// Módulos (clases) críticos
const MODULOS = ['ValidacionLogin', 'Api', 'RealtimeCanal$Companion', 'Reporte$Companion', 'Mensaje$Companion'];

const contador = (bloque, tipo) => {
  const re = new RegExp(`<counter type="${tipo}" missed="(\\d+)" covered="(\\d+)"/>`, 'g');
  let m, ultimo = null;
  while ((m = re.exec(bloque))) ultimo = { perdidas: +m[1], cubiertas: +m[2] };
  return ultimo;
};
const pct = (c) => (!c ? '—' : `${(100 * c.cubiertas / (c.cubiertas + c.perdidas)).toFixed(1)} % (${c.cubiertas}/${c.cubiertas + c.perdidas})`);

const filas = [];
const reClase = /<class name="com\/seppro\/movil\/([^"]+)"[^>]*>([\s\S]*?)<\/class>/g;
let c;
const porClase = {};
while ((c = reClase.exec(xml))) {
  const [_, clase, cuerpo] = c;
  const reMet = /<method name="([^"]+)" desc="[^"]*"(?: line="(\d+)")?>([\s\S]*?)<\/method>/g;
  let m;
  while ((m = reMet.exec(cuerpo))) {
    const nombre = `${clase}.${m[1]}`;
    if (CRITICOS.includes(nombre)) {
      const cx = contador(m[3], 'COMPLEXITY');
      filas.push({
        metodo: nombre, linea: m[2] ?? '',
        instrucciones: pct(contador(m[3], 'INSTRUCTION')),
        ramas: pct(contador(m[3], 'BRANCH')),
        lineas: pct(contador(m[3], 'LINE')),
        complejidad: cx ? cx.cubiertas + cx.perdidas : '',
      });
    }
  }
  // contadores de la clase = los que aparecen después del último </method>
  const resto = cuerpo.slice(cuerpo.lastIndexOf('</method>'));
  porClase[clase] = { i: contador(resto, 'INSTRUCTION'), b: contador(resto, 'BRANCH') };
}

console.log('\n=== Métodos críticos ===');
console.table(filas);

console.log('=== Módulos críticos ===');
let ti = { cubiertas: 0, perdidas: 0 }, tb = { cubiertas: 0, perdidas: 0 };
const mods = MODULOS.map((m) => {
  const { i, b } = porClase[m] ?? {};
  if (i) { ti.cubiertas += i.cubiertas; ti.perdidas += i.perdidas; }
  if (b) { tb.cubiertas += b.cubiertas; tb.perdidas += b.perdidas; }
  return { modulo: m, instrucciones: pct(i), ramas: pct(b) };
});
console.table(mods);
console.log('Total módulos críticos -> instrucciones', pct(ti), '| ramas', pct(tb));

const total = xml.slice(xml.lastIndexOf('</package>'));
console.log('Toda la app (incluye pantallas sin pruebas JVM) -> instrucciones',
  pct(contador(total, 'INSTRUCTION')), '| ramas', pct(contador(total, 'BRANCH')));
