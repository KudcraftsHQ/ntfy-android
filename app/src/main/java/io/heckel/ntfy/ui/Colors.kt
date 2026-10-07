package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import io.heckel.ntfy.R
import io.heckel.ntfy.util.isDarkThemeOn

class Colors {
    companion object {
        /** kudcrafts: the fdroid flavor wears the web app's flat, ink-on-canvas look (see src/fdroid/res/values/kc_theme.xml) */
        private val KUDCRAFTS_THEME = io.heckel.ntfy.BuildConfig.FLAVOR == "fdroid"

        fun primary(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }

        fun onPrimary(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorOnPrimary, Color.GREEN)
        }

        fun notificationIcon(context: Context): Int {
            // kudcrafts: fixed mascot teal. Notifications are built from a Service context with no
            // Material theme, so resolving colorPrimary fell back to Color.GREEN (the lime badge).
            return KC_BRAND_TEAL
        }

        private val KC_BRAND_TEAL = 0xFF299483.toInt()

        fun linkColor(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }

        fun itemSelectedBackground(context: Context): Int {
            return ContextCompat.getColor(context, R.color.md_theme_surfaceContainerHigh)
        }

        fun cardBackgroundColor(context: Context): Int {
            if (KUDCRAFTS_THEME) return MaterialColors.getColor(context, R.attr.colorSurface, Color.WHITE) // Flat rows
            return if (isDarkThemeOn(context)) {
                MaterialColors.getColor(context, R.attr.colorSurfaceContainer, Color.GRAY)
            } else {
                MaterialColors.getColor(context, R.attr.colorSurface, Color.WHITE)
            }
        }

        fun cardSelectedBackgroundColor(context: Context): Int {
            if (KUDCRAFTS_THEME) return MaterialColors.getColor(context, R.attr.colorSurfaceContainerHigh, Color.GRAY)
            return if (isDarkThemeOn(context)) {
                MaterialColors.getColor(context, R.attr.colorSurfaceContainerHigh, Color.GRAY)
            } else {
                MaterialColors.getColor(context, R.attr.colorSurfaceContainerHighest, Color.GRAY)
            }
        }

        fun statusBarNormal(context: Context, dynamicColors: Boolean, darkMode: Boolean): Int {
            val default = context.resources.getColor(R.color.action_bar, null)
            return if (dynamicColors) {
                // Use colorSurface for both light and dark mode when dynamic colors are enabled
                MaterialColors.getColor(context, R.attr.colorSurface, default)
            } else {
                default
            }
        }

        fun shouldUseLightStatusBar(dynamicColors: Boolean, darkMode: Boolean): Boolean {
            if (KUDCRAFTS_THEME) return !darkMode // Light canvas toolbar -> dark status bar icons
            // Use light status bar (dark icons) when dynamic colors are enabled in light mode
            return dynamicColors && !darkMode
        }

        fun toolbarTextColor(context: Context, dynamicColors: Boolean, darkMode: Boolean): Int {
            if (KUDCRAFTS_THEME) return MaterialColors.getColor(context, R.attr.colorOnSurface, Color.BLACK) // Ink on canvas
            return if (dynamicColors) {
                // Use colorOnSurface (dark on light, light on dark) when dynamic colors are enabled
                MaterialColors.getColor(context, R.attr.colorOnSurface, Color.BLACK)
            } else {
                if (darkMode) {
                    // In dark mode, toolbar is gray (surfaceContainer), so use light text
                    MaterialColors.getColor(context, R.attr.colorOnSurface, Color.WHITE)
                } else {
                    // In light mode, toolbar is teal (primary), so use white text
                    MaterialColors.getColor(context, R.attr.colorOnPrimary, Color.WHITE)
                }
            }
        }

        fun dangerText(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorError, Color.RED)
        }

        fun swipeToRefreshColor(context: Context): Int {
            return MaterialColors.getColor(context, R.attr.colorPrimary, Color.GREEN)
        }
    }
}
