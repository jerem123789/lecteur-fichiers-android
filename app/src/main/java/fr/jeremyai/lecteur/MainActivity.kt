package fr.jeremyai.lecteur

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val HOTE = "appassets.androidplatform.net"
private const val ORIGINE = "https://$HOTE"
private const val PREFIXE_ENTRANT = "/__entrant/"
private const val MAX_FICHIERS_DOSSIER = 100

// Même politique que public/_headers sur le site.
private const val CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'; " +
    "worker-src 'self' blob:; connect-src 'self' blob: data:; img-src 'self' blob: data:; " +
    "media-src 'self' blob:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; " +
    "frame-src 'self' blob: about:; object-src 'none'; base-uri 'self'; form-action 'none'"

private val TYPES = mapOf(
    "html" to "text/html", "css" to "text/css", "js" to "text/javascript",
    "mjs" to "text/javascript", "wasm" to "application/wasm",
)

private class Entrant(val uri: Uri, val nom: String, val type: String)
private class Sortie(val fichier: File, val nom: String, val type: String)

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageChargee = false
    private val enAttente = mutableListOf<JSONObject>()
    private val entrants = ConcurrentHashMap<String, Entrant>()
    private val compteur = AtomicInteger()

    private var rappelChoix: ValueCallback<Array<Uri>>? = null
    private val sorties = ConcurrentHashMap<String, Sortie>()
    private val aEnregistrer = ArrayDeque<Sortie>()
    private var enregistrementEnCours: Sortie? = null

    private val choisir = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        rappelChoix?.onReceiveValue(uris.toTypedArray())
        rappelChoix = null
    }

    private val choisirDossier = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { arbre ->
        if (arbre == null) return@registerForActivityResult
        Thread {
            val fichiers = mutableListOf<Pair<Uri, String>>()
            parcourir(arbre, DocumentsContract.getTreeDocumentId(arbre), fichiers)
            runOnUiThread {
                if (fichiers.size >= MAX_FICHIERS_DOSSIER) {
                    Toast.makeText(this, "Dossier limité aux $MAX_FICHIERS_DOSSIER premiers fichiers", Toast.LENGTH_LONG).show()
                }
                fichiers.sortedBy { it.second.lowercase() }.forEach { (uri, nom) -> ajouter(uri, nom, "") }
                livrer()
            }
        }.start()
    }

    private val enregistrer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultat ->
        val sortie = enregistrementEnCours
        enregistrementEnCours = null
        val cible = resultat.data?.data
        if (sortie != null && cible != null) {
            try {
                contentResolver.openOutputStream(cible)?.use { flux ->
                    sortie.fichier.inputStream().use { it.copyTo(flux) }
                }
                Toast.makeText(this, "Fichier enregistré", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Enregistrement impossible", Toast.LENGTH_LONG).show()
            }
        }
        sortie?.fichier?.delete()
        enregistrerSuivant()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = creerWebView()
        // Android 15 dessine l'app sous les barres système : le cadre garde la page à l'écart.
        val cadre = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bleu))
            addView(web)
        }
        setContentView(cadre)
        ViewCompat.setOnApplyWindowInsetsListener(cadre) { vue, insets ->
            val barres = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vue.setPadding(barres.left, barres.top, barres.right, barres.bottom)
            insets
        }
        File(cacheDir, "sorties").deleteRecursively()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__lecteurRetour()") { ferme ->
                    if (ferme != "true") finish()
                }
            }
        })
        web.loadUrl("$ORIGINE/index.html")
        recevoir(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recevoir(intent)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun creerWebView() = WebView(this).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        addJavascriptInterface(Pont(), "LecteurAndroid")
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(vue: WebView, requete: WebResourceRequest): WebResourceResponse? {
            val url = requete.url
            if (url.scheme == "blob" || url.scheme == "data" || url.scheme == "about") return null
            if (url.host != HOTE) return reponse(403, "text/plain", ByteArrayInputStream(ByteArray(0)))
            // Le module DWG demande « js//libredwg-web.wasm » : un serveur web corrige seul, pas les assets.
            val chemin = url.path.orEmpty().replace(Regex("/+"), "/")
            if (chemin.startsWith(PREFIXE_ENTRANT)) return lireEntrant(chemin.removePrefix(PREFIXE_ENTRANT))
            val asset = chemin.trimStart('/').ifEmpty { "index.html" }
            return try {
                reponse(200, TYPES[asset.substringAfterLast('.')] ?: "application/octet-stream", assets.open(asset))
            } catch (e: Exception) {
                reponse(404, "text/plain", ByteArrayInputStream(ByteArray(0)))
            }
        }

        override fun shouldOverrideUrlLoading(vue: WebView, requete: WebResourceRequest): Boolean =
            requete.url.host != HOTE && requete.url.scheme != "blob"

        override fun onPageFinished(vue: WebView, url: String) {
            pageChargee = true
            livrer()
        }

        override fun onRenderProcessGone(vue: WebView, detail: RenderProcessGoneDetail): Boolean {
            Toast.makeText(this@MainActivity, "Fichier trop lourd pour ce téléphone", Toast.LENGTH_LONG).show()
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            vue: WebView,
            rappel: ValueCallback<Array<Uri>>,
            parametres: FileChooserParams,
        ): Boolean {
            rappelChoix?.onReceiveValue(null)
            rappelChoix = rappel
            choisir.launch(arrayOf("*/*"))
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d("Lecteur", "${message.messageLevel()} ${message.message()}")
            return true
        }
    }

    private fun reponse(code: Int, type: String, flux: InputStream) = WebResourceResponse(
        type, null, code, if (code == 200) "OK" else "Erreur",
        mapOf("Content-Security-Policy" to CSP, "X-Content-Type-Options" to "nosniff"),
        flux,
    )

    private fun lireEntrant(id: String): WebResourceResponse {
        val entrant = entrants.remove(id)
        val flux = entrant?.let {
            try {
                contentResolver.openInputStream(it.uri)
            } catch (e: Exception) {
                null
            }
        } ?: return reponse(404, "text/plain", ByteArrayInputStream(ByteArray(0)))
        return reponse(200, entrant.type, flux)
    }

    private fun recevoir(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(flux(intent))
            Intent.ACTION_SEND_MULTIPLE -> flux2(intent)
            else -> emptyList()
        }
        for (uri in uris) ajouter(uri, nomDe(uri), intent?.type ?: contentResolver.getType(uri).orEmpty())
        // Évite de rouvrir le même fichier si Android recrée l'écran.
        intent?.action = Intent.ACTION_MAIN
        livrer()
    }

    private fun ajouter(uri: Uri, nom: String, type: String) {
        val id = compteur.incrementAndGet().toString()
        entrants[id] = Entrant(uri, nom, "application/octet-stream")
        enAttente += JSONObject().put("url", PREFIXE_ENTRANT + id).put("nom", nom).put("type", type)
    }

    private fun parcourir(arbre: Uri, parent: String, fichiers: MutableList<Pair<Uri, String>>) {
        val enfants = DocumentsContract.buildChildDocumentsUriUsingTree(arbre, parent)
        val colonnes = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(enfants, colonnes, null, null, null)?.use { curseur ->
            while (curseur.moveToNext() && fichiers.size < MAX_FICHIERS_DOSSIER) {
                val id = curseur.getString(0)
                val nom = curseur.getString(1)
                when {
                    nom.startsWith(".") -> Unit
                    curseur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR -> parcourir(arbre, id, fichiers)
                    else -> fichiers += DocumentsContract.buildDocumentUriUsingTree(arbre, id) to nom
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun flux(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun flux2(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

    private fun nomDe(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
            }
        } catch (e: Exception) {
            Log.w("Lecteur", "Nom illisible", e)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
    }

    private fun livrer() {
        if (!pageChargee || enAttente.isEmpty()) return
        val liste = JSONArray(enAttente.toList()).toString()
        enAttente.clear()
        web.evaluateJavascript("window.__lecteurOuvrir($liste)", null)
    }

    private fun enregistrerSuivant() {
        if (enregistrementEnCours != null) return
        val sortie = aEnregistrer.removeFirstOrNull() ?: return
        enregistrementEnCours = sortie
        enregistrer.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(sortie.type.ifEmpty { "application/octet-stream" })
                .putExtra(Intent.EXTRA_TITLE, sortie.nom),
        )
    }

    private fun imprimer(titre: String) {
        val base = web.createPrintDocumentAdapter(titre)
        val adaptateur = object : PrintDocumentAdapter() {
            override fun onStart() = base.onStart()
            override fun onLayout(
                ancien: PrintAttributes?, nouveau: PrintAttributes?, annulation: CancellationSignal?,
                rappel: LayoutResultCallback, extras: Bundle?,
            ) = base.onLayout(ancien, nouveau, annulation, rappel, extras)
            override fun onWrite(
                pages: Array<out PageRange>?, destination: ParcelFileDescriptor?,
                annulation: CancellationSignal?, rappel: WriteResultCallback?,
            ) = base.onWrite(pages, destination, annulation, rappel)
            override fun onFinish() {
                base.onFinish()
                web.evaluateJavascript("window.__lecteurFinImpression()", null)
            }
        }
        getSystemService(PrintManager::class.java).print(titre, adaptateur, null)
    }

    private inner class Pont {
        @JavascriptInterface
        fun debut(nom: String, type: String): String {
            val dossier = File(cacheDir, "sorties").apply { mkdirs() }
            val id = compteur.incrementAndGet().toString()
            sorties[id] = Sortie(File(dossier, id), nom.substringAfterLast('/'), type)
            return id
        }

        @JavascriptInterface
        fun morceau(id: String, base64: String) {
            sorties[id]?.fichier?.appendBytes(Base64.decode(base64, Base64.NO_WRAP))
        }

        @JavascriptInterface
        fun fin(id: String) {
            val sortie = sorties.remove(id) ?: return
            runOnUiThread {
                aEnregistrer.addLast(sortie)
                enregistrerSuivant()
            }
        }

        @JavascriptInterface
        fun choisirDossier() = runOnUiThread { this@MainActivity.choisirDossier.launch(null) }

        @JavascriptInterface
        fun imprimer(titre: String) = runOnUiThread { this@MainActivity.imprimer(titre) }
    }
}
