/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
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

package com.amaze.filemanager.ui.dialogs

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import com.afollestad.materialdialogs.MaterialDialog
import com.amaze.filemanager.R
import com.amaze.filemanager.application.AppConfig
import com.amaze.filemanager.database.UtilsHandler
import com.amaze.filemanager.database.models.OperationData
import com.amaze.filemanager.ui.activities.MainActivity
import com.amaze.filemanager.ui.fragments.MainFragment
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_GRID_COLUMNS
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_GRID_COLUMNS_DEFAULT
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_SHOW_DIVIDERS
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_SHOW_FILE_SIZE
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_SHOW_HIDDENFILES
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_SHOW_LAST_MODIFIED
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_SHOW_PERMISSIONS
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants.PREFERENCE_SHOW_THUMB
import com.amaze.filemanager.utils.DataUtils

/**
 * Sprint 10 — single dialog that bundles every per-folder appearance toggle
 * (layout, grid density, thumbnail / metadata visibility, dividers, hidden
 * files) so the user can adjust them from the toolbar instead of digging
 * through the global Settings screen. Each toggle persists to the same
 * [SharedPreferences] keys the existing settings UI uses, ensuring both paths
 * stay in sync.
 */
object ViewOptionsDialog {
    private const val GRID_COLUMNS_MIN = 1
    private const val GRID_COLUMNS_MAX = 6

    @JvmStatic
    fun show(
        mainActivity: MainActivity,
        mainFragment: MainFragment,
    ) {
        val prefs = mainActivity.prefs
        val theme = mainActivity.appTheme
        val accent = mainActivity.accent
        val view =
            LayoutInflater.from(mainActivity)
                .inflate(R.layout.dialog_view_options, null, false)

        val layoutList = view.findViewById<RadioButton>(R.id.view_options_layout_list_radio)
        val layoutGrid = view.findViewById<RadioButton>(R.id.view_options_layout_grid_radio)
        val gridColumnsLabel = view.findViewById<TextView>(R.id.view_options_grid_columns_label)
        val gridColumnsSeek = view.findViewById<SeekBar>(R.id.view_options_grid_columns_seek)
        val showThumbnails = view.findViewById<CheckBox>(R.id.view_options_show_thumbnails)
        val showFileSize = view.findViewById<CheckBox>(R.id.view_options_show_file_size)
        val showLastModified = view.findViewById<CheckBox>(R.id.view_options_show_last_modified)
        val showPermissions = view.findViewById<CheckBox>(R.id.view_options_show_permissions)
        val showDividers = view.findViewById<CheckBox>(R.id.view_options_show_dividers)
        val showHidden = view.findViewById<CheckBox>(R.id.view_options_show_hidden)

        val viewModel = mainFragment.mainFragmentViewModel
        val currentlyGrid = viewModel?.isList?.let { !it } ?: false
        layoutList.isChecked = !currentlyGrid
        layoutGrid.isChecked = currentlyGrid

        val storedColumns =
            prefs.getString(PREFERENCE_GRID_COLUMNS, PREFERENCE_GRID_COLUMNS_DEFAULT)
                ?.toIntOrNull()
                ?: PREFERENCE_GRID_COLUMNS_DEFAULT.toInt()
        val initialColumns = storedColumns.coerceIn(GRID_COLUMNS_MIN, GRID_COLUMNS_MAX)
        gridColumnsSeek.max = GRID_COLUMNS_MAX - GRID_COLUMNS_MIN
        gridColumnsSeek.progress = initialColumns - GRID_COLUMNS_MIN
        gridColumnsLabel.text =
            mainActivity.getString(R.string.view_options_grid_columns) + ": " + initialColumns
        gridColumnsSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    val cols = progress + GRID_COLUMNS_MIN
                    gridColumnsLabel.text =
                        mainActivity.getString(R.string.view_options_grid_columns) + ": " + cols
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        showThumbnails.isChecked = prefs.getBoolean(PREFERENCE_SHOW_THUMB, true)
        showFileSize.isChecked = prefs.getBoolean(PREFERENCE_SHOW_FILE_SIZE, false)
        showLastModified.isChecked = prefs.getBoolean(PREFERENCE_SHOW_LAST_MODIFIED, true)
        showPermissions.isChecked = prefs.getBoolean(PREFERENCE_SHOW_PERMISSIONS, false)
        showDividers.isChecked = prefs.getBoolean(PREFERENCE_SHOW_DIVIDERS, false)
        showHidden.isChecked = prefs.getBoolean(PREFERENCE_SHOW_HIDDENFILES, false)

        MaterialDialog.Builder(mainActivity)
            .title(R.string.view_options)
            .theme(theme.materialDialogTheme)
            .customView(view, true)
            .positiveText(R.string.view_options_apply)
            .positiveColor(accent)
            .negativeText(R.string.cancel)
            .negativeColor(accent)
            .onPositive { _, _ ->
                applyChanges(
                    mainActivity,
                    mainFragment,
                    prefs,
                    layoutGrid.isChecked,
                    gridColumnsSeek.progress + GRID_COLUMNS_MIN,
                    showThumbnails.isChecked,
                    showFileSize.isChecked,
                    showLastModified.isChecked,
                    showPermissions.isChecked,
                    showDividers.isChecked,
                    showHidden.isChecked,
                )
            }
            .build()
            .show()
    }

    @Suppress("LongParameterList")
    private fun applyChanges(
        mainActivity: MainActivity,
        mainFragment: MainFragment,
        prefs: SharedPreferences,
        wantGrid: Boolean,
        gridColumns: Int,
        showThumbnails: Boolean,
        showFileSize: Boolean,
        showLastModified: Boolean,
        showPermissions: Boolean,
        showDividers: Boolean,
        showHidden: Boolean,
    ) {
        prefs
            .edit()
            .putString(PREFERENCE_GRID_COLUMNS, gridColumns.toString())
            .putBoolean(PREFERENCE_SHOW_THUMB, showThumbnails)
            .putBoolean(PREFERENCE_SHOW_FILE_SIZE, showFileSize)
            .putBoolean(PREFERENCE_SHOW_LAST_MODIFIED, showLastModified)
            .putBoolean(PREFERENCE_SHOW_PERMISSIONS, showPermissions)
            .putBoolean(PREFERENCE_SHOW_DIVIDERS, showDividers)
            .putBoolean(PREFERENCE_SHOW_HIDDENFILES, showHidden)
            .apply()

        // Persist the per-folder layout to the same stores the existing
        // R.id.view menu writes to (in-memory DataUtils + UtilsHandler SQLite),
        // so the choice survives an app restart and stays in sync with the
        // legacy toggle path.
        val viewModel = mainFragment.mainFragmentViewModel ?: return
        val currentPath = mainFragment.currentPath
        if (currentPath != null) {
            persistPathLayout(currentPath, wantGrid)
        }
        // Pull the freshly-saved grid column count back into the view model
        // before switchView() rebuilds the grid layout manager.
        viewModel.initColumns(prefs)
        mainFragment.switchView()
        // switchView() only rebuilds the grid layout manager when transitioning
        // list→grid; if we were already in grid mode, the cached
        // GridLayoutManager keeps its old span count. applyGridColumns() forces
        // the span update so the SeekBar's effect is visible immediately.
        mainFragment.applyGridColumns()
        // Force a list reload so the recycler adapter rebinds with the new
        // per-row metadata flags (file size, last modified, permissions,
        // dividers) and respects the updated hidden-files preference.
        if (currentPath != null) {
            mainFragment.loadlist(
                currentPath,
                false,
                viewModel.openMode,
                true,
            )
        }
        mainActivity.invalidateOptionsMenu()
    }

    /**
     * Mirrors the persistence pattern in `MainActivity.onOptionsItemSelected`
     * for `R.id.view`: write the new layout to the SQLite store via
     * [UtilsHandler] (background thread), drop the previous opposite-layout
     * row if any, then update the in-memory [DataUtils] cache. Without the
     * SQLite write, the choice is wiped on next app start because DataUtils
     * is rehydrated from the database in [MainActivity.onCreate].
     */
    private fun persistPathLayout(
        currentPath: String,
        wantGrid: Boolean,
    ) {
        val utilsHandler: UtilsHandler = AppConfig.getInstance().utilsHandler
        val previousLayout =
            DataUtils.getInstance().getListOrGridForPath(currentPath, DataUtils.LIST)
        AppConfig.getInstance().runInBackground {
            if (wantGrid) {
                if (previousLayout == DataUtils.LIST) {
                    utilsHandler.removeFromDatabase(
                        OperationData(UtilsHandler.Operation.LIST, currentPath),
                    )
                }
                utilsHandler.saveToDatabase(
                    OperationData(UtilsHandler.Operation.GRID, currentPath),
                )
            } else {
                if (previousLayout == DataUtils.GRID) {
                    utilsHandler.removeFromDatabase(
                        OperationData(UtilsHandler.Operation.GRID, currentPath),
                    )
                }
                utilsHandler.saveToDatabase(
                    OperationData(UtilsHandler.Operation.LIST, currentPath),
                )
            }
        }
        DataUtils.getInstance().setPathAsGridOrList(
            currentPath,
            if (wantGrid) DataUtils.GRID else DataUtils.LIST,
        )
    }
}
