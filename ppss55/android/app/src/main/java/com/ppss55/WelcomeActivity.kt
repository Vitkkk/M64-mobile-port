package com.ppss55

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.kytyps5.android.LauncherSettings
import com.kytyps5.android.MainActivity

/**
 * First run: what PPSS55 is (and is not), the all-files permission the native core needs, then the
 * games folder via the system picker (handled by MainActivity, which persists the grant).
 */
class WelcomeActivity : Activity() {

    private lateinit var permissionButton: android.widget.TextView
    private lateinit var permissionState: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(c, 24)
            setPadding(p, p, p, p)
            addView(Ui.text(c, "PPSS55", 34f, Ui.TEXT, bold = true))
            addView(Ui.text(c, "Emulador experimental de PlayStation 5 para Android", 16f, Ui.DIM))
            addView(Ui.spacer(c, 18))
            addView(Ui.card(c).apply {
                addView(Ui.text(c, "O que é", 15f, bold = true))
                addView(Ui.text(c, "Um port do núcleo KytyPS5-Android: o código x86-64 dos jogos roda no tradutor FEXCore e os comandos da GPU são traduzidos para Vulkan (Adreno/Turnip). É experimental: poucos jogos funcionam e o desempenho varia muito.", 14f, Ui.DIM))
                addView(Ui.spacer(c, 8))
                addView(Ui.text(c, "Seus arquivos", 15f, bold = true))
                addView(Ui.text(c, "O app não contém jogos, firmware nem chaves. Use apenas cópias que você mesmo extraiu de jogos que possui: pastas extraídas (com sce_sys/param.json) ou imagens .exfat / .zar.", 14f, Ui.DIM))
            })
            addView(Ui.spacer(c, 14))
            addView(Ui.card(c).apply {
                addView(Ui.text(c, "1. Acesso aos arquivos", 15f, bold = true))
                addView(Ui.text(c, "O núcleo nativo abre os arquivos do jogo por caminho (POSIX), então o Android exige \"acesso a todos os arquivos\" além da pasta escolhida.", 14f, Ui.DIM))
                permissionState = Ui.text(c, "", 13f)
                addView(permissionState)
                permissionButton = Ui.button(c, "Conceder acesso") { openAllFilesAccess() }
                addView(permissionButton)
                addView(Ui.spacer(c, 10))
                addView(Ui.text(c, "2. Pasta de jogos", 15f, bold = true))
                addView(Ui.text(c, "Em seguida, escolha a pasta onde estão seus jogos no seletor do Android. Você pode trocá-la depois.", 14f, Ui.DIM))
            })
            addView(Ui.spacer(c, 18))
            addView(Ui.button(c, "Escolher pasta de jogos", primary = true) { finishOnboarding() })
        }
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val p = Ui.dp(c, 24)
            v.setPadding(p + bars.left, p + bars.top, p + bars.right, p + bars.bottom)
            insets
        }
        setContentView(ScrollView(c).apply { setBackgroundColor(Ui.BG); addView(content) })
    }

    override fun onResume() {
        super.onResume()
        val granted = Environment.isExternalStorageManager()
        permissionState.text = if (granted) "✓ Concedido" else "Ainda não concedido"
        permissionState.setTextColor(if (granted) Ui.OK else Ui.WARN)
        permissionButton.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun openAllFilesAccess() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun finishOnboarding() {
        LauncherSettings(this).onboarded = true
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_FOLDER, true))
        finish()
    }
}
