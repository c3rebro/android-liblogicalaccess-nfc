package de.shansen.liblogicalaccessnfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import de.shansen.liblogicalaccessnfc.databinding.ActivityMainBinding

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var adapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = NfcAdapter.getDefaultAdapter(this)
        binding.status.text = when {
            adapter == null -> "This device has no NFC adapter."
            adapter?.isEnabled != true -> "NFC is disabled."
            else -> "Ready. Hold an ISO-DEP card near the phone."
        }

        binding.details.text = "Native bridge: ${NativeBridge.version()}"
    }

    override fun onResume() {
        super.onResume()
        adapter?.enableReaderMode(
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
        adapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.toHex()
        val techs = tag.techList.joinToString()

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            runOnUiThread {
                binding.status.text = "Tag detected, but no ISO-DEP support."
                binding.details.text = "UID: $uid\nTechnologies: $techs"
            }
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 3000

            val transport = AndroidIsoDepTransport(isoDep)
            NativeBridge.attachTransport(transport)

            runOnUiThread {
                binding.status.text = "ISO-DEP tag connected."
                binding.details.text =
                    "UID: $uid\n" +
                    "Technologies: $techs\n" +
                    "Max transceive: ${isoDep.maxTransceiveLength}\n" +
                    "Native bridge: ${NativeBridge.version()}"
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.status.text = "NFC error: ${e.message}"
            }
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
            NativeBridge.detachTransport()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
