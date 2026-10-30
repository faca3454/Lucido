package com.brainrotless.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Herramientas de los APK de prueba ("dev", "dev-<commit>"): las mediciones y diagnósticos del script
 * (assets/devtools.js) y la medición automática de IG Lite y YT Lite, al final de Ajustes. La versión
 * publicada se arma con src/nodevtools, donde nada de esto existe (ver app/build.gradle.kts).
 */
object DevTools {
    private const val EXTRA_BENCH = "bench"
    private var js: String? = null

    /** Va antes de block.user.js en cada página. */
    fun script(c: Context): String =
        js ?: c.assets.open("devtools.js").bufferedReader().use { it.readText() }.also { js = it }

    /** Puente del script de prueba (LucidoDev): copiar el diagnóstico de deslizar para responder. */
    fun attach(v: WebView) = v.addJavascriptInterface(object {
        @JavascriptInterface fun report(text: String) = v.post { copy(v.context, text.take(10_000), R.string.diag_copied) }
    }, "LucidoDev")

    /** Al portapapeles; con [msg], avisa. */
    fun copy(c: Context, text: String, msg: Int? = null) {
        c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Lúcido", text))
        if (msg != null) Toast.makeText(c, c.getString(msg), Toast.LENGTH_LONG).show()
    }

    /** Ajustes: "Medir IG Lite y YT Lite", al final. */
    fun addTo(f: PreferenceFragmentCompat) {
        val c = f.requireContext()
        val cat = PreferenceCategory(c).apply { title = c.getString(R.string.dev_cat) }
        f.preferenceScreen.addPreference(cat)
        cat.addPreference(Preference(c).apply {
            title = c.getString(R.string.dev_bench)
            summary = c.getString(R.string.dev_bench_summary)
            setOnPreferenceClickListener {
                c.startActivity(Intent(c, MainActivity::class.java).putExtra(EXTRA_BENCH, true))
                true
            }
        })
        cat.addPreference(Preference(c).apply {
            title = c.getString(R.string.dev_data)
            summary = c.getString(R.string.dev_data_summary)
            setOnPreferenceClickListener { showData(f); true }
        })
    }

    /**
     * Para probar "Videos solo al tocar", los anuncios y las historias: los datos que bajó la app (las páginas
     * de IG Lite y YT Lite van por la app) desde que se puso en cero, o desde que se prendió el teléfono, y, de
     * cada página abierta, cuánto video bajó y lo que hizo el script (devtools.js lo guarda aunque la página se
     * recargue). Se puede copiar para pasarlo al chat. "Poner en cero" empieza todo de nuevo.
     */
    private fun showData(f: PreferenceFragmentCompat) = f.lifecycleScope.launch {
        val c = f.requireContext()
        val now = TrafficStats.getUidRxBytes(Process.myUid())
        if (now < 0) return@launch Toast.makeText(c, R.string.dev_data_none, Toast.LENGTH_LONG).show()
        val prefs = c.getSharedPreferences("devtools", Context.MODE_PRIVATE)
        val zero = prefs.getLong("rx", 0)
        val fromBoot = zero > now // se reinició el teléfono: el contador de Android volvió a cero
        val since = if (fromBoot || zero == 0L) System.currentTimeMillis() - SystemClock.elapsedRealtime() else prefs.getLong("rx_at", 0)
        val mb = String.format(Locale.getDefault(), "%.1f", (now - if (fromBoot) 0 else zero) / 1_048_576.0)
        val at = DateUtils.formatSameDayTime(since, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val pages = openPages()
        val reports = pages.mapNotNull { eval(it, "window.__lucidoDev ? __lucidoDev.report() : null") as? String }
        val text = (listOf(c.getString(R.string.dev_data_msg, at, mb)) +
            (if (pages.isEmpty()) listOf(c.getString(R.string.dev_data_no_pages)) else reports)).joinToString("\n\n")
        AlertDialog.Builder(c).setTitle(R.string.dev_data).setMessage(text)
            .setPositiveButton(R.string.dev_data_reset) { _, _ ->
                prefs.edit().putLong("rx", now).putLong("rx_at", System.currentTimeMillis()).apply()
                for (v in openPages()) v.evaluateJavascript("window.__lucidoDev && __lucidoDev.reset()", null)
                Toast.makeText(c, R.string.dev_data_zero, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton(R.string.dev_copy) { _, _ -> copy(c, "Lúcido datos · ${BuildConfig.VERSION_NAME}\n$text", R.string.dev_data_copied) }
            .setNegativeButton(R.string.done, null).show()
    }

    /** Las páginas de IG Lite y YT Lite que siguen vivas (también un rato después de cerrarla desde Recientes). */
    private fun openPages(): List<WebView> = MainActivity.openPages()

    // Si la página no contesta (Android puede no hacerlo si cambia mientras tanto), a los 3 s sigue sin ella
    private suspend fun eval(v: WebView, js: String): Any? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { k -> v.evaluateJavascript(js) { k.resume(it ?: "null") } }
    }?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }

    /** MainActivity: se abrió desde Ajustes para medir. */
    fun onIntent(a: MainActivity, i: Intent) {
        if (!i.getBooleanExtra(EXTRA_BENCH, false)) return
        i.removeExtra(EXTRA_BENCH)
        Bench(a).run()
    }
}

/**
 * Recorre IG Lite y YT Lite [ROUNDS] veces, como una persona: abrir, ir a otra página y volver, recargar
 * con el gesto, abrir un video. No abre chats (no marca mensajes como vistos). Abajo dice por dónde va;
 * al final muestra los tiempos, los copia y se pueden compartir. Si salís de la app o tocás Detener, se cancela.
 */
private class Bench(val a: MainActivity) {
    private class Left : Exception()
    private val rows = linkedMapOf<String, MutableList<String>>() // paso → un valor por vuelta
    private var round = 0
    private var n = 0 // paso de la vuelta
    private var perRound = 0

    // Mientras mide: la página se ve pero no se toca, la pantalla no se apaga y abajo dice por dónde va
    private val progress = Brand.text(a, a.getString(R.string.dev_bench) + "…", Brand.Type.BODY, R.color.brand_on_tonal).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val overlay = FrameLayout(a).apply {
        isClickable = true
        keepScreenOn = true
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Brand.px(a, 56)
            setPadding(Brand.px(a, 16), Brand.px(a, 8), Brand.px(a, 8), Brand.px(a, 8))
            setBackgroundColor(Brand.color(a, R.color.brand_tonal))
            addView(progress, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Brand.accentChip(a, a.getString(R.string.dev_bench_stop)) { stop() })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }
    private val job = a.lifecycleScope.launch(start = CoroutineStart.LAZY) {
        try { measure() } catch (e: Left) { cancelled() } finally { (overlay.parent as? ViewGroup)?.removeView(overlay) }
    }

    fun run() {
        a.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        job.start()
    }

    private fun stop() { job.cancel(); cancelled() }

    private fun cancelled() = Toast.makeText(a, R.string.dev_bench_cancelled, Toast.LENGTH_LONG).show()

    private suspend fun measure() {
        val state = "IG solo mensajes: ${if (Lock.ig(a) != null) "sí" else "no"} · YT bloqueado: ${if (Lock.yt(a) != null) "sí" else "no"}"
        // Lo medido mientras usabas las páginas, antes de que la medición las recargue
        val before = pages(a.window.decorView).mapNotNull { json(eval(it, "window.__lucidoDev ? __lucidoDev.summary() : null")) as? String }
        repeat(ROUNDS) {
            round = it + 1
            n = 0
            perRound = if (Lock.yt(a) == null) 7 else 4
            val ig = a.benchShow(youtube = false)
            load("IG abrir el inbox", ig) { ig.loadUrl(MainActivity.IG_INBOX) }
            step("IG ir a solicitudes", ig, "__lucidoGo('/direct/requests/')")
            step("IG atrás", ig, "history.back()")
            load("IG recargar con el gesto", ig) { a.benchReload() }
            if (Lock.yt(a) != null) return@repeat
            val yt = a.benchShow(youtube = true)
            load("YT abrir el inicio", yt) { yt.loadUrl(MainActivity.YT_HOME) }
            if (eval(yt, "!!document.querySelector('a[href^=\"/watch\"]')") != "true") {
                add("YT abrir un video hasta que arranca", "sin videos en el inicio")
                return@repeat
            }
            step("YT abrir un video hasta que arranca", yt, "document.querySelector('a[href^=\"/watch\"]').click()", video = true)
            eval(yt, "document.querySelectorAll('video').forEach(m => m.pause())")
            step("YT atrás", yt, "history.back()")
        }
        a.benchShow(youtube = false)
        val wv = WebViewCompat.getCurrentWebViewPackage(a)?.versionName ?: "?"
        val text = (listOf(
            "Lúcido medición automática · ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "Android ${Build.VERSION.RELEASE} · WebView $wv",
            state,
            "ms, un valor por vuelta; las cargas, desde que empieza a cargar. + = seguía cambiando a los 10 s, " +
                "— = no terminó en 20 s, tl = tareas largas en el paso (cantidad/ms)",
        ) + rows.map { (k, v) -> "$k: ${v.joinToString(" · ")}" } +
            (if (before.isEmpty()) emptyList() else listOf("", "Antes de medir (como las estabas usando):") + before)
        ).joinToString("\n")
        DevTools.copy(a, text)
        AlertDialog.Builder(a).setTitle(R.string.dev_bench_done).setMessage(text)
            .setPositiveButton(R.string.dev_share) { _, _ ->
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
            .setNeutralButton(R.string.dev_copy_again) { _, _ -> DevTools.copy(a, text, R.string.perf_copied) }
            .setNegativeButton(R.string.done, null).show()
    }

    /** Carga entera: el primer dibujo, el contenido (sale la foto) y la página quieta. */
    private suspend fun load(name: String, v: WebView, go: () -> Unit) {
        next()
        val old = eval(v, "window.__lucidoDev ? __lucidoDev.id : null") // la página de antes: su resultado no cuenta
        go()
        val r = result(v, old) ?: return add(name, "—")
        add("$name, primer dibujo", ms(r, "fcp"))
        add("$name, con contenido", ms(r, "ready"))
        add("$name, completa", ms(r, "ms") + r.optString("more") + tasks(r))
    }

    /** Un paso dentro de la página ([js]): hasta que queda quieta o, con [video], hasta que arranca. */
    private suspend fun step(name: String, v: WebView, js: String, video: Boolean = false) {
        next()
        val id = json(eval(v, "window.__lucidoDev ? __lucidoDev.start($video) : null")) as? String ?: return add(name, "—")
        eval(v, js)
        val r = result(v, "null") ?: return add(name, "—")
        val reloaded = if (r.optString("id") != id) " (recargó)" else "" // no navegó adentro: cargó de nuevo
        add(name, ms(r, "ms") + r.optString("more") + reloaded + tasks(r))
    }

    private suspend fun next() {
        n++
        progress.text = a.getString(R.string.dev_bench_progress, round, ROUNDS, n, perRound)
        delay(SETTLE)
    }

    private fun ms(r: JSONObject, k: String) = if (r.isNull(k)) "?" else r.optLong(k).toString()

    private fun tasks(r: JSONObject) = r.optJSONArray("long")?.let { " tl ${it.optInt(0)}/${it.optInt(1)}" } ?: ""

    private fun add(name: String, value: String) { rows.getOrPut(name) { mutableListOf() }.add(value) }

    /** Le pregunta a la página hasta que terminó el paso (20 s como mucho). [old]: id de la página de antes, en JSON. */
    private suspend fun result(v: WebView, old: String): JSONObject? {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) {
            delay(250)
            if (!a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) throw Left()
            (json(eval(v, "window.__lucidoDev ? __lucidoDev.result($old) : null")) as? JSONObject)?.let { return it }
        }
        return null
    }

    // Si la página cambia mientras tanto, Android puede no contestar nunca: a los 3 s, sin respuesta
    private suspend fun eval(v: WebView, js: String): String = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { k -> v.evaluateJavascript(js) { k.resume(it ?: "null") } }
    } ?: "null"

    private fun json(s: String): Any? = runCatching { JSONTokener(s).nextValue() }.getOrNull()

    private fun pages(v: View): List<WebView> = when (v) {
        is WebView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { pages(v.getChildAt(it)) }
        else -> emptyList()
    }

    private companion object {
        const val ROUNDS = 3
        const val SETTLE = 1500L // entre pasos, para que la página termine lo que hace después de quedar quieta
    }
}
