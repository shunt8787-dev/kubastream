package com.example.apkcollection

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var adminKeyInput: EditText
    private lateinit var uploadButton: View
    private lateinit var donatePanel: View
    private lateinit var adapter: ApkAdapter

    private var tapCount = 0
    private var lastTapTime = 0L
    private var pendingInstallFile: File? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) uploadFile(uri)
    }

    private val baseUrl: String
        get() = prefs.getString("server_base_url", "")?.trimEnd('/') ?: ""

    private val adminKey: String
        get() = prefs.getString("admin_key", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            prefs = getSharedPreferences("kubastream", Context.MODE_PRIVATE)

            val logo = findViewById<TextView>(R.id.logo)
            settingsPanel = findViewById(R.id.settingsPanel)
            serverUrlInput = findViewById(R.id.serverUrlInput)
            adminKeyInput = findViewById(R.id.adminKeyInput)
            uploadButton = findViewById(R.id.uploadApkButton)
            donatePanel = findViewById(R.id.donatePanel)
            swipeRefresh = findViewById(R.id.swipeRefresh)
            recycler = findViewById(R.id.apkList)

            applyLogoGradient(logo)
            serverUrlInput.setText(prefs.getString("server_base_url", ""))
            adminKeyInput.setText(adminKey)

            adapter = ApkAdapter(
                emptyList(),
                onClick = { onApkTapped(it) },
                onRename = { onRenameTapped(it) },
                onDelete = { onDeleteTapped(it) }
            )
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = adapter
            setUnlockedState(adminKey.isNotBlank())

            uploadButton.setOnClickListener { pickFileLauncher.launch("*/*") }

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
                val url = serverUrlInput.text.toString().trim()
                val key = adminKeyInput.text.toString().trim()
                prefs.edit()
                    .putString("server_base_url", url)
                    .putString("admin_key", key)
                    .apply()

                if (key.isBlank()) {
                    setUnlockedState(false)
                    Toast.makeText(this, "Server saved", Toast.LENGTH_SHORT).show()
                    loadFiles()
                } else {
                    verifyAdminKey(url, key)
                }
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

    private fun setUnlockedState(value: Boolean) {
        adapter.setUnlocked(value)
        uploadButton.visibility = if (value) View.VISIBLE else View.GONE
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
            adapter.update(emptyList())
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
                    adapter.update(files)
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

    private fun verifyAdminKey(url: String, key: String) {
        thread {
            try {
                val conn = URL("${url.trimEnd('/')}/api/verify").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Kuba-Key", key)
                conn.connectTimeout = 10000
                val code = conn.responseCode
                runOnUiThread {
                    if (code in 200..299) {
                        setUnlockedState(true)
                        Toast.makeText(this, "Unlocked, delete/rename/upload enabled", Toast.LENGTH_SHORT).show()
                    } else {
                        setUnlockedState(false)
                        Toast.makeText(this, "Wrong key, browsing only", Toast.LENGTH_LONG).show()
                    }
                    loadFiles()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setUnlockedState(false)
                    Toast.makeText(this, "Couldn't verify key: ${e.message}", Toast.LENGTH_LONG).show()
                    loadFiles()
                }
            }
        }
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

    private fun getFileName(uri: Uri): String {
        var name = "upload.apk"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.let { name = it }
            }
        }
        return name
    }

    private fun uploadFile(uri: Uri) {
        val fileName = getFileName(uri)
        if (!fileName.lowercase().endsWith(".apk")) {
            Toast.makeText(this, "Only .apk files can be uploaded", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Uploading $fileName", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Couldn't read file")

                val boundary = "----KubaBoundary${System.currentTimeMillis()}"
                val conn = URL("$baseUrl/api/upload").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("X-Kuba-Key", adminKey)
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.connectTimeout = 15000
                conn.readTimeout = 60000

                conn.outputStream.use { out ->
                    val head = "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                        "Content-Type: application/vnd.android.package-archive\r\n\r\n"
                    out.write(head.toByteArray())
                    out.write(bytes)
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }

                val code = conn.responseCode
                runOnUiThread {
                    if (code in 200..299) {
                        Toast.makeText(this, "Uploaded $fileName", Toast.LENGTH_SHORT).show()
                        loadFiles()
                    } else if (code == 401) {
                        setUnlockedState(false)
                        Toast.makeText(this, "Key no longer valid, unlock again", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Upload failed ($code)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun onDeleteTapped(apk: RemoteApk) {
        AlertDialog.Builder(this)
            .setTitle("Delete file")
            .setMessage("Delete \"${apk.key}\"?")
            .setPositiveButton("Delete") { _, _ -> deleteRemoteFile(apk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRemoteFile(apk: RemoteApk) {
        thread {
            try {
                val conn = URL("$baseUrl/api/files/${Uri.encode(apk.key)}").openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.setRequestProperty("X-Kuba-Key", adminKey)
                conn.connectTimeout = 10000
                val code = conn.responseCode
                runOnUiThread {
                    if (code in 200..299) {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        loadFiles()
                    } else if (code == 401) {
                        setUnlockedState(false)
                        Toast.makeText(this, "Key no longer valid, unlock again", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Delete failed ($code)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun onRenameTapped(apk: RemoteApk) {
        val input = EditText(this)
        input.setText(apk.key)
        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setMessage("New name for \"${apk.key}\" (must end in .apk)")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != apk.key) {
                    renameRemoteFile(apk.key, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameRemoteFile(oldKey: String, newKey: String) {
        thread {
            try {
                val body = JSONObject().put("oldKey", oldKey).put("newKey", newKey).toString()
                val conn = URL("$baseUrl/api/rename").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-Kuba-Key", adminKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                runOnUiThread {
                    if (code in 200..299) {
                        Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show()
                        loadFiles()
                    } else if (code == 401) {
                        setUnlockedState(false)
                        Toast.makeText(this, "Key no longer valid, unlock again", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Rename failed ($code)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Rename failed: ${e.message}", Toast.LENGTH_LONG).show() }
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
