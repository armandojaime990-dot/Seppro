package com.seppro.movil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Prácticas 13 y 14 — AUT-02 (unitarias): lógica de negocio de la verificación (ViewModel + StateFlow).
 * El backend y el sensor se sustituyen por dobles de prueba: se valida SOLO la lógica.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerificacionViewModelTest {

    private class RepoFalso : RepositorioVerificacion {
        var llamadasVerificar = 0
        var errorVerificar: Exception? = null
        var correoDisponible = true
        val alertas = mutableListOf<Pair<String, String?>>()

        override suspend fun verificar(pedidoId: Int, t: Telemetria, sensor: String?): ResultadoVerificacion {
            llamadasVerificar++
            errorVerificar?.let { throw it }
            return ResultadoVerificacion(pedidoId, if (t.golpe) "alerta_integridad" else "entregado_verificado",
                t.golpe, if (t.golpe) null else "GAR-00001", if (t.golpe) null else "2027-11-24", false)
        }

        override suspend fun resolverAlerta(pedidoId: Int, accion: String, motivo: String?): ResultadoAlerta {
            alertas += accion to motivo
            return if (accion == "rechazar") ResultadoAlerta(pedidoId, "rechazado", 9, "REP009")
            else ResultadoAlerta(pedidoId, "entregado_con_danos", null, null)
        }

        override suspend fun enviarCertificado(pedidoId: Int) = correoDisponible
    }

    private class Lector(private val t: Telemetria? = null, private val error: Exception? = null) : LectorTelemetria {
        override suspend fun leer(direccion: String): Telemetria = error?.let { throw it } ?: t!!
    }

    private lateinit var repo: RepoFalso
    private val avisosReloj = mutableListOf<String>()
    private lateinit var vm: VerificacionViewModel

    @Before fun preparar() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = RepoFalso()
        vm = VerificacionViewModel(repo) { pedido, estado, _ -> avisosReloj += "$pedido:$estado" }
    }

    @After fun limpiar() = Dispatchers.resetMain()

    // Entrada válida -> resultado esperado (garantía y aviso al reloj)
    @Test fun sinGolpe_verificaActivaGarantiaYAvisaAlReloj() {
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 0)), "AA:BB", "Tag")
        val e = vm.estado.value as EstadoVerificacion.Verificado
        assertEquals("GAR-00001", e.resultado.garantiaFolio)
        assertEquals(true, e.correoEnviado)
        assertEquals(listOf("42:entregado_verificado"), avisosReloj)
    }

    @Test fun conGolpe_pasaAAlertaDeIntegridad() {
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 1)), "AA:BB", "Tag")
        assertEquals(EstadoVerificacion.Alerta(42, Telemetria("SEPPRO-42", 1)), vm.estado.value)
        assertEquals(listOf("42:alerta_integridad"), avisosReloj)
    }

    @Test fun rechazarEquipo_abreReporteYRecortaMotivo() {
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 1)), "AA:BB", "Tag")
        vm.rechazarEquipo(42, "  caja abollada " + "x".repeat(600))
        val r = (vm.estado.value as EstadoVerificacion.Resuelto).resultado
        assertEquals("rechazado", r.estado)
        assertEquals("REP009", r.reporteFolio)
        assertEquals(500, repo.alertas.single().second!!.length)
    }

    // Entrada vacía o fuera de rango
    @Test fun sinPedido_noLlamaAlServidor() {
        vm.verificar(0, Lector(Telemetria("SEPPRO-42", 0)), "AA:BB", null)
        assertTrue(vm.estado.value is EstadoVerificacion.Error)
        assertEquals(0, repo.llamadasVerificar)
    }

    @Test fun sensorDeOtroPaquete_noSeRegistra() {
        vm.verificar(42, Lector(Telemetria("SEPPRO-43", 0)), "AA:BB", null)
        val e = vm.estado.value as EstadoVerificacion.Error
        assertTrue(e.mensaje.contains("SEPPRO-43"))
        assertEquals(0, repo.llamadasVerificar)
    }

    @Test fun lecturaInvalidaDelSensor_errorControlado() {
        vm.verificar(42, Lector(error = TelemetriaInvalidaException("Estatus fuera de rango (7): debe ser 0 o 1")), "AA", null)
        assertEquals("Lectura inválida del Smart Tag: Estatus fuera de rango (7): debe ser 0 o 1",
            (vm.estado.value as EstadoVerificacion.Error).mensaje)
    }

    // Manejo de excepciones del servidor y de la red
    @Test fun excepciones_seTraducenAMensajes() {
        repo.errorVerificar = ApiException(400, "El pedido no está listo para verificar (estado actual: pagado)")
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 0)), "AA", null)
        assertEquals("El pedido no está listo para verificar (estado actual: pagado)",
            (vm.estado.value as EstadoVerificacion.Error).mensaje)

        vm.reiniciar()
        repo.errorVerificar = ApiException(404, "Could not find the function public.verificar_entrega")
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 0)), "AA", null)
        assertEquals(VerificacionViewModel.FUNCION_NO_INSTALADA, (vm.estado.value as EstadoVerificacion.Error).mensaje)

        vm.reiniciar()
        repo.errorVerificar = IOException("timeout")
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 0)), "AA", null)
        assertTrue((vm.estado.value as EstadoVerificacion.Error).mensaje.startsWith("Sin conexión"))

        vm.reiniciar()
        repo.errorVerificar = SesionExpiradaException()
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 0)), "AA", null)
        assertTrue((vm.estado.value as EstadoVerificacion.Error).sesionExpirada)
    }

    @Test fun correoNoDisponible_laGarantiaSigueActiva() {
        repo.correoDisponible = false
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 0)), "AA", null)
        val e = vm.estado.value as EstadoVerificacion.Verificado
        assertEquals(false, e.correoEnviado)
        assertEquals("GAR-00001", e.resultado.garantiaFolio)
    }

    @Test fun alertaPendiente_seResuelveSinReleerElSensor() {
        vm.mostrarAlertaPendiente(0) // fuera de rango: se ignora
        assertEquals(EstadoVerificacion.Inactivo, vm.estado.value)
        vm.mostrarAlertaPendiente(42)
        assertEquals(EstadoVerificacion.Alerta(42, null), vm.estado.value)
        vm.aceptarConDanos(42)
        assertEquals("entregado_con_danos", (vm.estado.value as EstadoVerificacion.Resuelto).resultado.estado)
    }

    @Test fun reiniciar_vuelveAInactivo() {
        vm.verificar(42, Lector(Telemetria("SEPPRO-42", 1)), "AA", null)
        vm.reiniciar()
        assertEquals(EstadoVerificacion.Inactivo, vm.estado.value)
    }
}
