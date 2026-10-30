package com.brainrotless.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Si se abre la app oficial (ícono, recientes, widget, compartir), salta a Lúcido.
 * De noche (modo dormir) también cierra los navegadores.
 */
class AppGuard : AccessibilityService() {

    private val redirects = mapOf(
        "com.instagram.android" to "$PKG.Instagram",
        "com.google.android.youtube" to "$PKG.YouTube"
    )
    private var lastJump = 0L
    private var foreground: String? = null // última app que pasó a primer plano

    private var lastShield = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui") return // la cortina de notificaciones no cambia la app de abajo

        // Escudo: durante un compromiso o el modo dormir, no dejar desinstalar ni detener Lúcido.
        // Se mira la pantalla de Ajustes/instalador también cuando cambia su contenido (diálogos).
        // isSystemManager va primero (comparar texto es barato): así los eventos de contenido del
        // resto de las apps se descartan sin calcular nada pesado.
        if (Protection.isSystemManager(pkg) && Protection.shielded(this) &&
            Protection.screenTargetsLucido(rootInActiveWindow, this)
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShield > 1500) {
                lastShield = now
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, getString(R.string.shield_blocked), Toast.LENGTH_LONG).show()
            }
            return
        }

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        countOfficial()
        foreground = pkg
        countOfficial()
        enforce(pkg)
    }

    // Uso de las apps oficiales (con la pausa del bloqueo): también suma al límite diario
    private var officialSince = 0L

    private fun countOfficial() {
        val now = SystemClock.elapsedRealtime()
        val counting = foreground in redirects && getSystemService(PowerManager::class.java)?.isInteractive == true
        if (counting && officialSince > 0)
            Usage.add(this, now - officialSince, if (foreground == "com.google.android.youtube") Usage.App.YT else Usage.App.IG)
        officialSince = if (counting) now else 0
    }

    private fun enforce(pkg: String) {
        if (Sleep.blocksBrowsers(this) && pkg in Browsers.packages(this)) {
            if (!debounce()) return
            performGlobalAction(GLOBAL_ACTION_HOME)
            val until = Sleep.current(this)?.let { Sleep.fmt(this, it.end.toLocalTime()) }
            Toast.makeText(this, getString(R.string.browsers_blocked, until), Toast.LENGTH_LONG).show()
            return
        }
        val alias = redirects[pkg] ?: return
        if (Bypass.active(this) || Official.allowed(this, pkg) || !debounce()) return
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(this, alias))
                .putExtra(MainActivity.EXTRA_FROM_GUARD, true) // que IG Lite no la devuelva a la oficial
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * El evento se dispara varias veces seguidas: actuar una sola vez. Si en ese rato se vuelve a abrir (tocar
     * el ícono otra vez enseguida), se mira de nuevo al terminar: si no, el navegador quedaba abierto, porque
     * no llega otro evento hasta que se cambia de app.
     */
    private fun debounce(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastJump < 1500) {
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, lastJump + 1600 - now)
            return false
        }
        lastJump = now
        return true
    }

    /** La app que se ve ahora (la ventana activa; el teclado no cuenta), o la del último evento. */
    private fun front() = rootInActiveWindow?.packageName?.toString() ?: foreground

    private val recheck = Runnable { front()?.let(::enforce) }

    // Cuando empieza la noche o se llega al límite, sacar al usuario de lo que tenga abierto
    // (navegador o app oficial con la pausa), sin esperar a que cambie de pantalla
    private val handler = Handler(Looper.getMainLooper())
    private fun locked() = Lock.any(this) || Sleep.blocksBrowsers(this)
    // Apps oficiales dentro del límite: al terminarse el tiempo (o empezar la noche o el foco) se sale
    // de la oficial, aunque ya hubiera otro bloqueo antes (ej: un foco que no bloquea YouTube)
    private var officialWasAllowed = false

    private val lockTicker = object : Runnable {
        override fun run() {
            countOfficial()
            Widgets.update(this@AppGuard)
            Protection.applyIconVisibility(this@AppGuard) // esconder/mostrar el ícono al cambiar la noche o el compromiso
            // Bloqueado: se mira qué app se ve (al empezar la noche o el límite, y por las dudas de que se haya
            // escapado un evento)
            if (locked()) front()?.let(::enforce)
            val official = foreground?.takeIf { it in redirects }
            val allowed = official != null && Official.allowed(this@AppGuard, official)
            if (official != null && officialWasAllowed && !allowed) enforce(official)
            officialWasAllowed = allowed
            handler.removeCallbacks(this)
            // De noche, con los navegadores bloqueados, cada 10 s: si uno se escapó, no queda abierto más que eso
            val most = if (Sleep.blocksBrowsers(this@AppGuard)) 10_000L else 60_000L
            handler.postDelayed(this, (Lock.msUntilChange(this@AppGuard) + 500).coerceIn(1_000, most))
        }
    }

    override fun onServiceConnected() {
        Protection.updateGuardNotice(this, guardOn = true)
        lockTicker.run()
    }

    // Se apagó la accesibilidad (o se está actualizando la app; al volver se borra el aviso)
    override fun onUnbind(intent: Intent?): Boolean {
        Protection.updateGuardNotice(this, guardOn = false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(lockTicker)
        super.onDestroy()
    }

    override fun onInterrupt() {}
}

/**
 * Apps oficiales dentro del límite (opcional): con un compromiso con límite diario, mientras quede
 * tiempo, IG Lite y YT Lite abren Instagram y YouTube oficiales y no se los redirige. Su uso suma al
 * límite igual (AppGuard lo cuenta); al terminarse, de noche, en foco o durante una pausa, todo vuelve
 * a IG Lite y YT Lite. Hace falta Accesibilidad: es la que saca de la oficial cuando se termina.
 */
object Official {
    const val IG = "com.instagram.android"
    const val YT = "com.google.android.youtube"

    fun enabled(c: Context) = c.userPrefs().getBoolean("official_in_limit", false)

    fun allowed(c: Context, pkg: String): Boolean {
        if (!enabled(c) || (Usage.remainingMs(c) ?: 0) <= 0 || Commitment.pauseActive(c) || !Setup.guardOn(c)) return false
        return (if (pkg == YT) Lock.yt(c) else Lock.ig(c)) == null
    }
}

/** Pausa temporal del bloqueo (ej: subir una historia con filtros desde la app oficial). */
object Bypass {
    private fun prefs(c: Context) = c.getSharedPreferences("bypass", Context.MODE_PRIVATE)
    private fun minutes(c: Context) = c.userPrefs().getString("bypass_minutes", "10")?.toIntOrNull() ?: 10

    // De noche o pasado el límite no hay pausa: las apps oficiales dejarían ver todo
    fun active(c: Context) = !Lock.any(c) && prefs(c).getLong("until", 0) > System.currentTimeMillis()
    fun start(c: Context) =
        prefs(c).edit().putLong("until", System.currentTimeMillis() + minutes(c) * 60_000L).apply()
    fun stop(c: Context) = prefs(c).edit().putLong("until", 0).apply()
    fun until(c: Context) = prefs(c).getLong("until", 0)
}

/** Botón en los ajustes rápidos: "Apps oficiales" on/off por unos minutos. */
class BypassTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (Lock.any(this)) return refresh()
        if (Bypass.active(this)) Bypass.stop(this) else Bypass.start(this)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val lock = Lock.yt(this) ?: Lock.ig(this)
        t.label = getString(R.string.tile_label)
        t.state = when {
            lock != null -> Tile.STATE_UNAVAILABLE
            Bypass.active(this) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29)
            t.subtitle = lock?.let { getString(R.string.tile_blocked, Lock.until(this, it)) }
        t.updateTile()
    }
}
