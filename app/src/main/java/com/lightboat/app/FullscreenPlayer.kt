package com.lightboat.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Hosts WebView's custom video/fullscreen view without replacing the browsing page. */
class FullscreenPlayer(
    private val activity: Activity,
    private val pageRoot: View,
    private val onChanged: () -> Unit
) {
    private var overlay: FrameLayout? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previouslyKeptAwake = false
    val isActive get() = overlay != null

    @SuppressLint("SourceLockedOrientationActivity")
    fun show(view: View, hidden: WebChromeClient.CustomViewCallback) {
        if (isActive || view.parent != null) { hidden.onCustomViewHidden(); return }
        previousOrientation = activity.requestedOrientation
        previouslyKeptAwake = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        callback = hidden
        val host = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        overlay = host
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        val size = (48 * activity.resources.displayMetrics.density).toInt()
        val close = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundColor(0x66000000)
            contentDescription = activity.getString(R.string.exit_player_fullscreen)
            setOnClickListener { hide() }
        }
        host.addView(close, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END))
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bounds = close.layoutParams as FrameLayout.LayoutParams
            if (bounds.topMargin != cutout.top || bounds.rightMargin != cutout.right) {
                bounds.topMargin = cutout.top
                bounds.rightMargin = cutout.right
                close.layoutParams = bounds
            }
            insets
        }
        activity.findViewById<FrameLayout>(android.R.id.content).addView(host, ViewGroup.LayoutParams(-1, -1))
        pageRoot.visibility = View.INVISIBLE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ViewCompat.requestApplyInsets(host)
        onChanged()
    }

    fun hide() {
        val host = overlay ?: return
        val hidden = callback
        overlay = null
        callback = null
        (host.parent as? ViewGroup)?.removeView(host)
        host.removeAllViews()
        pageRoot.visibility = View.VISIBLE
        activity.requestedOrientation = previousOrientation
        if (!previouslyKeptAwake) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hidden?.onCustomViewHidden()
        onChanged()
    }
}
