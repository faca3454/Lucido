package com.brainrotless.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/**
 * Cómo se dibujan las pantallas del panel (Modo dormir, Modo foco, Compromiso, Instagram y
 * YouTube, Estadísticas), separado de dónde salen los datos (PanelActivity los junta). Se pueden
 * dibujar con datos de prueba (Paparazzi).
 */
object PanelScreen {
    /** Qué hace cada toque, de todas las pantallas del panel. */
    class Actions(
        val back: () -> Unit = {},
        val turnOnGuard: () -> Unit = {},
        val explainRestricted: () -> Unit = {},
        // Modo dormir
        val sleepOn: (Boolean) -> Unit = {},
        val pickNight: (DayOfWeek?) -> Unit = {},
        val perDay: (Boolean) -> Unit = {},
        val browsers: (Boolean) -> Unit = {},
        val sleepWarn: (String) -> Unit = {},
        // Modo foco
        val focusOn: (Boolean) -> Unit = {},
        val pickFocus: () -> Unit = {},
        val focusDays: (Set<DayOfWeek>) -> Unit = {},
        val focusYouTube: (Boolean) -> Unit = {},
        val sessionWarn: (String) -> Unit = {},
        // Compromiso
        val draftDays: (Int) -> Unit = {},
        val limit: (Int) -> Unit = {}, // minutos, 0 = sin límite
        val startCommit: () -> Unit = {},
        val showLast: () -> Unit = {},
        val pause: () -> Unit = {},
        val openUsageAccess: () -> Unit = {},
        val stats: () -> Unit = {},
        val debugEnd: () -> Unit = {},
        // Instagram y YouTube
        val feed: (String) -> Unit = {},
        val pref: (key: String, on: Boolean) -> Unit = { _, _ -> },
        val openAccessibility: () -> Unit = {},
        val enableMessages: () -> Unit = {},
        val openLinks: () -> Unit = {},
        // Estadísticas
        val statsRange: (Int) -> Unit = {},
        val weekly: (Boolean) -> Unit = {},
    )

    // ---------- Modo dormir ----------

    class SleepState(
        val on: Boolean = false,
        val commitActive: Boolean = false,
        val guardOn: Boolean = true,
        val restricted: Boolean = false,
        val perDay: Boolean = false,
        val nights: Map<DayOfWeek?, Pair<LocalTime, LocalTime>> = mapOf(null to (LocalTime.of(23, 30) to LocalTime.of(7, 0))), // null: el horario general
        val browsersOn: Boolean = true,
        val browsers: List<String> = emptyList(),
        val warn: String = "15",
    )

    fun sleep(c: Context, s: SleepState, a: Actions) = screen(c) { add ->
        add(header(c, c.getString(R.string.home_sleep), a.back, toggle(c, s.on, a.sleepOn)), 0)
        if (s.commitActive) add(banner(c, c.getString(R.string.panel_locked)), 12)
        add(muted(c, c.getString(R.string.pref_sleep_on_summary)), 4)
        if (s.on && !s.guardOn) add(guardNotice(c, R.string.need_guard_sleep, s.restricted, a), 12)

        // La noche en el dial del día, con la luna al acostarse y el sol al levantarse
        if (!s.perDay) {
            val (sleep, wake) = s.nights.getValue(null)
            fun hours(t: LocalTime) = t.hour + t.minute / 60f
            val size = Brand.px(c, 240)
            add(FrameLayout(c).apply {
                addView(DayDial(c).apply {
                    night = hours(sleep) to hours(wake)
                    handles = true
                    alpha = if (s.on) 1f else 0.38f // apagado, el horario queda guardado pero en segundo plano
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // las horas se leen abajo
                }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }, 4)
        }
        add(Brand.card(column(c,
            if (!s.perDay) {
                val (sleep, wake) = s.nights.getValue(null)
                timePair(c, c.getString(R.string.panel_bed), sleep, c.getString(R.string.panel_wake), wake,
                    R.color.brand_night, R.color.brand_accent) { a.pickNight(null) }
            } else column(c, *DayOfWeek.values().map { d ->
                val (sl, w) = s.nights[d] ?: s.nights.getValue(null)
                linkRow(c, c.getString(nightTitle(d)), range(c, sl, w)) { a.pickNight(d) }
            }.toTypedArray()),
            space(c, 10),
            switchRow(c, c.getString(R.string.pref_per_day), c.getString(R.string.panel_per_day_sub), s.perDay, a.perDay)
        )), 12)

        val browsers = when {
            !s.guardOn -> c.getString(R.string.browsers_need_guard)
            else -> s.browsers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: c.getString(R.string.browsers_none)
        }
        add(Brand.card(switchRow(c, c.getString(R.string.pref_browsers), browsers, s.browsersOn, a.browsers)), 12)

        add(label(c, c.getString(R.string.pref_warn)), 18)
        val warnValues = listOf("0", "5", "10", "15", "30")
        add(Brand.card(chips(c, warnValues.map { minutesChip(it.toInt(), c.getString(R.string.panel_no_warning)) },
            warnValues.indexOf(s.warn)) { i -> a.sleepWarn(warnValues[i]) }), 12)
    }

    private fun nightTitle(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> R.string.pref_night_1
        DayOfWeek.TUESDAY -> R.string.pref_night_2
        DayOfWeek.WEDNESDAY -> R.string.pref_night_3
        DayOfWeek.THURSDAY -> R.string.pref_night_4
        DayOfWeek.FRIDAY -> R.string.pref_night_5
        DayOfWeek.SATURDAY -> R.string.pref_night_6
        DayOfWeek.SUNDAY -> R.string.pref_night_7
    }

    // ---------- Modo foco ----------

    class FocusState(
        val on: Boolean = false,
        val guardOn: Boolean = true,
        val restricted: Boolean = false,
        val start: LocalTime = LocalTime.of(9, 0),
        val end: LocalTime = LocalTime.of(13, 0),
        val days: Set<DayOfWeek> = emptySet(),
        val blocksYouTube: Boolean = true,
        val sessionWarn: String = "20",
    )

    fun focus(c: Context, s: FocusState, a: Actions) = screen(c) { add ->
        add(header(c, c.getString(R.string.home_focus), a.back, toggle(c, s.on, a.focusOn)), 0)
        add(muted(c, c.getString(R.string.pref_focus_on_summary)), 4)
        if (s.on && !s.guardOn) add(guardNotice(c, R.string.need_guard_focus, s.restricted, a), 12)

        add(Brand.card(column(c,
            timePair(c, c.getString(R.string.pick_focus_start), s.start, c.getString(R.string.pick_focus_end), s.end, onClick = a.pickFocus),
            space(c, 12),
            dayChips(c, s.days) { d -> a.focusDays(if (d in s.days) s.days - d else s.days + d) }
        )), 12)

        add(Brand.card(switchRow(c, c.getString(R.string.pref_focus_youtube), c.getString(R.string.pref_focus_youtube_summary),
            s.blocksYouTube, a.focusYouTube)), 12)

        add(label(c, c.getString(R.string.pref_session)), 18)
        val values = listOf("0", "10", "15", "20", "30", "45", "60")
        add(Brand.card(column(c,
            muted(c, c.getString(R.string.panel_session_sub)),
            space(c, 10),
            chips(c, values.map { minutesChip(it.toInt(), c.getString(R.string.panel_off)) }, values.indexOf(s.sessionWarn)) { i ->
                a.sessionWarn(values[i])
            }
        )), 12)
    }

    // ---------- Compromiso ----------

    /** Las reglas que el compromiso no deja aflojar, según lo que está prendido. */
    fun rules(c: Context, sleepOn: Boolean, browsersOn: Boolean, limitMinutes: Int?) = buildList {
        if (sleepOn) {
            add(c.getString(R.string.rule_sleep))
            if (browsersOn) add(c.getString(R.string.rule_browsers))
        }
        limitMinutes?.let { add(c.getString(R.string.rule_limit, formatDuration(it * 60_000L))) }
    }

    class NewCommitState(
        val draftDays: Int = Commitment.MIN_DAYS,
        val limitMinutes: Int? = null,
        val rules: List<String> = emptyList(),
        val guardOn: Boolean = true,
        val restricted: Boolean = false,
        val lastSummary: String? = null,
    )

    private val LIMITS = listOf(0, 30, 45, 60, 90, 120, 180)
    private val DURATIONS = listOf(7, 14, 30, 60, 90)

    fun newCommitment(c: Context, s: NewCommitState, a: Actions) = screen(c) { add ->
        add(header(c, c.getString(R.string.home_commit), a.back), 0)
        add(muted(c, c.getString(R.string.panel_commit_intro)), 4)

        add(label(c, c.getString(R.string.panel_duration)), 18)
        val pauses = Commitment.pausesFor(s.draftDays)
        add(Brand.card(column(c,
            stepper(c, s.draftDays.toString(), c.resources.getQuantityString(R.plurals.days_word, s.draftDays) +
                " · " + c.resources.getQuantityString(R.plurals.pauses_n, pauses, pauses),
                c.getString(R.string.panel_days_minus), c.getString(R.string.panel_days_plus),
                onMinus = { a.draftDays((s.draftDays - 1).coerceAtLeast(Commitment.MIN_DAYS)) },
                onPlus = { a.draftDays((s.draftDays + 1).coerceAtMost(365)) }),
            space(c, 10),
            chips(c, DURATIONS.map { c.resources.getQuantityString(R.plurals.days_n, it, it) },
                DURATIONS.indexOf(s.draftDays)) { i -> a.draftDays(DURATIONS[i]) }
        )), 12)

        add(label(c, c.getString(R.string.pref_limit)), 18)
        add(Brand.card(limitChips(c, s.limitMinutes, a)), 12)

        add(Brand.card(column(c,
            Brand.text(c, c.getString(R.string.panel_pauses), Brand.Type.TITLE, bold = true),
            space(c, 4),
            muted(c, c.getString(R.string.panel_pauses_explain, pauses))
        )), 12)

        add(Brand.card(column(c,
            Brand.text(c, c.getString(R.string.panel_you_wont), Brand.Type.TITLE, bold = true),
            space(c, 6),
            if (s.rules.isEmpty()) muted(c, c.getString(R.string.commit_needs))
            else muted(c, s.rules.joinToString("\n"))
        )), 12)
        // Sin Accesibilidad, abrir las apps oficiales se saltearía el límite: no se puede empezar
        if (!s.guardOn) add(guardNotice(c, R.string.need_guard_commit, s.restricted, a), 12)
        val ready = s.rules.isNotEmpty() && s.guardOn
        add(Brand.primaryButton(c, c.getString(R.string.commit_start), a.startCommit).apply {
            isEnabled = ready
            alpha = if (ready) 1f else 0.4f
        }, 20)

        s.lastSummary?.let { last ->
            add(Brand.card(linkRow(c, c.getString(R.string.pref_last), last.lines().first(), a.showLast)), 16)
        }
    }

    class ActiveCommitState(
        val guardOn: Boolean = true,
        val restricted: Boolean = false,
        val daysLeft: Int = 0,
        val totalDays: Int = Commitment.MIN_DAYS,
        val endMillis: Long = 0,
        val usedMs: Long = 0,
        val limitMs: Long? = null,
        val limitMinutes: Int? = null,
        val pausesLeft: Int = 0,
        val pauseUntil: Long? = null, // pausa en curso
        val canPause: Boolean = false,
        val usageAccess: Boolean = false,
        val history: Map<LocalDate, DayUsage> = emptyMap(), // para la fila de estadísticas
        val today: LocalDate = LocalDate.of(2026, 1, 1),
        val before: Long? = null, // promedio de antes de Lúcido
        val debug: Boolean = false,
        val animate: Boolean = false, // la barra se llena desde cero (al abrir)
    )

    fun activeCommitment(c: Context, s: ActiveCommitState, a: Actions) = screen(c) { add ->
        add(header(c, c.getString(R.string.home_commit), a.back), 0)
        if (!s.guardOn) add(guardNotice(c, R.string.need_guard_commit, s.restricted, a), 4)
        val end = Date(s.endMillis)

        // Los días que faltan, en grande, y cuánto del compromiso ya pasó
        val total = s.totalDays.coerceAtLeast(1)
        add(Brand.card(column(c,
            LinearLayout(c).apply {
                gravity = Gravity.BOTTOM
                addView(Brand.number(c, s.daysLeft.toString(), Brand.Type.HERO))
                addView(Brand.text(c, c.resources.getQuantityString(R.plurals.days_remaining, s.daysLeft), Brand.Type.TITLE, R.color.brand_text_muted)
                    .apply { setPadding(Brand.px(c, 10), 0, 0, Brand.px(c, 8)) })
            },
            space(c, 12),
            FillBar(c, strong = true).apply { setFraction(1f - s.daysLeft.toFloat() / total, s.animate) },
            space(c, 12),
            muted(c, c.getString(R.string.panel_until, DateFormat.getDateFormat(c).format(end), DateFormat.getTimeFormat(c).format(end)))
        ).apply {
            contentDescription = s.daysLeft.toString() + " " + c.resources.getQuantityString(R.plurals.days_remaining, s.daysLeft) + ". " +
                c.getString(R.string.panel_until, DateFormat.getDateFormat(c).format(end), DateFormat.getTimeFormat(c).format(end))
        }), 4)

        // Hoy y pausas
        val usage = formatDuration(s.usedMs)
        val limitText = s.limitMs?.let { c.getString(R.string.widget_of_limit, formatDuration(it)) } ?: c.getString(R.string.widget_no_limit)
        add(LinearLayout(c).apply {
            addView(stat(c, c.getString(R.string.widget_title), usage, limitText), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(stat(c, c.getString(R.string.panel_pauses), s.pausesLeft.toString(), c.getString(R.string.panel_pauses_sub)),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Brand.px(c, 12) })
        }, 12)

        // Cómo vas comparado con antes y, de acá, las estadísticas (con el ícono escondido no hay inicio)
        add(Brand.card(linkRow(c, c.getString(R.string.stats_title), statsBrief(c, s.history, s.today, s.before), a.stats)), 12)

        when {
            s.pauseUntil != null -> add(banner(c, c.getString(R.string.pause_running,
                DateFormat.getTimeFormat(c).format(Date(s.pauseUntil)))), 12)
            s.canPause -> add(Brand.primaryButton(c, c.getString(R.string.pref_pause), a.pause), 12)
        }

        // Endurecer el límite (subirlo no se puede)
        add(label(c, c.getString(R.string.pref_limit)), 18)
        add(Brand.card(limitChips(c, s.limitMinutes, a)), 12)

        add(label(c, c.getString(R.string.panel_extras)), 18)
        add(Brand.card(
            if (s.usageAccess) infoRow(c, c.getString(R.string.pref_compare), c.getString(R.string.usage_access_on))
            else linkRow(c, c.getString(R.string.pref_compare), c.getString(R.string.usage_access_off), a.openUsageAccess)
        ), 12)

        if (s.debug) add(Brand.secondaryButton(c, c.getString(R.string.pref_debug_end), a.debugEnd), 20)
    }

    private fun limitChips(c: Context, current: Int?, a: Actions) =
        chips(c, LIMITS.map { if (it == 0) c.getString(R.string.widget_no_limit) else formatDuration(it * 60_000L) },
            LIMITS.indexOf(current ?: 0)) { i -> a.limit(LIMITS[i]) }

    // ---------- Instagram y YouTube ----------

    class AppsState(
        val feed: String = "normal",
        val prefs: Map<String, Boolean> = emptyMap(), // los que faltan, con su valor de siempre
        val guardOn: Boolean = false,
        val listenerOn: Boolean = false,
        val linksOn: Boolean? = null,
        val restricted: Boolean = false,
    )

    /** Interruptores de esta pantalla y su valor si nunca se tocaron. */
    val APP_PREFS = mapOf(
        "ig_block_explore" to true, "ig_tap_to_play" to false, "ig_reels_in_limit" to true,
        "ig_block_ads" to true, "yt_block_ads" to true,
        "smooth_transitions" to false, "swipe_reply" to true, "official_in_limit" to false,
    )

    fun apps(c: Context, s: AppsState, a: Actions) = screen(c) { add ->
        fun prefSwitch(key: String, title: Int, sub: Int) =
            switchRow(c, c.getString(title), c.getString(sub), s.prefs[key] ?: APP_PREFS.getValue(key)) { on -> a.pref(key, on) }

        add(header(c, c.getString(R.string.panel_apps), a.back), 0)
        add(label(c, c.getString(R.string.pref_feed)), 8)
        val feeds = listOf("normal", "following", "blocked")
        add(Brand.card(chips(c, c.resources.getStringArray(R.array.ig_feed_entries).toList(), feeds.indexOf(s.feed)) { i ->
            a.feed(feeds[i])
        }), 12)

        add(Brand.card(column(c,
            prefSwitch("ig_block_explore", R.string.pref_explore, R.string.pref_explore_summary),
            space(c, 12),
            prefSwitch("ig_tap_to_play", R.string.pref_tap, R.string.pref_tap_summary),
            space(c, 12),
            prefSwitch("ig_reels_in_limit", R.string.pref_reels, R.string.pref_reels_summary)
        )), 12)

        add(label(c, c.getString(R.string.pref_cat_ads)), 18)
        add(Brand.card(column(c,
            prefSwitch("ig_block_ads", R.string.pref_ig_ads, R.string.pref_ig_ads_summary),
            space(c, 12),
            prefSwitch("yt_block_ads", R.string.pref_yt_ads, R.string.pref_yt_ads_summary)
        )), 12)

        add(label(c, c.getString(R.string.pref_cat_comfort)), 18)
        add(Brand.card(column(c,
            prefSwitch("smooth_transitions", R.string.pref_smooth, R.string.pref_smooth_summary),
            space(c, 12),
            prefSwitch("swipe_reply", R.string.pref_swipe, R.string.pref_swipe_summary)
        )), 12)

        // Cada uno con su permiso, que se pide recién acá (o desde la función que lo necesita)
        add(label(c, c.getString(R.string.panel_official)), 18)
        val box = column(c,
            prefSwitch("official_in_limit", R.string.pref_official, R.string.pref_official_summary),
            space(c, 14),
            permissionRow(c, c.getString(R.string.pref_guard), c.getString(R.string.guard_sub), s.guardOn, onClick = a.openAccessibility),
            space(c, 14),
            permissionRow(c, c.getString(R.string.messages_title), c.getString(R.string.messages_sub), s.listenerOn, onClick = a.enableMessages),
            space(c, 14),
            permissionRow(c, c.getString(R.string.links_title), c.getString(R.string.links_sub), s.linksOn,
                action = c.getString(R.string.set_up), onClick = a.openLinks)
        )
        if (s.restricted && !(s.guardOn && s.listenerOn)) box.addView(Brand.text(c, c.getString(R.string.gray_title), Brand.Type.BODY,
            R.color.brand_primary, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = Brand.px(c, 48)
            setOnClickListener { a.explainRestricted() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Brand.px(c, 6) })
        add(Brand.card(box), 12)
    }

    /** Qué hace un permiso y, a la derecha, una tilde si ya está o el botón para activarlo. [done] null: no se puede saber. */
    private fun permissionRow(c: Context, title: String, sub: String, done: Boolean?, action: String = c.getString(R.string.turn_on),
                              onClick: () -> Unit) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Brand.px(c, 56)
        addView(column(c, Brand.text(c, title, Brand.Type.TITLE, bold = true), muted(c, sub)), LinearLayout.LayoutParams(0, WRAP, 1f))
        val status = if (done == true) ImageView(c).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(Brand.color(c, R.color.brand_primary))
            contentDescription = c.getString(R.string.turned_on)
        } else Brand.secondaryButton(c, action, onClick)
        addView(status, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Brand.px(c, 12) })
    }

    // ---------- Estadísticas ----------

    /**
     * Una línea de cómo vas, para la fila que lleva a las estadísticas (compromiso, Ajustes): contra
     * antes de Lúcido, si se puede; si no, el promedio de la semana o lo de hoy.
     */
    fun statsBrief(c: Context, history: Map<LocalDate, DayUsage>, today: LocalDate, before: Long?): String {
        val avg = StatsMath.average(StatsMath.window(history, today, 7))
        return when {
            avg != null && before != null && before > 0 -> StatsMath.change(before, avg).let { change ->
                when {
                    change < 0 -> "${-change}% " + c.getString(R.string.stats_less)
                    change > 0 -> "$change% " + c.getString(R.string.stats_more)
                    else -> c.getString(R.string.stats_same).replaceFirstChar { it.uppercase() }
                }
            }
            avg != null -> c.getString(R.string.stats_avg) + ": " + formatDuration(avg)
            history.isNotEmpty() -> c.getString(R.string.stats_today) + ": " + formatDuration(history[today]?.total ?: 0)
            else -> c.getString(R.string.stats_brief_empty)
        }
    }

    class StatsState(
        val history: Map<LocalDate, DayUsage> = emptyMap(),
        val today: LocalDate = LocalDate.of(2026, 1, 1),
        val range: Int = 7,
        val before: Long? = null, // promedio de antes de Lúcido
        val usageAccess: Boolean = false,
        val limitMs: Long? = null,
        val commitStart: LocalDate = today,
        val goal: StreakGoal? = null,
        val weeklyOn: Boolean = true,
        val lastSummary: String? = null,
        val resetAt: LocalTime = LocalTime.of(4, 0),
        val animate: Boolean = false, // los gráficos crecen desde cero (al abrir o cambiar el rango)
    )

    fun stats(c: Context, s: StatsState, a: Actions) = screen(c) { add ->
        add(header(c, c.getString(R.string.stats_title), a.back), 0)
        if (s.history.isEmpty()) {
            add(Brand.card(muted(c, c.getString(R.string.stats_empty))), 8)
            return@screen
        }

        // Un solo filtro, arriba de todo lo que cambia con él
        val ranges = listOf(7, 30)
        add(chips(c, listOf(c.getString(R.string.stats_range_7), c.getString(R.string.stats_range_30)), ranges.indexOf(s.range)) { i ->
            a.statsRange(ranges[i])
        }, 4)

        val window = StatsMath.window(s.history, s.today, s.range)
        val avg = StatsMath.average(window)
        add(compareCard(c, s, window, avg, a), 12)
        add(streakCard(c, s), 12)

        add(label(c, c.getString(R.string.stats_by_day)), 18)
        add(Brand.card(dayChart(c, s, window)), 12)
        add(statTiles(c, s, window, avg), 12)
        splitCard(c, s, window)?.let { add(it, 12) }
        add(Brand.card(switchRow(c, c.getString(R.string.weekly_pref), c.getString(R.string.weekly_pref_sub), s.weeklyOn, a.weekly)), 12)

        s.lastSummary?.let { last ->
            add(Brand.card(linkRow(c, c.getString(R.string.pref_last), last.lines().first(), a.showLast)), 16)
        }
    }

    /** Antes de Lúcido contra el promedio de ahora: el número grande es el cambio. */
    private fun compareCard(c: Context, s: StatsState, window: List<DayUsage?>, avg: Long?, a: Actions): View {
        val days = StatsMath.completed(window).size
        val title = Brand.text(c, c.getString(R.string.stats_compare), Brand.Type.TITLE, bold = true)
        val before = s.before
        if (before == null) {
            val box = column(c, title, space(c, 4), muted(c, c.getString(if (s.usageAccess) R.string.stats_no_before else R.string.stats_need_access)))
            if (!s.usageAccess) box.addView(Brand.secondaryButton(c, c.getString(R.string.stats_allow), a.openUsageAccess),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = Brand.px(c, 12) })
            return Brand.card(box)
        }
        if (avg == null) return Brand.card(column(c, title, space(c, 4),
            muted(c, c.getString(R.string.stats_before) + ": " + c.getString(R.string.stats_per_day, formatDuration(before))),
            muted(c, c.getString(R.string.stats_wait))))

        val change = StatsMath.change(before, avg)
        val sign = if (change < 0) "−" else if (change > 0) "+" else ""
        val box = column(c,
            title,
            space(c, 8),
            LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Brand.number(c, "$sign${abs(change)}%", Brand.Type.DISPLAY + 6, if (change <= 0) R.color.brand_primary else R.color.brand_warning))
                addView(muted(c, c.getString(when {
                    change < 0 -> R.string.stats_less
                    change > 0 -> R.string.stats_more
                    else -> R.string.stats_same
                })), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Brand.px(c, 12) })
            },
            space(c, 14),
            compareRow(c, c.getString(R.string.stats_before), null, before, maxOf(before, avg), strong = false, animate = s.animate),
            space(c, 14),
            compareRow(c, c.getString(R.string.stats_now), c.resources.getQuantityString(R.plurals.stats_avg_days, days, days),
                avg, maxOf(before, avg), strong = true, animate = s.animate)
        )
        val extras = listOfNotNull(
            (before - avg).takeIf { it > 0 }?.let { c.getString(R.string.stats_week_less, rough(it * 7)) },
            StatsMath.recovered(s.history, s.today, before)?.let { c.getString(R.string.stats_recovered, rough(it)) }
        )
        if (extras.isNotEmpty()) {
            box.addView(space(c, 14))
            box.addView(banner(c, extras.joinToString("\n")))
        }
        return Brand.card(box)
    }

    /** Días seguidos cumpliendo la meta (límite del compromiso o usar menos que antes) y los últimos 14. */
    private fun streakCard(c: Context, s: StatsState): View {
        val title = Brand.text(c, c.getString(R.string.streak_title), Brand.Type.TITLE, bold = true)
        val goal = s.goal ?: return Brand.card(column(c, title, space(c, 4), muted(c, c.getString(R.string.streak_none))))
        val (current, best) = StatsMath.streaks(s.history, s.today, goal.max, goal.from)
        return Brand.card(column(c,
            title,
            space(c, 6),
            LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Brand.number(c, current.toString()))
                addView(column(c,
                    Brand.text(c, c.resources.getQuantityString(R.plurals.streak_days, current), Brand.Type.TITLE, bold = true),
                    muted(c, c.getString(if (goal.isLimit) R.string.streak_limit else R.string.streak_before))
                ), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Brand.px(c, 12) })
            },
            space(c, 12),
            StreakDots(c).apply { marks = StatsMath.goalMarks(s.history, s.today, goal.max, goal.from, 14) },
            space(c, 8),
            LinearLayout(c).apply {
                addView(muted(c, c.getString(R.string.streak_last)), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(muted(c, c.getString(R.string.streak_best, c.resources.getQuantityString(R.plurals.days_n, best, best))))
            }
        ))
    }

    private fun compareRow(c: Context, name: String, sub: String?, value: Long, max: Long, strong: Boolean, animate: Boolean) = column(c,
        LinearLayout(c).apply {
            gravity = Gravity.BOTTOM
            addView(Brand.text(c, if (sub != null) "$name · $sub" else name, Brand.Type.BODY, R.color.brand_text_muted), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(Brand.text(c, c.getString(R.string.stats_per_day, formatDuration(value)), Brand.Type.BODY, bold = true))
        },
        space(c, 6),
        FillBar(c, strong).apply { setFraction(value.toFloat() / max, animate) }
    )

    /** Columnas por día; tocar una (o deslizar) muestra ese día arriba del gráfico. */
    private fun dayChart(c: Context, s: StatsState, window: List<DayUsage?>): View {
        val n = window.size
        val days = (0 until n).map { s.today.minusDays((n - 1 - it).toLong()) }
        val names = c.resources.getStringArray(R.array.weekday_entries)
        val callout = Brand.text(c, "", Brand.Type.TITLE, bold = true)
        fun show(i: Int) {
            val d = days[i]
            val day = if (i == n - 1) c.getString(R.string.stats_today) else "${names[d.dayOfWeek.value - 1]} ${d.dayOfMonth}"
            callout.text = "$day · ${formatDuration(window[i]?.total ?: 0)}"
        }
        val chart = DayBars(c).apply {
            values = window.map { it?.total }
            labels = days.mapIndexed { i, d ->
                when {
                    n <= 7 -> names[d.dayOfWeek.value - 1].take(1)
                    (n - 1 - i) % 7 == 0 -> d.dayOfMonth.toString()
                    else -> ""
                }
            }
            limit = s.limitMs
            if (limit != null) limitFrom = StatsMath.indexOf(s.commitStart, s.today, n)
            before = s.before
            limitLabel = c.getString(R.string.stats_limit_line)
            beforeLabel = c.getString(R.string.stats_before_line)
            selected = n - 1
            onSelect = { show(it) }
            contentDescription = c.getString(R.string.stats_by_day)
            if (s.animate) animateIn()
        }
        show(n - 1)
        return column(c, callout, muted(c, c.getString(R.string.stats_hint)), space(c, 14), chart)
    }

    /** Promedio, total, mejor día y días dentro del límite (o hoy, si no hay límite). */
    private fun statTiles(c: Context, s: StatsState, window: List<DayUsage?>, avg: Long?): View {
        val names = c.resources.getStringArray(R.array.weekday_entries)
        val completed = StatsMath.completed(window).size
        val best = StatsMath.best(window)
        val limit = s.limitMs
        val tiles = listOf(
            stat(c, c.getString(R.string.stats_avg), avg?.let(::formatDuration) ?: "—",
                c.resources.getQuantityString(R.plurals.stats_finished_days, completed, completed)),
            stat(c, c.getString(R.string.stats_total), formatDuration(window.filterNotNull().sumOf { it.total }),
                c.getString(if (s.range == 7) R.string.stats_range_7 else R.string.stats_range_30)),
            stat(c, c.getString(R.string.stats_best), best?.let { formatDuration(it.second) } ?: "—",
                best?.let { (i, _) -> s.today.minusDays((window.size - 1 - i).toLong()).let { "${names[it.dayOfWeek.value - 1]} ${it.dayOfMonth}" } } ?: ""),
            if (limit != null) StatsMath.within(window, limit, StatsMath.indexOf(s.commitStart, s.today, window.size)).let { (ok, of) ->
                stat(c, c.getString(R.string.stats_within), c.getString(R.string.stats_of, ok, of), c.getString(R.string.stats_days_sub))
            } else stat(c, c.getString(R.string.stats_today), formatDuration(window.last()?.total ?: 0),
                c.getString(R.string.home_resets, Sleep.fmt(c, s.resetAt)))
        )
        val gap = Brand.px(c, 12)
        return column(c, *tiles.chunked(2).mapIndexed { r, pair ->
            LinearLayout(c).apply {
                if (r > 0) setPadding(0, gap, 0, 0)
                pair.forEachIndexed { i, t -> addView(t, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) marginStart = gap }) }
            }
        }.toTypedArray())
    }

    /** Cuánto fue Instagram y cuánto YouTube (desde que Lúcido los separa). */
    private fun splitCard(c: Context, s: StatsState, window: List<DayUsage?>): View? {
        if (window.filterNotNull().sumOf { it.total } == 0L) return null
        val (ig, yt) = StatsMath.split(window)
        val box = column(c, Brand.text(c, c.getString(R.string.panel_apps), Brand.Type.TITLE, bold = true))
        if (ig + yt == 0L) {
            box.addView(space(c, 4))
            box.addView(muted(c, c.getString(R.string.stats_split_none)))
            return Brand.card(box)
        }
        val max = maxOf(ig, yt).toFloat()
        val (igPct, ytPct) = StatsMath.percents(ig, yt)
        fun appRow(icon: Int, name: String, ms: Long, pct: Int) = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(c).apply { setImageResource(icon); contentDescription = name },
                LinearLayout.LayoutParams(Brand.px(c, 28), Brand.px(c, 28)))
            addView(column(c,
                LinearLayout(c).apply {
                    addView(Brand.text(c, name, Brand.Type.BODY, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(Brand.text(c, "${formatDuration(ms)} · $pct%", Brand.Type.BODY, R.color.brand_text_muted))
                },
                space(c, 6),
                FillBar(c, strong = true).apply { setFraction(ms / max, s.animate) }
            ), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Brand.px(c, 12) })
        }
        box.addView(space(c, 12))
        box.addView(appRow(R.mipmap.ic_ig, "Instagram", ig, igPct))
        box.addView(space(c, 12))
        box.addView(appRow(R.mipmap.ic_yt, "YouTube", yt, ytPct))

        // Si en la ventana hay días de antes de separarlos, decir desde cuándo cuenta
        val windowStart = s.today.minusDays(window.size - 1L)
        val firstSplit = s.history.filterValues { it.ig + it.yt > 0 }.keys.minOrNull()
        if (firstSplit != null && s.history.keys.any { it >= windowStart && it < firstSplit }) {
            val millis = firstSplit.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            box.addView(space(c, 10))
            box.addView(muted(c, c.getString(R.string.stats_split_since,
                DateUtils.formatDateTime(c, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR))))
        }
        return Brand.card(box)
    }

    /** Duración aproximada para textos como "≈ 13 h menos por semana". */
    private fun rough(ms: Long) = "≈ " + if (ms >= 2 * 3_600_000L) "${(ms + 1_800_000) / 3_600_000} h"
        else formatDuration(((ms + 150_000) / 300_000).coerceAtLeast(1) * 300_000)

    // ---------- Piezas ----------

    /** La columna de la pantalla; [fill] agrega cada pieza con su margen de arriba. */
    private fun screen(c: Context, fill: ((View, Int) -> Unit) -> Unit): LinearLayout {
        val pad = Brand.px(c, 16)
        val list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Brand.px(c, 4), pad, Brand.px(c, 24))
        }
        fill { v, top -> list.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Brand.px(c, top) }) }
        return list
    }

    /** Falta Accesibilidad para [why]: el aviso con el botón y, en Android 13+, la ayuda del permiso gris. */
    private fun guardNotice(c: Context, why: Int, restricted: Boolean, a: Actions) = Brand.notice(
        c, c.getString(why), c.getString(R.string.turn_on), a.turnOnGuard,
        help = if (restricted) c.getString(R.string.gray_title) else null, onHelp = a.explainRestricted
    )

    /** Volver, título y, opcional, el interruptor principal de la pantalla. */
    private fun header(c: Context, title: String, back: () -> Unit, switch: ToggleView? = null) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Brand.px(c, 64)
        addView(Brand.iconButton(c, R.drawable.ic_back, c.getString(R.string.panel_back), onClick = back))
        val t = Brand.text(c, title, Brand.Type.HEADLINE, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) }
        addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Brand.px(c, 4) })
        switch?.let {
            it.contentDescription = title // el interruptor de la pantalla lleva su nombre
            addView(it)
        }
    }

    private fun column(c: Context, vararg views: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it, it.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP)) }
    }

    // Space y no View: una View común con alto WRAP ocupa todo el lugar que le ofrecen y empuja
    // afuera lo que viene después; Space mide exactamente su alto mínimo.
    private fun space(c: Context, dp: Int) = Space(c).apply { minimumHeight = Brand.px(c, dp) }

    private fun muted(c: Context, s: String) = Brand.text(c, s, Brand.Type.BODY, R.color.brand_text_muted)

    /** Título de sección (como los subtítulos de lista de Material): en el color de la marca. */
    private fun label(c: Context, s: String) = Brand.text(c, s, Brand.Type.BODY, R.color.brand_primary, bold = true).apply {
        setPadding(Brand.px(c, 4), 0, 0, Brand.px(c, 2))
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun banner(c: Context, s: String) = Brand.text(c, s, Brand.Type.BODY, R.color.brand_on_tonal).apply {
        background = Brand.rounded(c, R.color.brand_tonal, 16f)
        val p = Brand.px(c, 14)
        setPadding(p, Brand.px(c, 10), p, Brand.px(c, 10))
    }

    private fun range(c: Context, a: LocalTime, b: LocalTime) = c.getString(R.string.night_range, Sleep.fmt(c, a), Sleep.fmt(c, b))

    private fun minutesChip(min: Int, zero: String) = if (min == 0) zero else formatDuration(min * 60_000L)

    /** Interruptor dibujado con Canvas (ToggleView): se ve seguro en cualquier teléfono. */
    private fun toggle(c: Context, checked: Boolean, onChange: (Boolean) -> Unit) = ToggleView(c).apply {
        this.checked = checked
        this.onChange = { on -> post { onChange(on) } }
    }

    /** Título y explicación con su interruptor; toda la fila lo cambia y TalkBack la lee como interruptor. */
    private fun switchRow(c: Context, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = toggle(c, checked, onChange)
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Brand.px(c, 56)
            addView(column(c, Brand.text(c, title, Brand.Type.TITLE, bold = true), muted(c, sub)), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Brand.px(c, 12) })
            setOnClickListener { sw.performClick() }
            sw.describe(this)
        }
    }

    private fun linkRow(c: Context, title: String, sub: String, onClick: () -> Unit) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Brand.px(c, 56)
        addView(column(c, Brand.text(c, title, Brand.Type.TITLE, bold = true), muted(c, sub)), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(Brand.chevron(c))
        background = Brand.pressable(c, GradientDrawable(), 12f)
        setOnClickListener { onClick() }
    }

    private fun infoRow(c: Context, title: String, sub: String) =
        column(c, Brand.text(c, title, Brand.Type.TITLE, bold = true), muted(c, sub)).apply { minimumHeight = Brand.px(c, 48) }

    /** Dos horas grandes lado a lado (dormir/despertar, empieza/termina); tocarlas las cambia. */
    private fun timePair(c: Context, l1: String, t1: LocalTime, l2: String, t2: LocalTime, dot1: Int? = null, dot2: Int? = null,
                         onClick: () -> Unit) = LinearLayout(c).apply {
        fun box(l: String, t: LocalTime, dot: Int?) = column(c,
            LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                dot?.let { addView(View(c).apply { background = Brand.rounded(c, it, 999f) }, LinearLayout.LayoutParams(Brand.px(c, 10), Brand.px(c, 10))
                    .apply { marginEnd = Brand.px(c, 6) }) }
                addView(Brand.text(c, l, Brand.Type.BODY, R.color.brand_on_tonal))
            },
            // Una sola línea: si la hora es larga (12 h, letra grande) se achica en vez de cortarse
            Brand.number(c, Sleep.fmt(c, t)).apply {
                maxLines = 1
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 18, Brand.Type.DISPLAY.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
                layoutParams = LinearLayout.LayoutParams(MATCH, Brand.px(c, 44))
            }
        ).apply {
            background = Brand.pressable(c, Brand.rounded(c, R.color.brand_tonal, 20f), 20f)
            setPadding(Brand.px(c, 16), Brand.px(c, 12), Brand.px(c, 12), Brand.px(c, 10))
            contentDescription = "$l, ${Sleep.fmt(c, t)}"
            setOnClickListener { onClick() }
        }
        addView(box(l1, t1, dot1), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(box(l2, t2, dot2), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = Brand.px(c, 12) })
    }

    /** Opciones como pastillas (40 dp a la vista, 48 al tocar); la elegida, llena. Pasan a la línea siguiente si no entran. */
    private fun chips(c: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) = FlowLayout(c).apply {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            addView(Brand.text(c, l, Brand.Type.BODY, if (on) R.color.brand_on_primary else R.color.brand_text, bold = true).apply {
                val base = if (on) Brand.rounded(c, R.color.brand_primary, 12f)
                    else Brand.rounded(c, R.color.brand_surface, 12f, stroke = R.color.brand_outline)
                background = Brand.inset48(c, Brand.pressable(c, base, 12f))
                gravity = Gravity.CENTER
                minHeight = Brand.px(c, 48)
                setPadding(Brand.px(c, 16), 0, Brand.px(c, 16), 0)
                isSelected = on
                setOnClickListener { if (!on) onSelect(i) }
            })
        }
    }

    /** Los siete días como círculos de 40 dp (L M M J V S D), cada uno en 48 dp: tocar uno lo prende o apaga. */
    private fun dayChips(c: Context, selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) = LinearLayout(c).apply {
        val initials = c.resources.getStringArray(R.array.weekday_entries)
        for (d in DayOfWeek.values()) {
            val on = d in selected
            val dot = Brand.text(c, initials[d.value - 1].take(1), Brand.Type.BODY, if (on) R.color.brand_on_primary else R.color.brand_text, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = initials[d.value - 1]
                isSelected = on
                val base = if (on) Brand.rounded(c, R.color.brand_primary, 999f)
                    else Brand.rounded(c, R.color.brand_surface, 999f, stroke = R.color.brand_outline)
                background = InsetDrawable(Brand.pressable(c, base, 999f), Brand.px(c, 4))
                setOnClickListener { onToggle(d) }
            }
            val size = Brand.px(c, 48)
            addView(FrameLayout(c).apply { addView(dot, FrameLayout.LayoutParams(size, size, Gravity.CENTER)) },
                LinearLayout.LayoutParams(0, size, 1f))
        }
    }

    private fun stepper(c: Context, value: String, sub: String, minus: String, plus: String, onMinus: () -> Unit, onPlus: () -> Unit) =
        LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            fun round(s: String, description: String, f: () -> Unit) = Brand.text(c, s, Brand.Type.HEADLINE, R.color.brand_primary).apply {
                gravity = Gravity.CENTER
                background = Brand.pressable(c, Brand.rounded(c, R.color.brand_surface, 999f, stroke = R.color.brand_outline), 999f)
                contentDescription = description
                setOnClickListener { f() }
            }
            val size = Brand.px(c, 48)
            addView(round("−", minus, onMinus), LinearLayout.LayoutParams(size, size))
            addView(column(c,
                Brand.number(c, value).apply { gravity = Gravity.CENTER },
                muted(c, sub).apply { gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END; maxLines = 1 }
            ), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(round("+", plus, onPlus), LinearLayout.LayoutParams(size, size))
        }

    private fun stat(c: Context, title: String, value: String, sub: String) = column(c,
        Brand.text(c, title, Brand.Type.BODY, R.color.brand_on_tonal),
        Brand.number(c, value, 24f).apply {
            setPadding(0, Brand.px(c, 4), 0, Brand.px(c, 2))
            maxLines = 1 // se achica en vez de partirse (letra grande, "23 h 59 min")
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 14, 24, 1, TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams(MATCH, Brand.px(c, 36))
        },
        Brand.text(c, sub, Brand.Type.LABEL + 1, R.color.brand_text_muted)
    ).apply {
        background = Brand.rounded(c, R.color.brand_tonal, 20f)
        setPadding(Brand.px(c, 16), Brand.px(c, 12), Brand.px(c, 16), Brand.px(c, 14))
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}

/** Fila que pasa a la línea siguiente cuando no entra (para las pastillas). */
class FlowLayout(c: Context) : ViewGroup(c) {
    private val gap = Brand.px(c, 8)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val max = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            measureChild(v, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + v.measuredWidth > max) { x = 0; y += rowH + gap; rowH = 0 }
            x += v.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            rowH = maxOf(rowH, v.measuredHeight)
        }
        // Con ancho justo ocupa todo; si no, solo lo que necesita (así se puede centrar)
        val w = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val max = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (x > 0 && x + v.measuredWidth > max) { x = 0; y += rowH + gap; rowH = 0 }
            v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + v.measuredWidth, paddingTop + y + v.measuredHeight)
            x += v.measuredWidth + gap
            rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}
