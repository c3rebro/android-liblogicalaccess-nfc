package de.shansen.liblogicalaccessnfc

import androidx.lifecycle.ViewModel
import de.shansen.rfcard.DesfireKey
import de.shansen.rfidgearruntime.DesfireQuickCheckConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActiveScanUseCase {
    QUICK_CHECK, RESTORE_TRANSPORT_CONFIG, FORMAT, FACTORY_RESET
}

enum class RunMode { MANUAL_ONE_SHOT, AUTO_REPEAT }

sealed interface ArmState {
    data object Disarmed : ArmState
    data object Armed : ArmState
    data object Running : ArmState
    data object WaitingForRemoval : ArmState
    data object PausedAfterBackground : ArmState
}

data class MainUiState(
    val selectedAction: ActiveScanUseCase = ActiveScanUseCase.QUICK_CHECK,
    val autorunEnabled: Boolean = false,
    val armState: ArmState = ArmState.Disarmed,
    val nfcStatus: String = "",
)

class AppSessionState : ViewModel() {
    var loaded = false
    var config = DesfireQuickCheckConfig()
    var piccKey: DesfireKey? = null
    var piccLabel: String? = null
    var pendingPdfLines: List<String>? = null
    /** Session-only: destructive autorun confirmed for current arm session. Reset in onPause/disarm. */
    var destructiveAutorunConfirmed: Boolean = false
    /** When true, the next completed scan exits to Disarmed (one-shot override for auto mode). */
    var rescanOncePending: Boolean = false

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun updateUiState(transform: (MainUiState) -> MainUiState) {
        _uiState.value = transform(_uiState.value)
    }

    override fun onCleared() {
        config.applicationKeys.values.flatten().forEach { it.key.clear() }
        config.defaultApplicationKeys.forEach { it.key.clear() }
        config.piccKeys.forEach { it.key.clear() }
        piccKey?.clear()
    }
}
