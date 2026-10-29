package com.brainrotless.app

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.max

/** Uso de Instagram y YouTube oficiales antes de instalar Lúcido (para la estadística final). */
object UsageAccess {
    private val OFFICIAL = listOf("com.instagram.android", "com.google.android.youtube")
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun granted(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun open(c: Context) {
        val pkg = Uri.parse("package:${c.packageName}")
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(direct) }.onFailure {
            runCatching { c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /**
     * Promedio diario en las 4 semanas antes de instalar Lúcido. Se calcula una vez y se
     * guarda, porque Android va borrando los datos viejos. null si no hay permiso o datos.
     */
    fun baseline(c: Context): Long? {
        val p = c.getSharedPreferences("usage_baseline", Context.MODE_PRIVATE)
        if (p.contains("ms_per_day")) return p.getLong("ms_per_day", 0)
        if (!granted(c)) return null
        val install = c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
        val from = install - 28 * DAY_MS
        val stats = c.getSystemService(UsageStatsManager::class.java)
            ?.queryAndAggregateUsageStats(from, install) ?: return null
        val found = OFFICIAL.mapNotNull { stats[it] }
        val ms = found.sumOf { it.totalTimeInForeground }
        if (ms <= 0) return null
        val since = max(from, found.minOf { it.firstTimeStamp })
        val days = ((install - since) / DAY_MS).coerceIn(1, 28)
        return (ms / days).also { p.edit().putLong("ms_per_day", it).apply() }
    }
}

/**
 * Difícil de sacar durante un compromiso o el modo dormir: el escudo de la accesibilidad contra
 * desinstalar o detener Lúcido, y aviso fijo si se apaga la accesibilidad.
 */
object Protection {
    private const val CHANNEL = "guard"
    private const val NOTIF_ID = 2003

    /** Accesibilidad apagada durante un compromiso: notificación fija hasta que se vuelva a activar. */
    @SuppressLint("MissingPermission") // sin permiso, notify() falla en silencio
    fun updateGuardNotice(c: Context, guardOn: Boolean = Setup.guardOn(c)) {
        val nm = NotificationManagerCompat.from(c)
        if (guardOn || !Commitment.active(c)) { nm.cancel(NOTIF_ID); return }
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(c.getString(R.string.channel_guard)).build()
        )
        val pi = PendingIntent.getActivity(
            c, 2, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = c.getString(R.string.guard_notif_text)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(c.getString(R.string.guard_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    fun isGuardNotice(sbn: StatusBarNotification) = sbn.packageName == PKG && sbn.id == NOTIF_ID

    // ---------- Escudo contra desinstalar / forzar detención (punto 3) ----------

    /** Protección activa: durante un compromiso o en horario de dormir. */
    fun shielded(c: Context) = Commitment.active(c) || Sleep.active(c)

    /** Pantallas del sistema desde donde se desinstala o se fuerza la detención de una app. */
    fun isSystemManager(pkg: String?) =
        pkg == "com.android.settings" || (pkg != null && pkg.contains("packageinstall"))

    private val ACTION_RE =
        Regex("desinstal|uninstall|forzar deten|forzar parada|force.?stop|detener", RegexOption.IGNORE_CASE)

    /**
     * True si la pantalla que se ve es para desinstalar o detener Lúcido (no otra app). Mira solo
     * texto: busca el paquete de Lúcido, o su nombre junto a una acción de desinstalar/detener.
     */
    fun screenTargetsLucido(root: AccessibilityNodeInfo?, c: Context): Boolean {
        root ?: return false
        val label = c.getString(R.string.app_name).lowercase()
        var hasLabel = false
        var hasAction = false
        var hasPkg = false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val node = queue.removeFirst()
            seen++
            val text = ((node.text ?: "").toString() + " " + (node.contentDescription ?: "")).lowercase()
            if (text.isNotBlank()) {
                if (text.contains(PKG)) hasPkg = true
                if (text.contains(label)) hasLabel = true
                if (ACTION_RE.containsMatchIn(text)) hasAction = true
            }
            if (hasPkg || (hasLabel && hasAction)) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    // ---------- Esconder el ícono de Lúcido (punto 4) ----------

    fun iconHidingEnabled(c: Context) = c.userPrefs().getBoolean("hide_icon", false)

    /** Esconde o muestra el ícono del panel de Lúcido según el ajuste y la protección. */
    fun applyIconVisibility(c: Context) {
        val hide = iconHidingEnabled(c) && shielded(c)
        val comp = ComponentName(c, HomeActivity::class.java)
        val pm = c.packageManager
        val want = if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (pm.getComponentEnabledSetting(comp) != want)
            runCatching { pm.setComponentEnabledSetting(comp, want, PackageManager.DONT_KILL_APP) }
                .onSuccess { Widgets.update(c, force = true) } // tocarlos abre el inicio o, escondido, las estadísticas
    }

    /** El ícono (y con él el inicio) está escondido ahora. */
    fun iconHidden(c: Context) = c.packageManager.getComponentEnabledSetting(ComponentName(c, HomeActivity::class.java)) ==
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
