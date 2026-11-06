package com.simple.cleaner

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var infoText: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanBtn: Button
    private lateinit var cleanBtn: Button
    private lateinit var cbJunk: CheckBox
    private lateinit var cbApk: CheckBox
    private lateinit var cbEmpty: CheckBox
    private lateinit var cbCache: CheckBox
    private lateinit var largeText: TextView

    private var junk = listOf<File>()
    private var apks = listOf<File>()
    private var emptyDirs = listOf<File>()
    private var large = listOf<File>()
    private var cacheSize = 0L

    private val junkExt = setOf("tmp", "temp", "log", "bak", "old", "dmp", "chk", "thumb", "cache")
    private val largeLimit = 100L * 1024 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshInfo()
    }

    override fun onResume() {
        super.onResume()
        if (::infoText.isInitialized) refreshInfo()
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Simple Cleaner"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        infoText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(infoText)

        scanBtn = Button(this).apply {
            text = "SCAN"
            setOnClickListener { startScan() }
        }
        root.addView(scanBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
        }
        root.addView(progress)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        cbJunk = checkbox("File sampah (.tmp, .log, thumbnail, dll)", root)
        cbApk = checkbox("File APK sisa instalasi", root)
        cbEmpty = checkbox("Folder kosong", root)
        cbCache = checkbox("Cache Simple Cleaner", root)

        largeText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(largeText)

        cleanBtn = Button(this).apply {
            text = "BERSIHKAN"
            isEnabled = false
            setOnClickListener { startClean() }
        }
        root.addView(cleanBtn)

        root.addView(Button(this).apply {
            text = "Setelan penyimpanan HP (hapus cache app lain)"
            setOnClickListener { openStorageSettings() }
        })
    }

    private fun checkbox(label: String, parent: LinearLayout): CheckBox {
        val cb = CheckBox(this).apply {
            text = label
            isChecked = true
            isEnabled = false
        }
        parent.addView(cb)
        return cb
    }

    private fun refreshInfo() {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        infoText.text = "Penyimpanan: ${fmt(total - free)} terpakai dari ${fmt(total)} (sisa ${fmt(free)})\n" +
                "RAM: ${fmt(mi.totalMem - mi.availMem)} terpakai dari ${fmt(mi.totalMem)}"
    }

    private fun fmt(b: Long): String = Formatter.formatShortFileSize(this, b)

    // ---------- Permission ----------
    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Aktifkan 'Izinkan akses semua file', lalu kembali dan tekan SCAN", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startScan()
    }

    private fun openStorageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- Scan ----------
    private fun startScan() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        scanBtn.isEnabled = false
        cleanBtn.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        status.text = "Sedang scan..."

        Thread {
            val j = mutableListOf<File>()
            val a = mutableListOf<File>()
            val e = mutableListOf<File>()
            val l = mutableListOf<File>()
            val root = Environment.getExternalStorageDirectory()
            try {
                walk(root, 0, j, a, e, l)
            } catch (t: Throwable) {
            }
            val cache = dirSize(cacheDir) + (externalCacheDir?.let { dirSize(it) } ?: 0L)
            ui.post {
                junk = j; apks = a; emptyDirs = e
                large = l.sortedByDescending { it.length() }.take(10)
                cacheSize = cache
                showResult()
            }
        }.start()
    }

    /** Return true jika folder kosong (setelah di-scan). */
    private fun walk(
        dir: File, depth: Int,
        j: MutableList<File>, a: MutableList<File>, e: MutableList<File>, l: MutableList<File>
    ): Boolean {
        val children = dir.listFiles() ?: return false
        if (children.isEmpty()) {
            if (depth >= 2) e.add(dir)
            return depth >= 2
        }
        var allGone = true
        for (f in children) {
            if (f.isDirectory) {
                // Lewati folder data app Android (tidak bisa/tidak aman)
                if (depth == 0 && f.name.equals("Android", true)) { allGone = false; continue }
                val name = f.name.lowercase()
                if (name == ".thumbnails" || name == "thumbnails" && depth > 0 || name == ".trash" || name == ".trashed") {
                    f.walkTopDown().filter { it.isFile }.forEach { j.add(it) }
                    allGone = false
                    continue
                }
                if (!walk(f, depth + 1, j, a, e, l)) allGone = false
            } else {
                allGone = false
                val ext = f.extension.lowercase()
                when {
                    ext in junkExt -> j.add(f)
                    ext == "apk" -> a.add(f)
                    f.length() >= largeLimit -> l.add(f)
                }
            }
        }
        if (allGone && depth >= 2) e.add(dir)
        return allGone
    }

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun showResult() {
        progress.visibility = android.view.View.GONE
        scanBtn.isEnabled = true
        val junkSize = junk.sumOf { it.length() }
        val apkSize = apks.sumOf { it.length() }

        cbJunk.text = "File sampah: ${junk.size} file (${fmt(junkSize)})"
        cbApk.text = "File APK: ${apks.size} file (${fmt(apkSize)})"
        cbEmpty.text = "Folder kosong: ${emptyDirs.size}"
        cbCache.text = "Cache Simple Cleaner: ${fmt(cacheSize)}"
        listOf(cbJunk, cbApk, cbEmpty, cbCache).forEach { it.isEnabled = true }

        largeText.text = if (large.isEmpty()) "Tidak ada file besar (>100 MB)."
        else "File besar (cek & hapus manual lewat File Manager):\n" +
                large.joinToString("\n") { "• ${fmt(it.length())}  ${it.name}" }

        status.text = "Scan selesai."
        cleanBtn.isEnabled = true
    }

    // ---------- Clean ----------
    private fun startClean() {
        cleanBtn.isEnabled = false
        scanBtn.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        status.text = "Membersihkan..."

        val doJunk = cbJunk.isChecked
        val doApk = cbApk.isChecked
        val doEmpty = cbEmpty.isChecked
        val doCache = cbCache.isChecked

        Thread {
            var freed = 0L
            fun del(list: List<File>) = list.forEach {
                val s = it.length()
                if (it.delete()) freed += s
            }
            if (doJunk) del(junk)
            if (doApk) del(apks)
            if (doEmpty) emptyDirs.sortedByDescending { it.path.length }.forEach { it.delete() }
            if (doCache) {
                freed += cacheSize
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            }
            ui.post {
                progress.visibility = android.view.View.GONE
                scanBtn.isEnabled = true
                status.text = "Selesai! Ruang dibebaskan: ${fmt(freed)}"
                junk = emptyList(); apks = emptyList(); emptyDirs = emptyList(); cacheSize = 0
                listOf(cbJunk, cbApk, cbEmpty, cbCache).forEach { it.isEnabled = false }
                largeText.text = ""
                refreshInfo()
            }
        }.start()
    }
}
