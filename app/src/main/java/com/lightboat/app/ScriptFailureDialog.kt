package com.lightboat.app

import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import com.lightboat.app.databinding.DialogScriptFailureBinding

internal class ScriptFailureDialog(
    activity: AppCompatActivity,
    private val runtime: Boolean,
    private val proceed: () -> Unit,
    private val retry: () -> Unit,
    private val cancelVisit: () -> Unit,
) : AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogScriptFailureBinding.inflate(activity.layoutInflater)
    private val failures = linkedMapOf<String, Pair<String, String>>()

    fun addFailure(script: SharedScript, message: String) {
        failures[script.id] = script.name to message.take(1000)
        binding.scriptFailureDetails.text = failures.values.joinToString("\n\n") { (name, error) -> "$name\n$error" }
        if (isShowing) binding.root.post { if (isShowing) updateLayout() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.scriptFailureTitle, true)
        binding.scriptFailureHelp.setText(if (runtime) R.string.script_failure_runtime_help else R.string.script_failure_help)
        binding.continueWithoutScripts.setOnClickListener { dismiss(); proceed() }
        binding.retryFailedScripts.setOnClickListener { dismiss(); retry() }
        binding.cancelScriptVisit.setOnClickListener { dismiss(); cancelVisit() }
        setCanceledOnTouchOutside(false)
        setOnCancelListener { cancelVisit() }
    }

    override fun onStart() { super.onStart(); updateLayout() }
    override fun updateLayout() {
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.86f).toInt()
        DialogLayout.apply(window, binding.root, DialogLayout.width(context, maxWidthDp = 560, marginDp = 32), maxHeight)
    }
}
