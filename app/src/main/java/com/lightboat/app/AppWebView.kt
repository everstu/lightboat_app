package com.lightboat.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView

class AppWebView(context: Context) : WebView(context) {
    private var clipboardGestureAt = -1L
    init {
        setOnLongClickListener { true }
        isLongClickable = false
        isHapticFeedbackEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = null
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = null

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) clipboardGestureAt = SystemClock.elapsedRealtime()
        if (action in setOf(AccessibilityNodeInfo.ACTION_CUT,
                AccessibilityNodeInfo.ACTION_PASTE, AccessibilityNodeInfo.ACTION_SET_SELECTION)) return false
        return super.performAccessibilityAction(action, arguments)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) clipboardGestureAt = SystemClock.elapsedRealtime()
        val clipboardShortcut = event.isCtrlPressed && event.keyCode in setOf(
            KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_A
        )
        val legacyShortcut = event.isShiftPressed && event.keyCode in setOf(
            KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL
        )
        return if (clipboardShortcut || legacyShortcut) true else super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) clipboardGestureAt = SystemClock.elapsedRealtime()
        return super.onTouchEvent(event)
    }

    fun consumeClipboardGesture(): Boolean {
        val allowed = clipboardGestureAt >= 0 && SystemClock.elapsedRealtime() - clipboardGestureAt <= 5000
        clipboardGestureAt = -1L
        return allowed
    }

    fun clearClipboardGesture() { clipboardGestureAt = -1L }
    fun markClipboardGesture() { clipboardGestureAt = SystemClock.elapsedRealtime() }
}
