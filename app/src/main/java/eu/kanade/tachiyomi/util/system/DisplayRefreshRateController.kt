package eu.kanade.tachiyomi.util.system

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window
import android.view.WindowManager
import karasu.domain.base.BasePreferences
import kotlin.math.abs

class DisplayRefreshRateController(private val window: Window) {

    private val handler = Handler(Looper.getMainLooper())
    private var preference = BasePreferences.DisplayRefreshRate.SYSTEM
    private var isClosed = false

    private val applyIdleRate = Runnable {
        if (!isClosed && preference == BasePreferences.DisplayRefreshRate.ADAPTIVE) {
            applyMode(selectIdleMode())
        }
    }

    fun onResume(value: BasePreferences.DisplayRefreshRate) {
        isClosed = false
        onPreferenceChanged(value)
    }

    fun onPreferenceChanged(value: BasePreferences.DisplayRefreshRate) {
        preference = value
        handler.removeCallbacks(applyIdleRate)
        when (value) {
            BasePreferences.DisplayRefreshRate.SYSTEM -> applyMode(null)
            BasePreferences.DisplayRefreshRate.ADAPTIVE -> applyMode(selectIdleMode())
            BasePreferences.DisplayRefreshRate.LOCK_90 -> applyMode(selectMode(90f))
            BasePreferences.DisplayRefreshRate.LOCK_120 -> applyMode(selectMode(120f))
        }
    }

    fun onInteraction() {
        if (isClosed || preference != BasePreferences.DisplayRefreshRate.ADAPTIVE) return
        handler.removeCallbacks(applyIdleRate)
        applyMode(supportedModes().maxByOrNull(Display.Mode::getRefreshRate))
        handler.postDelayed(applyIdleRate, IDLE_DELAY_MS)
    }

    fun onPause() {
        isClosed = true
        handler.removeCallbacks(applyIdleRate)
        applyMode(null)
    }

    fun close() {
        isClosed = true
        handler.removeCallbacks(applyIdleRate)
        applyMode(null)
    }

    private fun selectIdleMode(): Display.Mode? {
        val modes = supportedModes()
        val highestRate = modes.maxOfOrNull(Display.Mode::getRefreshRate) ?: return null
        val targetRate = if (highestRate >= 119f) 90f else 60f
        return modes
            .filter { it.refreshRate < highestRate - 1f }
            .minByOrNull { abs(it.refreshRate - targetRate) }
            ?: modes.maxByOrNull(Display.Mode::getRefreshRate)
    }

    private fun selectMode(targetRate: Float): Display.Mode? =
        supportedModes().minByOrNull { abs(it.refreshRate - targetRate) }
            ?.takeIf { abs(it.refreshRate - targetRate) <= RATE_TOLERANCE }

    private fun supportedModes(): List<Display.Mode> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val display = window.decorView.display
            ?: (window.context.getSystemService(WindowManager::class.java)?.defaultDisplay)
            ?: return emptyList()
        val currentMode = display.mode
        return display.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
    }

    private fun applyMode(mode: Display.Mode?) {
        val attributes = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val modeId = mode?.modeId ?: 0
            if (attributes.preferredDisplayModeId == modeId) return
            attributes.preferredDisplayModeId = modeId
        } else {
            val refreshRate = mode?.refreshRate ?: 0f
            if (attributes.preferredRefreshRate == refreshRate) return
            attributes.preferredRefreshRate = refreshRate
        }
        window.attributes = attributes
    }

    private companion object {
        const val IDLE_DELAY_MS = 1_500L
        const val RATE_TOLERANCE = 0.5f
    }
}
