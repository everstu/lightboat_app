package com.lightboat.app

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import com.lightboat.app.databinding.DialogScriptBatchBinding

internal class ScriptBatchDialog(activity: AppCompatActivity, private val add: (String) -> Unit) :
    AppCompatDialog(activity, R.style.Theme_Lightboat_ActionDialog), ResponsiveDialog {
    private val binding = DialogScriptBatchBinding.inflate(activity.layoutInflater)
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener { updateLayout() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setAccessibilityHeading(binding.batchTitle, true)
        binding.cancelBatchScripts.setOnClickListener { dismiss() }
        binding.confirmBatchScripts.setOnClickListener {
            try { add(binding.batchScriptsInput.text.toString()); dismiss() }
            catch (error: IllegalArgumentException) {
                binding.batchScriptsInput.error = error.message
                binding.batchScriptsInput.requestFocus()
            }
        }
    }

    override fun onStart() { super.onStart(); binding.root.viewTreeObserver.addOnGlobalLayoutListener(layoutChanged); updateLayout() }
    override fun onStop() { binding.root.viewTreeObserver.removeOnGlobalLayoutListener(layoutChanged); super.onStop() }
    override fun updateLayout() {
        val density = context.resources.displayMetrics.density
        val visible = Rect().also { window?.decorView?.getWindowVisibleDisplayFrame(it) }
        val width = minOf((600 * density).toInt(), context.resources.displayMetrics.widthPixels - (24 * density).toInt())
        val maximumHeight = minOf((520 * density).toInt(), visible.height() - (16 * density).toInt()).coerceAtLeast(1)
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST))
        val height = binding.root.measuredHeight
        window?.let { if (it.attributes.width != width || it.attributes.height != height) it.setLayout(width, height) }
    }
}
