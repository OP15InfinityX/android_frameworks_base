/*
 * Copyright (C) 2026 Project Infinity X
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

package com.android.systemui.statusbar.pipeline.shared.ui.model

import android.view.View
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal data class HomeStatusBarVisibilityInput(
    val model: VisibilityModel,
    val isHomeStatusBarVisible: Boolean,
    val isKeyguardVisible: Boolean,
    val hideLockscreenStatusBar: Boolean,
)

/** Skip only the first home-bar reveal after a deliberately hidden keyguard bar. */
internal fun Flow<HomeStatusBarVisibilityInput>.withoutHiddenKeyguardRevealAnimation():
    Flow<VisibilityModel> = flow {
    var waitingForUnlock = false
    var revealWithoutAnimation = false
    collect { input ->
        if (!input.hideLockscreenStatusBar) {
            waitingForUnlock = false
            revealWithoutAnimation = false
        } else if (input.isKeyguardVisible && !input.isHomeStatusBarVisible) {
            waitingForUnlock = true
        }

        if (input.model.visibility != View.VISIBLE) {
            revealWithoutAnimation = false
        } else if (waitingForUnlock && input.isHomeStatusBarVisible) {
            revealWithoutAnimation = true
        }

        // Consume even if this particular child is disabled: a later app/flag change
        // is not an unlock. Keep the reveal flag while visible so StateFlow conflation
        // cannot replace the instant reveal with a fade in the same frame.
        if (input.isHomeStatusBarVisible) {
            waitingForUnlock = false
        }
        emit(
            input.model.copy(
                shouldAnimateChange = input.model.shouldAnimateChange && !revealWithoutAnimation
            )
        )
    }
}
