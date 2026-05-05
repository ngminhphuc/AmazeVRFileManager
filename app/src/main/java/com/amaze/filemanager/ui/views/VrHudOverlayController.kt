/*
 * Copyright (C) 2014-2020 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.ui.views

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.MaterialDialog
import com.amaze.filemanager.R
import com.amaze.filemanager.ui.activities.MainActivity
import com.amaze.filemanager.ui.activities.MediaServersActivity
import com.amaze.filemanager.ui.activities.SmbServersActivity
import com.amaze.filemanager.ui.activities.WebDavServersActivity
import com.amaze.filemanager.ui.dialogs.ViewOptionsDialog
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants

/**
 * Sprint 13: VR HUD overlay controller.
 *
 * Wires the floating quick-action panel inflated by [layout_vr_hud] into
 * [MainActivity]. The HUD is only meaningful for the file-list flows so it
 * tracks whether the activity is currently showing a [MainFragment] (the
 * controller hides the HUD on AppsList / Cloud chooser / Trash bin views to
 * avoid surfacing actions that have no fragment to dispatch to).
 *
 * The controller installs a touch listener on the panel that resets a
 * fade-out timer; after [IDLE_MS] ms with no interaction the HUD slowly
 * dims to [IDLE_ALPHA] but stays click-through, so the user can still
 * reach the buttons without an explicit "wake" gesture. Any tap or focus
 * change restores full opacity. This mirrors the behaviour Quest's own
 * system bar uses for its dock.
 */
class VrHudOverlayController(private val activity: MainActivity) {
    companion object {
        private const val IDLE_MS = 3000L
        private const val IDLE_ALPHA = 0.4f
        private const val ACTIVE_ALPHA = 1.0f
    }

    private val container: View? = activity.findViewById(R.id.vr_hud_container)
    private val handler = Handler(Looper.getMainLooper())
    private val fader = Runnable { container?.animate()?.alpha(IDLE_ALPHA)?.setDuration(400)?.start() }

    fun attach() {
        val panel = container ?: return
        panel.findViewById<View>(R.id.vr_hud_home).setOnClickListener { onHome() }
        panel.findViewById<View>(R.id.vr_hud_back).setOnClickListener { onBack() }
        panel.findViewById<View>(R.id.vr_hud_up).setOnClickListener { onUp() }
        panel.findViewById<View>(R.id.vr_hud_forward).setOnClickListener { onForward() }
        panel.findViewById<View>(R.id.vr_hud_search).setOnClickListener { onSearch() }
        panel.findViewById<View>(R.id.vr_hud_drawer).setOnClickListener { onDrawer() }
        panel.findViewById<View>(R.id.vr_hud_view_options).setOnClickListener { onViewOptions() }
        panel.findViewById<View>(R.id.vr_hud_servers).setOnClickListener { onServers() }
        // Touch & focus reset the idle timer.
        panel.setOnTouchListener { _, _ ->
            wakeUp()
            false
        }
        for (i in 0 until panel.let { (it as? android.view.ViewGroup)?.childCount ?: 0 }) {
            (panel as android.view.ViewGroup).getChildAt(i).setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) wakeUp()
            }
        }
    }

    /**
     * Refresh visibility according to the persisted preference and the
     * current foreground fragment. Called from MainActivity at the same
     * lifecycle points where the toolbar nav buttons are refreshed
     * ([MainActivity.invalidateOptionsMenu]) so the HUD never lags
     * behind the toolbar.
     */
    fun refreshVisibility(showForCurrentFragment: Boolean) {
        val panel = container ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val enabled =
            prefs.getBoolean(
                PreferencesConstants.PREFERENCE_VR_HUD_OVERLAY,
                PreferencesConstants.DEFAULT_PREFERENCE_VR_HUD_OVERLAY,
            )
        panel.visibility = if (enabled && showForCurrentFragment) View.VISIBLE else View.GONE
        if (panel.visibility == View.VISIBLE) wakeUp()
    }

    /** Toggle the HUD on/off and persist the new state. */
    fun toggle(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val current =
            prefs.getBoolean(
                PreferencesConstants.PREFERENCE_VR_HUD_OVERLAY,
                PreferencesConstants.DEFAULT_PREFERENCE_VR_HUD_OVERLAY,
            )
        val next = !current
        prefs.edit().putBoolean(PreferencesConstants.PREFERENCE_VR_HUD_OVERLAY, next).apply()
        activity.invalidateOptionsMenu()
        return next
    }

    fun isShowing(): Boolean = container?.visibility == View.VISIBLE

    /**
     * Whether the HUD is currently enabled by the persisted preference,
     * regardless of whether the active fragment is showing it. Used by
     * the "Show / Hide VR HUD" overflow toggle so that the title and the
     * subsequent toggle action both refer to the same persisted state
     * (otherwise on AppsList / FtpServer the panel is force-GONE and
     * isShowing() would lie about the user's intent).
     */
    fun isEnabledByPreference(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        return prefs.getBoolean(
            PreferencesConstants.PREFERENCE_VR_HUD_OVERLAY,
            PreferencesConstants.DEFAULT_PREFERENCE_VR_HUD_OVERLAY,
        )
    }

    private fun wakeUp() {
        val panel = container ?: return
        panel.animate().cancel()
        panel.alpha = ACTIVE_ALPHA
        handler.removeCallbacks(fader)
        handler.postDelayed(fader, IDLE_MS)
    }

    private fun onHome() {
        wakeUp()
        activity.currentMainFragment?.home()
    }

    private fun onBack() {
        wakeUp()
        activity.currentMainFragment?.navigateBack()
    }

    private fun onUp() {
        wakeUp()
        activity.currentMainFragment?.goBack()
    }

    private fun onForward() {
        wakeUp()
        activity.currentMainFragment?.navigateForward()
    }

    private fun onSearch() {
        wakeUp()
        val sv = activity.appbar?.searchView ?: return
        if (!sv.isShown) sv.revealSearchView() else sv.hideSearchView()
    }

    private fun onDrawer() {
        wakeUp()
        val drawer = activity.drawer ?: return
        if (drawer.isLocked) return
        if (drawer.isOpen) drawer.close() else drawer.open()
    }

    private fun onViewOptions() {
        wakeUp()
        val frag = activity.currentMainFragment ?: return
        ViewOptionsDialog.show(activity, frag)
    }

    private fun onServers() {
        wakeUp()
        MaterialDialog.Builder(activity)
            .theme(activity.appTheme.materialDialogTheme)
            .title(R.string.vr_hud_servers_dialog_title)
            .items(
                activity.getString(R.string.vr_hud_servers_smb),
                activity.getString(R.string.vr_hud_servers_webdav),
                activity.getString(R.string.vr_hud_servers_media),
            )
            .itemsCallback { _, _, which, _ ->
                val target =
                    when (which) {
                        0 -> SmbServersActivity::class.java
                        1 -> WebDavServersActivity::class.java
                        else -> MediaServersActivity::class.java
                    }
                activity.startActivity(Intent(activity, target))
            }
            .show()
    }
}
