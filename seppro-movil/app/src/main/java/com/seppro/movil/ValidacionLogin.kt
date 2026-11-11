package com.seppro.movil

import android.util.Patterns

/**
 * Práctica 5 (código seguro) aplicada al inicio de sesión de SEPPRO.
 *
 * Valida el correo y la contraseña ANTES de enviarlos a Supabase Auth.
 * Esta validación del lado del cliente no sustituye a la autenticación:
 * quien decide si las credenciales son correctas es el servidor (Supabase).
 */
object ValidacionLogin {

    /** Longitud máxima de un correo (RFC 5321). */
    const val MAX_EMAIL = 254

    /** Mínimo de Supabase Auth y del registro de la tienda web. */
    const val MIN_PASSWORD = 6

    /** Supabase (bcrypt) solo toma en cuenta los primeros 72 caracteres. */
    const val MAX_PASSWORD = 72

    /** No dice si falló el correo o la contraseña. */
    const val MENSAJE_GENERICO = "Correo o contraseña incorrectos"

    /** Devuelve el mensaje de error para mostrar, o null si los datos son válidos. */
    fun validar(email: String, password: String): String? = when {
        email.isEmpty() || password.isEmpty() -> "Llena todos los campos"
        password.isBlank() -> "Llena todos los campos" // contraseña de solo espacios
        email.length > MAX_EMAIL || password.length > MAX_PASSWORD -> MENSAJE_GENERICO
        email.any { it.isWhitespace() } || !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
            "Escribe un correo válido"
        password.length < MIN_PASSWORD -> MENSAJE_GENERICO
        else -> null
    }
}
