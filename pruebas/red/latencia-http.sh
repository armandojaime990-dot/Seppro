#!/usr/bin/env bash
# Práctica 11 — NET-02: latencia HTTP desglosada (DNS, TCP, TLS, primer byte, total) con curl.
# Diez solicitudes consecutivas al MISMO endpoint que usa la app (pedidos del cliente), mismas condiciones.
# Uso (desde la raíz del repo): SEPPRO_PASS=<contraseña> bash pruebas/red/latencia-http.sh
set -euo pipefail
URL=https://sbhkqjueesxsjkfootuq.supabase.co
KEY=$(grep -o 'ANON_KEY = "[^"]*"' seppro-movil/app/src/main/java/com/seppro/movil/SupabaseClient.kt | cut -d'"' -f2)
: "${SEPPRO_PASS:?Falta SEPPRO_PASS}"
TOKEN=$(curl -s -X POST "$URL/auth/v1/token?grant_type=password" -H "apikey: $KEY" -H "Content-Type: application/json" \
  -d "{\"email\":\"cliente1@seppro.com\",\"password\":\"$SEPPRO_PASS\"}" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

echo "solicitud,codigo,dns_ms,tcp_ms,tls_ms,primer_byte_ms,total_ms,bytes"
for i in $(seq 1 10); do
  # Conexión nueva en cada solicitud (como la primera petición de la app al abrir una pantalla)
  curl -s -o /dev/null "$URL/rest/v1/pedidos?select=*,pedido_items(*)&order=created_at.desc" \
    -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN" \
    -w "$i,%{http_code},%{time_namelookup},%{time_connect},%{time_appconnect},%{time_starttransfer},%{time_total},%{size_download}\n" |
  awk -F, '{printf "%s,%s,%.1f,%.1f,%.1f,%.1f,%.1f,%s\n",$1,$2,$3*1000,$4*1000,$5*1000,$6*1000,$7*1000,$8}'
done
