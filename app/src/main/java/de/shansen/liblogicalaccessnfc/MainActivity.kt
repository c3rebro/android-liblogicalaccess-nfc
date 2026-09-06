package de.shansen.liblogicalaccessnfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import de.shansen.liblogicalaccessnfc.databinding.ActivityMainBinding
import de.shansen.liblogicalaccessnfc.databinding.DialogDesfireQuickCheckKeyBinding
import de.shansen.rfcard.DesfireAuthenticate
import de.shansen.rfcard.DesfireFactoryDefaults
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import de.shansen.rfidgearruntime.DesfireQuickCheckConfig
import de.shansen.rfidgearruntime.DesfireQuickCheckKey
import de.shansen.rfidgearruntime.DesfireQuickCheckKeyFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocument
import de.shansen.rfidgearruntime.DesfireQuickCheckReportDocumentFactory
import de.shansen.rfidgearruntime.DesfireQuickCheckReportEnvironment
import de.shansen.rfidgearruntime.DesfireQuickCheckService
import de.shansen.rfidgearruntime.DesfireQuickCheckTextRenderer
import de.shansen.rfusecase.DesfireFactoryResetAuthorization
import de.shansen.rfusecase.DesfireFactoryResetUseCase
import de.shansen.rfusecase.DesfireFormatAuthorization
import de.shansen.rfusecase.DesfireFormatUseCase
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    enum class ActiveScanUseCase {
        QUICK_CHECK, FORMAT, FACTORY_RESET
    }

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null

    private val quickCheckService = DesfireQuickCheckService()
    private val formatUseCase = DesfireFormatUseCase()
    private val factoryResetUseCase = DesfireFactoryResetUseCase()

    private data class DefaultPiccCandidate(val label: String, val create: () -> DesfireKey)
    private val defaultPiccCandidates = listOf(
        DefaultPiccCandidate("DES zeros (factory)") { DesfireFactoryDefaults.piccMasterKey() },
        DefaultPiccCandidate("AES zeros") { DesfireKey(ByteArray(16), DesfireKeyType.AES, 0, 0) }
    )

    var quickCheckConfig = DesfireQuickCheckConfig()
        private set
    var piccMasterKeyLabel: String? = null
        private set
    var piccMasterKey: DesfireKey? = null
        private set
    var activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
        internal set

    private lateinit var actionsFragment: ActionsFragment
    private lateinit var resultsFragment: ResultsFragment
    private lateinit var settingsFragment: SettingsFragment

    private var pendingExportDocument: DesfireQuickCheckReportDocument? = null

    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actionsFragment.loadProjectFromUri(uri)
    }

    private val createQuickCheckPdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val document = pendingExportDocument ?: return@registerForActivityResult
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    DesfireQuickCheckPdfRenderer().write(document, output)
                } ?: error("Unable to open the selected PDF destination.")
            }
            runOnUiThread {
                result.onSuccess {
                    actionsFragment.updateStatus("Quick Check PDF exported.")
                }.onFailure { error ->
                    actionsFragment.updateStatus("PDF export failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val nfcStatus = when {
            nfcAdapter == null -> "This device has no NFC adapter."
            nfcAdapter?.isEnabled != true -> "NFC is disabled."
            else -> "Ready. Hold a DESFire card near the phone."
        }
        actionsFragment.updateStatus(nfcStatus)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
        )
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun showFragment(id: Int) {
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

    // --- Methods called by ActionsFragment ---

    fun selectQuickCheckUseCase() {
        activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
        actionsFragment.updateUseCaseSummary()
        actionsFragment.updateStatus("Quick Check selected. Hold a DESFire card near the phone.")
    }

    fun selectFormatUseCase() {
        activeScanUseCase = ActiveScanUseCase.FORMAT
        actionsFragment.updateUseCaseSummary()
        actionsFragment.updateStatus("Format selected. Present the DESFire card — a confirmation dialog will appear.")
    }

    fun selectFactoryResetUseCase() {
        activeScanUseCase = ActiveScanUseCase.FACTORY_RESET
        actionsFragment.updateUseCaseSummary()
        actionsFragment.updateStatus("Factory Reset selected. Present the DESFire card — a confirmation dialog will appear.")
    }

    fun launchOpenProject() {
        openProject.launch(arrayOf("*/*"))
    }

    // --- Methods called by SettingsFragment ---

    fun showAddQuickCheckKeyDialog(prefillAid: Int? = null) {
        val dialogBinding = DialogDesfireQuickCheckKeyBinding.inflate(layoutInflater)
        val keyTypes = listOf(DesfireKeyType.AES, DesfireKeyType.TDES_3K, DesfireKeyType.DES)
        dialogBinding.keyType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            keyTypes.map(::keyTypeLabel)
        )
        prefillAid?.let { dialogBinding.aid.setText("0x%06X".format(it)) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("DESFire application key")
            .setView(dialogBinding.root)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialogBinding.label.error = null
                dialogBinding.keyHex.error = null
                dialogBinding.keyNumber.error = null

                val result = runCatching {
                    val aid = DesfireQuickCheckKeyFactory.parseAid(dialogBinding.aid.text.toString())
                    val keyNo = dialogBinding.keyNumber.text.toString().trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Key number must be a decimal number between 0 and 15.")
                    val type = keyTypes[dialogBinding.keyType.selectedItemPosition]
                    val key = DesfireQuickCheckKeyFactory.fromHex(
                        label = dialogBinding.label.text.toString(),
                        keyHex = dialogBinding.keyHex.text.toString(),
                        type = type,
                        keyNumber = keyNo
                    )
                    aid to key
                }

                result.onSuccess { (aid, key) ->
                    quickCheckConfig = quickCheckConfig.withApplicationKey(aid, key)
                    settingsFragment.updateKeySummaries()
                    actionsFragment.updateStatus("Key added for AID 0x%06X.".format(aid))
                    dialog.dismiss()
                }.onFailure { error ->
                    val msg = error.message ?: "Invalid DESFire key."
                    when {
                        msg.startsWith("Key label") -> dialogBinding.label.error = msg
                        msg.startsWith("DESFire key number") -> dialogBinding.keyNumber.error = msg
                        else -> dialogBinding.keyHex.error = msg
                    }
                }
            }
        }
        dialog.show()
    }

    fun clearSessionQuickCheckKeys() {
        quickCheckConfig.applicationKeys.values.flatten().forEach { it.key.clear() }
        quickCheckConfig.defaultApplicationKeys.forEach { it.key.clear() }
        quickCheckConfig.piccKeys.forEach { it.key.clear() }
        quickCheckConfig = DesfireQuickCheckConfig()
    }

    fun setPiccKey(label: String, key: DesfireKey) {
        piccMasterKey?.clear()
        piccMasterKeyLabel = label
        piccMasterKey = key
    }

    fun clearPiccKey() {
        piccMasterKey?.clear()
        piccMasterKey = null
        piccMasterKeyLabel = null
    }

    fun buildQuickCheckKeySummary(): String {
        if (quickCheckConfig.applicationKeys.isEmpty()) {
            return "No application-specific keys configured.\nKeys are session-only."
        }
        return buildString {
            appendLine("Application keys (session-only):")
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

    fun exportQuickCheckPdf(document: DesfireQuickCheckReportDocument) {
        pendingExportDocument = document
        val uid = document.card.uid.ifBlank { "unknown" }
        createQuickCheckPdf.launch("desfire-quick-check-$uid.pdf")
    }

    // --- NFC tag handling ---

    override fun onTagDiscovered(tag: Tag) {
        val uidText = tag.id.toHex()
        val techList = tag.techList.toList()

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            runOnUiThread {
                actionsFragment.updateStatus("Tag detected, but no ISO-DEP support (UID: $uidText).")
            }
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 5000

            val transport = AndroidIsoDepTransport(isoDep)
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
                ActiveScanUseCase.QUICK_CHECK -> runQuickCheck(tag, isoDep, techList)
            }
        } catch (e: Exception) {
            runOnUiThread {
                actionsFragment.updateStatus("NFC error: ${e.message}")
            }
        } finally {
            NativeBridge.detachTransport()
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun runQuickCheck(tag: Tag, isoDep: IsoDep, techList: List<String>) {
        runOnUiThread {
            actionsFragment.updateStatus("Quick Check running... keep the card in the NFC field.")
        }

        val report = quickCheckService.run(
            backend = NativeDesfireCardBackend(tag.id),
            config = buildQuickCheckConfigWithDefaults()
        )
        val document = DesfireQuickCheckReportDocumentFactory.from(
            report = report,
            generatedAt = OffsetDateTime.now().toString(),
            environment = DesfireQuickCheckReportEnvironment(
                nfcTechnologies = techList,
                maxTransceiveLength = isoDep.maxTransceiveLength,
                backendVersion = NativeBridge.version()
            )
        )

        // Auto-set PICC master key if a default candidate authenticated the directory listing
        val detectedPiccLabel = maybeAutoSetPiccMasterKey(report.directoryAuthenticatedWith?.label)

        runOnUiThread {
            if (detectedPiccLabel != null) settingsFragment.updateKeySummaries()

            val item = ScanHistoryItem(
                uid = tag.id.toHex(),
                cardLabel = "DESFire",
                timestamp = System.currentTimeMillis(),
                document = document
            )
            resultsFragment.addScanResult(item)
            binding.bottomNav.selectedItemId = R.id.nav_results

            val firstMissingKeyAid = report.needsKeys.firstOrNull()
            val reportError = report.error
            actionsFragment.updateStatus(when {
                reportError != null -> "Quick Check failed: ${report.errorMessage ?: reportError.rfidGearName}"
                detectedPiccLabel != null && firstMissingKeyAid == null ->
                    "Quick Check complete — PICC key auto-detected: $detectedPiccLabel."
                detectedPiccLabel != null ->
                    "Quick Check partial — PICC key: $detectedPiccLabel. AID 0x%06X needs app key.".format(firstMissingKeyAid)
                firstMissingKeyAid != null ->
                    "Quick Check partial: AID 0x%06X requires authentication.".format(firstMissingKeyAid)
                else -> "Quick Check complete."
            })

            if (firstMissingKeyAid != null && !isFinishing) {
                showAddQuickCheckKeyDialog(firstMissingKeyAid)
            }
        }
    }

    /** Builds a Quick Check config that always probes the two factory-default PICC keys first,
     *  then the user-configured PICC key (if any), then any manually-added piccKeys. */
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

    /** If [authenticatedLabel] matches a default candidate and no PICC key is configured yet,
     *  auto-sets [piccMasterKey] and returns the detected label; otherwise returns null. */
    private fun maybeAutoSetPiccMasterKey(authenticatedLabel: String?): String? {
        if (authenticatedLabel == null || piccMasterKey != null) return null
        val matched = defaultPiccCandidates.find { it.label == authenticatedLabel } ?: return null
        piccMasterKey = matched.create()
        piccMasterKeyLabel = "Auto: ${matched.label}"
        return matched.label
    }

    /** Tries each default PICC candidate against the card (transport must be attached).
     *  Returns a fresh key instance for the first one that authenticates, or null. */
    private fun probeDefaultPiccKey(tag: Tag): DesfireKey? {
        for (candidate in defaultPiccCandidates) {
            val key = candidate.create()
            val backend = NativeDesfireCardBackend(tag.id)
            val connectResult = backend.connect()
            if (!connectResult.isSuccess) { backend.disconnect(); continue }
            val authResult = backend.execute(
                DesfireAuthenticate(appId = 0, key = key)
            )
            backend.disconnect()
            if (authResult.isSuccess) return key
            key.clear()
        }
        return null
    }

    private fun runFormat(tag: Tag, uidText: String, techList: List<String>) {
        runOnUiThread { actionsFragment.updateStatus("Format preflight running... keep card in field.") }

        val preflightResult = formatUseCase.preflight(NativeDesfireCardBackend(tag.id))
        val preflight = preflightResult.value

        if (!preflightResult.isSuccess || preflight == null) {
            runOnUiThread {
                activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Format preflight failed: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            }
            return
        }

        val currentKey = piccMasterKey ?: probeDefaultPiccKey(tag)
        if (currentKey == null) {
            runOnUiThread {
                activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Format: no default PICC key matched. Configure it in Settings.")
            }
            return
        }

        val confirmed = CompletableFuture<Boolean>()
        runOnUiThread {
            if (isFinishing) { confirmed.complete(false); return@runOnUiThread }
            AlertDialog.Builder(this)
                .setTitle("Format DESFire card?")
                .setMessage(
                    "UID: ${preflight.identity.uid.toHex()}\n\n" +
                    "All applications and files will be permanently deleted.\n" +
                    "This cannot be undone.\n\n" +
                    "Keep the card in the NFC field."
                )
                .setNegativeButton("Cancel") { _, _ -> confirmed.complete(false) }
                .setPositiveButton("Format") { _, _ -> confirmed.complete(true) }
                .setOnCancelListener { confirmed.complete(false) }
                .show()
        }

        if (!confirmed.get()) {
            runOnUiThread {
                activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Format cancelled.")
            }
            return
        }

        runOnUiThread { actionsFragment.updateStatus("Executing FORMAT_PICC... keep card in field.") }

        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val result = formatUseCase.execute(NativeDesfireCardBackend(tag.id), authorization, currentKey)

        runOnUiThread {
            activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
            actionsFragment.updateUseCaseSummary()
            actionsFragment.updateStatus(
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
        runOnUiThread { actionsFragment.updateStatus("Factory Reset preflight running... keep card in field.") }

        val preflightResult = factoryResetUseCase.preflight(NativeDesfireCardBackend(tag.id))
        val preflight = preflightResult.value

        if (!preflightResult.isSuccess || preflight == null) {
            runOnUiThread {
                activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Factory Reset preflight failed: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            }
            return
        }

        val currentKey = piccMasterKey ?: probeDefaultPiccKey(tag)
        if (currentKey == null) {
            runOnUiThread {
                activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Factory Reset: no default PICC key matched. Configure it in Settings.")
            }
            return
        }

        val confirmed = CompletableFuture<Boolean>()
        runOnUiThread {
            if (isFinishing) { confirmed.complete(false); return@runOnUiThread }
            AlertDialog.Builder(this)
                .setTitle("Factory Reset DESFire card?")
                .setMessage(
                    "UID: ${preflight.identity.uid.toHex()}\n\n" +
                    "All applications and files will be deleted.\n" +
                    "PICC master key #0 will be reset to the DES zero key.\n" +
                    "This cannot be undone.\n\n" +
                    "Keep the card in the NFC field."
                )
                .setNegativeButton("Cancel") { _, _ -> confirmed.complete(false) }
                .setPositiveButton("Reset") { _, _ -> confirmed.complete(true) }
                .setOnCancelListener { confirmed.complete(false) }
                .show()
        }

        if (!confirmed.get()) {
            runOnUiThread {
                activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Factory Reset cancelled.")
            }
            return
        }

        runOnUiThread { actionsFragment.updateStatus("Executing Factory Reset... keep card in field.") }

        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val result = factoryResetUseCase.execute(NativeDesfireCardBackend(tag.id), authorization, currentKey)

        runOnUiThread {
            activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
            actionsFragment.updateUseCaseSummary()
            actionsFragment.updateStatus(
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

    internal fun keyTypeLabel(type: DesfireKeyType): String = when (type) {
        DesfireKeyType.AES -> "AES"
        DesfireKeyType.TDES_3K -> "3K3DES"
        DesfireKeyType.DES -> "DES / 2K3DES"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG_ACTIONS = "actions"
        private const val TAG_RESULTS = "results"
        private const val TAG_SETTINGS = "settings"
    }
}
