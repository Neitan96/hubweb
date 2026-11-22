package br.com.hubweb

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

enum class Screen { LAUNCHER, BROWSER, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: ConfigRepo
    private lateinit var pool: WebPool
    private lateinit var telemetry: Telemetry
    private lateinit var tracker: UserTracker
    private lateinit var tips: TipsRepo
    private lateinit var updater: AppUpdater
    private var adiadoVersao by mutableStateOf("") // versão cujo aviso o usuário adiou ("Depois")
    private var autoVersao = "" // versão já tentada sozinha (device owner) nesta execução

    private var screen by mutableStateOf(Screen.LAUNCHER)
    private var current by mutableStateOf<Entry?>(null)
    private var calcEnabled by mutableStateOf(false)
    private var fabsOn by mutableStateOf<Set<String>>(emptySet())
    private var showFabDialog by mutableStateOf(false)
    private var fabsLocked by mutableStateOf(false)
    private var popupEntry by mutableStateOf<Entry?>(null)
    private var crachaItem by mutableStateOf<AppItem?>(null)
    private var crachaError by mutableStateOf<String?>(null)
    private var crachaIdx by mutableStateOf(0) // detecção "cracha" em uso (o item pode ter várias detecções)

    private val handler = Handler(Looper.getMainLooper())
    private var autoFeito = false // abrirAoEntrar já abriu o sistema nesta entrada no app
    private var lastInput = SystemClock.elapsedRealtime()

    // --- resultados de permissões / seletor de arquivos usados pelos WebViews ---
    private var cameraCb: ((Boolean) -> Unit)? = null
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraCb?.invoke(it); cameraCb = null
        }
    private val storageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Leitor de códigos (câmera): o resultado é digitado no campo focado do site que pediu.
    private var scanCallback: ((String) -> Unit)? = null
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val code = result.contents
        val cb = scanCallback
        scanCallback = null
        // Espera o app voltar ao primeiro plano antes de usar o código.
        if (!code.isNullOrEmpty() && cb != null) handler.postDelayed({ cb(code) }, 400)
    }

    /** Lê um código pela câmera e o digita no campo focado do sistema. */
    private fun scanFor(entry: Entry) = startScan { code ->
        try { KeyInjector.type(entry.webView, code, entry.item.leitorSufixo) } catch (_: Exception) {}
    }

    private fun startScan(onCode: (String) -> Unit) {
        scanCallback = onCode
        scanLauncher.launch(
            ScanOptions()
                .setPrompt("Aponte a câmera para o código")
                .setBeepEnabled(true)
                .setOrientationLocked(false)
                // Lista explícita (1D + QR e outros 2D) em vez de ALL_CODE_TYPES.
                .setDesiredBarcodeFormats(
                    ScanOptions.ONE_D_CODE_TYPES + listOf(
                        ScanOptions.QR_CODE, ScanOptions.DATA_MATRIX, ScanOptions.PDF_417, "AZTEC",
                    ),
                ),
        )
    }

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { telemetry.kick() }
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            fileCb = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ManagedConfig.apply(this, prefs) // URL e token vindos do MDM, antes de decidir a tela inicial
        resetSessionIfRebooted()
        repo = ConfigRepo(this, prefs).also {
            it.loadCache()
            // Lista nova pode ligar/desligar o kiosk na hora (só com a tela em primeiro plano).
            it.onApplied = {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) applyKiosk()
                applyFabDefaults() // itens flutuantes novos na lista
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) abrirAuto() // 1ª abertura: a lista acabou de chegar
                telemetry.onConfigApplied() // primeira abertura: a lista acabou de chegar, envia a telemetria já
                tips.refresh(gatilho = "config") // o bloco "dicas" pode ter aparecido, mudado de URL ou saído da lista
            }
        }
        tips = TipsRepo(this, prefs, repo).also { it.loadCache() }
        updater = AppUpdater(this, prefs, repo)
        // O MDM pode mudar a configuração gerenciada com o app aberto: aplica e busca a lista na hora.
        val filtroMdm = android.content.IntentFilter(android.content.Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiverMdm, filtroMdm, android.content.Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiverMdm, filtroMdm)
        tracker = UserTracker(prefs)
        UrlVars.ligar(tracker, repo, prefs) // variáveis da urlInicial: {usuario}, {versaoLista}...
        pool = WebPool(this, prefs, tracker)
        telemetry = Telemetry(applicationContext, prefs, repo) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
        telemetry.tracker = tracker
        telemetry.estadoApp = {
            val nulo = org.json.JSONObject.NULL
            fun iso(ms: Long) = if (ms > 0) DeviceStats.iso(ms) else nulo
            org.json.JSONObject().apply {
                put("app", org.json.JSONObject().apply {
                    put("tela", screen.name.lowercase())
                    put("sistemaAberto", current?.item?.nome ?: nulo)
                    put("naMemoria", pool.resumo())
                    // Há quanto tempo o app está rodando (processo) e há quanto não recebe toque nem tecla.
                    put("abertoHaSegundos", (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000)
                    put("ultimoToqueHaSegundos", (SystemClock.elapsedRealtime() - lastInput) / 1000)
                })
                put("lista", org.json.JSONObject().apply {
                    put("aplicadaEm", iso(repo.lastUpdate))
                    put("erro", repo.error ?: nulo)
                    put("versao", repo.versaoLista ?: nulo)
                    put("sistemas", repo.items.size)
                })
                put("dicas", org.json.JSONObject().apply {
                    put("aplicadaEm", iso(tips.lastUpdate))
                    put("erro", tips.error ?: nulo)
                })
            }
        }
        tracker.telemetry = telemetry
        calcEnabled = prefs.calcButton
        fabsOn = prefs.fabsOn
        fabsLocked = prefs.fabsLocked
        applyFabDefaults() // lista já em cache
        if (!prefs.permsAsked) {
            prefs.permsAsked = true
            requestAllPermissions()
        }
        // Primeiro uso (sem URL e sem senha da lista): abre direto nas configurações.
        screen = if (prefs.configUrl.isBlank() && !prefs.hasPassword) Screen.SETTINGS else Screen.LAUNCHER

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.BROWSER -> {
                        val wv = current?.webView
                        if (wv != null && wv.canGoBack()) wv.goBack() else closeBrowser()
                    }
                    Screen.SETTINGS -> screen = Screen.LAUNCHER
                    Screen.LAUNCHER -> if (!prefs.kiosk) finish()
                }
            }
        })

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                // Gatilho "hub": toda vez que o launcher aparece (ao abrir o app e ao voltar de um sistema ou das configurações).
                LaunchedEffect(screen) {
                    if (screen == Screen.LAUNCHER) {
                        repo.refresh(gatilho = "hub")
                        tips.refresh(gatilho = "hub")
                    }
                }
                // Atualização do app: device owner instala sozinho (no hub, para não derrubar um sistema em uso);
                // nos demais aparelhos o hub mostra o aviso.
                val novo = updater.nova
                LaunchedEffect(screen) {
                    if (screen == Screen.LAUNCHER) updater.verificar(gatilho = "hub")
                }
                LaunchedEffect(screen, novo?.versao, updater.disponivel) {
                    if (screen == Screen.LAUNCHER && novo != null && updater.disponivel && updater.deviceOwner &&
                        autoVersao != novo.versao
                    ) {
                        autoVersao = novo.versao
                        updater.instalar()
                    }
                }
                AppUpdateDialog(
                    updater,
                    mostrar = screen == Screen.LAUNCHER && !updater.deviceOwner && novo?.versao != adiadoVersao,
                    onDepois = { adiadoVersao = novo?.versao ?: "" },
                )
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (screen) {
                        Screen.LAUNCHER -> LauncherScreen(
                            repo = repo,
                            tips = tips,
                            verify = prefs::verifyPassword,
                            hasPassword = { prefs.hasPassword },
                            floatingOn = calcShown() || repo.floating.any { it.obrigatorio || it.id in fabsOn },
                            onFloating = { showFabDialog = true },
                            onOpen = ::openApp,
                            onRefresh = { repo.refresh(force = true) },
                            onSettings = { screen = Screen.SETTINGS },
                        )
                        Screen.BROWSER -> current?.let { e ->
                            BrowserScreen(
                                entry = e,
                                tips = tips,
                                onLauncher = ::closeBrowser,
                                onBack = { if (e.webView.canGoBack()) e.webView.goBack() },
                                onReload = {
                                    if (e.error != null || e.webView.url == null) e.webView.loadUrl(e.item.startUrl(this@MainActivity))
                                    else e.webView.reload()
                                },
                                onHome = { e.webView.loadUrl(e.item.startUrl(this@MainActivity)) },
                                onScan = if (e.item.leitorCamera) ({ scanFor(e) }) else null,
                                onUser = if (crachaIndex(e.item) >= 0) ({ askCracha(e.item) }) else null,
                            )
                        }
                        Screen.SETTINGS -> SettingsScreen(
                            prefs = prefs,
                            repo = repo,
                            telemetry = telemetry,
                            tracker = tracker,
                            tips = tips,
                            updater = updater,
                            onSaved = {
                                applyKiosk()
                                repo.refresh(force = true)
                                screen = Screen.LAUNCHER
                            },
                            onRefresh = {
                                repo.refresh(force = true)
                                tips.refresh(force = true)
                            },
                            onClose = { screen = Screen.LAUNCHER },
                            onExitApp = ::exitApp,
                        )
                    }
                    // Botão flutuante da calculadora (escolha do usuário, sem senha); fica sobre o launcher e os sistemas.
                    if (calcShown() && screen != Screen.SETTINGS) {
                        CalculatorFab(
                            prefs = prefs,
                            onHide = { changeCalc(false) },
                            canHide = !repo.calculadora.obrigatoria,
                            travada = repo.calculadora.travada,
                            posicao = repo.calculadora.posicao,
                            userLocked = fabsLocked,
                        )
                    }
                    // URLs da lista como botões flutuantes: "obrigatorio" fica sempre ligado; as demais, por escolha do usuário.
                    if (screen != Screen.SETTINGS) {
                        repo.floating.filter { it.obrigatorio || it.id in fabsOn }.forEachIndexed { i, item ->
                            key(item.id) { ItemFab(prefs, item, slot = i + 1, userLocked = fabsLocked) { openPopup(item) } }
                        }
                    }
                    popupEntry?.let { pe ->
                        PopupBrowser(
                            pe,
                            tips,
                            onClose = ::closePopup,
                            onScan = if (pe.item.leitorCamera) ({ scanFor(pe) }) else null,
                            onUser = if (crachaIndex(pe.item) >= 0) ({ askCracha(pe.item) }) else null,
                        )
                    }
                    if (showFabDialog) {
                        FloatingButtonsDialog(
                            calcEnabled = calcEnabled,
                            calcAvailable = repo.calculadora.habilitada,
                            calcForced = repo.calculadora.obrigatoria,
                            onCalc = ::changeCalc,
                            items = repo.floating,
                            enabledIds = fabsOn,
                            onToggle = { item, on -> changeFab(item, on) },
                            locked = fabsLocked,
                            onLock = { fabsLocked = it; prefs.fabsLocked = it },
                            onDismiss = { showFabDialog = false },
                        )
                    }
                    // Identificação por crachá (usuario.tipo = "cracha").
                    crachaItem?.let { item ->
                        CrachaDialog(
                            sistema = item.nome,
                            required = item.usuarios.getOrNull(crachaIdx)?.crachaObrigatorio == true &&
                                tracker.get(item, crachaIdx) == null,
                            error = crachaError,
                            onSubmit = { submitCracha(item, it) },
                            onScan = { startScan { code -> submitCracha(item, code.trim()) } },
                            onCancel = { cancelCracha(item) },
                        )
                    }
                }
            }
        }
    }

    private val receiverMdm = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (ManagedConfig.apply(this@MainActivity, prefs)) {
                repo.refresh(force = true)
                tips.refresh(force = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (repo.abrirAoEntrar?.quando == "sempre") autoFeito = false // voltou ao app: pode abrir de novo
        if (ManagedConfig.apply(this, prefs)) { // mudou enquanto o app estava parado
            repo.refresh(force = true)
            tips.refresh(force = true)
        }
        // Gatilho "abrir": o app foi aberto ou trazido para a frente. Cada busca só acontece se a lista permitir
        // esse gatilho e se o intervalo mínimo (atualizacao.lista / atualizacao.dicas) já passou.
        repo.refresh(gatilho = "abrir")
        tips.refresh(gatilho = "abrir")
        updater.verificar(gatilho = "abrir")
    }

    override fun onResume() {
        super.onResume()
        applyKiosk()
        current?.takeIf { screen == Screen.BROWSER }?.webView?.onResume()
        popupEntry?.webView?.onResume()
        AppStats.visivel(true)
        abrirAuto()
        telemetry.start()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        AppStats.visivel(false)
        telemetry.stop()
        pool.pauseAll()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiverMdm) } catch (_: Exception) {}
        UrlVars.limpar()
        handler.removeCallbacks(tick)
        pool.destroyAll()
        super.onDestroy()
    }

    // Toques e teclas (inclusive leitor de código de barras em modo teclado) zeram o timeout.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        return super.dispatchKeyEvent(event)
    }

    private val tick = object : Runnable {
        override fun run() {
            val min = prefs.timeoutMin
            val idleMs = SystemClock.elapsedRealtime() - lastInput
            if (min > 0 && idleMs > min * 60_000L) {
                tracker.onTimeout(repo.items) // aparelho compartilhado: desliga os usuários por crachá
                if (screen == Screen.BROWSER) closeBrowser()
                else if (screen == Screen.SETTINGS) screen = Screen.LAUNCHER
            }
            handler.postDelayed(this, 15_000)
        }
    }

    /**
     * `abrirAoEntrar` da lista: abre o sistema escolhido ao entrar no app. Só age no launcher (não tira o usuário das
     * Configurações nem de um sistema), uma vez por entrada, e espera o crachá/primeiro uso resolver (sem lista, sem sistema).
     */
    private fun abrirAuto() {
        val a = repo.abrirAoEntrar ?: return
        if (autoFeito || screen != Screen.LAUNCHER || crachaItem != null) return
        val item = repo.items.firstOrNull { it.nome.equals(a.sistema, ignoreCase = true) } ?: return
        autoFeito = true
        openApp(item, a.url)
    }

    /** A calculadora aparece: a lista a habilita e ela é obrigatória ou o usuário a ligou. */
    private fun calcShown(): Boolean {
        val c = repo.calculadora
        return c.habilitada && (c.obrigatoria || calcEnabled)
    }

    private fun changeCalc(on: Boolean) {
        calcEnabled = on
        prefs.calcButton = on
    }

    /**
     * O Android não pede permissões na instalação; o jeito mais próximo é pedir tudo na primeira abertura:
     * localização (telemetria), câmera (sistemas com câmera) e, no Android 9 ou anterior, armazenamento (downloads).
     * O que for negado continua sendo pedido sob demanda quando o recurso for usado.
     */
    private fun requestAllPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) locationLauncher.launch(wanted.toTypedArray())
    }

    /**
     * Botões flutuantes opcionais: na primeira vez que um item aparece na lista, `flutuanteInicial` decide se já vem
     * ligado; depois vale só a escolha do usuário (mudar o JSON não religa nem desliga o que ele escolheu).
     * Idem para a calculadora com `calculadoraInicial`. Itens `obrigatorio` ficam sempre ligados.
     */
    private fun applyFabDefaults() {
        val seen = prefs.fabsSeen
        var novos = seen
        var on = fabsOn
        for (item in repo.floating) {
            if (item.id in novos) continue
            novos = novos + item.id
            if (item.flutuanteInicial && !item.obrigatorio) on = on + item.id
        }
        if (novos != seen) {
            prefs.fabsSeen = novos
            if (on != fabsOn) {
                fabsOn = on
                prefs.fabsOn = on
            }
        }
        val calc = repo.calculadora.inicial
        if (calc != null && !prefs.calcInitialApplied) {
            prefs.calcInitialApplied = true
            changeCalc(calc)
        }
    }

    private fun changeFab(item: AppItem, on: Boolean) {
        fabsOn = if (on) fabsOn + item.id else fabsOn - item.id
        prefs.fabsOn = fabsOn
    }

    private fun openPopup(item: AppItem) {
        val e = pool.popup(item)
        popupEntry = e
        pool.setPopupActive(e)
        if (needsCracha(item)) askCracha(item)
    }

    // --- identificação por crachá ---

    /** Posição da detecção "cracha" do item (ou -1). */
    private fun crachaIndex(item: AppItem) = item.usuarios.indexOfFirst { it.tipo == "cracha" }

    private fun needsCracha(item: AppItem): Boolean {
        val idx = crachaIndex(item)
        return idx >= 0 && tracker.get(item, idx) == null
    }

    private fun askCracha(item: AppItem) {
        crachaError = null
        crachaIdx = crachaIndex(item)
        crachaItem = item
    }

    private fun submitCracha(item: AppItem, code: String) {
        val re = item.usuarios.getOrNull(crachaIdx)?.regex
        if (re != null && !runCatching { Regex(re).matches(code) }.getOrDefault(true)) {
            crachaError = "Formato inválido"
            return
        }
        crachaError = null
        crachaItem = null
        tracker.report(item, crachaIdx, code, "cracha", novo = true) // ler o mesmo crachá de novo é um novo login
    }

    private fun cancelCracha(item: AppItem) {
        crachaItem = null
        crachaError = null
        // Identificação obrigatória e ainda sem usuário: não deixa usar o sistema.
        if (item.usuarios.getOrNull(crachaIdx)?.crachaObrigatorio == true && tracker.get(item, crachaIdx) == null) {
            if (current?.item?.id == item.id) closeBrowser()
            if (popupEntry?.item?.id == item.id) closePopup()
        }
    }

    private fun closePopup() {
        popupEntry = null
        pool.setPopupActive(null)
    }

    private fun openApp(item: AppItem, abrirEm: String? = null) {
        val e = pool.open(item, abrirEm)
        current = e
        AppStats.abrirSistema(item.nome)
        screen = Screen.BROWSER
        requestedOrientation = when (item.orientacao) {
            "retrato" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "paisagem" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        lastInput = SystemClock.elapsedRealtime()
        pool.setActive(e)
        if (needsCracha(item)) askCracha(item)
    }

    private fun closeBrowser() {
        current = null
        AppStats.fecharSistema()
        screen = Screen.LAUNCHER
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        pool.setActive(null)
    }

    private fun exitApp() {
        try { stopLockTask() } catch (_: Exception) {}
        finishAndRemoveTask()
    }

    /** Fixa o app na tela (kiosk) conforme a configuração. Com device owner não há aviso nem saída pelo sistema. */
    private fun applyKiosk() {
        val am = getSystemService(ActivityManager::class.java)
        val locked = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        if (prefs.kiosk) {
            // Só fixa a tela como device owner. Sem ele o Android mostraria o aviso de como desafixar,
            // então o kiosk "simples" só esconde as barras e bloqueia o Voltar.
            val dpm = getSystemService(DevicePolicyManager::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(ComponentName(this, AdminReceiver::class.java), arrayOf(packageName))
                if (!locked) try { startLockTask() } catch (_: Exception) {}
            }
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            if (locked) try { stopLockTask() } catch (_: Exception) {}
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Cookies valem enquanto o aparelho não for reiniciado; no primeiro start após o boot são apagados. */
    private fun resetSessionIfRebooted() {
        val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val rebooted = if (bootCount >= 0) bootCount != prefs.bootCount
        else abs(bootTime - prefs.bootTime) > 120_000
        if (rebooted) {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            prefs.usersJson = "" // os usuários identificados valem enquanto os cookies valem
            CookieManager.getInstance().flush()
        }
        prefs.bootCount = bootCount
        prefs.bootTime = bootTime
    }

    // --- chamados pelo WebPool ---

    fun withCameraPermission(cb: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cb(true)
        } else {
            cameraCb = cb
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun pickFile(params: WebChromeClient.FileChooserParams, cb: ValueCallback<Array<Uri>>): Boolean {
        fileCb?.onReceiveValue(null)
        fileCb = cb
        return try {
            fileLauncher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            fileCb = null
            cb.onReceiveValue(null)
            false
        }
    }

    fun hasStoragePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun requestStoragePermission() = storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}
