package com.lightboat.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import com.lightboat.app.databinding.DialogProfileNoteBinding

/**
 * Production-styled, read-only note viewer. The body remains plain text:
 * TextView handles long-press selection and "copy all" uses the system
 * clipboard without dismissing the dialog.
 */
internal class ProfileNoteDialog(
    activity: AppCompatActivity,
    private val profileName: String,
    private val note: String,
    private val copyAll: (String) -> Unit,
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogProfileNoteBinding.inflate(activity.layoutInflater)
    private val hasNote = note.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        ViewCompat.setAccessibilityHeading(binding.noteTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, context.getString(R.string.profile_note_title))
        binding.noteProfile.text = context.getString(R.string.profile_note_dialog_profile, profileName)
        binding.closeNote.setOnClickListener { dismiss() }
        if (hasNote) {
            binding.noteText.text = note
            binding.noteText.setTextIsSelectable(false)
            binding.noteText.setTextIsSelectable(true)
            binding.copyNote.setOnClickListener { copyAll(note) }
        } else {
            binding.noteText.setText(R.string.profile_note_empty)
            binding.noteText.setTextColor(context.getColor(R.color.muted))
            binding.noteText.setTextIsSelectable(false)
            binding.noteText.isLongClickable = false
            binding.noteText.isFocusable = false
            binding.noteText.isFocusableInTouchMode = false
            binding.copyNote.visibility = View.GONE
            // The only action should read as the dialog's primary action.
            binding.closeNote.setBackgroundResource(R.drawable.bg_config_primary)
            binding.closeNote.setTextColor(context.getColor(R.color.white))
        }
    }

    override fun onStart() {
        super.onStart()
        updateLayout()
        binding.root.post {
            if (isShowing) {
                enableSelectionForAttachedWindow()
                updateLayout()
            }
        }
    }

    /**
     * The platform only enables text-selection controllers when the root view
     * belongs to a window that supports handles. Doing this once after the
     * dialog is attached avoids a "does not support text selection" state.
     */
    private fun enableSelectionForAttachedWindow() {
        if (!hasNote) return
        binding.noteText.setTextIsSelectable(false)
        binding.noteText.setTextIsSelectable(true)
    }

    override fun updateLayout() {
        val compact = context.resources.configuration.screenHeightDp < 480
        binding.noteProfile.visibility = if (compact) View.GONE else View.VISIBLE
        val width = DialogLayout.width(context, maxWidthDp = 560, marginDp = 32)
        val metrics = context.resources.displayMetrics
        val windowVisible = DialogLayout.visibleHeight(window)
        // On a configuration change the dialog window can keep reporting its
        // previous frame for one pass; never exceed the current screen height.
        val visibleHeight = windowVisible.takeIf { it >= DialogLayout.dp(context, 160) && it <= metrics.heightPixels }
            ?: metrics.heightPixels
        val maximumDialog = (visibleHeight - DialogLayout.dp(context, 24))
            .coerceAtLeast(DialogLayout.dp(context, 160))

        // Measure header/footer without constraining the dialog height, so the
        // title and action buttons always get their full required height. The
        // body only receives what remains; tight layouts first reduce spacing.
        applySpacing(compact, tight = false)
        var chrome = measureChrome(width)
        var bodyHeight = maximumDialog - chrome - DialogLayout.dp(context, 4)
        if (bodyHeight < DialogLayout.dp(context, 56)) {
            applySpacing(compact, tight = true)
            chrome = measureChrome(width)
            bodyHeight = maximumDialog - chrome - DialogLayout.dp(context, 4)
        }
        binding.noteScroll.maximumHeight = bodyHeight.coerceAtLeast(1)
        DialogLayout.apply(window, binding.root, width, maximumDialog)
    }

    private fun measureChrome(width: Int): Int {
        binding.noteScroll.maximumHeight = 1
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return (binding.root.measuredHeight - binding.noteScroll.measuredHeight).coerceAtLeast(0)
    }

    private fun applySpacing(compact: Boolean, tight: Boolean) {
        val horizontal = DialogLayout.dp(context, if (tight) 18 else if (compact) 20 else 24)
        val vertical = DialogLayout.dp(context, if (tight) 8 else if (compact) 12 else 24)
        binding.root.setPadding(horizontal, vertical, horizontal, vertical)

        (binding.noteCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = DialogLayout.dp(context, if (tight) 8 else if (compact) 10 else 18)
            binding.noteCard.layoutParams = it
        }
        val cardPadding = DialogLayout.dp(context, if (tight) 8 else if (compact) 10 else 16)
        binding.noteCard.setPadding(cardPadding, cardPadding, cardPadding, cardPadding)

        (binding.noteActions.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = DialogLayout.dp(context, if (tight) 8 else if (compact) 12 else 24)
            binding.noteActions.layoutParams = it
        }
        val buttonHeight = DialogLayout.dp(context, if (tight) 48 else 52)
        binding.closeNote.minimumHeight = buttonHeight
        binding.copyNote.minimumHeight = buttonHeight
    }
}
