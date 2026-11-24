#!/usr/bin/env bash
# Práctica 11 — utilidades para manejar el emulador por ADB (prueba de interrupción NET-05/06 y WB-06).
# Uso: source pruebas/red/emulador.sh
ADB="${ADB:-C:/Users/arman/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1  # Git Bash: no convertir /sdcard en ruta de Windows
EVID="${EVID:-pruebas/evidencias}"

# Toca el primer elemento cuya propiedad text (o content-desc) contenga el texto dado
tocar() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local xml; xml=$("$ADB" exec-out cat /sdcard/ui.xml)
  local b; b=$(echo "$xml" | tr '>' '\n' | grep -E "(text|content-desc)=\"[^\"]*$1" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
  if [ -z "$b" ]; then echo "No encontré: $1"; return 1; fi
  set -- $b
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

escribir() { "$ADB" shell input text "$(echo "$1" | sed 's/ /%s/g')"; }
captura() { "$ADB" exec-out screencap -p > "$EVID/$1.png"; echo "captura: $EVID/$1.png"; }
textos() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -oE 'text="[^"]+"' | sed 's/text=//' ; }
avion() { "$ADB" shell cmd connectivity airplane-mode "$1"; sleep 3; echo "modo avión: $1"; }
