package com.lightboat.app

import android.content.Context
import android.webkit.WebSettings

object MobileUserAgent {
    fun default(context: Context): String = WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace(" Version/4.0", "")
}
