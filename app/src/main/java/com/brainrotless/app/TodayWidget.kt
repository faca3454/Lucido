package com.brainrotless.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDateTime
import java.util.Date

/** Widget "Hoy" (2×2): anillo con el uso, límite, estado y accesos a IG Lite y YT Lite. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.update(c, force = true)
}

/** Widget "Mini" (1×1): solo el anillo con los minutos de hoy. */
class MiniWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.update(c, force = true)
}

/** Widget "Barra" (4×1): uso, barrita, estado y accesos, en una fila. */
class BarWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.update(c, force = true)
}

/**
 * Arma y actualiza los tres widgets. Todos los textos se ponen desde acá (así respetan el idioma
 * elegido en la app) y el anillo se dibuja como una imagen chica. Barato si no cambió nada.
 */
object Widgets {
    private var shown: String? = null // lo último que se mostró: no repetir actualizaciones iguales

    /**
     * Lo que muestran los widgets, ya calculado. [night] y [focus] en horas decimales (para el
     * dial), [now] la hora actual.
     */
    class Data(
        val used: String, val limit: String?, val progress: Float?, val over: Boolean,
        val status: String, val ringNumber: String, val ringUnit: String,
        val night: Pair<Float, Float>?, val focus: Pair<Float, Float>?, val now: Float,
    )

    private fun data(c: Context): Data {
        val usedMs = Usage.todayMs(c)
        val reset = Sleep.fmt(c, Usage.nextReset(c).toLocalTime())
        val over = Usage.overLimit(c)
        val status = when {
            Sleep.active(c) -> c.getString(R.string.widget_night, Lock.until(c, Lock.Reason.NIGHT))
            Focus.active(c) -> c.getString(R.string.widget_focus, Lock.until(c, Lock.Reason.FOCUS))
            Commitment.pauseActive(c) ->
                c.getString(R.string.home_pause_until, DateFormat.getTimeFormat(c).format(Date(Commitment.pauseUntil(c))))
            over -> c.getString(R.string.home_over, reset)
            else -> c.getString(R.string.home_resets, reset)
        }
        // Lo mismo que el dial del inicio: la noche que viene (o la de ahora) y el foco de hoy
        val now = TrustedClock.now(c)
        fun hours(t: LocalDateTime) = t.hour + t.minute / 60f
        val night = (Sleep.current(c) ?: Sleep.nextNight(c)).takeIf { Sleep.enabled(c) }
        val focus = (Focus.current(c) ?: Focus.next(c)).takeIf { Focus.enabled(c) && it?.start?.toLocalDate() == now.toLocalDate() }
        return data(c, usedMs, Usage.limitMs(c), over, status,
            night?.let { hours(it.start) to hours(it.end) }, focus?.let { hours(it.start) to hours(it.end) }, hours(now))
    }

    /** Los números y textos del widget a partir del uso de hoy, el límite, el estado y el día. */
    fun data(c: Context, usedMs: Long, limitMs: Long?, over: Boolean, status: String,
             night: Pair<Float, Float>? = null, focus: Pair<Float, Float>? = null, now: Float = 12f): Data {
        val m = usedMs / 60_000
        val (number, unit) = if (m < 60) "$m" to c.getString(R.string.widget_unit_min)
            else "%d:%02d".format(m / 60, m % 60) to c.getString(R.string.widget_unit_h)
        return Data(
            used = formatDuration(usedMs),
            limit = limitMs?.let { formatDuration(it) },
            progress = limitMs?.let { (usedMs.toFloat() / it).coerceIn(0f, 1f) },
            over = over, status = status, ringNumber = number, ringUnit = unit,
            night = night, focus = focus, now = now,
        )
    }

    fun update(c: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(c) ?: return
        fun ids(cls: Class<*>) = mgr.getAppWidgetIds(ComponentName(c, cls))
        val today = ids(TodayWidget::class.java)
        val mini = ids(MiniWidget::class.java)
        val bar = ids(BarWidget::class.java)
        if (today.isEmpty() && mini.isEmpty() && bar.isEmpty()) return

        val d = data(c)
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // La hora, de a 10 minutos: el sol del dial se mueve sin redibujar a cada rato
        val key = "${d.used}|${d.limit}|${((d.progress ?: 0f) * 100).toInt()}|${d.status}|${Lang.current(c)}|$night|" +
            "${d.night}|${d.focus}|${(d.now * 6).toInt()}"
        if (!force && key == shown) return
        shown = key

        val big by lazy { dial(c, d, 112) }
        runCatching {
            if (mini.isNotEmpty()) mgr.updateAppWidget(mini, mini(c, d, big).apply {
                setOnClickPendingIntent(R.id.w_root, home(c))
            })
            if (bar.isNotEmpty()) mgr.updateAppWidget(bar, bar(c, d).apply { shortcuts(c) })
            if (today.isNotEmpty()) mgr.updateAppWidget(today, today(c, d, big).apply { shortcuts(c) })
        }
    }

    /** Mini (1×1): el dial con los minutos adentro. */
    fun mini(c: Context, d: Data, dial: Bitmap = dial(c, d, 112)) = RemoteViews(c.packageName, R.layout.widget_mini).apply {
        setImageViewBitmap(R.id.w_ring, dial)
        setTextViewText(R.id.w_number, d.ringNumber)
        setTextViewText(R.id.w_unit, d.ringUnit)
        setContentDescription(R.id.w_root, describe(c, d))
    }

    /** Barra (4×1): el dial chico, lo usado contra el límite, el estado y los accesos. */
    fun bar(c: Context, d: Data) = RemoteViews(c.packageName, R.layout.widget_bar).apply {
        setImageViewBitmap(R.id.w_ring, dial(c, d, 48))
        setTextViewText(R.id.w_amount, amountWithLimit(c, d))
        setTextViewText(R.id.w_status, d.status)
        setContentDescription(R.id.w_root, describe(c, d))
        setContentDescription(R.id.w_ig, c.getString(R.string.label_ig))
        setContentDescription(R.id.w_yt, c.getString(R.string.label_yt))
    }

    /** Hoy (2×2): el dial con lo usado y el límite adentro, los accesos al costado y el estado abajo. */
    fun today(c: Context, d: Data, dial: Bitmap = dial(c, d, 112)) = RemoteViews(c.packageName, R.layout.widget_today).apply {
        setImageViewBitmap(R.id.w_ring, dial)
        setTextViewText(R.id.w_number, d.used)
        setTextViewText(R.id.w_limit, d.limit?.let { c.getString(R.string.widget_of_limit, it) } ?: c.getString(R.string.widget_no_limit))
        setTextViewText(R.id.w_status, d.status)
        setContentDescription(R.id.w_root, describe(c, d))
        setContentDescription(R.id.w_ig, c.getString(R.string.label_ig))
        setContentDescription(R.id.w_yt, c.getString(R.string.label_yt))
    }

    /** Lo que lee TalkBack: "Hoy, 38 min de 1 h. Se reinicia a las 4:00". */
    private fun describe(c: Context, d: Data) = c.getString(R.string.widget_title) + ", " + d.used +
        (d.limit?.let { " " + c.getString(R.string.widget_of_limit, it) } ?: "") + ". " + d.status

    /** "23 min de 1 h", con el límite más chico y apagado. */
    private fun amountWithLimit(c: Context, d: Data): CharSequence {
        val limit = d.limit?.let { c.getString(R.string.usage_of, it) } ?: return d.used
        return SpannableString(d.used + limit).apply {
            val from = d.used.length
            setSpan(RelativeSizeSpan(0.75f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Brand.color(c, R.color.brand_text_muted)), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun RemoteViews.shortcuts(c: Context) {
        setOnClickPendingIntent(R.id.w_root, home(c))
        setOnClickPendingIntent(R.id.w_ig, open(c, alias(c, "Instagram"), 11))
        setOnClickPendingIntent(R.id.w_yt, open(c, alias(c, "YouTube"), 12))
    }

    /** El dial del día (el mismo del inicio, en versión compacta) como imagen de [sizeDp]. */
    private fun dial(c: Context, d: Data, sizeDp: Int): Bitmap {
        val size = Brand.px(c, sizeDp).coerceIn(96, 384)
        val view = DayDial(c).apply {
            compact = true
            night = d.night
            focus = d.focus
            now = d.now
            usage = d.progress ?: 0f // sin límite, el anillo de adentro queda vacío
            over = d.over
            ground = Brand.color(c, R.color.brand_surface) // el fondo del widget
        }
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    /** El inicio de Lúcido o, con el ícono escondido (que apaga el inicio), las estadísticas. */
    private fun home(c: Context) = open(c,
        if (Protection.iconHidden(c)) PanelActivity.intent(c, PanelActivity.STATS) else Intent(c, HomeActivity::class.java), 10)

    private fun alias(c: Context, name: String) =
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName(c, "$PKG.$name"))

    private fun open(c: Context, i: Intent, code: Int) = PendingIntent.getActivity(
        c, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
