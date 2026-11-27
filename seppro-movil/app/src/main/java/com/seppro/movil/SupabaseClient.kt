package com.seppro.movil

object SupabaseClient {
    // Mismas credenciales que seppro-wearos y environment.ts
    const val URL = "https://sbhkqjueesxsjkfootuq.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InNiaGtxanVlZXN4c2prZm9vdHVxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY0MDE4NDcsImV4cCI6MjEwMTk3Nzg0N30.eWHoi6xpJsaSkHw91aSoT_pdV229tqb0xz054hT5iCg"

    // Sesión en memoria (nunca en disco ni en Logcat)
    var accessToken: String? = null
    var userId: String? = null

    // Perfil (tabla perfiles): el rol decide qué ve el usuario en Soporte
    var nombre: String = ""
    var rol: String = Roles.USUARIO

    fun cerrarSesion() {
        accessToken = null
        userId = null
        nombre = ""
        rol = Roles.USUARIO
    }
}

/** Roles de perfiles.rol (Práctica 7). */
object Roles {
    const val ADMIN = "admin"
    const val AGENTE = "agente"
    const val USUARIO = "usuario"
    const val INVITADO = "invitado"

    fun esStaff(rol: String) = rol == ADMIN || rol == AGENTE

    /**
     * Permisos por rol: cliente e invitado usan Pedidos, Verificar, Historial y Soporte;
     * agente y administrador solo Soporte. El servidor aplica lo mismo (supabase-permisos-roles.sql).
     */
    fun puedeVerificar(rol: String) = !esStaff(rol)

    fun etiqueta(rol: String) = when (rol) {
        ADMIN -> "Administrador"
        AGENTE -> "Agente"
        INVITADO -> "Invitado"
        else -> "Cliente"
    }
}
