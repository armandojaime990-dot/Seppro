package com.seppro.wearos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * LoginActivity - Autenticación nativa contra Supabase
 */
class LoginActivity : ComponentActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var loginBtn: Button

    // Entregable 2: permiso para mostrar los avisos de verificación que manda el teléfono
    private val permisoNotificaciones = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permisoNotificaciones.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 32)
            setBackgroundColor(0xFFFFFFFF.toInt()) // Blanco
        }

        // Title
        val title = TextView(this).apply {
            text = "SEPPRO"
            textSize = 18f
            setTextColor(0xFF003D7A.toInt()) // Azul Primary
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        mainLayout.addView(title)

        // Email
        emailInput = EditText(this).apply {
            hint = "Correo"
            textSize = 12f
            setHintTextColor(0xFF94A3B8.toInt())
            setTextColor(0xFF0F172A.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            filters = arrayOf(android.text.InputFilter.LengthFilter(ValidacionLogin.MAX_EMAIL))
            setSingleLine()
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFF1F5F9.toInt()) // Light gray
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }
        mainLayout.addView(emailInput)

        // Password
        passwordInput = EditText(this).apply {
            hint = "Contraseña"
            textSize = 12f
            setHintTextColor(0xFF94A3B8.toInt())
            setTextColor(0xFF0F172A.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(ValidacionLogin.MAX_PASSWORD))
            setSingleLine()
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFF1F5F9.toInt()) // Light gray
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }
        mainLayout.addView(passwordInput)

        // Status text
        statusText = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(0xFFEF5350.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(statusText)

        // Login Button
        loginBtn = Button(this).apply {
            text = "Entrar"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF0072C6.toInt()) // Azul Accent
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                performLogin()
            }
        }
        mainLayout.addView(loginBtn)

        val scrollView = ScrollView(this).apply {
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
            statusText.setTextColor(0xFFEF5350.toInt())
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
                    Log.i(TAG, "Inicio de sesión correcto")

                    runOnUiThread {
                        statusText.text = "¡Conectado!"
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                } else {
                    // Mensaje genérico: no se revela si falló el correo o la contraseña
                    // (ni se muestra el texto del servidor). Solo se registra el código HTTP.
                    Log.w(TAG, "Inicio de sesión rechazado (HTTP $responseCode)")
                    runOnUiThread {
                        statusText.text = ValidacionLogin.MENSAJE_GENERICO
                        statusText.setTextColor(0xFFEF5350.toInt())
                        loginBtn.isEnabled = true
                        passwordInput.text.clear()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Sin printStackTrace: solo el tipo de error, nunca datos del usuario
                Log.w(TAG, "Error de conexión en el inicio de sesión: ${e.javaClass.simpleName}")
                runOnUiThread {
                    statusText.text = "Error de conexión"
                    statusText.setTextColor(0xFFEF5350.toInt())
                    loginBtn.isEnabled = true
                }
            }
        }
    }

    companion object {
        private const val TAG = "SEPPRO_Login"
    }
}
