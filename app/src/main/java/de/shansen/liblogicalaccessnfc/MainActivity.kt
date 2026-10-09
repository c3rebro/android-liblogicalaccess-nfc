package de.shansen.liblogicalaccessnfc

import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import de.shansen.rfcard.MifareIdentification
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shansen.liblogicalaccessnfc.databinding.ActivityMainBinding
import de.shansen.liblogicalaccessnfc.databinding.DialogDesfireQuickCheckKeyBinding
import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireChangePiccMasterKey
import de.shansen.rfcard.DesfireFactoryDefaults
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfidgearruntime.DesfireQuickCheckConfig
import de.shansen.rfidgearruntime.DesfireQuickCheckKey
import de.shansen.rfidgearruntime.DesfireQuickCheckKeyFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocumentFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReportEnvironment
import de.shansen.rfidgearruntime.DesfireQuickCheckService
import de.shansen.rfidgearruntime.DesfireQuickCheckTextRenderer
import de.shansen.rfusecase.DesfireFactoryResetAuthorization
import de.shansen.rfusecase.DesfireFactoryResetUseCase
import de.shansen.rfusecase.DesfireFormatAuthorization
import de.shansen.rfusecase.DesfireFormatUseCase
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null

    private val quickCheckService = DesfireQuickCheckService()
    private val formatUseCase = DesfireFormatUseCase()
    private val factoryResetUseCase = DesfireFactoryResetUseCase()

    private data class DefaultPiccCandidate(val label: String, val create: () -> DesfireKey)
    private val defaultPiccCandidates = listOf(
        DefaultPiccCandidate("DES zeros (factory)") { DesfireFactoryDefaults.piccMasterKey() },
        DefaultPiccCandidate("AES zeros (non-factory key type)") { DesfireKey(ByteArray(16), DesfireKeyType.AES, 0, 0) }
    )

    internal val sessionState by lazy { ViewModelProvider(this)[AppSessionState::class.java] }
    private val preferences by lazy { getSharedPreferences("app-state", MODE_PRIVATE) }
    private val savedKeys by lazy { SavedKeyStore(this) }

    var quickCheckConfig: DesfireQuickCheckConfig
        get() = sessionState.config
        private set(value) { sessionState.config = value }
    var piccMasterKeyLabel: String?
        get() = sessionState.piccLabel
        private set(value) { sessionState.piccLabel = value }
    var piccMasterKey: DesfireKey?
        get() = sessionState.piccKey
        private set(value) { sessionState.piccKey = value }

    private var activeScanUseCase: ActiveScanUseCase
        get() = sessionState.uiState.value.selectedAction
        set(value) {
            sessionState.updateUiState { it.copy(selectedAction = value) }
            preferences.edit().putString("active-action", value.name).apply()
        }

    var autorunEnabled: Boolean
        get() = sessionState.uiState.value.autorunEnabled
        set(value) {
            sessionState.updateUiState { it.copy(autorunEnabled = value) }
            preferences.edit().putBoolean("autorun-enabled", value).apply()
        }

    val runMode: RunMode get() = if (autorunEnabled) RunMode.AUTO_REPEAT else RunMode.MANUAL_ONE_SHOT

    private var armState: ArmState
        get() = sessionState.uiState.value.armState
        set(value) { sessionState.updateUiState { it.copy(armState = value) } }

    var soundEnabled: Boolean
        get() = preferences.getBoolean("sound-enabled", false)
        set(value) { preferences.edit().putBoolean("sound-enabled", value).apply() }

    private lateinit var actionsFragment: ActionsFragment
    private lateinit var resultsFragment: ResultsFragment
    private lateinit var settingsFragment: SettingsFragment

    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actionsFragment.loadProjectFromUri(uri)
    }

    private val createLogZip = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val result = runCatching {
                val files = AppLogger.logFiles()
                check(files.isNotEmpty()) { "No log files found." }
                contentResolver.openOutputStream(uri)?.use { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        files.forEach { file ->
                            zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } ?: error("Unable to open output stream.")
            }
            runOnUiThread {
                result.onFailure { e ->
                    android.widget.Toast.makeText(this, "Log export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val createQuickCheckPdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val lines = sessionState.pendingPdfLines ?: return@registerForActivityResult
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    DesfireQuickCheckPdfRenderer().writeLines(lines, output)
                } ?: error("Unable to open the selected PDF destination.")
            }
            runOnUiThread {
                result.onSuccess {
                    setNfcStatus("Quick Check PDF exported.")
                }.onFailure { error ->
                    setNfcStatus("PDF export failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.log("APP", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) commit=${BuildConfig.GIT_COMMIT} bridge=${NativeBridge.version()}")

        val savedAction = runCatching {
            ActiveScanUseCase.valueOf(preferences.getString("active-action", "QUICK_CHECK")!!)
        }.getOrDefault(ActiveScanUseCase.QUICK_CHECK)
        val savedAutorun = preferences.getBoolean("autorun-enabled", false)

        if (!sessionState.loaded) {
            runCatching { savedKeys.load() }.onSuccess { entries ->
                entries.forEach { entry ->
                    if (entry.aid == null) {
                        piccMasterKey = entry.key; piccMasterKeyLabel = entry.label
                    } else quickCheckConfig = quickCheckConfig.withApplicationKey(entry.aid,
                        DesfireQuickCheckKey(entry.label, entry.key))
                }
                sessionState.loaded = true
            }.onFailure {
                android.widget.Toast.makeText(this, "Saved keys could not be unlocked. Re-enter them in Settings.", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // Seed StateFlow before fragments are committed so onViewCreated sees correct state.
        val nfcStatus = when {
            NfcAdapter.getDefaultAdapter(this) == null -> "This device has no NFC adapter."
            NfcAdapter.getDefaultAdapter(this)?.isEnabled != true -> "NFC is disabled."
            else -> "Ready. Hold an NFC card near the phone to identify it."
        }
        sessionState.updateUiState { it.copy(selectedAction = savedAction, autorunEnabled = savedAutorun, nfcStatus = nfcStatus) }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.fragmentContainer.updatePadding(top = bars.top)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            actionsFragment = ActionsFragment()
            resultsFragment = ResultsFragment()
            settingsFragment = SettingsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, actionsFragment, TAG_ACTIONS)
                .add(R.id.fragmentContainer, resultsFragment, TAG_RESULTS)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .hide(resultsFragment)
                .hide(settingsFragment)
                .commitNow()
        } else {
            actionsFragment = supportFragmentManager.findFragmentByTag(TAG_ACTIONS) as ActionsFragment
            resultsFragment = supportFragmentManager.findFragmentByTag(TAG_RESULTS) as ResultsFragment
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as SettingsFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showFragment(item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = preferences.getInt("selected-menu", R.id.nav_actions)

        // Observe UI state to drive the autorun banner
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                sessionState.uiState.collect { state ->
                    updateAutorunBannerFromState(state)
                }
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        // NFC reader mode is NOT automatically re-enabled here.
        // The user must tap the arm button on the Actions screen to start scanning.
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        when (armState) {
            is ArmState.Armed, is ArmState.WaitingForRemoval, is ArmState.Running -> {
                armState = ArmState.PausedAfterBackground
                sessionState.destructiveAutorunConfirmed = false
            }
            else -> {}
        }
        super.onPause()
    }

    private fun showFragment(id: Int) {
        preferences.edit().putInt("selected-menu", id).apply()
        val tx = supportFragmentManager.beginTransaction()
        listOf(
            R.id.nav_actions to actionsFragment,
            R.id.nav_results to resultsFragment,
            R.id.nav_settings to settingsFragment
        ).forEach { (navId, fragment) ->
            if (navId == id) tx.show(fragment) else tx.hide(fragment)
        }
        tx.commit()
    }

    private fun updateAutorunBannerFromState(state: MainUiState) {
        val runMode = if (state.autorunEnabled) RunMode.AUTO_REPEAT else RunMode.MANUAL_ONE_SHOT
        val armState = state.armState
        val isAutoActive = runMode == RunMode.AUTO_REPEAT &&
            (armState is ArmState.Armed || armState is ArmState.WaitingForRemoval)
        val isPaused = armState is ArmState.PausedAfterBackground

        when {
            isPaused -> {
                val bg = MaterialColors.getColor(binding.autorunWarning, com.google.android.material.R.attr.colorSurfaceVariant)
                val fg = MaterialColors.getColor(binding.autorunWarning, com.google.android.material.R.attr.colorOnSurfaceVariant)
                binding.autorunWarning.setBackgroundColor(bg)
                binding.autorunWarning.setTextColor(fg)
                binding.autorunWarning.text = "Auto mode paused — open Actions to resume"
                binding.autorunWarning.visibility = View.VISIBLE
                binding.autorunWarning.setOnClickListener {
                    binding.bottomNav.selectedItemId = R.id.nav_actions
                }
            }
            isAutoActive -> {
                val isDestructive = isDestructiveAction(state.selectedAction)
                val isWrite = state.selectedAction == ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG
                val (bg, fg, prefix) = when {
                    isDestructive -> Triple(
                        MaterialColors.getColor(binding.autorunWarning, com.google.android.material.R.attr.colorError),
                        MaterialColors.getColor(binding.autorunWarning, com.google.android.material.R.attr.colorOnError),
                        "⚠ Auto"
                    )
                    isWrite -> Triple(
                        getColor(R.color.color_warning),
                        getColor(R.color.color_on_warning),
                        "⚠ Auto"
                    )
                    else -> Triple(
                        MaterialColors.getColor(binding.autorunWarning, com.google.android.material.R.attr.colorSecondaryContainer),
                        MaterialColors.getColor(binding.autorunWarning, com.google.android.material.R.attr.colorOnSecondaryContainer),
                        "Auto"
                    )
                }
                binding.autorunWarning.setBackgroundColor(bg)
                binding.autorunWarning.setTextColor(fg)
                binding.autorunWarning.text = "$prefix: ${actionDisplayLabel(state.selectedAction)}"
                binding.autorunWarning.visibility = View.VISIBLE
                binding.autorunWarning.setOnClickListener { disarm() }
            }
            else -> {
                binding.autorunWarning.visibility = View.GONE
                binding.autorunWarning.setOnClickListener(null)
            }
        }
    }

    // --- Arm / disarm ---

    /** Arms the NFC scanner. Shows a confirmation dialog first for destructive AUTO_REPEAT. */
    fun arm() {
        val readiness = checkAutorunReadiness()
        if (readiness != null) {
            android.widget.Toast.makeText(this, readiness, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (runMode == RunMode.AUTO_REPEAT && isDestructiveAction(activeScanUseCase) &&
            !sessionState.destructiveAutorunConfirmed
        ) {
            showDestructiveAutorunConfirmation(
                actionLabel = actionDisplayLabel(activeScanUseCase),
                onConfirmed = {
                    sessionState.destructiveAutorunConfirmed = true
                    performArm()
                },
                onDeclined = {}
            )
            return
        }
        performArm()
    }

    private fun performArm() {
        armState = ArmState.Armed
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        )
        AppLogger.log("ARM", "Armed; action=${activeScanUseCase.name} runMode=${runMode.name}")
    }

    fun disarm() {
        armState = ArmState.Disarmed
        sessionState.destructiveAutorunConfirmed = false
        nfcAdapter?.disableReaderMode(this)
        AppLogger.log("ARM", "Disarmed")
    }

    /** Arms a single-shot Quick Check regardless of current autorun setting. */
    fun rescanOnce() {
        if (activeScanUseCase != ActiveScanUseCase.QUICK_CHECK) {
            activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
        }
        sessionState.rescanOncePending = true
        when (armState) {
            is ArmState.Armed, is ArmState.WaitingForRemoval, is ArmState.Running -> {
                // Already in position; rescanOncePending flag ensures one-shot exit
            }
            else -> {
                armState = ArmState.Armed
                nfcAdapter?.enableReaderMode(
                    this, this,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
                )
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_actions
        AppLogger.log("ARM", "Rescan once armed; action=QUICK_CHECK")
    }

    // --- Methods called by ActionsFragment ---

    fun selectQuickCheckUseCase() {
        disarmIfActive()
        activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
        AppLogger.log("ACTION", "Selected: Quick Check")
        setNfcStatus("Quick Check selected.")
    }

    fun selectRestoreTransportUseCase() {
        disarmIfActive()
        activeScanUseCase = ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG
        AppLogger.log("ACTION", "Selected: Restore Transport Config")
        setNfcStatus("Restore Transport Config selected.")
    }

    fun selectFormatUseCase() {
        disarmIfActive()
        val wasAutorun = autorunEnabled
        if (wasAutorun) {
            autorunEnabled = false
            sessionState.destructiveAutorunConfirmed = false
        }
        activeScanUseCase = ActiveScanUseCase.FORMAT
        AppLogger.log("ACTION", "Selected: Format${if (wasAutorun) "; auto-repeat disabled (destructive)" else ""}")
        setNfcStatus(if (wasAutorun)
            "Format selected. Auto-repeat was disabled — re-enable in Settings for repeat mode."
        else
            "Format selected. Tap 'Wait for card' — a confirmation dialog will appear.")
    }

    fun selectFactoryResetUseCase() {
        disarmIfActive()
        val wasAutorun = autorunEnabled
        if (wasAutorun) {
            autorunEnabled = false
            sessionState.destructiveAutorunConfirmed = false
        }
        activeScanUseCase = ActiveScanUseCase.FACTORY_RESET
        AppLogger.log("ACTION", "Selected: Factory Reset${if (wasAutorun) "; auto-repeat disabled (destructive)" else ""}")
        setNfcStatus(if (wasAutorun)
            "Factory Reset selected. Auto-repeat was disabled — re-enable in Settings for repeat mode."
        else
            "Factory Reset selected. Tap 'Wait for card' — a confirmation dialog will appear.")
    }

    private fun disarmIfActive() {
        when (armState) {
            is ArmState.Armed, is ArmState.WaitingForRemoval, is ArmState.PausedAfterBackground -> disarm()
            else -> {}
        }
    }

    fun applyAutorun(enabled: Boolean, onDeclined: (() -> Unit)? = null) {
        if (!enabled && (armState is ArmState.Armed || armState is ArmState.WaitingForRemoval)) {
            disarm()
        }
        autorunEnabled = enabled
        if (!enabled) sessionState.destructiveAutorunConfirmed = false
        AppLogger.log("SETTINGS", "Auto-repeat ${if (enabled) "enabled" else "disabled"} for action=${activeScanUseCase.name}")
    }

    internal fun isDestructiveAction(action: ActiveScanUseCase): Boolean =
        action == ActiveScanUseCase.FORMAT || action == ActiveScanUseCase.FACTORY_RESET

    private fun actionDisplayLabel(action: ActiveScanUseCase): String = when (action) {
        ActiveScanUseCase.QUICK_CHECK -> "Quick Check (read-only)"
        ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG -> "Restore Transport Config"
        ActiveScanUseCase.FORMAT -> "Format DESFire card"
        ActiveScanUseCase.FACTORY_RESET -> "Factory Reset DESFire card"
    }

    private fun showDestructiveAutorunConfirmation(actionLabel: String, onConfirmed: () -> Unit, onDeclined: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title_destructive_autorun)
            .setMessage(getString(R.string.msg_destructive_autorun_confirm, actionLabel))
            .setPositiveButton(R.string.btn_enable) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onDeclined() }
            .setOnCancelListener { onDeclined() }
            .show()
    }

    fun checkAutorunReadiness(): String? = when (activeScanUseCase) {
        ActiveScanUseCase.QUICK_CHECK -> null
        ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG ->
            if (piccMasterKey == null) "Restore Transport Config requires the current PICC master key. Set it in Settings → PICC Master Key." else null
        ActiveScanUseCase.FORMAT, ActiveScanUseCase.FACTORY_RESET -> null
    }

    private fun playSuccessSound() {
        if (!soundEnabled) return
        Thread {
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80).apply {
                    startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    Thread.sleep(300)
                    release()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun playFailureSound() {
        if (!soundEnabled) return
        Thread {
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80).apply {
                    startTone(ToneGenerator.TONE_PROP_NACK, 400)
                    Thread.sleep(500)
                    release()
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun launchOpenProject() {
        openProject.launch(arrayOf("*/*"))
    }

    // --- Methods called by SettingsFragment ---

    fun showAddQuickCheckKeyDialog(prefillAid: Int? = null) {
        val dialogBinding = DialogDesfireQuickCheckKeyBinding.inflate(layoutInflater)
        val keyTypes = listOf(DesfireKeyType.AES, DesfireKeyType.TDES_3K, DesfireKeyType.DES)
        val keyTypeLabels = keyTypes.map(::keyTypeLabel)

        dialogBinding.keyType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, keyTypeLabels)
        )
        dialogBinding.keyType.setText(keyTypeLabels[0], false)
        prefillAid?.let { dialogBinding.aid.setText("0x%06X".format(it)) }

        fun selectedKeyType() = keyTypes[keyTypeLabels.indexOf(dialogBinding.keyType.text.toString()).coerceAtLeast(0)]

        val hexFilter = InputFilter { source, _, _, _, _, _ ->
            val filtered = source.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (filtered.length == source.length) null else filtered.toString()
        }
        fun updateHexCounter() {
            val max = if (selectedKeyType() == DesfireKeyType.TDES_3K) 48 else 32
            val len = dialogBinding.keyHex.text?.length ?: 0
            dialogBinding.keyHex.filters = arrayOf(hexFilter, InputFilter.LengthFilter(max))
            dialogBinding.hexCounter.text = "$len / $max hex chars"
            dialogBinding.hexCounter.setTypeface(null,
                if (len == max) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        updateHexCounter()
        dialogBinding.keyHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateHexCounter() }
        })
        dialogBinding.keyType.setOnItemClickListener { _, _, _, _ -> updateHexCounter() }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title_app_key)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_add, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialogBinding.tilLabel.error = null
                dialogBinding.tilKeyHex.error = null
                dialogBinding.tilKeyNumber.error = null

                val result = runCatching {
                    val aid = DesfireQuickCheckKeyFactory.parseAid(dialogBinding.aid.text.toString())
                    val keyNo = dialogBinding.keyNumber.text.toString().trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Key number must be a decimal number between 0 and 15.")
                    val type = selectedKeyType()
                    val key = DesfireQuickCheckKeyFactory.fromHex(
                        label = dialogBinding.label.text.toString(),
                        keyHex = dialogBinding.keyHex.text.toString(),
                        type = type,
                        keyNumber = keyNo
                    )
                    aid to key
                }

                result.onSuccess { (aid, key) ->
                    if (dialogBinding.savePermanently.isChecked) {
                        val saved = runCatching { savedKeys.save(aid, key.label, key.key) }
                        if (saved.isFailure) {
                            dialogBinding.tilKeyHex.error = "Unable to save securely. Try adding a session key instead."
                            key.key.clear()
                            return@onSuccess
                        }
                    }
                    quickCheckConfig = quickCheckConfig.withApplicationKey(aid, key)
                    AppLogger.log("KEY", "App key added: '${key.label}' AID=0x%06X type=${key.key.type} #${key.key.number} permanent=${dialogBinding.savePermanently.isChecked}".format(aid))
                    settingsFragment.updateKeySummaries()
                    setNfcStatus("Key added for AID 0x%06X.".format(aid))
                    dialog.dismiss()
                }.onFailure { error ->
                    val msg = error.message ?: "Invalid DESFire key."
                    when {
                        msg.startsWith("Key label") -> dialogBinding.tilLabel.error = msg
                        msg.startsWith("DESFire key number") -> dialogBinding.tilKeyNumber.error = msg
                        else -> dialogBinding.tilKeyHex.error = msg
                    }
                }
            }
        }
        dialog.show()
    }

    fun clearSessionQuickCheckKeys() {
        val count = quickCheckConfig.applicationKeys.values.sumOf { it.size }
        savedKeys.removeApplicationKeys()
        quickCheckConfig.applicationKeys.values.flatten().forEach { it.key.clear() }
        quickCheckConfig.defaultApplicationKeys.forEach { it.key.clear() }
        quickCheckConfig.piccKeys.forEach { it.key.clear() }
        quickCheckConfig = DesfireQuickCheckConfig()
        AppLogger.log("KEY", "All application keys cleared (was $count keys)")
    }

    fun setPiccKey(label: String, key: DesfireKey, permanently: Boolean = false) {
        if (permanently) savedKeys.save(null, label, key) else savedKeys.removePiccKey()
        piccMasterKey?.clear()
        piccMasterKeyLabel = label
        piccMasterKey = key
        AppLogger.log("KEY", "PICC master key set: '$label' type=${key.type} permanent=$permanently")
    }

    fun clearPiccKey() {
        savedKeys.removePiccKey()
        piccMasterKey?.clear()
        piccMasterKey = null
        piccMasterKeyLabel = null
        AppLogger.log("KEY", "PICC master key cleared")
    }

    fun buildQuickCheckKeySummary(): String {
        if (quickCheckConfig.applicationKeys.isEmpty()) {
            return "No application-specific keys configured."
        }
        return buildString {
            appendLine("Application keys (session keys and securely saved keys):")
            quickCheckConfig.applicationKeys.toSortedMap().forEach { (aid, keys) ->
                appendLine("AID 0x%06X".format(aid))
                keys.forEach { key ->
                    appendLine("  - ${key.ref().label} [${keyTypeLabel(key.key.type)} key #${key.key.number}]")
                }
            }
        }.trimEnd()
    }

    fun buildPiccKeySummary(): String {
        val key = piccMasterKey ?: return "No PICC master key configured."
        return "${piccMasterKeyLabel ?: "PICC master key"} [${keyTypeLabel(key.type)} key #${key.number}]"
    }

    // --- Methods called by ResultsFragment ---

    fun exportScanPdf(item: ScanHistoryItem) {
        sessionState.pendingPdfLines = item.pdfLines()
        createQuickCheckPdf.launch("rfidgear-${item.uid}-${item.timestamp}.pdf")
    }

    fun exportAllScansPdf(items: List<ScanHistoryItem>) {
        if (items.isEmpty()) return
        sessionState.pendingPdfLines = buildList {
            add("RFIDGear scan history")
            add("Scans: ${items.size}")
            items.forEachIndexed { index, item ->
                add(""); add("Scan ${index + 1} of ${items.size}")
                addAll(item.pdfLines())
            }
        }
        createQuickCheckPdf.launch("rfidgear-all-scans.pdf")
    }

    // --- NFC tag handling ---

    override fun onTagDiscovered(tag: Tag) {
        // Guard: only process if Armed
        if (armState !is ArmState.Armed) {
            AppLogger.log("NFC", "Tag ${tag.id.toHex()} ignored; armState=${armState::class.simpleName}")
            return
        }
        armState = ArmState.Running

        val uidText = tag.id.toHex()
        val techList = tag.techList.toList()
        val scanStart = android.os.SystemClock.elapsedRealtime()
        AppLogger.log("NFC", "Tag discovered: UID=$uidText techs=${techList.joinToString()}")

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            val classic = MifareClassic.get(tag)
            val ultralight = MifareUltralight.get(tag)
            val sak = NfcA.get(tag)?.sak?.toInt()?.and(0xFF)
            val label = when {
                sak == 0x10 || sak == 0x11 -> "MIFARE Plus (SL2-compatible)"
                classic != null -> "MIFARE Classic-compatible"
                ultralight != null -> "MIFARE Ultralight-compatible"
                sak == 0x08 || sak == 0x18 || sak == 0x09 -> "Classic / Plus SL1-compatible"
                sak == 0 -> "NFC Type 2-compatible"
                else -> "NFC card"
            }
            val capability = when {
                classic != null -> "This phone exposes the Classic read/write API. Sector authentication keys are required for protected access. RFIDGear does not yet implement Classic memory read/write. Classic-compatible cards can include Plus in SL1."
                ultralight != null -> "This phone exposes the Ultralight read/write API. RFIDGear does not yet implement Ultralight memory read/write. Protection depends on the card."
                else -> "The phone did not expose a Classic or Ultralight API for this card. NFC-A detection alone does not guarantee Classic authentication/read/write support."
            }
            AppLogger.log("NFC", "UID=$uidText not ISO-DEP: $label")
            recordIdentification(tag, label, "Not a DESFire ISO-DEP card. $capability")
            transitionAfterScan(tag)
            return
        }

        try {
            AppLogger.log("NFC", "UID=$uidText connecting ISO-DEP")
            isoDep.connect()
            isoDep.timeout = 1500
            val transport = AndroidIsoDepTransport(isoDep) { event ->
                AppLogger.log("NFC", "UID=$uidText $event")
            }
            AppLogger.log("NFC", "UID=$uidText identifying product family; exchange timeout=1500ms")

            val family = if (MifareIdentification.legacyPlus(isoDep.historicalBytes))
                MifareIdentification.Family.PLUS else MifareIdentification.identify(transport::transceive)
            AppLogger.log(
                "NFC",
                "UID=$uidText identification=${family.label} elapsed=${android.os.SystemClock.elapsedRealtime() - scanStart}ms"
            )
            if (family != MifareIdentification.Family.DESFIRE) {
                val message = if (family == MifareIdentification.Family.UNKNOWN)
                    "ISO-DEP detected, but the card family could not be confirmed. DESFire actions were not run."
                else "${family.label} identified. The selected DESFire action does not support this card family; no DESFire authentication or write operation was run."
                AppLogger.log("NFC", "UID=$uidText not DESFire: ${family.label}")
                recordIdentification(tag, family.label, message)
                return
            }

            isoDep.timeout = 5000
            AppLogger.log("NFC", "UID=$uidText DESFire confirmed, action=${activeScanUseCase.name} runMode=${runMode.name} exchangeTimeout=5000ms")
            NativeBridge.attachTransport(transport)

            when (activeScanUseCase) {
                ActiveScanUseCase.FORMAT -> {
                    runFormat(tag, uidText, techList)
                    return
                }
                ActiveScanUseCase.FACTORY_RESET -> {
                    runFactoryReset(tag, uidText, techList)
                    return
                }
                ActiveScanUseCase.RESTORE_TRANSPORT_CONFIG -> {
                    runRestoreTransportConfig(tag, uidText)
                    return
                }
                ActiveScanUseCase.QUICK_CHECK -> runQuickCheck(tag, isoDep, techList)
            }
        } catch (e: Exception) {
            AppLogger.log("NFC", "UID=$uidText exception: ${e.javaClass.simpleName} ${e.message}\n${e.stackTraceToString()}")
            setNfcStatus("NFC error: ${e.message}")
        } finally {
            NativeBridge.detachTransport()
            try { isoDep.close() } catch (_: Exception) {}
            AppLogger.log("NFC", "UID=$uidText ISO-DEP closed; scan elapsed=${android.os.SystemClock.elapsedRealtime() - scanStart}ms")
            transitionAfterScan(tag)
        }
    }

    private fun transitionAfterScan(tag: Tag) {
        if (armState !is ArmState.Running) return
        val forceManual = sessionState.rescanOncePending
        if (forceManual) sessionState.rescanOncePending = false

        if (forceManual || runMode == RunMode.MANUAL_ONE_SHOT) {
            runOnUiThread {
                armState = ArmState.Disarmed
                nfcAdapter?.disableReaderMode(this@MainActivity)
            }
        } else {
            // AUTO_REPEAT: wait for tag removal then re-arm
            armState = ArmState.WaitingForRemoval
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            nfcAdapter?.ignore(tag, 500, {
                if (armState is ArmState.WaitingForRemoval) {
                    armState = ArmState.Armed
                    AppLogger.log("NFC", "Tag removed; re-armed for AUTO_REPEAT")
                }
            }, handler)
        }
    }

    private fun recordIdentification(tag: Tag, label: String, message: String) {
        val nfcA = NfcA.get(tag)
        val cardText = buildString {
            appendLine("Card")
            appendLine("UID: ${tag.id.toHex()}")
            appendLine("Technology: $label")
            nfcA?.let {
                appendLine("SAK: 0x%02X".format(it.sak.toInt() and 0xFF))
                appendLine("ATQA (Android byte order): ${it.atqa.toHex()}")
            }
            appendLine()
            append(message)
        }
        val environment = buildString {
            appendLine("NFC technologies: ${tag.techList.joinToString()}")
            IsoDep.get(tag)?.let { appendLine("Max transceive: ${it.maxTransceiveLength} bytes") }
            append("Native bridge: ${NativeBridge.version()}")
        }
        runOnUiThread {
            resultsFragment.addScanResult(ScanHistoryItem(tag.id.toHex(), label, System.currentTimeMillis(),
                savedCardText = cardText, savedEnvironmentText = environment))
            binding.bottomNav.selectedItemId = R.id.nav_results
            setNfcStatus(message)
        }
    }

    private fun runQuickCheck(tag: Tag, isoDep: IsoDep, techList: List<String>) {
        val uid = tag.id.toHex()
        AppLogger.log("QUICK_CHECK", "UID=$uid start")
        setNfcStatus("Quick Check running… keep the card in the NFC field.")

        val scanConfig = buildQuickCheckConfigWithDefaults()
        val report = try {
            quickCheckService.run(backend = NativeDesfireCardBackend(tag.id), config = scanConfig)
        } finally {
            scanConfig.piccKeys.take(defaultPiccCandidates.size).forEach { it.key.clear() }
        }
        val document = DesfireQuickCheckReportDocumentFactory.from(
            report = report,
            generatedAt = OffsetDateTime.now().toString(),
            environment = DesfireQuickCheckReportEnvironment(
                nfcTechnologies = techList,
                maxTransceiveLength = isoDep.maxTransceiveLength,
                backendVersion = NativeBridge.version()
            )
        )

        val directoryLabel = report.directoryAuthenticatedWith?.label
        val defaultDirectoryLabel = directoryLabel?.takeIf { label -> defaultPiccCandidates.any { it.label == label } }
        val probe = if (defaultDirectoryLabel == null) probeDefaultPiccKey(tag) else null
        val detectedPiccLabel = defaultDirectoryLabel ?: probe?.first
            ?: "Neither DES zeros nor AES zeros authenticated (key required or probe unavailable)"
        probe?.second?.clear()

        runOnUiThread {
            val item = ScanHistoryItem(
                uid = tag.id.toHex(),
                cardLabel = "DESFire",
                timestamp = System.currentTimeMillis(),
                document = document,
                detectedPiccKeyLabel = detectedPiccLabel
            )
            resultsFragment.addScanResult(item)
            binding.bottomNav.selectedItemId = R.id.nav_results

            val firstMissingKeyAid = report.needsKeys.firstOrNull()
            val reportError = report.error
            setNfcStatus(when {
                reportError != null -> "Quick Check failed: ${report.errorMessage ?: reportError.rfidGearName}"
                firstMissingKeyAid == null ->
                    "Quick Check complete — PICC key: $detectedPiccLabel."
                else ->
                    "Quick Check partial — PICC key: $detectedPiccLabel. AID 0x%06X needs app key.".format(firstMissingKeyAid)
            })

            AppLogger.log("QUICK_CHECK", "UID=$uid piccKey='$detectedPiccLabel' error=$reportError missingAids=${report.needsKeys.map { "0x%06X".format(it) }}")
            if (reportError != null) playFailureSound() else playSuccessSound()
        }
    }

    private fun buildQuickCheckConfigWithDefaults(): DesfireQuickCheckConfig {
        val allPiccCandidates = buildList {
            defaultPiccCandidates.forEach { add(DesfireQuickCheckKey(it.label, it.create())) }
            piccMasterKey?.let { key ->
                add(DesfireQuickCheckKey(piccMasterKeyLabel ?: "User PICC key", key))
            }
            addAll(quickCheckConfig.piccKeys)
        }
        return quickCheckConfig.copy(piccKeys = allPiccCandidates)
    }

    private fun probeDefaultPiccKey(tag: Tag): Pair<String, DesfireKey>? {
        for (candidate in defaultPiccCandidates) {
            val key = candidate.create()
            val backend = NativeDesfireCardBackend(tag.id)
            val connectResult = backend.connect()
            if (!connectResult.isSuccess) { backend.disconnect(); key.clear(); continue }
            val authResult = backend.execute(DesfireAuthenticate(appId = 0, key = key))
            backend.disconnect()
            if (authResult.isSuccess) return candidate.label to key
            key.clear()
        }
        return null
    }

    private fun runFormat(tag: Tag, uidText: String, techList: List<String>) {
        AppLogger.log("FORMAT", "UID=$uidText preflight start")
        setNfcStatus("Format preflight running… keep card in field.")

        val preflightResult = formatUseCase.preflight(NativeDesfireCardBackend(tag.id))
        val preflight = preflightResult.value

        if (!preflightResult.isSuccess || preflight == null) {
            AppLogger.log("FORMAT", "UID=$uidText preflight FAILED: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            setNfcStatus("Format preflight failed: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            return
        }

        val currentKey = piccMasterKey ?: probeDefaultPiccKey(tag)?.second
        if (currentKey == null) {
            AppLogger.log("FORMAT", "UID=$uidText no PICC key available")
            setNfcStatus("Format: no default PICC key matched. Configure it in Settings.")
            return
        }

        val confirmed = CompletableFuture<Boolean>()
        runOnUiThread {
            if (isFinishing) { confirmed.complete(false); return@runOnUiThread }
            MaterialAlertDialogBuilder(this)
                .setTitle("Format DESFire card?")
                .setMessage(
                    "UID: ${preflight.identity.uid.toHex()}\n\n" +
                    "All applications and files will be permanently deleted.\n" +
                    "This cannot be undone.\n\n" +
                    "Keep the card in the NFC field."
                )
                .setNegativeButton(R.string.btn_cancel) { _, _ -> confirmed.complete(false) }
                .setPositiveButton(R.string.btn_format) { _, _ -> confirmed.complete(true) }
                .setOnCancelListener { confirmed.complete(false) }
                .show()
        }

        if (!confirmed.get()) {
            AppLogger.log("FORMAT", "UID=$uidText cancelled by user")
            setNfcStatus("Format cancelled.")
            return
        }

        AppLogger.log("FORMAT", "UID=$uidText confirmed, executing")
        setNfcStatus("Executing FORMAT_PICC… keep card in field.")

        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val result = formatUseCase.execute(NativeDesfireCardBackend(tag.id), authorization, currentKey)

        AppLogger.log("FORMAT", "UID=$uidText result: success=${result.verifiedSuccess} status=${result.status.name} msg=${result.message}")
        if (result.verifiedSuccess) playSuccessSound() else playFailureSound()

        runOnUiThread {
            setNfcStatus(
                if (result.verifiedSuccess) "Format complete: card is empty."
                else "Format: ${result.message ?: result.status.name}"
            )
            val item = ScanHistoryItem(
                uid = uidText,
                cardLabel = "DESFire Format",
                timestamp = System.currentTimeMillis(),
                formatResult = result
            )
            resultsFragment.addScanResult(item)
            binding.bottomNav.selectedItemId = R.id.nav_results
        }
    }

    private fun runFactoryReset(tag: Tag, uidText: String, techList: List<String>) {
        AppLogger.log("FACTORY_RESET", "UID=$uidText preflight start")
        setNfcStatus("Factory Reset preflight running… keep card in field.")

        val preflightResult = factoryResetUseCase.preflight(NativeDesfireCardBackend(tag.id))
        val preflight = preflightResult.value

        if (!preflightResult.isSuccess || preflight == null) {
            AppLogger.log("FACTORY_RESET", "UID=$uidText preflight FAILED: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            setNfcStatus("Factory Reset preflight failed: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            return
        }

        val currentKey = piccMasterKey ?: probeDefaultPiccKey(tag)?.second
        if (currentKey == null) {
            AppLogger.log("FACTORY_RESET", "UID=$uidText no PICC key available")
            setNfcStatus("Factory Reset: no default PICC key matched. Configure it in Settings.")
            return
        }

        val confirmed = CompletableFuture<Boolean>()
        runOnUiThread {
            if (isFinishing) { confirmed.complete(false); return@runOnUiThread }
            MaterialAlertDialogBuilder(this)
                .setTitle("Factory Reset DESFire card?")
                .setMessage(
                    "UID: ${preflight.identity.uid.toHex()}\n\n" +
                    "All applications and files will be deleted.\n" +
                    "PICC master key #0 will be reset to the DES zero key.\n" +
                    "This cannot be undone.\n\n" +
                    "Keep the card in the NFC field."
                )
                .setNegativeButton(R.string.btn_cancel) { _, _ -> confirmed.complete(false) }
                .setPositiveButton(R.string.btn_reset) { _, _ -> confirmed.complete(true) }
                .setOnCancelListener { confirmed.complete(false) }
                .show()
        }

        if (!confirmed.get()) {
            AppLogger.log("FACTORY_RESET", "UID=$uidText cancelled by user")
            setNfcStatus("Factory Reset cancelled.")
            return
        }

        AppLogger.log("FACTORY_RESET", "UID=$uidText confirmed, executing")
        setNfcStatus("Executing Factory Reset… keep card in field.")

        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val result = factoryResetUseCase.execute(NativeDesfireCardBackend(tag.id), authorization, currentKey)

        AppLogger.log("FACTORY_RESET", "UID=$uidText result: success=${result.verifiedSuccess} status=${result.status.name} msg=${result.message}")
        if (result.verifiedSuccess) playSuccessSound() else playFailureSound()

        runOnUiThread {
            setNfcStatus(
                if (result.verifiedSuccess) "Factory Reset complete."
                else "Factory Reset: ${result.message ?: result.status.name}"
            )
            val item = ScanHistoryItem(
                uid = uidText,
                cardLabel = "DESFire Factory Reset",
                timestamp = System.currentTimeMillis(),
                factoryResetResult = result
            )
            resultsFragment.addScanResult(item)
            binding.bottomNav.selectedItemId = R.id.nav_results
        }
    }

    private fun runRestoreTransportConfig(tag: Tag, uidText: String) {
        AppLogger.log("RESTORE_TRANSPORT", "UID=$uidText start")
        setNfcStatus("Restoring PICC transport config… keep card in field.")

        val currentKey = piccMasterKey
        if (currentKey == null) {
            AppLogger.log("RESTORE_TRANSPORT", "UID=$uidText no PICC key configured")
            setNfcStatus("Restore Transport Config: PICC master key not configured. Set it in Settings.")
            playFailureSound()
            return
        }

        val backend = NativeDesfireCardBackend(tag.id)
        val connectResult = backend.connect()
        if (!connectResult.isSuccess) {
            AppLogger.log("RESTORE_TRANSPORT", "UID=$uidText connect failed")
            backend.disconnect()
            setNfcStatus("Restore Transport Config: could not connect to card.")
            playFailureSound()
            return
        }

        val authResult = backend.execute(DesfireAuthenticate(appId = 0, key = currentKey))
        if (!authResult.isSuccess) {
            AppLogger.log("RESTORE_TRANSPORT", "UID=$uidText authentication failed")
            backend.disconnect()
            setNfcStatus("Restore Transport Config: authentication failed. Check PICC master key.")
            playFailureSound()
            return
        }

        val transportKey = DesfireFactoryDefaults.piccMasterKey()
        val changeResult = backend.execute(DesfireChangePiccMasterKey(currentKey, transportKey))
        transportKey.clear()
        backend.disconnect()

        val success = changeResult.isSuccess
        val resultText = if (success) "PICC master key changed to DES factory default (32× 0x00)."
                         else "Restore Transport Config: key change command rejected."

        AppLogger.log("RESTORE_TRANSPORT", "UID=$uidText result: success=$success")
        if (success) playSuccessSound() else playFailureSound()

        runOnUiThread {
            setNfcStatus(if (success) "Restore Transport Config complete." else resultText)
            val item = ScanHistoryItem(
                uid = uidText,
                cardLabel = "DESFire Transport Config Restored",
                timestamp = System.currentTimeMillis(),
                savedCardText = resultText
            )
            resultsFragment.addScanResult(item)
            binding.bottomNav.selectedItemId = R.id.nav_results
        }
    }

    fun exportLog() {
        val ts = OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        createLogZip.launch("rfidgear-log-$ts.zip")
    }

    internal fun keyTypeLabel(type: DesfireKeyType): String = when (type) {
        DesfireKeyType.AES -> "AES"
        DesfireKeyType.TDES_3K -> "3K3DES"
        DesfireKeyType.DES -> "DES / 2K3DES"
    }

    /** Thread-safe: updates StateFlow from any thread; collectors on Main will receive it. */
    internal fun setNfcStatus(msg: String) {
        sessionState.updateUiState { it.copy(nfcStatus = msg) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG_ACTIONS = "actions"
        private const val TAG_RESULTS = "results"
        private const val TAG_SETTINGS = "settings"
    }
}
