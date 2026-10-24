/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.common.ui.compose

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.CrossWindowBlurListeners
import android.view.View
import android.view.ViewTreeObserver
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import com.android.systemui.res.R
import java.util.function.Consumer

private class BlurStateHolder(val view: View) {
    var blurDrawable: BackgroundBlurDrawable? = null
    var lastAlphaInt = -1
    var lastRadiusPx = -1
    var lastCornerRadiusPx = -1f

    fun getOrCreateDrawable(cornerRadiusPx: Float): BackgroundBlurDrawable? {
        if (blurDrawable == null) {
            val rootImpl = view.viewRootImpl ?: return null
            blurDrawable = rootImpl.createBackgroundBlurDrawable()?.apply {
                setBlurRadius(0)
                setAlpha(0)
                setCornerRadius(cornerRadiusPx)
            }
        }
        return blurDrawable
    }

    fun updateDrawable(
        combinedAlpha: Float,
        blurRadiusPx: Float,
        cornerRadiusPx: Float
    ): Boolean {
        val drawable = getOrCreateDrawable(cornerRadiusPx) ?: return false
        val alphaInt = (combinedAlpha * 255).toInt()
        val radiusPx = (blurRadiusPx * combinedAlpha).toInt()

        if (combinedAlpha <= 0.01f || alphaInt <= 2) {
            if (lastAlphaInt != 0 || lastRadiusPx != 0) {
                drawable.setBlurRadius(0)
                drawable.setAlpha(0)
                drawable.setBounds(0, 0, 0, 0)
                lastAlphaInt = 0
                lastRadiusPx = 0
            }
            return false
        } else {
            if (lastAlphaInt != alphaInt || lastRadiusPx != radiusPx || lastCornerRadiusPx != cornerRadiusPx) {
                drawable.setBlurRadius(radiusPx)
                drawable.setAlpha(alphaInt)
                drawable.setCornerRadius(cornerRadiusPx)
                lastAlphaInt = alphaInt
                lastRadiusPx = radiusPx
                lastCornerRadiusPx = cornerRadiusPx
            }
            return true
        }
    }

    fun cleanup() {
        blurDrawable?.apply {
            setBlurRadius(0)
            setAlpha(0)
            setBounds(0, 0, 0, 0)
        }
        blurDrawable = null
        lastAlphaInt = -1
        lastRadiusPx = -1
        lastCornerRadiusPx = -1f
    }
}

@Composable
fun rememberIsBlurSupported(): Boolean {
    val context = LocalContext.current
    var isBlurSupported by remember {
        mutableStateOf(
            runCatching {
                CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled
            }.getOrDefault(false)
        )
    }
    DisposableEffect(context) {
        val listener = Consumer<Boolean> { supported ->
            isBlurSupported = supported
        }
        val crossWindowBlurListeners = runCatching { CrossWindowBlurListeners.getInstance() }.getOrNull()
        if (crossWindowBlurListeners != null) {
            crossWindowBlurListeners.addListener(context.mainExecutor, listener)
            isBlurSupported = crossWindowBlurListeners.isCrossWindowBlurEnabled
        }
        onDispose {
            crossWindowBlurListeners?.removeListener(listener)
        }
    }
    return isBlurSupported
}

@Composable
fun Modifier.translucentBlurBackground(
    cornerRadius: Dp,
    isBlurSupported: Boolean = rememberIsBlurSupported(),
    alphaProgress: Float = 1f,
    opaqueColor: Color = MaterialTheme.colorScheme.surfaceBright,
    blurRadiusDp: Dp? = null,
    tintColor: Color? = null,
): Modifier {
    if (!isBlurSupported) {
        return this.background(color = opaqueColor, shape = RoundedCornerShape(cornerRadius))
    }

    val resolvedBlurRadiusDp = blurRadiusDp ?: dimensionResource(R.dimen.volume_dialog_background_surface_blur_radius)
    val resolvedTintColor = tintColor ?: colorResource(R.color.volume_dialog_view_background_blur).copy(alpha = 0.58f)

    val view = LocalView.current
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { resolvedBlurRadiusDp.toPx() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val holder = remember(view) { BlurStateHolder(view) }

    DisposableEffect(holder, view, alphaProgress, blurRadiusPx, cornerRadiusPx) {
        val syncBlur = {
            val hierarchyAlpha = calculateEffectiveAlpha(view)
            val combinedAlpha = (alphaProgress.coerceIn(0f, 1f) * hierarchyAlpha).coerceIn(0f, 1f)
            holder.updateDrawable(combinedAlpha, blurRadiusPx, cornerRadiusPx)
        }

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            syncBlur()
            true
        }

        var registeredObserver: ViewTreeObserver? = null

        val registerListener = {
            val observer = view.viewTreeObserver
            if (observer.isAlive) {
                if (registeredObserver != observer) {
                    registeredObserver?.let {
                        if (it.isAlive) it.removeOnPreDrawListener(preDrawListener)
                    }
                    observer.addOnPreDrawListener(preDrawListener)
                    registeredObserver = observer
                }
            }
            syncBlur()
        }

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                registerListener()
            }

            override fun onViewDetachedFromWindow(v: View) {
                registeredObserver?.let {
                    if (it.isAlive) it.removeOnPreDrawListener(preDrawListener)
                }
                registeredObserver = null
                holder.cleanup()
            }
        }

        view.addOnAttachStateChangeListener(attachListener)
        if (view.isAttachedToWindow) {
            registerListener()
        } else {
            syncBlur()
        }

        onDispose {
            view.removeOnAttachStateChangeListener(attachListener)
            registeredObserver?.let {
                if (it.isAlive) it.removeOnPreDrawListener(preDrawListener)
            }
            if (!view.isAttachedToWindow) {
                val currentObserver = view.viewTreeObserver
                if (currentObserver.isAlive) {
                    currentObserver.removeOnPreDrawListener(preDrawListener)
                }
            }
            holder.cleanup()
            view.invalidate()
        }
    }

    val blurModifier = Modifier.drawBehind {
        val hierarchyAlpha = calculateEffectiveAlpha(view)
        val combinedAlpha = (alphaProgress.coerceIn(0f, 1f) * hierarchyAlpha).coerceIn(0f, 1f)
        val isActive = holder.updateDrawable(combinedAlpha, blurRadiusPx, cornerRadiusPx)
        if (isActive) {
            holder.blurDrawable?.let { drawable ->
                val w = size.width.toInt()
                val h = size.height.toInt()
                if (w > 0 && h > 0) {
                    drawable.setBounds(0, 0, w, h)
                    drawIntoCanvas { canvas ->
                        drawable.draw(canvas.nativeCanvas)
                    }
                }
            }
        }
    }

    return this
        .then(blurModifier)
        .background(color = resolvedTintColor, shape = RoundedCornerShape(cornerRadius))
}

fun View.applyTranslucentBlurBackground(
    cornerRadiusPx: Float,
    isBlurSupported: Boolean,
    fallbackBackgroundColor: Int? = null,
    blurRadiusPx: Float = context.resources.getDimensionPixelSize(R.dimen.volume_dialog_background_surface_blur_radius).toFloat(),
    tintColor: Int = (context.getColor(R.color.volume_dialog_view_background_blur) and 0x00FFFFFF) or ((255 * 0.58f).toInt() shl 24),
) {
    if (!isBlurSupported) {
        if (fallbackBackgroundColor != null) {
            setBackgroundResource(R.drawable.media_output_dialog_background)
            background?.setTint(fallbackBackgroundColor)
        }
        return
    }

    val applyBlur: () -> Unit = {
        val rootImpl = viewRootImpl
        if (rootImpl != null) {
            val blurDrawable = rootImpl.createBackgroundBlurDrawable()
            blurDrawable.setCornerRadius(cornerRadiusPx)
            blurDrawable.setBlurRadius(blurRadiusPx.toInt())

            val tintDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(cornerRadiusPx)
                setColor(tintColor)
            }

            background = LayerDrawable(arrayOf(blurDrawable, tintDrawable))
        }
    }

    if (viewRootImpl != null) {
        applyBlur()
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                removeOnAttachStateChangeListener(this)
                applyBlur()
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        post { applyBlur() }
    }
}

private fun calculateEffectiveAlpha(view: View?): Float {
    var alpha = 1f
    var v: View? = view
    while (v != null && alpha > 0f) {
        if (v.visibility != View.VISIBLE) return 0f
        val parentAlpha = v.alpha * v.transitionAlpha
        if (parentAlpha <= 0f) return 0f
        alpha *= parentAlpha
        v = v.parent as? View
    }
    return alpha.coerceIn(0f, 1f)
}
