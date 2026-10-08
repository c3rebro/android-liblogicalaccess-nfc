package de.shansen.liblogicalaccessnfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import de.shansen.rfcard.MifareIdentification
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.lifecycle.ViewModelProvider
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
        DefaultPiccCandidate("AES zeros (non-factory key type)") { DesfireKey(ByteArray(16), DesfireKeyType.AES, 0, 0) }
    )

    private val session by lazy { ViewModelProvider(this)[AppSessionState::class.java] }
    private val preferences by lazy { getSharedPreferences("app-state", MODE_PRIVATE) }
    private val savedKeys by lazy { SavedKeyStore(this) }
    var quickCheckConfig: DesfireQuickCheckConfig
        get() = session.config
        private set(value) { session.config = value }
    var piccMasterKeyLabel: String?
        get() = session.piccLabel
        private set(value) { session.piccLabel = value }
    var piccMasterKey: DesfireKey?
        get() = session.piccKey
        private set(value) { session.piccKey = value }
    var activeScanUseCase = ActiveScanUseCase.QUICK_CHECK
        internal set(value) {
            field = value
            preferences.edit().putString("active-action", value.name).apply()
        }

    private lateinit var actionsFragment: ActionsFragment
    private lateinit var resultsFragment: ResultsFragment
    private lateinit var settingsFragment: SettingsFragment


    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actionsFragment.loadProjectFromUri(uri)
    }

    private val createQuickCheckPdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val lines = session.pendingPdfLines ?: return@registerForActivityResult
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    DesfireQuickCheckPdfRenderer().writeLines(lines, output)
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
        activeScanUseCase = runCatching {
            ActiveScanUseCase.valueOf(preferences.getString("active-action", "QUICK_CHECK")!!)
        }.getOrDefault(ActiveScanUseCase.QUICK_CHECK)
        if (!session.loaded) {
            runCatching { savedKeys.load() }.onSuccess { entries ->
                entries.forEach { entry ->
                    if (entry.aid == null) {
                        piccMasterKey = entry.key; piccMasterKeyLabel = entry.label
                    } else quickCheckConfig = quickCheckConfig.withApplicationKey(entry.aid,
                        DesfireQuickCheckKey(entry.label, entry.key))
                }
                session.loaded = true
            }.onFailure {
                android.widget.Toast.makeText(this, "Saved keys could not be unlocked. Re-enter them in Settings.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
        actionsFragment.updateUseCaseSummary()

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
                    if (dialogBinding.savePermanently.isChecked) {
                        val saved = runCatching { savedKeys.save(aid, key.label, key.key) }
                        if (saved.isFailure) {
                            dialogBinding.keyHex.error = "Unable to save securely. Try adding a session key instead."
                            key.key.clear()
                            return@onSuccess
                        }
                    }
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
        savedKeys.removeApplicationKeys()
        quickCheckConfig.applicationKeys.values.flatten().forEach { it.key.clear() }
        quickCheckConfig.defaultApplicationKeys.forEach { it.key.clear() }
        quickCheckConfig.piccKeys.forEach { it.key.clear() }
        quickCheckConfig = DesfireQuickCheckConfig()
    }

    fun setPiccKey(label: String, key: DesfireKey, permanently: Boolean = false) {
        if (permanently) savedKeys.save(null, label, key) else savedKeys.removePiccKey()
        piccMasterKey?.clear()
        piccMasterKeyLabel = label
        piccMasterKey = key
    }

    fun clearPiccKey() {
        savedKeys.removePiccKey()
        piccMasterKey?.clear()
        piccMasterKey = null
        piccMasterKeyLabel = null
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
        session.pendingPdfLines = item.pdfLines()
        createQuickCheckPdf.launch("rfidgear-${item.uid}-${item.timestamp}.pdf")
    }

    fun exportAllScansPdf(items: List<ScanHistoryItem>) {
        if (items.isEmpty()) return
        session.pendingPdfLines = buildList {
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
        val uidText = tag.id.toHex()
        val techList = tag.techList.toList()

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
            recordIdentification(tag, label, "Not a DESFire ISO-DEP card. $capability")
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 5000

            val family = if (MifareIdentification.legacyPlus(isoDep.historicalBytes))
                MifareIdentification.Family.PLUS else MifareIdentification.identify(isoDep::transceive)
            if (family != MifareIdentification.Family.DESFIRE) {
                val message = if (family == MifareIdentification.Family.UNKNOWN)
                    "ISO-DEP detected, but the card family could not be confirmed. DESFire actions were not run."
                else "${family.label} identified. The selected DESFire action does not support this card family; no DESFire authentication or write operation was run."
                recordIdentification(tag, family.label, message)
                return
            }

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
            actionsFragment.updateStatus(message)
        }
    }

    private fun runQuickCheck(tag: Tag, isoDep: IsoDep, techList: List<String>) {
        runOnUiThread {
            actionsFragment.updateStatus("Quick Check running... keep the card in the NFC field.")
        }

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

        // Detection belongs to this card, never to a previously scanned card or the user key.
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
            actionsFragment.updateStatus(when {
                reportError != null -> "Quick Check failed: ${report.errorMessage ?: reportError.rfidGearName}"
                firstMissingKeyAid == null ->
                    "Quick Check complete — PICC key: $detectedPiccLabel."
                else ->
                    "Quick Check partial — PICC key: $detectedPiccLabel. AID 0x%06X needs app key.".format(firstMissingKeyAid)
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

    /** Tries each default PICC candidate against the card (transport must be attached).
     *  Returns the candidate label and a fresh key for the first one that authenticates, or null. */
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
        runOnUiThread { actionsFragment.updateStatus("Format preflight running... keep card in field.") }

        val preflightResult = formatUseCase.preflight(NativeDesfireCardBackend(tag.id))
        val preflight = preflightResult.value

        if (!preflightResult.isSuccess || preflight == null) {
            runOnUiThread {
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Format preflight failed: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            }
            return
        }

        val currentKey = piccMasterKey ?: probeDefaultPiccKey(tag)?.second
        if (currentKey == null) {
            runOnUiThread {
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
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Format cancelled.")
            }
            return
        }

        runOnUiThread { actionsFragment.updateStatus("Executing FORMAT_PICC... keep card in field.") }

        val authorization = DesfireFormatAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val result = formatUseCase.execute(NativeDesfireCardBackend(tag.id), authorization, currentKey)

        runOnUiThread {
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
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Factory Reset preflight failed: ${preflightResult.message ?: preflightResult.error.rfidGearName}")
            }
            return
        }

        val currentKey = piccMasterKey ?: probeDefaultPiccKey(tag)?.second
        if (currentKey == null) {
            runOnUiThread {
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
                actionsFragment.updateUseCaseSummary()
                actionsFragment.updateStatus("Factory Reset cancelled.")
            }
            return
        }

        runOnUiThread { actionsFragment.updateStatus("Executing Factory Reset... keep card in field.") }

        val authorization = DesfireFactoryResetAuthorization.confirm(preflight, preflight.confirmationPhrase)
        val result = factoryResetUseCase.execute(NativeDesfireCardBackend(tag.id), authorization, currentKey)

        runOnUiThread {
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
