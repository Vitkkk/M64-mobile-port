package io.github.zenith.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.zenith.R
import io.github.zenith.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que roda a instalação: o Android não mata o download e o
 * preparo (minutos) quando o jogador sai do app, e a notificação mostra o andamento.
 */
class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Installer.cancel()
            return START_NOT_STICKY
        }
        channel()
        val notification = notification(Installer.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        if (work?.isActive != true) {
            val keep = intent?.getBooleanExtra(EXTRA_KEEP, false) ?: false
            work = scope.launch {
                val watcher = launch {
                    var last = 0L
                    Installer.state.collectLatest { s ->
                        val now = System.currentTimeMillis()
                        if (now - last > 750 || !s.running) {
                            last = now
                            manager().notify(ID, notification(s))
                        }
                    }
                }
                Installer.run(applicationContext, keep)
                watcher.cancel()
                val s = Installer.state.value
                stopForeground(STOP_FOREGROUND_DETACH)
                manager().notify(ID, notification(s))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Instalação do jogo", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(s: Installer.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (s.step == Installer.Step.DONE) "Minecraft ${s.version} instalado" else "Instalando Minecraft ${s.version}")
            .setContentText(s.error ?: "${s.step.label}: ${s.message}".take(120))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(s.running)
        if (s.running) {
            builder.setProgress(1000, (s.overall * 1000).toInt(), s.fraction < 0 && s.step != Installer.Step.DOWNLOAD)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, InstallService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
            )
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "install"
        private const val ID = 4242
        const val ACTION_CANCEL = "io.github.zenith.install.CANCEL"
        const val EXTRA_KEEP = "keep"

        fun start(context: Context, keepDownloads: Boolean) {
            val intent = Intent(context, InstallService::class.java).putExtra(EXTRA_KEEP, keepDownloads)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancel(context: Context) {
            Installer.cancel()
            context.startService(Intent(context, InstallService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
