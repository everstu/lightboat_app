package com.lightboat.app

// MainActivity handles orientation changes without recreation, so its visible dialogs resize explicitly.
internal interface ResponsiveDialog {
    fun updateLayout()
}
