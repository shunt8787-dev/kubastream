package com.example.apkcollection

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class RemoteApk(val key: String, val size: Long, val uploaded: String)

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var settingsPanel: View
    private lateinit var serverUrlInput: EditText
    private lateinit var donatePanel: View

    private var tapCount = 0
    private var lastTapTime = 0L
    private var pendingInstallFile: File? = null

    private val baseUrl: String
        get() = prefs.getString("server_base_url", "")?.trimEnd('/') ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            prefs = getSharedPreferences("kubastream", Context.MODE_PRIVATE)

            val logo = findViewById<TextView>(R.id.logo)
            settingsPanel = findViewById(R.id.settingsPanel)
            serverUrlInput = findViewById(R.id.serverUrlInput)
            donatePanel = findViewById(R.id.donatePanel)
            swipeRefresh = findViewById(R.id.swipeRefresh)
            recycler = findViewById(R.id.apkList)

            applyLogoGradient(logo)
            serverUrlInput.setText(prefs.getString("server_base_url", ""))

            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = ApkAdapter(emptyList()) { onApkTapped(it) }

            logo.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapTime > 1500) tapCount = 0
                lastTapTime = now
                tapCount++
                if (tapCount >= 5) {
                    tapCount = 0
                    settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }

            findViewById<View>(R.id.saveServerUrl).setOnClickListener {
                prefs.edit().putString("server_base_url", serverUrlInput.text.toString().trim()).apply()
                Toast.makeText(this, "Server saved", Toast.LENGTH_SHORT).show()
                loadFiles()
            }

            findViewById<View>(R.id.insertCoin).setOnClickListener {
                donatePanel.visibility = if (donatePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }

            swipeRefresh.setOnRefreshListener { loadFiles() }

            loadFiles()
        } catch (t: Throwable) {
            showCrashScreen(t)
        }
    }

    private fun showCrashScreen(t: Throwable) {
        val scrollView = android.widget.ScrollView(this)
        val tv = TextView(this)
        tv.text = "KUBASTREAM crashed during startup:\n\n" + android.util.Log.getStackTraceString(t)
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.setBackgroundColor(android.graphics.Color.BLACK)
        tv.setPadding(24, 24, 24, 24)
        tv.textSize = 12f
        scrollView.addView(tv)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstallFile
        if (file != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) {
            pendingInstallFile = null
            installApk(file)
        }
    }

    private fun applyLogoGradient(logo: TextView) {
        logo.post {
            try {
                val shader = LinearGradient(
                    0f, 0f, 0f, logo.textSize,
                    intArrayOf(
                        getColor(R.color.chrome),
                        getColor(R.color.logo_mid),
                        getColor(R.color.cyan),
                        getColor(R.color.pink)
                    ),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
                logo.paint.shader = shader
                logo.invalidate()
            } catch (t: Throwable) {
            }
        }
    }

    private fun loadFiles() {
        if (baseUrl.isBlank()) {
            swipeRefresh.isRefreshing = false
            Toast.makeText(this, "Tap the logo 5 times to set your server URL", Toast.LENGTH_LONG).show()
            (recycler.adapter as ApkAdapter).update(emptyList())
            return
        }
        swipeRefresh.isRefreshing = true
        thread {
            try {
                val json = httpGet("$baseUrl/api/files")
                val arr = JSONArray(json)
                val files = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    RemoteApk(o.getString("key"), o.optLong("size"), o.optString("uploaded"))
                }
                runOnUiThread {
                    (recycler.adapter as ApkAdapter).update(files)
                    swipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this, "Couldn't reach server: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.inputStream.use { input -> return input.bufferedReader().readText() }
    }

    private fun onApkTapped(apk: RemoteApk) {
        Toast.makeText(this, "Downloading ${apk.key}", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val dir = File(cacheDir, "apks").apply { mkdirs() }
                val outFile = File(dir, apk.key)
                val conn = URL("$baseUrl/download/${Uri.encode(apk.key)}").openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                runOnUiThread { proceedToInstall(outFile) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun proceedToInstall(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            Toast.makeText(this, "Allow installs from this app, then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        installApk(file)
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
