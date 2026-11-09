/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.quickactions.media.ui.viewmodel

import android.content.Context
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.media.domain.interactor.MediaControlChipInteractor
import com.android.systemui.statusbar.quickactions.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.quickactions.shared.model.ChipContent
import com.android.systemui.statusbar.quickactions.shared.model.ChipIcon
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipId
import com.android.systemui.statusbar.quickactions.shared.model.QuickActionChipModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.map

/**
 * [StatusBarPopupChipViewModel] for a media control chip in the status bar. This view model is
 * responsible for converting the [MediaControlChipModel] to a [QuickActionChipModel] that can be
 * used to display a media control chip.
 */
class MediaControlChipViewModel
@AssistedInject
constructor(
    @Application private val applicationContext: Context,
    mediaControlChipInteractor: MediaControlChipInteractor,
    private val popupViewModelFactory: MediaControlPopupViewModel.Factory,
) : StatusBarPopupChipViewModel, HydratedActivatable() {

    /**
     * A snapshot [State] of the current [QuickActionChipModel]. This emits a new
     * [QuickActionChipModel] whenever the underlying [MediaControlChipModel] changes.
     */
    override val chip: QuickActionChipModel by
        mediaControlChipInteractor.mediaControlChipModel
            .map { model -> toPopupChipModel(model) }
            .hydratedStateOf(
                initialValue = QuickActionChipModel.Hidden(QuickActionChipId.MediaControl)
            )

    private fun toPopupChipModel(model: MediaControlChipModel?): QuickActionChipModel {
        if (model == null || model.songName.isNullOrEmpty()) {
            return QuickActionChipModel.Hidden(QuickActionChipId.MediaControl)
        }

        val contentDescription = model.appName?.let { ContentDescription.Loaded(description = it) }

        val defaultIcon =
            model.artworkIcon
                ?: model.appIcon
                ?: Icon.Resource(
                    resId = com.android.internal.R.drawable.ic_audio_media,
                    contentDescription = contentDescription,
                )

        val mainIcon = ChipIcon(icon = defaultIcon)
        val playPauseIcon = model.createIcon()

        val normalizedTitle = normalizeSongTitle(model.songName.toString(), model.artistName?.toString())

        return QuickActionChipModel.PopupChip(
            chipId = QuickActionChipId.MediaControl,
            icons = listOf(mainIcon, playPauseIcon),
            chipContent = ChipContent.Text(normalizedTitle),
            popupViewModelFactory = popupViewModelFactory,
        )
    }

    private fun MediaControlChipModel.createIcon(): ChipIcon {
        val playOrPause = playOrPause ?: return getDefaultIcon()
        val icon = playOrPause.icon ?: return getDefaultIcon()
        val action = playOrPause.action ?: return getDefaultIcon()

        // This creates a separate copy of the icon so it can be styled individually for the media
        // chip
        val copyIcon = icon.constantState?.newDrawable()?.mutate() ?: icon

        val contentDescription =
            ContentDescription.Loaded(description = playOrPause.contentDescription.toString())

        return ChipIcon(
            icon = Icon.Loaded(drawable = copyIcon, contentDescription = contentDescription),
            onClick = { action.run() },
            isHighlighted = true,
        )
    }

    /** fallback in case [MediaControlChipModel.playOrPause] is incomplete */
    private fun MediaControlChipModel.getDefaultIcon(): ChipIcon {
        val contentDescription = appName?.let { ContentDescription.Loaded(description = it) }

        val defaultIcon =
            artworkIcon
                ?: appIcon
                ?: Icon.Resource(
                    resId = com.android.internal.R.drawable.ic_audio_media,
                    contentDescription = contentDescription,
                )

        return ChipIcon(icon = defaultIcon)
    }

    private fun normalizeSongTitle(title: String, artist: String?): String {
        if (title.isBlank()) return title
        val separatorRegex = Regex("\\s*[-–—|•]\\s*")
        val parts = title.split(separatorRegex, limit = 2)
        if (parts.size != 2) return title

        val left = parts[0].trim()
        val right = parts[1].trim()
        if (left.isBlank() || right.isBlank()) return title

        val artistKey = artist?.toComparableKey() ?: return title
        val leftKey = left.toComparableKey()
        val rightKey = right.toComparableKey()

        return when {
            leftKey.contains(artistKey) -> right
            rightKey.contains(artistKey) -> left
            else -> title
        }
    }

    private fun String.toComparableKey(): String {
        return lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    @AssistedFactory
    interface Factory {
        fun create(): MediaControlChipViewModel
    }
}
