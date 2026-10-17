package io.github.m64port

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: RomStore
    private lateinit var status: TextView
    private lateinit var importSyms: Button
    private lateinit var play: Button
    private lateinit var remove: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RomStore(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.legal_notice)
            setPadding(0, pad / 2, 0, pad)
        })
        status = TextView(this).apply { setPadding(0, 0, 0, pad) }
        column.addView(status)

        column.addView(button(R.string.import_rom) { pick(REQUEST_ROM) })
        importSyms = button(R.string.import_syms) { pick(REQUEST_SYMS) }
        column.addView(importSyms)
        play = button(R.string.recompile) { recompileAndPlay() }
        column.addView(play)
        remove = button(R.string.remove_rom) {
            store.deleteRom()
            refresh()
        }
        column.addView(remove)

        setContentView(ScrollView(this).apply { addView(column) })
        refresh()
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(label)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun pick(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("API de Activity sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_ROM -> {
                status.text = "Verificando ROM…"
                thread {
                    val result = store.importRom(uri)
                    runOnUiThread {
                        refresh()
                        if (!result.ok) status.text = "ROM recusada: ${result.message}"
                    }
                }
            }
            REQUEST_SYMS -> {
                val version = store.versionId() ?: return
                if (!store.importSyms(uri, version)) {
                    status.text = "Arquivo não parece uma tabela de símbolos do N64Recomp."
                } else {
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        val rom = store.romFile()
        val version = store.versionId()
        val syms = version?.let { store.symsFile(it) }
        status.text = when {
            rom == null -> getString(R.string.no_rom)
            syms == null -> "ROM importada (sm64.$version).\n" + getString(R.string.no_syms)
            else -> "ROM importada (sm64.$version) e tabela de símbolos pronta."
        }
        importSyms.visibility = if (rom != null && syms == null) View.VISIBLE else View.GONE
        play.isEnabled = rom != null && syms != null
        remove.visibility = if (rom != null) View.VISIBLE else View.GONE
    }

    private fun recompileAndPlay() {
        val rom = store.romFile() ?: return
        val syms = store.symsFile(store.versionId() ?: return) ?: return
        // A recompilação e o jogo rodam no processo da GameActivity.
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_ROM, rom.absolutePath)
                .putExtra(GameActivity.EXTRA_SYMS, syms.absolutePath)
        )
    }

    companion object {
        private const val REQUEST_ROM = 1
        private const val REQUEST_SYMS = 2
    }
}
