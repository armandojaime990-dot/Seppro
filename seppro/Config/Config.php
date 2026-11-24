<?php
// Versión anterior (PHP). Seguridad: las credenciales ya NO van en el código fuente.
// Se leen de variables de entorno (Apache: SetEnv en el VirtualHost o en un .htaccess
// fuera del repositorio). La contraseña SMTP que estaba aquí quedó expuesta en el
// historial de Git y debe revocarse en la cuenta de Google (Contraseñas de aplicaciones).
function seppro_env(string $nombre, string $porDefecto = ''): string
{
    $valor = getenv($nombre);
    return ($valor === false || $valor === '') ? $porDefecto : $valor;
}

define('BASE_URL', seppro_env('SEPPRO_BASE_URL', 'http://localhost/seppro/'));
define('HOST', seppro_env('SEPPRO_DB_HOST', 'localhost'));
define('USER', seppro_env('SEPPRO_DB_USER', 'root'));
define('PASS', seppro_env('SEPPRO_DB_PASS'));
define('DB', seppro_env('SEPPRO_DB_NAME', 'seppro'));
const CHARSET = "charset=utf8";
const TITLE = "SEPPRO";
const MONEDA = "MXN";
define('CLIENTE_ID', seppro_env('SEPPRO_PAYPAL_CLIENT_ID')); // se consigue en PayPal Developer

define('USER_SMTP', seppro_env('SEPPRO_SMTP_USER'));
define('PASS_SMTP', seppro_env('SEPPRO_SMTP_PASS'));
define('PUERTO_SMTP', (int) seppro_env('SEPPRO_SMTP_PORT', '465'));
define('HOST_SMTP', seppro_env('SEPPRO_SMTP_HOST', 'smtp.gmail.com'));
