package org.librestore

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tts: TtsManager
    private lateinit var root: FrameLayout
    private var downloadManager: DownloadInstallManager? = null
    private val backStack = ArrayDeque<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TtsManager(this)
        root = FrameLayout(this)

        // Captura qualquer crash e salva em arquivo para leitura posterior
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val msg = buildString {
                    append(ex.javaClass.name)
                    append(": ")
                    append(ex.message ?: "sem mensagem")
                    append("\n")
                    ex.stackTrace.take(5).forEach { append("  at $it\n") }
                }
                // Salva em arquivo legível
                val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "librestore_crash.txt")
                f.writeText(msg)
                runOnUiThread { mostrarErro(msg) }
                Thread.sleep(30000) // 30 segundos para dar tempo de ler
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, ex)
        }
        root.setBackgroundColor(Color.parseColor("#F5F5F5"))
        setContentView(root)
        verificarPermissoes()
    }

    // ── PERMISSÕES ───────────────────────────────────────────────────────────

    private fun verificarPermissoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 6-9: pede WRITE_EXTERNAL_STORAGE em runtime
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    101
                )
                return
            }
        }
        // Android 10+ não precisa de permissão para DownloadManager
        mostrarCarregando()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            mostrarCarregando()
        }
    }

    // ── TELA 0: CARREGANDO ÍNDICE ────────────────────────────────────────────

    private fun mostrarCarregando() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 0, 48, 0)
        }
        val tv = TextView(this).apply {
            text = "Baixando índice do repositório...\n(pode demorar alguns segundos)"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#212121"))
            contentDescription = "Baixando índice do repositório, aguarde"
        }
        val pb = ProgressBar(this)
        layout.addView(pb)
        layout.addView(tv)
        trocarTela(layout)
        tts.falar("Baixando índice do repositório. Aguarde.")

        thread {
            val ok = FDroidApi.carregarIndice(this)
            runOnUiThread {
                if (ok) {
                    mostrarCategorias()
                } else {
                    // Mostrar erro com botão de retry
                    tv.text = "Erro ao baixar índice.\nVerifique sua conexão."
                    tv.contentDescription = "Erro ao baixar índice. Verifique sua conexão."
                    pb.visibility = View.GONE
                    val btn = Button(this).apply {
                        text = "Tentar novamente"
                        contentDescription = "Botão tentar novamente"
                        setBackgroundColor(Color.parseColor("#1976D2"))
                        setTextColor(Color.WHITE)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 32, 0, 0) }
                        layoutParams = lp
                    }
                    btn.setOnClickListener { mostrarCarregando() }
                    layout.addView(btn)
                    tts.falar("Erro ao baixar índice. Verifique sua conexão e toque em tentar novamente.")
                }
            }
        }
    }

    // ── TELA 1: CATEGORIAS ───────────────────────────────────────────────────

    private fun mostrarCategorias() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(criarBarra("LibreStore", mostrarVoltar = false))

        // Campo de busca
        val busca = SearchView(this).apply {
            queryHint = "Buscar aplicativos..."
            contentDescription = "Campo de busca de aplicativos"
            setPadding(16, 8, 16, 8)
        }
        busca.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (query.length >= 2) mostrarResultadoBusca(query)
                return true
            }
            override fun onQueryTextChange(q: String) = false
        })
        layout.addView(busca)

        // Botão recarregar índice
        val btnRecarregar = Button(this).apply {
            text = "Recarregar índice"
            contentDescription = "Botão recarregar índice do repositório"
            setBackgroundColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 4, 16, 4) }
            layoutParams = lp
        }
        btnRecarregar.setOnClickListener {
            FDroidApi.limparCache(this)
            mostrarCarregando()
        }
        layout.addView(btnRecarregar)

        val btnGerenciar = Button(this).apply {
            text = "Gerenciar APKs baixados"
            contentDescription = "Botao gerenciar APKs baixados"
            setBackgroundColor(Color.parseColor("#E65100"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16, 4, 16, 4) }
            layoutParams = lp
        }
        btnGerenciar.setOnClickListener { mostrarGerenciarApks() }
        layout.addView(btnGerenciar)

        // Lista de categorias
        val categorias = FDroidApi.CATEGORIES
        val adapter = object : ArrayAdapter<FDroidCategory>(
            this, android.R.layout.simple_list_item_1, categorias
        ) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val tv = super.getView(pos, cv, parent) as TextView
                tv.text = categorias[pos].name
                tv.setPadding(32, 24, 32, 24)
                tv.textSize = 17f
                tv.setTextColor(Color.parseColor("#212121"))
                tv.contentDescription = "Categoria ${categorias[pos].name}"
                return tv
            }
        }
        val lista = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 1
            contentDescription = "Lista de categorias"
        }
        lista.setOnItemClickListener { _, _, pos, _ ->
            try {
                val cat = categorias[pos]
                tts.falar("Abrindo categoria ${cat.name}")
                mostrarAppsCategoria(cat)
            } catch (e: Throwable) {
                mostrarErro("Erro ao abrir categoria: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        layout.addView(lista, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        trocarTela(layout)
        backStack.clear()
        tts.falar("${categorias.size - 1} categorias disponíveis. Use o campo de busca ou selecione uma categoria.")
    }

    // ── TELA 2: LISTA DE APPS ────────────────────────────────────────────────

    private fun mostrarAppsCategoria(categoria: FDroidCategory) {
        backStack.clear()
        empilhar { mostrarCategorias() }
        val layout = criarLayoutLista("Categoria: ${categoria.name}") {
            FDroidApi.getAppsByCategory(categoria.id)
        }
        trocarTela(layout)
    }

    private fun mostrarResultadoBusca(query: String) {
        backStack.clear()
        empilhar { mostrarCategorias() }
        val layout = criarLayoutLista("Resultados: $query") {
            FDroidApi.searchApps(query)
        }
        trocarTela(layout)
    }

    private fun criarLayoutLista(titulo: String, carregarApps: () -> List<FDroidApp>): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(criarBarra(titulo, mostrarVoltar = true))

        val progresso = TextView(this).apply {
            text = "Carregando..."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, 48, 0, 0)
            contentDescription = "Carregando lista de aplicativos"
        }
        layout.addView(progresso, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val lista = ListView(this).apply {
            visibility = View.GONE
            contentDescription = "Lista de aplicativos"
        }
        layout.addView(lista, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        thread {
            val apps = try {
                carregarApps()
            } catch (e: Throwable) {
                runOnUiThread { mostrarErro("Erro ao carregar apps: ${e.message ?: e.javaClass.simpleName}") }
                return@thread
            }
            runOnUiThread {
                progresso.visibility = View.GONE
                if (apps.isEmpty()) {
                    progresso.text = "Nenhum resultado encontrado"
                    progresso.visibility = View.VISIBLE
                    tts.falar("Nenhum resultado encontrado")
                    return@runOnUiThread
                }

                // Paginação: mostra 100 por vez para não travar com 20k itens
                val PAGE = 100
                var paginaAtual = 0
                val totalPaginas = (apps.size + PAGE - 1) / PAGE

                fun paginaAtual() = apps.subList(
                    paginaAtual * PAGE,
                    minOf((paginaAtual + 1) * PAGE, apps.size)
                )

                fun atualizarLista() {
                    val pagina = paginaAtual()
                    val adapter = object : ArrayAdapter<FDroidApp>(
                        this, android.R.layout.simple_list_item_1, pagina
                    ) {
                        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                            val app = pagina[pos]
                            val container = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(24, 16, 24, 16)
                                contentDescription = "${app.name}. ${app.summary.take(60)}"
                            }
                            val tvNome = TextView(context).apply {
                                text = app.name
                                textSize = 16f
                                setTextColor(Color.parseColor("#212121"))
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            }
                            val tvDesc = TextView(context).apply {
                                text = app.summary.take(80)
                                textSize = 13f
                                setTextColor(Color.parseColor("#757575"))
                            }
                            container.addView(tvNome)
                            container.addView(tvDesc)
                            return container
                        }
                    }
                    lista.adapter = adapter
                    lista.setOnItemClickListener { _, _, pos, _ ->
                        try {
                            val app = pagina[pos]
                            tts.falar("Abrindo ${app.name}")
                            empilhar {
                                trocarTela(layout)
                                tts.falar("Lista de aplicativos")
                            }
                            mostrarDetalheApp(app)
                        } catch (e: Throwable) {
                            mostrarErro("Erro ao abrir app: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                }

                // Botões de navegação de página
                val navLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 4, 16, 4)
                }
                val btnAnterior = Button(this).apply {
                    text = "< Anterior"
                    setBackgroundColor(Color.parseColor("#455A64"))
                    setTextColor(Color.WHITE)
                    textSize = 12f
                }
                val tvPagina = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(Color.parseColor("#212121"))
                }
                val btnProximo = Button(this).apply {
                    text = "Proximo >"
                    setBackgroundColor(Color.parseColor("#455A64"))
                    setTextColor(Color.WHITE)
                    textSize = 12f
                }

                fun atualizarNav() {
                    tvPagina.text = "${paginaAtual + 1}/$totalPaginas"
                    tvPagina.contentDescription = "Pagina ${paginaAtual + 1} de $totalPaginas"
                    btnAnterior.isEnabled = paginaAtual > 0
                    btnProximo.isEnabled = paginaAtual < totalPaginas - 1
                }

                btnAnterior.setOnClickListener {
                    if (paginaAtual > 0) {
                        paginaAtual--
                        atualizarLista()
                        atualizarNav()
                        tts.falar("Pagina ${paginaAtual + 1}")
                    }
                }
                btnProximo.setOnClickListener {
                    if (paginaAtual < totalPaginas - 1) {
                        paginaAtual++
                        atualizarLista()
                        atualizarNav()
                        tts.falar("Pagina ${paginaAtual + 1}")
                    }
                }

                navLayout.addView(btnAnterior, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                navLayout.addView(tvPagina, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                navLayout.addView(btnProximo, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                layout.addView(navLayout)

                atualizarLista()
                atualizarNav()
                lista.visibility = View.VISIBLE
                tts.falar("${apps.size} aplicativos. Pagina 1 de $totalPaginas.")
            }
        }
        return layout
    }

    // ── TELA 3: DETALHE DO APP ───────────────────────────────────────────────

    private fun mostrarDetalheApp(app: FDroidApp) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(layout)

        layout.addView(criarBarra(app.name, mostrarVoltar = true))

        layout.addView(textView(app.name, 22f, Color.parseColor("#212121"), bold = true).apply {
            setPadding(24, 24, 24, 4)
        })
        if (!app.latestVersion.isNullOrBlank()) {
            layout.addView(textView("Versão: ${app.latestVersion}", 14f,
                Color.parseColor("#757575")).apply { setPadding(24, 4, 24, 4) })
        }
        if (app.apkSize > 0) {
            val tam = Formatter.formatFileSize(this, app.apkSize)
            layout.addView(textView("Tamanho: $tam", 14f,
                Color.parseColor("#757575")).apply { setPadding(24, 4, 24, 4) })
        }
        if (app.summary.isNotBlank()) {
            layout.addView(textView(app.summary, 16f, Color.parseColor("#424242")).apply {
                setPadding(24, 16, 24, 8)
            })
        }
        if (app.description.isNotBlank()) {
            layout.addView(textView(app.description.take(800), 15f,
                Color.parseColor("#616161")).apply { setPadding(24, 8, 24, 16) })
        }

        // Barra de progresso
        val barraContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            visibility = View.GONE
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            contentDescription = "Progresso do download"
        }
        val progressText = textView("Preparando download...", 14f, Color.parseColor("#1976D2")).apply {
            contentDescription = "Status do download"
        }
        barraContainer.addView(progressText)
        barraContainer.addView(progressBar)
        layout.addView(barraContainer)

        // Botão instalar
        val btnInstalar = Button(this).apply {
            text = if (app.latestApkUrl != null) "Instalar" else "APK indisponível"
            isEnabled = app.latestApkUrl != null
            contentDescription = "Botão instalar ${app.name}"
            setBackgroundColor(Color.parseColor("#1976D2"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(24, 16, 24, 8) }
            layoutParams = lp
        }
        layout.addView(btnInstalar)

        // Botão cancelar
        val btnCancelar = Button(this).apply {
            text = "Cancelar download"
            visibility = View.GONE
            contentDescription = "Botão cancelar download"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(24, 4, 24, 24) }
            layoutParams = lp
        }
        layout.addView(btnCancelar)

        btnInstalar.setOnClickListener {
            btnInstalar.isEnabled = false
            btnInstalar.text = "Baixando..."
            barraContainer.visibility = View.VISIBLE
            btnCancelar.visibility = View.VISIBLE
            downloadManager = DownloadInstallManager(
                context = this,
                tts = tts,
                onProgress = { percent, downloaded, total ->
                    progressBar.progress = percent
                    val tamBaixado = Formatter.formatFileSize(this, downloaded)
                    val tamTotal = Formatter.formatFileSize(this, total)
                    progressText.text = "$percent% — $tamBaixado de $tamTotal"
                    progressBar.contentDescription = "Download $percent por cento. $tamBaixado de $tamTotal"
                },
                onFinished = { sucesso ->
                    barraContainer.visibility = View.GONE
                    btnCancelar.visibility = View.GONE
                    btnInstalar.text = if (sucesso) "Instalar" else "Tentar novamente"
                    btnInstalar.isEnabled = true
                    if (!sucesso) Toast.makeText(this, "Erro ao baixar ${app.name}", Toast.LENGTH_SHORT).show()
                }
            )
            downloadManager!!.baixar(app)
        }

        btnCancelar.setOnClickListener {
            downloadManager?.cancelar()
            tts.falar("Download cancelado")
            barraContainer.visibility = View.GONE
            btnCancelar.visibility = View.GONE
            btnInstalar.text = "Instalar"
            btnInstalar.isEnabled = true
        }

        // Botão compartilhar link do APK
        if (app.latestApkUrl != null) {
            val btnCompartilhar = Button(this).apply {
                text = "Compartilhar link"
                contentDescription = "Botao compartilhar link do APK de ${app.name}"
                setBackgroundColor(Color.parseColor("#00796B"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(24, 4, 24, 4) }
                layoutParams = lp
            }
            btnCompartilhar.setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Link APK", app.latestApkUrl)
                clipboard.setPrimaryClip(clip)
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND)
                shareIntent.type = "text/plain"
                shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, app.name + " " + app.latestApkUrl)
                shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "Download: " + app.name)
                startActivity(android.content.Intent.createChooser(shareIntent, "Compartilhar link"))
                tts.falar("Link copiado e compartilhado")
            }
            layout.addView(btnCompartilhar)
        }

        trocarTela(scroll)

        val desc = buildString {
            append(app.name)
            if (!app.latestVersion.isNullOrBlank()) append(", versão ${app.latestVersion}")
            if (app.summary.isNotBlank()) append(". ${app.summary}")
        }
        tts.falar(desc)
    }

    // ── TELA GERENCIAR APKs ──────────────────────────────────────────────────

    private fun mostrarGerenciarApks() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        layout.addView(criarBarra("APKs baixados", mostrarVoltar = true))
        empilhar { mostrarCategorias() }

        // APKs no cacheDir interno (onde o app salva para instalar)
        val cacheApks = cacheDir.listFiles { f -> f.name.endsWith(".apk") }?.toList() ?: emptyList()

        if (cacheApks.isEmpty()) {
            val tv = textView("Nenhum APK em cache.", 16f, Color.parseColor("#757575")).apply {
                setPadding(24, 32, 24, 0)
                gravity = android.view.Gravity.CENTER
            }
            layout.addView(tv)
            tts.falar("Nenhum APK em cache.")
        } else {
            // Tamanho total
            val totalBytes = cacheApks.sumOf { it.length() }
            val totalFmt = android.text.format.Formatter.formatFileSize(this, totalBytes)
            val tvTotal = textView("${cacheApks.size} arquivo(s) — $totalFmt", 14f,
                Color.parseColor("#757575")).apply {
                setPadding(24, 16, 24, 8)
            }
            layout.addView(tvTotal)

            // Botão apagar tudo
            val btnApagarTudo = Button(this).apply {
                text = "Apagar todos os APKs"
                contentDescription = "Botao apagar todos os APKs em cache"
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(24, 4, 24, 16) }
                layoutParams = lp
            }
            layout.addView(btnApagarTudo)

            // Lista de APKs
            val scroll = android.widget.ScrollView(this)
            val listaLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(listaLayout)

            fun popularLista(arquivos: List<java.io.File>) {
                listaLayout.removeAllViews()
                arquivos.forEach { apk ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(24, 12, 16, 12)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        // Linha separadora
                        setBackgroundColor(Color.WHITE)
                    }
                    val tvNome = textView(
                        apk.nameWithoutExtension + "\n" +
                        android.text.format.Formatter.formatFileSize(this, apk.length()),
                        14f, Color.parseColor("#212121")
                    ).apply {
                        contentDescription = "${apk.nameWithoutExtension}, " +
                            android.text.format.Formatter.formatFileSize(this@MainActivity, apk.length())
                    }
                    val btnApagar = Button(this).apply {
                        text = "Apagar"
                        contentDescription = "Apagar APK ${apk.nameWithoutExtension}"
                        setBackgroundColor(Color.parseColor("#D32F2F"))
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(8, 0, 0, 0) }
                        layoutParams = lp
                    }
                    btnApagar.setOnClickListener {
                        apk.delete()
                        tts.falar("APK ${apk.nameWithoutExtension} apagado")
                        mostrarGerenciarApks()
                    }
                    row.addView(tvNome, LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(btnApagar)
                    listaLayout.addView(row)

                    // Divisor
                    val div = android.view.View(this).apply {
                        setBackgroundColor(Color.parseColor("#E0E0E0"))
                    }
                    listaLayout.addView(div, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1))
                }
            }

            popularLista(cacheApks)
            layout.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            btnApagarTudo.setOnClickListener {
                cacheApks.forEach { it.delete() }
                tts.falar("Todos os APKs apagados")
                mostrarGerenciarApks()
            }

            val plural = if (cacheApks.size == 1) "1 APK em cache" else "${cacheApks.size} APKs em cache"
            tts.falar("$plural. Total: $totalFmt")
        }

        trocarTela(layout)
    }

    // ── TELA DE ERRO ─────────────────────────────────────────────────────────

    private fun mostrarErro(msg: String) {
        tts.falar("Erro: $msg")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 0, 48, 0)
        }
        val tv = TextView(this).apply {
            text = "Erro: $msg"
            textSize = 16f
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.CENTER
            contentDescription = "Erro: $msg"
        }
        val btn = Button(this).apply {
            text = "Voltar"
            contentDescription = "Botão voltar"
            setBackgroundColor(Color.parseColor("#1976D2"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) }
            layoutParams = lp
        }
        btn.setOnClickListener { voltar() }
        layout.addView(tv)
        layout.addView(btn)
        trocarTela(layout)
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    private fun criarBarra(titulo: String, mostrarVoltar: Boolean): LinearLayout {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1976D2"))
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        if (mostrarVoltar) {
            val btn = Button(this).apply {
                text = "<"
                contentDescription = "Voltar"
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                textSize = 18f
                minWidth = 0; minimumWidth = 0
            }
            btn.setOnClickListener { voltar() }
            barra.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val tv = TextView(this).apply {
            text = titulo
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(16, 0, 0, 0)
            contentDescription = titulo
        }
        barra.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return barra
    }

    private fun textView(texto: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = texto
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun trocarTela(view: View) {
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun empilhar(voltar: () -> Unit) { backStack.addLast(voltar) }

    private fun voltar() { if (backStack.isNotEmpty()) backStack.removeLast().invoke() }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (backStack.isNotEmpty()) voltar() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadManager?.cancelar()
        tts.destruir()
    }
}
