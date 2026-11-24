#!/usr/bin/env bash
# Práctica 12 — Fase 2: profiling de seppro-movil en el emulador (mismo guion para "antes" y "después").
#
# Ejecuta las operaciones obligatorias mientras muestrea CPU (top, cada 1 s) y memoria (dumpsys meminfo):
#   1) inicio de sesión como cliente2 (3 005 pedidos) -> consulta de "Mis pedidos"
#   2) consulta repetida: "Actualizar" x3
#   3) navegación entre las 4 pestañas (Historial vuelve a consultar)
#   4) escritura: 3 mensajes en el chat de un reporte
#   5) desplazamiento de la lista (procesamiento de vistas) + dumpsys gfxinfo
# Uso (desde la raíz del repo, con el emulador encendido y la app instalada):
#   bash pruebas/practica12/perfil-app.sh antes      (o "despues")
set -uo pipefail
ETQ="${1:?Uso: perfil-app.sh antes|despues}"
source pruebas/red/emulador.sh
OUT="pruebas/practica12/evidencias/$ETQ"; mkdir -p "$OUT"; EVID="$OUT"
PKG=com.seppro.movil
T0=$(date +%s)
marca() { echo "$(( $(date +%s) - T0 ))s  $1" | tee -a "$OUT/marcas.txt"; }
: > "$OUT/marcas.txt"

"$ADB" shell input keyevent KEYCODE_WAKEUP; "$ADB" shell wm dismiss-keyguard
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
"$ADB" shell input keyevent KEYCODE_HOME; "$ADB" shell am start -W -n $PKG/.LoginActivity >/dev/null 2>&1; sleep 3
PID=$("$ADB" shell pidof $PKG | tr -d '\r')
marca "app abierta (PID $PID)"

# Muestreo de CPU en el dispositivo (1 s) durante todo el guion
"$ADB" shell "top -b -d 1 -n 150 -p $PID -o %CPU,RES > /data/local/tmp/top_$ETQ.txt 2>&1 &"
# Muestreo de memoria (PSS) desde el equipo
( while true; do
    pss=$("$ADB" shell dumpsys meminfo $PKG 2>/dev/null | grep -m1 "TOTAL PSS" | awk '{print $3}')
    echo "$(( $(date +%s) - T0 )) $pss" >> "$OUT/memoria_pss_kb.txt"; sleep 2
  done ) & MEM=$!
: > "$OUT/memoria_pss_kb.txt"
sleep 3

# 1) Login como cliente2 -> carga de Mis pedidos
tocar "seppro.com"; "$ADB" shell input keycombination 113 29; "$ADB" shell input keyevent KEYCODE_DEL
escribir "cliente2@seppro.com"; tocar "Contrase"; escribir "123456"; "$ADB" shell input keyevent 111; sleep 1
marca "1 login + consulta de pedidos"; tocar "Entrar"; sleep 25

# 2) Consulta repetida
for i in 1 2 3; do marca "2 Actualizar #$i"; tocar "Actualizar"; sleep 15; done

# 3) Navegación
marca "3 pestaña Verificar"; tocar "Verificar"; sleep 4
marca "3 pestaña Historial (consulta)"; tocar "Historial"; sleep 20
marca "3 pestaña Soporte"; tocar "Soporte"; sleep 6
marca "3 pestaña Pedidos (consulta)"; tocar "Pedidos"; sleep 20

# 4) Escritura: 3 mensajes en un reporte de cliente2
tocar "Soporte"; sleep 5; tocar "REP0"; sleep 6
for i in 1 2 3; do
  marca "4 enviar mensaje #$i"; tocar "Escribe un mensaje"; escribir "Perfil $ETQ mensaje $i"; tocar "Enviar"; sleep 4
done
"$ADB" shell input keyevent 111; "$ADB" shell input keyevent KEYCODE_BACK; sleep 2

# 5) Procesamiento de vistas: desplazamiento de la lista de pedidos
tocar "Pedidos"; sleep 20
"$ADB" shell dumpsys gfxinfo $PKG reset >/dev/null
marca "5 desplazamiento x10"
for i in 1 2 3 4 5; do "$ADB" shell input swipe 540 1800 540 400 200; sleep 0.3; "$ADB" shell input swipe 540 400 540 1800 200; sleep 0.3; done
"$ADB" shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt"
captura "pantalla-final"
marca "fin"

kill $MEM 2>/dev/null; sleep 1
"$ADB" shell pkill -f "top -b -d 1" 2>/dev/null
"$ADB" pull /data/local/tmp/top_$ETQ.txt "$OUT/top_cpu.txt" >/dev/null
"$ADB" logcat -d -s SEPPRO_Perf:* SEPPRO_Pedidos:* SEPPRO_Chat:* AndroidRuntime:E ActivityManager:W > "$OUT/logcat.txt"
"$ADB" logcat -d --pid=$PID -s Choreographer:I > "$OUT/choreographer.txt"
"$ADB" shell dumpsys meminfo $PKG > "$OUT/meminfo-final.txt"
"$ADB" logcat -d -b crash > "$OUT/crash.txt"
echo "Evidencias en $OUT"
