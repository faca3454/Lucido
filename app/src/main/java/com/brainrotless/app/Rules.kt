package com.brainrotless.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Reglas de bloqueo (block.user.js).
 *
 * La app trae una copia incluida y, si está activado en Ajustes, descarga la versión
 * de la rama main del repo. La descargada solo se usa si su @version es igual o
 * mayor que la incluida, así un APK nuevo nunca queda pisado por reglas viejas.
 */
object Rules {
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/$REPO/main/app/src/main/assets/block.user.js"
    private const val CACHE_FILE = "rules.js"
    private const val MAX_BYTES = 256 * 1024
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    // En memoria: MainActivity pide el script en cada tick y leerlo es ir al disco
    @Volatile private var loaded: Pair<Boolean, String>? = null // reglas remotas activadas → reglas
    private var built = Triple("", "", "") // configuración, reglas y el script armado con ellas

    fun version(js: String): Int =
        Regex("@version\\s+(\\d+)").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun bundled(c: Context): String =
        c.assets.open("block.user.js").bufferedReader().use { it.readText() }

    /** Script de reglas que corresponde usar ahora. */
    fun current(c: Context): String {
        val remote = c.userPrefs().getBoolean("remote_rules", true)
        loaded?.let { (r, js) -> if (r == remote) return js }
        return read(c, remote).also { loaded = remote to it }
    }

    private fun read(c: Context, remoteOn: Boolean): String {
        val base = bundled(c)
        if (!remoteOn) return base
        val cached = File(c.filesDir, CACHE_FILE)
        if (cached.exists()) {
            val remote = runCatching { cached.readText() }.getOrNull()
            if (remote != null && version(remote) >= version(base)) return remote
        }
        return base
    }

    /**
     * Script final que se inyecta: configuración de Ajustes + reglas (en los APK de prueba, con sus
     * herramientas antes). Se rearma solo si algo cambió.
     */
    fun build(c: Context): String {
        Setup.migratePrefs(c)
        val p = c.userPrefs()
        val reels = Lock.reelsAllowed(c)
        val cfg = JSONObject()
            .put("igFeed", p.getString("ig_feed", "normal"))
            .put("igBlockExplore", p.getBoolean("ig_block_explore", true))
            .put("igTapToPlay", p.getBoolean("ig_tap_to_play", false))
            .put("igReels", reels)
            .put("ytShorts", reels)
            .put("smooth", p.getBoolean("smooth_transitions", false))
            .put("swipeReply", p.getBoolean("swipe_reply", true))
            .put("igBlockAds", p.getBoolean("ig_block_ads", true))
            .put("ytBlockAds", p.getBoolean("yt_block_ads", true))
        // Hora de dormir, foco o límite diario: Instagram solo para mensajes. El porqué y hasta cuándo
        // lo dice la barra de la app (el cartel del script, restrictedMsg, queda para Safari)
        if (Lock.ig(c) != null) cfg.put("restricted", true)
        val config = cfg.toString()
        val rules = current(c)
        if (config != built.first || rules != built.second) built = Triple(config, rules, "window.__noscrollCfg=$config;\n${DevTools.script(c)}$rules")
        return built.third
    }

    /** Solo la configuración (JSON), para aplicarla en una página abierta sin recargarla. */
    fun config(c: Context): String {
        build(c)
        return built.first
    }

    /** Busca reglas nuevas en segundo plano; llama a onUpdated (en otro hilo) si cambiaron. */
    fun refreshAsync(c: Context, force: Boolean = false, onUpdated: () -> Unit) {
        val app = c.applicationContext
        if (!app.userPrefs().getBoolean("remote_rules", true)) return
        val meta = app.getSharedPreferences("rules_meta", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - meta.getLong("last_check", 0) < CHECK_EVERY_MS) return

        thread(name = "rules-update") {
            val body = runCatching { download() }.getOrNull() ?: return@thread
            meta.edit().putLong("last_check", now).apply()

            // Validación mínima: que sea nuestro script y no más viejo que el incluido
            val valid = body.contains("==UserScript==") &&
                body.contains("__noscroll") &&
                version(body) >= version(bundled(app))
            if (!valid || body == current(app)) return@thread

            val tmp = File(app.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(body)
            if (tmp.renameTo(File(app.filesDir, CACHE_FILE))) {
                loaded = null
                onUpdated()
            }
        }
    }

    private fun download(): String? {
        val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
        }
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
