// Capturas de la app móvil web (PWA) simulando un iPhone 14 (390x844, Safari iOS).
// Uso: SEPPRO_PASS=<contraseña> PUPPETEER_DIR=<carpeta con puppeteer-core> node pruebas/practica13-14/capturas-pwa.mjs [url-base]
// Requiere `ng serve` (http://localhost:4200) y Chrome instalado.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const require = createRequire(path.join(process.env.PUPPETEER_DIR ?? '.', 'x.js'));
const puppeteer = require('puppeteer-core');
const BASE = process.argv[2] ?? 'http://localhost:4200';
const PASS = process.env.SEPPRO_PASS;
const CUENTA = process.env.SEPPRO_CUENTA ?? 'cliente2@seppro.com';
const OUT = 'pruebas/practica13-14/evidencias/pwa';
mkdirSync(OUT, { recursive: true });

const navegador = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true,
});
const pag = await navegador.newPage();
await pag.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1');
await pag.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
const errores = [];
pag.on('pageerror', (e) => errores.push(e.message));

const foto = async (nombre) => { await new Promise((ok) => setTimeout(ok, 1500)); await pag.screenshot({ path: `${OUT}/${nombre}.png` }); console.log('captura', nombre); };
const tocarTexto = async (texto) => {
  await pag.evaluate((t) => [...document.querySelectorAll('button, a')].find((b) => b.textContent.trim().startsWith(t))?.click(), texto);
};

await pag.goto(`${BASE}/app/login`, { waitUntil: 'networkidle2' });
await foto('01-login');

await pag.evaluate(() => { const e = document.querySelector('input[name=email]'); e.value = ''; e.dispatchEvent(new Event('input')); });
await pag.type('input[name=email]', CUENTA);
await pag.type('input[name=password]', PASS);
await pag.click('button[type=submit]');
await pag.waitForFunction(() => location.pathname.startsWith('/app/pedidos'), { timeout: 20000 });
await pag.waitForFunction(() => document.body.innerText.includes('Mostrando') || document.body.innerText.includes('No tienes'), { timeout: 20000 });
await foto('02-pedidos');

const enPuerta = await pag.evaluate(() => [...document.querySelectorAll('button')].some((b) => b.textContent.includes('Verificar paquete')));
await tocarTexto('Historial');
await pag.waitForFunction(() => location.pathname.endsWith('/historial'));
await foto('03-historial');

await pag.goto(`${BASE}/app/verificar?pedido=${process.env.SEPPRO_PEDIDO ?? 3005}`, { waitUntil: 'networkidle2' });
await foto('04-verificar');
if (process.env.SEPPRO_SIMULAR) {
  await pag.evaluate(() => [...document.querySelectorAll('button')].find((b) => b.textContent.includes('No encuentras'))?.click());
  await tocarTexto(process.env.SEPPRO_SIMULAR === '1' ? 'Llegó golpeado' : 'Llegó en buen estado');
  await new Promise((ok) => setTimeout(ok, 5000));
  await foto(`05-verificar-simulado-${process.env.SEPPRO_SIMULAR}`);
}

await pag.goto(`${BASE}/app/soporte`, { waitUntil: 'networkidle2' });
await foto('06-soporte');
const hayReporte = await pag.evaluate(() => { const t = document.querySelector('article.m-tarjeta'); t?.click(); return !!t; });
if (hayReporte) {
  await pag.waitForFunction(() => location.pathname.startsWith('/app/reporte/'), { timeout: 10000 });
  await new Promise((ok) => setTimeout(ok, 3000));
  await foto('07-reporte-chat');
}

console.log(JSON.stringify({ errores, pedidoEnPuerta: enPuerta }));
await navegador.close();
