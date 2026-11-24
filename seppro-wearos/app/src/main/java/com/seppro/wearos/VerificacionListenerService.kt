package com.seppro.wearos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Entregable 2: el teléfono avisa al reloj el resultado de la verificación con el Smart Tag
 * (Wear OS Data Layer, ruta /seppro/verificacion). El reloj muestra una notificación con vibración.
 * Teléfono y reloj comparten applicationId (com.seppro.movil) y firma, requisito de Data Layer.
 */
class VerificacionListenerService : WearableListenerService() {

    override fun onMessageReceived(evento: MessageEvent) {
        if (evento.path != RUTA) return
        val aviso = try {
            AvisoVerificacion.desdeJson(String(evento.data, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Mensaje inválido del teléfono: ${e.javaClass.simpleName}")
            return
        }
        mostrar(this, aviso)
    }

    companion object {
        const val RUTA = "/seppro/verificacion"
        private const val TAG = "SEPPRO_Reloj"
        private const val CANAL = "verificacion"

        fun mostrar(context: Context, aviso: AvisoVerificacion) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL, "Verificación de entregas",
                NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Sin permiso de notificaciones")
                return
            }
            val abrir = PendingIntent.getActivity(context, 0,
                Intent(context, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notificacion = NotificationCompat.Builder(context, CANAL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(aviso.titulo)
                .setContentText(aviso.detalle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(aviso.detalle))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(longArrayOf(0, 300, 150, 300))
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .build()
            NotificationManagerCompat.from(context).notify(aviso.pedidoId, notificacion)
        }
    }
}

/** Mensaje del teléfono: {"pedido_id":42,"estado":"entregado_verificado","detalle":"..."}. */
data class AvisoVerificacion(val pedidoId: Int, val estado: String, val detalle: String) {
    val titulo: String
        get() = when (estado) {
            "entregado_verificado" -> "✅ Pedido #$pedidoId verificado"
            "alerta_integridad" -> "🚨 Alerta en pedido #$pedidoId"
            "rechazado" -> "⛔ Pedido #$pedidoId rechazado"
            "entregado_con_danos" -> "⚠️ Pedido #$pedidoId con daños"
            else -> "Pedido #$pedidoId"
        }

    companion object {
        fun desdeJson(texto: String): AvisoVerificacion {
            val j = JSONObject(texto)
            val id = j.getInt("pedido_id")
            require(id > 0) { "pedido_id inválido" }
            return AvisoVerificacion(id, j.getString("estado"), j.optString("detalle").take(200))
        }
    }
}
