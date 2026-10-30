package com.brainrotless.app.screens

import com.brainrotless.app.DayUsage
import com.brainrotless.app.PanelScreen
import com.brainrotless.app.StreakGoal
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PanelShots : Shots(theme = "PanelTheme") {
    private val a = PanelScreen.Actions()
    private val today = LocalDate.of(2026, 10, 21)
    private val min = 60_000L
    private fun millis(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---------- Modo dormir ----------

    @Test fun dormir() = shot("dormir", Shots.ALL, { c -> PanelScreen.sleep(c, PanelScreen.SleepState(
        on = true, browsers = listOf("Chrome", "Firefox"),
    ), a) })

    @Test fun dormirPorDia() = shot("dormir_por_dia", listOf(BASE, DARK, WORST), { c -> PanelScreen.sleep(c, PanelScreen.SleepState(
        on = true, commitActive = true, perDay = true, guardOn = false, restricted = true,
        nights = mapOf<DayOfWeek?, Pair<LocalTime, LocalTime>>(null to (LocalTime.of(23, 30) to LocalTime.of(7, 0))) +
            DayOfWeek.values().associateWith { LocalTime.of(if (it.value >= 5) 1 else 23, 0) to LocalTime.of(if (it.value >= 6) 10 else 7, 0) },
        warn = "30",
    ), a) })

    // ---------- Modo foco ----------

    @Test fun foco() = shot("foco", Shots.ALL, { c -> PanelScreen.focus(c, PanelScreen.FocusState(
        on = true, days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    ), a) })

    @Test fun focoApagado() = shot("foco_apagado", listOf(BASE, DARK), { c -> PanelScreen.focus(c, PanelScreen.FocusState(
        on = true, guardOn = false, sessionWarn = "0",
    ), a) })

    // ---------- Compromiso ----------

    @Test fun compromisoNuevo() = shot("compromiso_nuevo", Shots.ALL, { c -> PanelScreen.newCommitment(c, PanelScreen.NewCommitState(
        draftDays = 14, limitMinutes = 60, rules = PanelScreen.rules(c, true, true, 60),
    ), a) })

    /** Sin nada prendido no se puede empezar; sin Accesibilidad tampoco. */
    @Test fun compromisoNoListo() = shot("compromiso_no_listo", listOf(BASE, DARK, WORST), { c -> PanelScreen.newCommitment(c, PanelScreen.NewCommitState(
        draftDays = 365, guardOn = false, restricted = true, lastSummary = "Terminaste tu compromiso de 30 días\nUsaste 41 min por día.",
    ), a) })

    @Test fun compromisoActivo() = shot("compromiso_activo", Shots.ALL, { c -> PanelScreen.activeCommitment(c, PanelScreen.ActiveCommitState(
        daysLeft = 9, totalDays = 14, endMillis = millis(today.plusDays(9), 21, 40), usedMs = 38 * min, limitMs = 60 * min,
        limitMinutes = 60, pausesLeft = 7, canPause = true, usageAccess = true, history = history, today = today, before = 95 * min,
    ), a) })

    @Test fun compromisoPausa() = shot("compromiso_pausa", listOf(BASE, DARK), { c -> PanelScreen.activeCommitment(c, PanelScreen.ActiveCommitState(
        daysLeft = 9, totalDays = 14, endMillis = millis(today.plusDays(9), 21, 40), usedMs = 64 * min, limitMs = 60 * min,
        limitMinutes = 60, pausesLeft = 6, pauseUntil = millis(today, 18, 15),
    ), a) })

    /** Último día, límite pasado, sin pausas y sin Accesibilidad. */
    @Test fun compromisoLimite() = shot("compromiso_limite", listOf(BASE, DARK, WORST), { c -> PanelScreen.activeCommitment(c, PanelScreen.ActiveCommitState(
        guardOn = false, restricted = true, daysLeft = 0, totalDays = 365, endMillis = millis(today, 23, 59), usedMs = 1439 * min,
        limitMs = 30 * min, limitMinutes = 30, pausesLeft = 0,
    ), a) })

    // ---------- Instagram y YouTube ----------

    @Test fun apps() = shot("apps", Shots.ALL, { c -> PanelScreen.apps(c, PanelScreen.AppsState(
        feed = "following", guardOn = true, listenerOn = false, linksOn = null, restricted = true,
    ), a) })

    // ---------- Estadísticas ----------

    private val history: Map<LocalDate, DayUsage> = (0 until 40).associate { i ->
        val total = listOf(52, 38, 71, 44, 29, 61, 47, 33, 90, 25)[i % 10] * min
        today.minusDays(i.toLong()) to if (i < 25) DayUsage(total, total * 2 / 3, total / 3) else DayUsage(total)
    }
    private fun stats(range: Int = 7) = PanelScreen.StatsState(
        history = history, today = today, range = range, before = 95 * min, usageAccess = true, limitMs = 60 * min,
        commitStart = today.minusDays(12), goal = StreakGoal(60 * min, today.minusDays(12), isLimit = true),
        lastSummary = "Terminaste tu compromiso de 7 días",
    )

    @Test fun estadisticas() = shot("estadisticas", Shots.ALL, { c -> PanelScreen.stats(c, stats(), a) })

    @Test fun estadisticas30() = shot("estadisticas_30", listOf(BASE, DARK), { c -> PanelScreen.stats(c, stats(30), a) })

    /** Primer día: sin datos. */
    @Test fun estadisticasVacias() = shot("estadisticas_vacias", listOf(BASE, DARK, EN), { c -> PanelScreen.stats(c, PanelScreen.StatsState(), a) })

    /** Un solo día, sin permiso de uso y sin meta. */
    @Test fun estadisticasPrimerDia() = shot("estadisticas_primer_dia", listOf(BASE, WORST), { c -> PanelScreen.stats(c, PanelScreen.StatsState(
        history = mapOf(today to DayUsage(1439 * min, 1000 * min, 439 * min)), today = today,
    ), a) })
}
