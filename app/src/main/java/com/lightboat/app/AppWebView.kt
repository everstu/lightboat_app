package com.lightboat.app

import android.content.Context
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView

class AppWebView(context: Context) : WebView(context) {
    init {
        setOnLongClickListener { true }
        isLongClickable = false
        isHapticFeedbackEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = null
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = null

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action in setOf(AccessibilityNodeInfo.ACTION_COPY, AccessibilityNodeInfo.ACTION_CUT,
                AccessibilityNodeInfo.ACTION_PASTE, AccessibilityNodeInfo.ACTION_SET_SELECTION)) return false
        return super.performAccessibilityAction(action, arguments)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val clipboardShortcut = event.isCtrlPressed && event.keyCode in setOf(
            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_INSERT
        )
        val legacyShortcut = event.isShiftPressed && event.keyCode in setOf(
            KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL
        )
        return if (clipboardShortcut || legacyShortcut) true else super.dispatchKeyEvent(event)
    }
}
