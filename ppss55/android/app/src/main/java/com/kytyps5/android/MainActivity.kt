package com.kytyps5.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.kytyps5.android.input.OverlayProfileStore
import com.kytyps5.android.library.CoverLoader
import com.kytyps5.android.library.GameGridAdapter
import com.kytyps5.android.library.GameSettingsDialog
import com.kytyps5.android.presets.GamePresets
import com.ppss55.Compatibility
import com.ppss55.DeviceProbe
import com.ppss55.LogReader
import com.ppss55.Ui
import com.ppss55.WelcomeActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PPSS55 launcher: game library, games folder (system picker, grant persisted), launcher settings and
 * device diagnostics. Games and the CPU self-test run in [GameActivity] in the ":game" process.
 */
class MainActivity : Activity() {

    private lateinit var settings: LauncherSettings
    private lateinit var drivers: GpuDriverPanel
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanRequest = 0

    private lateinit var coreChip: TextView
    private lateinit var storageBanner: LinearLayout
    private lateinit var folderPath: TextView
    private lateinit var message: TextView
    private lateinit var covers: CoverLoader
    private lateinit var grid: GameGridAdapter
    private lateinit var gameSettings: GameSettingsDialog

    private val diag by lazy { getSharedPreferences(DIAG_PREFS, MODE_PRIVATE) }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LauncherSettings(this)
        if (!settings.onboarded) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        drivers = GpuDriverPanel(
            activity = this,
            settings = settings,
            worker = worker,
            post = { mainHandler.post(it) },
            showMessage = ::showMessage,
            label = { Ui.text(this, it, 13f, Ui.DIM) },
        )
        covers = CoverLoader(targetPx = COVER_DP.dp)
        grid = GameGridAdapter(this, covers, COVER_DP.dp, onPlay = ::launch, onSettings = { gameSettings.show(it) })
        gameSettings = GameSettingsDialog(
            activity = this,
            settings = settings,
            layouts = OverlayProfileStore(File(filesDir, OverlayProfileStore.DIRECTORY_NAME)),
            presets = GamePresets(this, settings),
            driverLabel = { drivers.gameButtonText(it.path) },
            chooseDriver = { game, done -> drivers.chooseForGame(game, done) },
        )
        setContentView(buildUi())
        applyDefaultGpuDriver()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PICK_FOLDER, false)) openFolderPicker()
    }

    /** Installs the bundled Turnip on supported Adreno GPUs on first run (off the main thread). */
    private fun applyDefaultGpuDriver() {
        val setup = DefaultGpuDriverSetup(this, settings, drivers.store)
        worker.execute {
            val changed = setup.apply() ?: return@execute
            mainHandler.post {
                drivers.refresh()
                showMessage(changed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!settings.onboarded) return
        refreshStorageState()
        if (Environment.isExternalStorageManager()) {
            ensureDefaultGamesFolder()
            scanGames(settings.gamesRoot)
        } else {
            showMessage("Conceda o acesso a todos os arquivos para procurar jogos.")
        }
        reportPendingSelfTest()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        if (::covers.isInitialized) covers.shutdown()
        super.onDestroy()
    }

    // ---- layout ----------------------------------------------------------------------------------

    private fun buildUi(): View {
        val c = this
        coreChip = Ui.text(c, "", 12f).apply { setPadding(10.dp, 4.dp, 10.dp, 4.dp) }
        updateCoreChip()
        folderPath = Ui.text(c, settings.gamesRoot, 13f, Ui.DIM).apply { maxLines = 2 }
        message = Ui.text(c, "", 13f, Ui.DIM)
        storageBanner = Ui.card(c).apply {
            addView(Ui.text(c, "Acesso a todos os arquivos não concedido", 14f, Ui.WARN, bold = true))
            addView(Ui.text(c, "O núcleo abre os jogos por caminho de arquivo, então precisa dessa permissão.", 13f, Ui.DIM))
            addView(Ui.button(c, "Conceder acesso", primary = true) { openAllFilesAccessSettings() })
        }

        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(c, "PPSS55", 26f, Ui.TEXT, bold = true))
                addView(Ui.text(c, "Emulador experimental de PS5 · núcleo KytyPS5-Android (FEXCore + Vulkan)", 12f, Ui.DIM))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(coreChip)
            addView(Ui.button(c, "Diagnóstico") { showDiagnostics() }, marginStart())
            addView(Ui.button(c, "Configurações") { showSettings() }, marginStart())
        }

        val folderCard = Ui.card(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(c, "Pasta de jogos", 13f, Ui.TEXT, bold = true))
                addView(folderPath)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.button(c, "Trocar pasta") { openFolderPicker() }, marginStart())
            addView(Ui.button(c, "Atualizar") { onScanRequested() }, marginStart())
        }

        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(header)
            addView(Ui.spacer(c, 12))
            addView(storageBanner)
            addView(folderCard)
            addView(message)
            addView(
                GridView(c).apply {
                    adapter = grid
                    numColumns = GridView.AUTO_FIT
                    columnWidth = (COVER_DP + 12).dp
                    stretchMode = GridView.STRETCH_SPACING_UNIFORM
                    verticalSpacing = 4.dp
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(Ui.text(c, Compatibility.FOOTNOTE, 11f, Ui.DIM))
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(16.dp + bars.left, 12.dp + bars.top, 16.dp + bars.right, 8.dp + bars.bottom)
            insets
        }
        return content
    }

    private fun marginStart() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dp }

    private fun updateCoreChip() {
        val installed = NativeLibrary.isInstalled(this)
        coreChip.text = if (installed) "● núcleo presente" else "● núcleo ausente"
        coreChip.setTextColor(if (installed) Ui.OK else Ui.ERR)
    }

    private fun dialog(title: String, body: View): AlertDialog =
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(ScrollView(this).apply { setPadding(20.dp, 8.dp, 20.dp, 8.dp); addView(body) })
            .setPositiveButton("Fechar", null)
            .show()

    // ---- settings -----------------------------------------------------------------------------------

    private fun showSettings() {
        val c = this
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(c, "Resolução de saída", 14f, bold = true))
            addView(radio(Resolution.entries, settings.resolution, { it.label }) { settings.resolution = it })
            addView(Ui.text(c, "Resolução interna (escala de renderização)", 14f, bold = true))
            addView(radio(RenderScale.entries, settings.renderScale, { if (it == RenderScale.NATIVE) "100%" else it.label }) { settings.renderScale = it })
            addView(Ui.text(c, "Apresentação / limite de quadros", 14f, bold = true))
            addView(Ui.text(c, "O núcleo não tem limitador de FPS próprio; VSync limita à taxa de atualização da tela.", 12f, Ui.DIM))
            addView(radio(PresentMode.entries, settings.presentMode, { it.label }, vertical = true) { settings.presentMode = it })
            addView(toggle("Cache de shaders em disco", settings.shaderDiskCache) { settings.shaderDiskCache = it })
            addView(Ui.text(c, "Buffer de áudio", 14f, bold = true))
            addView(Ui.text(c, "Maior = menos picotes quando o jogo engasga, porém som mais atrasado. 40 ms é o valor original do núcleo.", 12f, Ui.DIM))
            addView(radio(LauncherSettings.AUDIO_LATENCY_CHOICES, settings.audioLatencyMs, { "$it ms" }) { settings.audioLatencyMs = it })
            addView(toggle("Mostrar FPS / frame time (HUD)", settings.hudEnabled) { settings.hudEnabled = it })
            addView(toggle("Registrar estatísticas por segundo (--perf-stats)", diag.getBoolean(KEY_PERF_STATS, false)) { diag.edit().putBoolean(KEY_PERF_STATS, it).apply() })
            addView(toggle("Controles virtuais na tela", settings.touchControlsEnabled) { settings.touchControlsEnabled = it })
            addView(toggle(GameSettingsDialog.GPU_TURBO_LABEL, settings.gpuTurbo) { settings.gpuTurbo = it })
            addView(Ui.spacer(c, 8))
            addView(Ui.text(c, "Driver Vulkan", 14f, bold = true))
            addView(drivers.build(onChanged = {}))
            addView(Ui.spacer(c, 8))
            addView(Ui.text(c, "Ajustes por jogo (controles, resolução, driver, presets, variáveis): toque em ⚙ ou segure o jogo.", 12f, Ui.DIM))
        }
        dialog("Configurações", body)
    }

    private fun <T> radio(values: List<T>, current: T, label: (T) -> String, vertical: Boolean = false, onPick: (T) -> Unit): RadioGroup {
        val group = RadioGroup(this).apply { orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL }
        values.forEach { value ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label(value)
                tag = value
                isChecked = value == current
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            @Suppress("UNCHECKED_CAST")
            (g.findViewById<View>(id)?.tag as? T)?.let(onPick)
        }
        return group
    }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    // ---- diagnostics ------------------------------------------------------------------------------

    private fun showDiagnostics() {
        val c = this
        val info = Ui.text(c, "Consultando o aparelho…", 13f, Ui.DIM)
        val result = Ui.text(c, "", 12f, Ui.TEXT).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(Ui.spacer(c, 8))
            addView(Ui.button(c, "Testar carregamento do núcleo (libmain.so)") {
                result.text = "Carregando…"
                worker.execute {
                    val r = DeviceProbe.tryLoadCore(this@MainActivity)
                    val text = r["error"]?.let { "FALHOU\n$it" } ?: ("OK\n" + r.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    mainHandler.post { result.text = text }
                }
            })
            addView(Ui.spacer(c, 6))
            addView(Ui.button(c, "Auto-teste da CPU x86-64 (FEXCore)") { runFexSelfTest() })
            addView(Ui.spacer(c, 6))
            addView(Ui.button(c, "Ver log da última sessão") { showLog(diag.getLong(KEY_LAST_LAUNCH, 0)) })
            addView(Ui.spacer(c, 8))
            addView(result)
        }
        dialog("Diagnóstico", body)
        worker.execute {
            val rows = DeviceProbe.summary(this)
            mainHandler.post { info.text = rows.joinToString("\n") { "${it.first}: ${it.second}" } }
        }
    }

    /**
     * Runs Cpu::GuestCpu::RunGuestCpuSelfTest in the game process: hand-assembled x86-64 code through
     * FEXCore (arithmetic, HLE calls, host->guest callbacks, fault handling). The verdict is read
     * back from the core's log when the launcher resumes.
     */
    private fun runFexSelfTest() {
        if (!NativeLibrary.isInstalled(this)) {
            showMessage(NativeLibrary.MISSING_MESSAGE)
            return
        }
        val now = System.currentTimeMillis()
        diag.edit().putLong(KEY_SELFTEST_START, now).putLong(KEY_LAST_LAUNCH, now).apply()
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(LaunchArgs.EXTRA_GAME_ARGS, arrayOf(LaunchArgs.FLAG_FEX_SELFTEST))
                .putExtra(LaunchArgs.EXTRA_TOUCH_CONTROLS, false)
                .putExtra(LaunchArgs.EXTRA_HUD, false),
        )
    }

    private fun reportPendingSelfTest() {
        val start = diag.getLong(KEY_SELFTEST_START, 0)
        if (start == 0L) return
        diag.edit().remove(KEY_SELFTEST_START).apply()
        worker.execute {
            val summary = LogReader.selfTestSummary(LogReader.read(start - 1000))
            mainHandler.post {
                dialog("Auto-teste FEXCore", Ui.text(this, summary, 12f).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) })
            }
        }
    }

    private fun showLog(since: Long) {
        worker.execute {
            val lines = LogReader.read(if (since > 0) since - 1000 else 0)
            val text = if (lines.isEmpty()) "(log vazio)" else lines.joinToString("\n")
            mainHandler.post {
                val view = Ui.text(this, text, 10f).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
                dialog("Log do núcleo (${lines.size} linhas)", view)
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PPSS55 log", text))
                showMessage("Log copiado para a área de transferência.")
            }
        }
    }

    // ---- games folder -------------------------------------------------------------------------------

    private fun onScanRequested() {
        if (!Environment.isExternalStorageManager()) {
            showMessage("Conceda o acesso a todos os arquivos antes de procurar jogos.")
            return
        }
        scanGames(settings.gamesRoot)
    }

    private fun ensureDefaultGamesFolder() {
        if (settings.gamesRoot != LauncherSettings.DEFAULT_GAMES_ROOT) return
        val folder = File(LauncherSettings.DEFAULT_GAMES_ROOT)
        if (!folder.isDirectory && !folder.mkdirs()) {
            showMessage("Não foi possível criar ${folder.path}; escolha uma pasta de jogos.")
        }
    }

    /** Storage Access Framework folder picker, opened at the current games folder. */
    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        StoragePaths.nearestExistingFolder(settings.gamesRoot)
            ?.let(StoragePaths::initialUriFor)
            ?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        try {
            startActivityForResult(intent, REQUEST_PICK_GAMES_FOLDER)
        } catch (e: ActivityNotFoundException) {
            showMessage("Nenhum seletor de pastas disponível neste aparelho.")
        }
    }

    @Deprecated("Platform Activity result API; this launcher has no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GpuDriverPanel.REQUEST_PICK_DRIVER && resultCode == RESULT_OK) {
            data?.data?.let(drivers::onPicked)
            return
        }
        if (requestCode != REQUEST_PICK_GAMES_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = StoragePaths.treeUriToPath(uri)
        if (path == null) {
            showMessage("Escolha uma pasta no armazenamento do aparelho ou cartão SD (pastas de nuvem não funcionam).")
            return
        }
        // Keep the grant across reboots and remember both the URI and the real path.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        settings.gamesTreeUri = uri.toString()
        settings.gamesRoot = path
        folderPath.text = path
        onScanRequested()
    }

    private fun scanGames(root: String) {
        val request = ++scanRequest
        showMessage("Procurando em $root …")
        worker.execute {
            val result = GameLibrary.scan(File(root))
            mainHandler.post {
                if (request == scanRequest && !isFinishing) renderScan(result)
            }
        }
    }

    private fun renderScan(result: ScanResult) {
        grid.games = (result as? ScanResult.Found)?.games.orEmpty()
        when (result) {
            is ScanResult.Failure -> showMessage(result.message)
            is ScanResult.Found -> when {
                result.games.isEmpty() ->
                    showMessage("Nenhum jogo aqui. Formatos suportados pelo núcleo: pasta extraída com sce_sys/param.json, imagem .exfat ou arquivo .zar (até 3 níveis de subpastas). Arquivos .pkg são listados, mas não rodam.")
                else -> {
                    val pkgs = result.games.count { !it.playable }
                    val playable = result.games.size - pkgs
                    showMessage(
                        "$playable jogo(s) jogável(is). Toque para iniciar; ⚙ ou segure para ajustes do jogo." +
                            if (pkgs > 0) " $pkgs arquivo(s) .pkg encontrados: não suportados (toque para saber por quê)." else "",
                    )
                }
            }
        }
    }

    private fun launch(game: GameEntry) {
        game.pkg?.let { pkg ->
            dialog("${game.title}: .pkg não suportado", Ui.text(this, pkg.explanation(), 14f))
            return
        }
        if (!NativeLibrary.isInstalled(this)) {
            showMessage(NativeLibrary.MISSING_MESSAGE)
            return
        }
        val options = LaunchOptions.resolve(
            globalResolution = settings.resolution,
            globalHud = settings.hudEnabled,
            globalTouch = settings.touchControlsEnabled,
            game = settings.gameSettings(game.path),
            globalRenderScale = settings.renderScale,
            globalGpuTurbo = settings.gpuTurbo,
        )
        val args = LaunchArgs.forGame(
            game.path, options.resolution, drivers.driverDirFor(game.path), options.renderScale, options.gpuTurbo,
        ) + LaunchArgs.extras(settings.presentMode, settings.shaderDiskCache, diag.getBoolean(KEY_PERF_STATS, false))
        diag.edit().putLong(KEY_LAST_LAUNCH, System.currentTimeMillis()).apply()
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(LaunchArgs.EXTRA_GAME_ARGS, args)
                .putExtra(LaunchArgs.EXTRA_GAME_PATH, game.path)
                // Launcher-wide audio buffer first: a game's own KYTY_AUDIO_LATENCY_MS line wins (later duplicates win).
                .putExtra(LaunchArgs.EXTRA_ENV, (listOf("${LauncherSettings.ENV_AUDIO_LATENCY}=${settings.audioLatencyMs}") + options.env.map { it.toString() }).toTypedArray())
                .putExtra(LaunchArgs.EXTRA_HUD, options.hud)
                .putExtra(LaunchArgs.EXTRA_TOUCH_CONTROLS, options.touchControls),
        )
    }

    private fun refreshStorageState() {
        storageBanner.visibility = if (Environment.isExternalStorageManager()) View.GONE else View.VISIBLE
        updateCoreChip()
    }

    private fun openAllFilesAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun showMessage(text: String) {
        if (::message.isInitialized) message.text = text
    }

    companion object {
        /** Set by WelcomeActivity: open the folder picker right away. */
        const val EXTRA_PICK_FOLDER = "com.ppss55.extra.PICK_FOLDER"
        private const val REQUEST_PICK_GAMES_FOLDER = 1
        private const val COVER_DP = 150
        private const val DIAG_PREFS = "ppss55_diag"
        private const val KEY_SELFTEST_START = "selftest_start"
        private const val KEY_LAST_LAUNCH = "last_launch"
        private const val KEY_PERF_STATS = "perf_stats"
    }
}
