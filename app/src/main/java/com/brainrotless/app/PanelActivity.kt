package com.brainrotless.app

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Date

/**
 * Pantallas de configuración con el estilo del inicio: Modo dormir, Modo foco, Compromiso e
 * Instagram y YouTube. Escriben en los mismos ajustes que la pantalla de Ajustes; cada cambio
 * vuelve a armar la pantalla (es chica y se arma al instante).
 */
class PanelActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA = "panel"
        const val SLEEP = "sleep"
        const val FOCUS = "focus"
        const val COMMIT = "commit"
        const val APPS = "apps"
        const val STATS = "stats"

        fun intent(c: Context, panel: String): Intent = Intent(c, PanelActivity::class.java).putExtra(EXTRA, panel)
        fun open(c: Context, panel: String) = c.startActivity(intent(c, panel))
    }

    private val notifAsk = NotifAsk(this)
    private lateinit var list: LinearLayout
    private val panel get() = intent.getStringExtra(EXTRA) ?: SLEEP
    private var draftDays = Commitment.MIN_DAYS // días elegidos antes de empezar un compromiso
    private var statsRange = 7 // días que muestran las estadísticas
    private var animateNext = true // los gráficos crecen al abrir la pantalla, no en cada cambio

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) })
    }

    override fun onResume() {
        super.onResume()
        render() // al volver de los ajustes del sistema (permisos) también
    }

    private fun render() {
        val a = actions()
        val animate = animateNext.also { animateNext = false }
        val screen = when (panel) {
            FOCUS -> PanelScreen.focus(this, focusState(), a)
            COMMIT -> if (Commitment.active(this)) PanelScreen.activeCommitment(this, activeState(animate), a)
                else PanelScreen.newCommitment(this, newState(), a)
            APPS -> PanelScreen.apps(this, appsState(), a)
            STATS -> PanelScreen.stats(this, statsState(animate), a)
            else -> PanelScreen.sleep(this, sleepState(), a)
        }
        // Las piezas pasan a la misma lista de siempre: así no se pierde lo que estaba desplazado
        list.removeAllViews()
        list.setPadding(screen.paddingLeft, screen.paddingTop, screen.paddingRight, screen.paddingBottom)
        while (screen.childCount > 0) screen.getChildAt(0).let { v ->
            val lp = v.layoutParams
            screen.removeViewAt(0)
            list.addView(v, lp)
        }
    }

    // ---------- Datos de cada pantalla ----------

    private fun sleepState() = PanelScreen.SleepState(
        on = Sleep.enabled(this),
        commitActive = Commitment.active(this),
        guardOn = Setup.guardOn(this),
        restricted = Setup.restrictedApplies(),
        perDay = Sleep.perDay(this),
        nights = (listOf<DayOfWeek?>(null) + DayOfWeek.values()).associateWith { Sleep.get(this, it) },
        browsersOn = userPrefs().getBoolean("sleep_browsers", true),
        browsers = if (Setup.guardOn(this)) Browsers.names(this) else emptyList(),
        warn = userPrefs().getString("sleep_warn", "15") ?: "15",
    )

    private fun focusState(): PanelScreen.FocusState {
        val (start, end) = Focus.times(this)
        return PanelScreen.FocusState(
            on = Focus.enabled(this),
            guardOn = Setup.guardOn(this),
            restricted = Setup.restrictedApplies(),
            start = start, end = end,
            days = Focus.days(this),
            blocksYouTube = Focus.blocksYouTube(this),
            sessionWarn = userPrefs().getString("session_warn", "20") ?: "20",
        )
    }

    private fun commitmentRules() = PanelScreen.rules(this, Sleep.enabled(this),
        userPrefs().getBoolean("sleep_browsers", true), Usage.limitMinutes(this))

    private fun newState() = PanelScreen.NewCommitState(
        draftDays = draftDays,
        limitMinutes = Usage.limitMinutes(this),
        rules = commitmentRules(),
        guardOn = Setup.guardOn(this),
        restricted = Setup.restrictedApplies(),
        lastSummary = Commitment.lastSummary(this),
    )

    private fun activeState(animate: Boolean): PanelScreen.ActiveCommitState {
        val usageAccess = UsageAccess.granted(this)
        if (usageAccess) UsageAccess.baseline(this) // calcularlo ya, antes de que Android borre los datos viejos
        return PanelScreen.ActiveCommitState(
            guardOn = Setup.guardOn(this),
            restricted = Setup.restrictedApplies(),
            daysLeft = Commitment.daysLeft(this).toInt(),
            totalDays = getSharedPreferences("commitment", MODE_PRIVATE).getInt("days", Commitment.MIN_DAYS),
            endMillis = Commitment.endMillis(this),
            usedMs = Usage.todayMs(this),
            limitMs = Usage.limitMs(this),
            limitMinutes = Usage.limitMinutes(this),
            pausesLeft = Commitment.pausesLeft(this),
            pauseUntil = Commitment.pauseUntil(this).takeIf { Commitment.pauseActive(this) },
            canPause = Commitment.canPause(this),
            usageAccess = usageAccess,
            history = Usage.history(this),
            today = Usage.today(this),
            before = UsageAccess.baseline(this),
            debug = BuildConfig.DEBUG,
            animate = animate,
        )
    }

    private fun appsState() = PanelScreen.AppsState(
        feed = userPrefs().getString("ig_feed", "normal") ?: "normal",
        prefs = PanelScreen.APP_PREFS.mapValues { (key, default) -> userPrefs().getBoolean(key, default) },
        guardOn = Setup.guardOn(this),
        listenerOn = Setup.listenerOn(this),
        linksOn = Setup.linksOn(this),
        restricted = Setup.restrictedApplies(),
    )

    private fun statsState(animate: Boolean) = PanelScreen.StatsState(
        history = Usage.history(this),
        today = Usage.today(this),
        range = statsRange,
        before = UsageAccess.baseline(this),
        usageAccess = UsageAccess.granted(this),
        limitMs = Usage.limitMs(this),
        commitStart = Commitment.startDay(this),
        goal = StreakGoal.of(this),
        weeklyOn = userPrefs().getBoolean("weekly_summary", true),
        lastSummary = Commitment.lastSummary(this),
        resetAt = Usage.nextReset(this).toLocalTime(),
        animate = animate,
    )

    // ---------- Qué hace cada toque ----------

    private fun actions() = PanelScreen.Actions(
        back = { finish() },
        turnOnGuard = { Setup.openAccessibility(this) },
        explainRestricted = { Setup.explainRestricted(this) },
        sleepOn = { on ->
            change({ it.copy(sleepOn = on) }) { putBoolean("sleep_on", on) }
            if (on) notifAsk.run() // para el aviso de antes de dormir
        },
        pickNight = ::pickNight,
        perDay = { on ->
            change({ s -> s.copy(nights = DayOfWeek.values().associateWith { Sleep.get(this, if (on) it else null) }) }) {
                putBoolean("sleep_per_day", on)
            }
        },
        browsers = { on -> change({ it.copy(browsers = on) }) { putBoolean("sleep_browsers", on) } },
        sleepWarn = { v -> change { putString("sleep_warn", v) } },
        focusOn = { on -> change { putBoolean("focus_on", on) } },
        pickFocus = {
            val (start, end) = Focus.times(this)
            pickTime(getString(R.string.pick_focus_start), start) { s ->
                pickTime(getString(R.string.pick_focus_end), end) { e -> Focus.setTimes(this, s, e); changed() }
            }
        },
        focusDays = { days -> change { putStringSet("focus_days", days.map { it.value.toString() }.toSet()) } },
        focusYouTube = { on -> change { putBoolean("focus_youtube", on) } },
        sessionWarn = { v -> change { putString("session_warn", v) } },
        draftDays = { d -> draftDays = d; render() },
        limit = { min ->
            if (Commitment.active(this)) change({ it.copy(limitMinutes = min.takeIf { m -> m > 0 }) }) { putString("daily_limit", min.toString()) }
            else change { putString("daily_limit", min.toString()) }
        },
        startCommit = { confirmCommitment(draftDays) },
        showLast = { Commitment.lastSummary(this)?.let { showSummary(it, markSeen = false) } },
        pause = {
            Dialogs.pauseConfirm(this, Commitment.pausesLeft(this)) {
                if (Commitment.startPause(this)) toast(getString(R.string.pause_15))
                changed()
            }.show()
        },
        openUsageAccess = { UsageAccess.open(this) },
        stats = { open(this, STATS) },
        debugEnd = {
            Commitment.cancelForTesting(this)
            Sleep.reschedule(this)
            Widgets.update(this)
            Commitment.onEnd(this)?.let { showSummary(it, markSeen = true) }
            render()
        },
        feed = { f -> change { putString("ig_feed", f) } },
        pref = { key, on -> change { putBoolean(key, on) } },
        openAccessibility = { Setup.openAccessibility(this) },
        enableMessages = { Setup.enableMessages(this, notifAsk) },
        openLinks = { Setup.openLinks(this) },
        statsRange = { n -> statsRange = n; animateNext = true; render() },
        weekly = { on ->
            change { putBoolean("weekly_summary", on) }
            if (on) notifAsk.run()
        },
    )

    /** Hora de dormir y después de despertar, del horario general (null) o de un día. */
    private fun pickNight(day: DayOfWeek?) {
        val (sleep, wake) = Sleep.get(this, day)
        pickTime(getString(R.string.pick_sleep), sleep) { newSleep ->
            pickTime(getString(R.string.pick_wake), wake) { newWake ->
                // El horario general rige todos los días; el de un día, solo ese
                val days = day?.let { listOf(it) } ?: DayOfWeek.values().toList()
                if (Commitment.forbids(this) { s -> s.copy(nights = s.nights + days.associateWith { newSleep to newWake }) }) {
                    toast(getString(R.string.loosen_forbidden))
                } else {
                    Sleep.set(this, day, newSleep, newWake)
                    changed()
                }
            }
        }
    }

    private fun confirmCommitment(days: Int) {
        val end = Date(System.currentTimeMillis() + days * 24 * 60 * 60 * 1000L)
        val dialog = Dialogs.confirmCommitment(this, days, end, commitmentRules()) {
            Commitment.start(this, days)
            Sleep.reschedule(this)
            Widgets.update(this)
            toast(getString(R.string.commit_started, days))
            render()
            notifAsk.run { offerCompare() } // avisos del límite y del final; después, comparar con antes
        }.show()
        // 5 segundos para leerlo antes de poder confirmar
        val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        start.isEnabled = false
        for (left in 5 downTo 0) start.postDelayed({
            start.text = if (left > 0) getString(R.string.start_countdown, left) else getString(R.string.start)
            start.isEnabled = left == 0
        }, (5 - left) * 1000L)
    }

    /** Recién empezado el compromiso: ofrecer el permiso para la estadística final. */
    private fun offerCompare() {
        if (UsageAccess.granted(this) || isFinishing) return
        Dialogs.offerCompare(this) { UsageAccess.open(this) }.show()
    }

    private fun showSummary(text: String, markSeen: Boolean) {
        if (markSeen) Commitment.markSummarySeen(this)
        Dialogs.summary(this, text, R.string.done,
            onAnother = { if (panel == COMMIT) render() else open(this, COMMIT) },
            onCharts = if (panel != STATS) ({ open(this, STATS) }) else null).show()
    }

    // ---------- Cambios ----------

    /** Guarda un cambio, salvo que afloje un compromiso activo, y vuelve a armar la pantalla. */
    private fun change(strict: ((Strictness) -> Strictness)? = null, write: SharedPreferences.Editor.() -> Unit) {
        if (strict != null && Commitment.forbids(this, strict)) {
            toast(getString(R.string.loosen_forbidden))
            render()
            return
        }
        val e = userPrefs().edit()
        e.write()
        e.apply()
        changed()
    }

    private fun changed() {
        Sleep.reschedule(this)
        Widgets.update(this)
        render()
    }

    private fun pickTime(title: String, t: LocalTime, done: (LocalTime) -> Unit) {
        TimePickerDialog(this, { _, h, m -> done(LocalTime.of(h, m)) }, t.hour, t.minute, DateFormat.is24HourFormat(this))
            .apply { setTitle(title) }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
