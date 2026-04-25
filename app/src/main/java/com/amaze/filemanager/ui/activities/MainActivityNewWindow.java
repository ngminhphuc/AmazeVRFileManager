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

package com.amaze.filemanager.ui.activities;

/**
 * Sibling of {@link MainActivity} used exclusively as the target of the "New window" action on Meta
 * Quest 3 and Android split-screen, declared in the manifest with {@code
 * launchMode="singleInstancePerTask"} + {@code documentLaunchMode="always"}.
 *
 * <p>Splitting this out as a subclass lets us keep the primary {@code MainActivity} on its original
 * {@code launchMode="singleInstance"} — preserving every existing intent-filter routing,
 * back-stack, and launcher behaviour unchanged — while still giving users a way to spawn additional
 * independent Amaze panels. The class deliberately carries no behaviour of its own; all UI and
 * logic is inherited from {@link MainActivity}.
 */
public class MainActivityNewWindow extends MainActivity {}
