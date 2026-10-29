package com.brainrotless.app

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.util.Date

/**
 * Ajustes técnicos: idioma, permisos, pausa del bloqueo y actualizaciones. Modo dormir, foco,
 * compromiso e Instagram/YouTube se configuran en sus pantallas (PanelActivity).
 */
class SettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Lang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = R.id.settings_container }
        setContentView(container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            Setup.migratePrefs(requireContext()) // antes de inflar, que guarda los valores por defecto
            setPreferencesFromResource(R.xml.preferences, rootKey)

            onClick("perm_notif") { Setup.openNotifications(requireContext()) }
            onClick("perm_links") { Setup.openLinks(requireContext()) }
            onClick("perm_guard") { Setup.openAccessibility(requireContext()) }
            onClick("perm_restricted") { Setup.explainRestricted(requireActivity()) }
            onClick("stats") { PanelActivity.open(requireContext(), PanelActivity.STATS) }
            onClick("guide") { startActivity(OnboardingActivity.intent(requireContext(), again = true)) }
            onClick("panel_sleep") { PanelActivity.open(requireContext(), PanelActivity.SLEEP) }
            onClick("panel_focus") { PanelActivity.open(requireContext(), PanelActivity.FOCUS) }
            onClick("panel_commit") { PanelActivity.open(requireContext(), PanelActivity.COMMIT) }
            onClick("panel_apps") { PanelActivity.open(requireContext(), PanelActivity.APPS) }
            onClick("bypass_now") { toggleBypass() }
            onClick("bypass_tile") { addTile() }
            onClick("check_now") {
                Updater.maybeCheck(requireActivity(), force = true)
                Rules.refreshAsync(requireContext(), force = true) { }
            }
            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>("hide_icon")?.setOnPreferenceChangeListener { _, _ ->
                view?.post { Protection.applyIconVisibility(requireContext()) } // tras guardar el ajuste
                true
            }
            findPreference<ListPreference>("language")?.apply {
                value = Lang.current(requireContext())
                setOnPreferenceChangeListener { _, v -> Lang.set(requireActivity(), v as String); true }
            }
            DevTools.addTo(this) // APK de prueba: la medición automática
        }

        override fun onResume() {
            super.onResume()
            // Volviendo de los ajustes del sistema o de una pantalla: refrescar los estados
            val c = requireContext()
            val listenerOn = Setup.listenerOn(c)
            val guardOn = Setup.guardOn(c)
            findPreference<Preference>("perm_notif")?.summary = status(listenerOn, getString(R.string.perm_notif_on))
            findPreference<Preference>("perm_guard")?.summary = status(guardOn, getString(R.string.perm_guard_on))
            findPreference<Preference>("perm_restricted")?.isVisible = Setup.restrictedApplies() && !(listenerOn && guardOn)
            refreshRules()
            refreshBypass()
        }

        /** Resumen de cada pantalla de reglas, como en el inicio, y de las estadísticas. */
        private fun refreshRules() {
            val c = requireContext()
            findPreference<Preference>("panel_sleep")?.summary = when {
                !Sleep.enabled(c) -> getString(R.string.home_sleep_off)
                else -> Sleep.get(c, null).let { (s, w) -> getString(R.string.night_range, Sleep.fmt(c, s), Sleep.fmt(c, w)) }
            }
            findPreference<Preference>("panel_focus")?.summary = when {
                !Focus.enabled(c) -> getString(R.string.home_focus_off)
                else -> Focus.times(c).let { (s, e) -> getString(R.string.night_range, Sleep.fmt(c, s), Sleep.fmt(c, e)) }
            }
            findPreference<Preference>("panel_commit")?.summary = if (Commitment.active(c)) {
                val left = Commitment.daysLeft(c).toInt()
                resources.getQuantityString(R.plurals.days_left_n, left, left)
            } else getString(R.string.home_commit_idle, Commitment.MIN_DAYS)
            findPreference<Preference>("panel_apps")?.summary = getString(R.string.panel_apps_summary)
            findPreference<Preference>("stats")?.summary =
                PanelScreen.statsBrief(c, Usage.history(c), Usage.today(c), UsageAccess.baseline(c))
        }

        // ---------- Pausa del bloqueo ----------

        private fun toggleBypass() {
            val c = requireContext()
            if (Bypass.active(c)) Bypass.stop(c) else Bypass.start(c)
            TileService.requestListeningState(c, ComponentName(c, BypassTile::class.java)) // que el botón se actualice
            refreshBypass()
        }

        private fun refreshBypass() {
            val c = requireContext()
            val lock = Lock.yt(c) ?: Lock.ig(c)
            findPreference<Preference>("bypass_now")?.apply {
                isEnabled = lock == null
                when {
                    lock != null -> {
                        title = getString(R.string.bypass_now)
                        summary = getString(R.string.tile_blocked, Lock.until(c, lock))
                    }
                    Bypass.active(c) -> {
                        title = getString(R.string.bypass_stop)
                        summary = getString(R.string.bypass_active, DateFormat.getTimeFormat(c).format(Date(Bypass.until(c))))
                    }
                    else -> {
                        title = getString(R.string.bypass_now)
                        summary = getString(R.string.bypass_idle)
                    }
                }
            }
            findPreference<Preference>("bypass_tile")?.isVisible = Build.VERSION.SDK_INT >= 33
        }

        /** Android 13+: ventana del sistema para agregar el botón "Apps oficiales" con un toque. */
        private fun addTile() {
            if (Build.VERSION.SDK_INT < 33) return
            val c = requireContext()
            c.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(c, BypassTile::class.java), getString(R.string.tile_label),
                Icon.createWithResource(c, R.drawable.ic_tile), c.mainExecutor
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> c.getString(R.string.tile_added)
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> c.getString(R.string.tile_already)
                    else -> null
                }
                msg?.let { Toast.makeText(c, it, Toast.LENGTH_SHORT).show() }
            }
        }

        private fun status(on: Boolean, what: String) =
            if (on) getString(R.string.status_on, what) else getString(R.string.status_off)

        private fun onClick(key: String, action: () -> Unit) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }
    }
}
