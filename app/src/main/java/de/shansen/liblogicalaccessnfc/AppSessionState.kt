package de.shansen.liblogicalaccessnfc

import androidx.lifecycle.ViewModel
import de.shansen.rfcard.DesfireKey
import de.shansen.rfidgearruntime.DesfireQuickCheckConfig

class AppSessionState : ViewModel() {
    var loaded = false
    var config = DesfireQuickCheckConfig()
    var piccKey: DesfireKey? = null
    var piccLabel: String? = null
    var pendingPdfLines: List<String>? = null
    override fun onCleared() {
        config.applicationKeys.values.flatten().forEach { it.key.clear() }
        config.defaultApplicationKeys.forEach { it.key.clear() }
        config.piccKeys.forEach { it.key.clear() }
        piccKey?.clear()
    }
}
