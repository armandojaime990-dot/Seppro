package com.seppro.movil

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * LoginActivity - Autenticación nativa contra Supabase (mismo flujo que el reloj)
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var loginBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // Título
        mainLayout.addView(TextView(this).apply {
            text = "SEPPRO"
            textSize = 32f
            setTextColor(COLOR_PRIMARY)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        mainLayout.addView(TextView(this).apply {
            text = "Verificación de entregas"
            textSize = 14f
            setTextColor(COLOR_TEXTO_SUAVE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(32))
        })

        // Correo (se rellena con el último usado)
        emailInput = EditText(this).apply {
            hint = "Correo"
            textSize = 16f
            setHintTextColor(0xFF94A3B8.toInt())
            setTextColor(COLOR_TEXTO)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = fondoRedondeado(this@LoginActivity, COLOR_GRIS_CLARO, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }
        emailInput.filters = arrayOf(InputFilter.LengthFilter(ValidacionLogin.MAX_EMAIL))
        val ultimoEmail = Prefs.get(this).getString(Prefs.ULTIMO_EMAIL, null)
        if (!ultimoEmail.isNullOrEmpty()) emailInput.setText(ultimoEmail)
        mainLayout.addView(emailInput)

        // Contraseña
        passwordInput = EditText(this).apply {
            hint = "Contraseña"
            textSize = 16f
            setHintTextColor(0xFF94A3B8.toInt())
            setTextColor(COLOR_TEXTO)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = fondoRedondeado(this@LoginActivity, COLOR_GRIS_CLARO, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(16)) }
        }
        passwordInput.filters = arrayOf(InputFilter.LengthFilter(ValidacionLogin.MAX_PASSWORD))
        mainLayout.addView(passwordInput)
        if (!ultimoEmail.isNullOrEmpty()) passwordInput.requestFocus()

        // Texto de estado
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_ERROR)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        mainLayout.addView(statusText)

        // Botón Entrar
        loginBtn = Button(this).apply {
            text = "Entrar"
            textSize = 16f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = fondoRedondeado(this@LoginActivity, COLOR_ACCENT, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            setOnClickListener { performLogin() }
        }
        mainLayout.addView(loginBtn)

        // Recuperación de acceso perdido (caso de la Práctica 7)
        mainLayout.addView(TextView(this).apply {
            text = "¿Olvidaste tu contraseña?"
            textSize = 14f
            setTextColor(COLOR_ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            setOnClickListener { recuperarAcceso() }
        })

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(mainLayout)
        }

        setContentView(scrollView)
    }

    private fun performLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        // Práctica 5: validar antes de enviar (la autenticación real la hace Supabase)
        val error = ValidacionLogin.validar(email, password)
        if (error != null) {
            Log.w(TAG, "Intento de inicio de sesión rechazado por validación")
            statusText.text = error
            statusText.setTextColor(COLOR_ERROR)
            return
        }

        statusText.text = "Iniciando sesión..."
        statusText.setTextColor(0xFF4CAF50.toInt())
        loginBtn.isEnabled = false

        thread {
            try {
                val url = URL("${SupabaseClient.URL}/auth/v1/token?grant_type=password")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SupabaseClient.ANON_KEY)
                conn.doOutput = true

                val jsonPayload = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }

                val out = OutputStreamWriter(conn.outputStream)
                out.write(jsonPayload.toString())
                out.close()

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)
                    // El JWT solo vive en memoria y nunca se escribe en Logcat
                    SupabaseClient.accessToken = jsonResponse.getString("access_token")
                    SupabaseClient.userId = jsonResponse.getJSONObject("user").getString("id")

                    // Perfil: el rol (admin, agente, usuario) decide qué ve en Soporte
                    if (!cargarPerfil()) {
                        Log.w(TAG, "Inicio de sesión rechazado: cuenta deshabilitada")
                        SupabaseClient.cerrarSesion()
                        runOnUiThread {
                            statusText.text = "Tu cuenta está deshabilitada. Contacta al administrador."
                            statusText.setTextColor(COLOR_ERROR)
                            loginBtn.isEnabled = true
                            passwordInput.text.clear()
                        }
                        return@thread
                    }
                    Log.i(TAG, "Inicio de sesión correcto")

                    // Guardar solo el correo (NUNCA la contraseña)
                    Prefs.get(this).edit().putString(Prefs.ULTIMO_EMAIL, email).apply()

                    runOnUiThread {
                        statusText.text = "¡Conectado!"
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                } else {
                    // Mensaje genérico: no se revela si falló el correo o la contraseña
                    // (ni se muestra el texto del servidor). Solo se registra el código HTTP.
                    Log.w(TAG, "Inicio de sesión rechazado (HTTP $responseCode)")
                    runOnUiThread {
                        statusText.text = ValidacionLogin.MENSAJE_GENERICO
                        statusText.setTextColor(COLOR_ERROR)
                        loginBtn.isEnabled = true
                        passwordInput.text.clear()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Sin printStackTrace: solo el tipo de error, nunca datos del usuario
                Log.w(TAG, "Error de conexión en el inicio de sesión: ${e.javaClass.simpleName}")
                SupabaseClient.cerrarSesion()
                runOnUiThread {
                    statusText.text = "Error de conexión"
                    statusText.setTextColor(COLOR_ERROR)
                    loginBtn.isEnabled = true
                }
            }
        }
    }

    /** Lee nombre, rol y habilitado de la tabla perfiles. Devuelve false si la cuenta está deshabilitada. */
    private fun cargarPerfil(): Boolean {
        val uid = SupabaseClient.userId ?: return false
        val filas = JSONArray(Api.get("/rest/v1/perfiles?select=nombre,rol,habilitado&id=eq.$uid"))
        if (filas.length() == 0) {
            // Sin perfil (no debería pasar: lo crea el trigger): se trata como cliente
            SupabaseClient.rol = Roles.USUARIO
            return true
        }
        val perfil = filas.getJSONObject(0)
        if (!perfil.optBoolean("habilitado", true)) return false
        SupabaseClient.nombre = perfil.optString("nombre", "")
        SupabaseClient.rol = perfil.optString("rol", Roles.USUARIO)
        return true
    }

    /**
     * Recuperación de acceso perdido: Supabase envía un enlace para cambiar la contraseña.
     * El mensaje es el mismo exista o no la cuenta (no se revela qué correos están registrados).
     */
    private fun recuperarAcceso() {
        val email = emailInput.text.toString().trim()
        if (email.isEmpty() || email.length > ValidacionLogin.MAX_EMAIL ||
            !ValidacionLogin.correoValido(email)) {
            statusText.text = "Escribe tu correo para recuperar el acceso"
            statusText.setTextColor(COLOR_ERROR)
            return
        }
        statusText.text = "Enviando..."
        statusText.setTextColor(COLOR_TEXTO_SUAVE)
        thread {
            try {
                val conn = URL("${SupabaseClient.URL}/auth/v1/recover").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SupabaseClient.ANON_KEY)
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("email", email).toString().toByteArray()) }
                Log.i(TAG, "Solicitud de recuperación de acceso (HTTP ${conn.responseCode})")
                conn.disconnect()
                runOnUiThread {
                    statusText.text = "Si el correo está registrado, recibirás un enlace para restablecer tu contraseña."
                    statusText.setTextColor(0xFF2E7D32.toInt())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error de conexión al recuperar acceso: ${e.javaClass.simpleName}")
                runOnUiThread {
                    statusText.text = "Error de conexión"
                    statusText.setTextColor(COLOR_ERROR)
                }
            }
        }
    }

    companion object {
        private const val TAG = "SEPPRO_Login"
    }
}
