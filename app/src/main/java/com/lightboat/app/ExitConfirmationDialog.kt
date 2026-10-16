package com.lightboat.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.net.toUri
import com.lightboat.app.databinding.DialogExitConfirmationBinding

internal class ExitConfirmationDialog(
    activity: AppCompatActivity,
    private val nextProfile: Profile?,
    private val browsing: Boolean,
    private val exit: () -> Unit,
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogExitConfirmationBinding.inflate(activity.layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        ViewCompat.setAccessibilityHeading(binding.exitTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, context.getString(R.string.exit_title))
        binding.exitMessage.setText(if (browsing) R.string.exit_message else R.string.exit_message_settings)
        binding.nextProfilePanel.visibility = if (nextProfile == null) View.GONE else View.VISIBLE
        nextProfile?.let { profile ->
            binding.nextProfileName.text = profile.name
            binding.nextProfileHost.text = profile.url.toUri().authority ?: profile.url
        }
        binding.cancelExit.setText(if (browsing) R.string.stay else R.string.stay_in_app)
        binding.cancelExit.setOnClickListener { dismiss() }
        binding.confirmExit.setOnClickListener { dismiss(); exit() }
    }

    override fun onStart() {
        super.onStart()
        updateLayout()
    }

    override fun updateLayout() {
        val density = context.resources.displayMetrics.density
        val width = minOf(context.resources.displayMetrics.widthPixels - (48 * density).toInt(), (420 * density).toInt())
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
