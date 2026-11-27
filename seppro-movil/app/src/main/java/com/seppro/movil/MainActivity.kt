package com.seppro.movil

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView

/**
 * MainActivity - Diseño "Americano": Toolbar arriba y BottomNavigationView abajo.
 * Las pestañas cambian entre Fragments con FragmentManager.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    /** Pedido elegido con "Verificar paquete" (0 = ninguno). */
    private var pedidoSeleccionadoId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // El token vive solo en memoria: si el sistema cerró la app, volver al login
        if (SupabaseClient.accessToken == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        pedidoSeleccionadoId = savedInstanceState?.getInt(KEY_PEDIDO, 0) ?: 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // Barra superior
        val toolbar = Toolbar(this).apply {
            title = "SEPPRO"
            setTitleTextColor(0xFFFFFFFF.toInt())
            // Solo el nombre de quien inició sesión
            subtitle = SupabaseClient.nombre.ifBlank { "Mi cuenta" }
            setSubtitleTextColor(0xFFDBEAFE.toInt())
            setBackgroundColor(COLOR_PRIMARY)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setSupportActionBar(toolbar)

        // Contenedor de Fragments
        val container = FrameLayout(this).apply { id = R.id.fragment_container }
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Barra inferior con 4 pestañas para el cliente: Pedidos, Verificar, Historial y Soporte.
        // Agente y administrador solo usan Soporte: no se muestra la barra.
        val colores = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(COLOR_ACCENT, 0xFF94A3B8.toInt())
        )
        bottomNav = BottomNavigationView(this).apply {
            id = R.id.bottom_nav
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            itemIconTintList = colores
            itemTextColor = colores
            menu.add(Menu.NONE, TAB_PEDIDOS, 0, "Pedidos")
                .setIcon(android.R.drawable.ic_menu_agenda)
            menu.add(Menu.NONE, TAB_VERIFICAR, 1, "Verificar")
                .setIcon(android.R.drawable.ic_menu_search)
            menu.add(Menu.NONE, TAB_HISTORIAL, 2, "Historial")
                .setIcon(android.R.drawable.ic_menu_recent_history)
            menu.add(Menu.NONE, TAB_SOPORTE, 3, "Soporte")
                .setIcon(android.R.drawable.ic_menu_help)
            setOnItemSelectedListener { item ->
                mostrarPestana(item.itemId)
                true
            }
        }
        if (!cliente) bottomNav.visibility = android.view.View.GONE
        root.addView(bottomNav, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        // Al rotar, FragmentManager restaura el Fragment actual por sí solo
        if (savedInstanceState == null) {
            mostrarPestana(if (cliente) TAB_PEDIDOS else TAB_SOPORTE)
        }
    }

    private val cliente get() = Roles.puedeVerificar(SupabaseClient.rol)

    private fun mostrarPestana(itemId: Int) {
        val fragment: Fragment = if (!cliente) SoporteFragment() else when (itemId) {
            TAB_VERIFICAR -> VerificarFragment.newInstance(pedidoSeleccionadoId)
            TAB_HISTORIAL -> HistorialFragment()
            TAB_SOPORTE -> SoporteFragment()
            else -> PedidosFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    override fun onStart() {
        super.onStart()
        Presencia.activar() // latido del agente/admin mientras la app está abierta
    }

    override fun onStop() {
        super.onStop()
        Presencia.pausar()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SALIR, 0, "Cerrar sesión")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SALIR) {
            AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("¿Quieres salir de tu cuenta?")
                .setPositiveButton("Salir") { _, _ -> Presencia.desconectar { irAlLogin() } }
                .setNegativeButton("Cancelar", null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Llamado desde PedidosFragment con el botón "Verificar paquete". */
    fun abrirVerificar(pedidoId: Int) {
        if (!cliente) return
        pedidoSeleccionadoId = pedidoId
        bottomNav.selectedItemId = TAB_VERIFICAR
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PEDIDO, pedidoSeleccionadoId)
    }

    companion object {
        const val TAB_PEDIDOS = 1
        const val TAB_VERIFICAR = 2
        const val TAB_HISTORIAL = 3
        const val TAB_SOPORTE = 4
        private const val MENU_SALIR = 100
        private const val KEY_PEDIDO = "pedido_seleccionado_id"
    }
}
