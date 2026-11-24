package com.seppro.movil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Prácticas 13 y 14 — AUT-04/05/06 (integración): la app real (VerificacionApi + Api + OkHttp +
 * ViewModel) contra un servidor HTTP simulado que responde igual que Supabase (MockWebServer).
 * Se valida la comunicación entre componentes: petición enviada, código HTTP y respuesta procesada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerificacionIntegracionTest {

    private lateinit var servidor: MockWebServer

    @Before fun iniciar() {
        servidor = MockWebServer()
        servidor.start()
        Api.baseUrl = servidor.url("").toString().trimEnd('/')
        SupabaseClient.accessToken = "jwt-de-prueba"
    }

    @After fun detener() {
        servidor.shutdown()
        SupabaseClient.cerrarSesion()
        Api.baseUrl = SupabaseClient.URL
        Dispatchers.resetMain()
    }

    // AUT-04: la app envía la telemetría al servicio y recibe una respuesta válida
    @Test fun verificarEntrega_enviaTelemetriaYProcesaRespuesta() = runBlocking {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"pedido_id":42,"estado":"entregado_verificado","golpe":false,"garantia_folio":"GAR-00007","garantia_vence":"2027-11-24","repetido":false}"""))

        val r = VerificacionApi.verificar(42, Telemetria("SEPPRO-42", 0), "SmartTag-01")

        val pet = servidor.takeRequest()
        assertEquals("POST", pet.method)
        assertEquals("/rest/v1/rpc/verificar_entrega", pet.path)
        assertEquals("Bearer jwt-de-prueba", pet.getHeader("Authorization"))
        assertEquals(SupabaseClient.ANON_KEY, pet.getHeader("apikey"))
        val cuerpo = JSONObject(pet.body.readUtf8())
        assertEquals(42, cuerpo.getInt("p_pedido"))
        assertEquals("SEPPRO-42", cuerpo.getString("p_paquete"))
        assertEquals(0, cuerpo.getInt("p_estatus"))
        assertEquals("SmartTag-01", cuerpo.getString("p_sensor"))
        assertEquals(ResultadoVerificacion(42, "entregado_verificado", false, "GAR-00007", "2027-11-24", false), r)
    }

    // AUT-05: la API devuelve el código HTTP esperado y la app muestra el mensaje del servidor
    @Test fun codigosHttp_seRespetan() = runBlocking {
        servidor.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"code":"55000","message":"El pedido no está listo para verificar (estado actual: pagado)"}"""))
        try {
            VerificacionApi.verificar(42, Telemetria("SEPPRO-42", 0), null)
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.codigo)
            assertEquals("El pedido no está listo para verificar (estado actual: pagado)", e.message)
        }
        servidor.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        try {
            VerificacionApi.verificar(42, Telemetria("SEPPRO-42", 0), null)
            fail("Debió lanzar SesionExpiradaException")
        } catch (e: SesionExpiradaException) { /* esperado: la app vuelve al login */ }

        // El certificado: 200 = enviado, 503 = correo sin configurar (la garantía sigue activa)
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("""{"enviado":true,"folio":"GAR-00007"}"""))
        assertTrue(VerificacionApi.enviarCertificado(42))
        servidor.enqueue(MockResponse().setResponseCode(503).setBody("""{"enviado":false}"""))
        assertFalse(VerificacionApi.enviarCertificado(42))
        servidor.takeRequest(); servidor.takeRequest()
        assertEquals("/functions/v1/enviar-certificado", servidor.takeRequest().path)
    }

    // AUT-05: el servidor no responde -> la pantalla muestra un mensaje adecuado (ViewModel + API real)
    @Test fun servidorCaido_pantallaMuestraMensajeAdecuado() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val vm = VerificacionViewModel(VerificacionApi) { _, _, _ -> }
        vm.verificar(42, object : LectorTelemetria {
            override suspend fun leer(direccion: String) = Telemetria("SEPPRO-42", 0)
        }, "AA:BB", null)
        val estado = withTimeout(10_000) { vm.estado.first { it is EstadoVerificacion.Error } }
        assertEquals("Sin conexión. Revisa tu Internet e intenta de nuevo.", (estado as EstadoVerificacion.Error).mensaje)
    }

    // AUT-06 (persistencia): el rechazo se guarda en el servidor y devuelve el reporte creado
    @Test fun rechazarEquipo_persisteYDevuelveReporte() = runBlocking {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"pedido_id":42,"estado":"rechazado","reporte_id":15,"reporte_folio":"REP015","repetido":false}"""))
        val r = VerificacionApi.resolverAlerta(42, "rechazar", "Carcasa abollada")
        val pet = servidor.takeRequest()
        assertEquals("/rest/v1/rpc/resolver_alerta", pet.path)
        val cuerpo = JSONObject(pet.body.readUtf8())
        assertEquals("rechazar", cuerpo.getString("p_accion"))
        assertEquals("Carcasa abollada", cuerpo.getString("p_motivo"))
        assertEquals(ResultadoAlerta(42, "rechazado", 15, "REP015"), r)
    }
}
