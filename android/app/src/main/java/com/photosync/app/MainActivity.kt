package com.photosync.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photosync.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val adapter = GalleryAdapter { item, excluded -> toggleExclude(item, excluded) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it } || hasMediaPerms()) loadGallery()
        else toast("Media permission is needed to find your photos")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        SyncWorker.ensureChannel(this)

        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter

        b.syncBtn.setOnClickListener { startSync("push") }
        b.pullBtn.setOnClickListener { startSync("pull") }
        b.settingsBtn.setOnClickListener { showSettings() }
        b.stopBtn.setOnClickListener {
            WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.ONE_TIME)
            toast("Stopping…")
        }

        setupBackupControls()
        observeWork()
        requestPerms()
    }

    // ---- permissions ---------------------------------------------------------

    private fun hasMediaPerms(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == 0 &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == 0
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == 0
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (hasMediaPerms()) loadGallery() else permLauncher.launch(perms.toTypedArray())
    }

    // ---- gallery (live) ------------------------------------------------------

    private fun loadGallery() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MediaRepo.scanAll(this@MainActivity) }
            b.subtitle.text = "${items.size} photos & videos on this device"
            val dao = Db.get(this@MainActivity).cache()
            // live updates: device list + synced flags + excluded set
            combine(dao.flowAll(), dao.flowExcluded()) { entries, excluded -> entries to excluded }
                .conflate()                       // a fast sync emits constantly; only the latest matters
                .collectLatest { (entries, excludedIds) ->
                    // Reconcile OFF the main thread. Index by mediaId so this is
                    // O(n) instead of an O(n^2) scan that pinned the UI thread.
                    val rows = withContext(Dispatchers.Default) {
                        val syncedById = HashMap<Long, Boolean?>(entries.size)
                        for (e in entries) syncedById[e.mediaId] = e.synced
                        val ex = excludedIds.toHashSet()
                        items.map { m -> GalleryRow(m, syncedById[m.id], m.id in ex) }   // null = not hashed yet
                    }
                    adapter.submitList(rows)
                    val syncedCount = rows.count { it.synced == true }
                    b.statusLine.text = "$syncedCount synced"
                }
        }
    }

    private fun toggleExclude(item: MediaItem, currentlyExcluded: Boolean) {
        lifecycleScope.launch {
            val dao = Db.get(this@MainActivity).cache()
            withContext(Dispatchers.IO) {
                if (currentlyExcluded) dao.unexclude(item.id)
                else dao.exclude(ExcludedItem(item.id))
            }
            toast(if (currentlyExcluded) "Included in sync" else "Excluded from sync")
        }
    }

    // ---- sync / pull ---------------------------------------------------------

    private fun startSync(mode: String) {
        if (!prefs.isConfigured) { showSettings(); toast("Set your server first"); return }
        SyncWorker.runNow(this, mode)
        toast(if (mode == "pull") "Pull started" else "Sync started")
    }

    private fun observeWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.ONE_TIME)
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        val phase = p.getString("phase") ?: "working"
                        val done = p.getInt("done", 0); val total = p.getInt("total", 0)
                        b.progress.visibility = android.view.View.VISIBLE
                        b.stopBtn.visibility = android.view.View.VISIBLE
                        b.progress.isIndeterminate = total == 0
                        if (total > 0) { b.progress.max = total; b.progress.progress = done }
                        b.statusLine.text = "$phase ${if (total>0) "$done/$total" else ""}"
                    }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
                        b.progress.visibility = android.view.View.GONE
                        b.stopBtn.visibility = android.view.View.GONE
                        val up = info.outputData.getInt("uploaded", 0)
                        val down = info.outputData.getInt("downloaded", 0)
                        b.statusLine.text = "Done: $up up, $down down"
                    }
                    WorkInfo.State.CANCELLED -> {
                        b.progress.visibility = android.view.View.GONE
                        b.stopBtn.visibility = android.view.View.GONE
                        b.statusLine.text = "Sync stopped"
                    }
                    else -> {}
                }
            }
    }

    // ---- backup controls -----------------------------------------------------

    private fun setupBackupControls() {
        b.backupSwitch.isChecked = prefs.backupEnabled
        when (prefs.backupInterval) {
            "weekly" -> b.intervalGroup.check(R.id.weekly)
            "monthly" -> b.intervalGroup.check(R.id.monthly)
            else -> b.intervalGroup.check(R.id.daily)
        }
        b.intervalGroup.visibility = if (prefs.backupEnabled) android.view.View.VISIBLE
            else android.view.View.GONE

        b.backupSwitch.setOnCheckedChangeListener { _, on ->
            prefs.backupEnabled = on
            b.intervalGroup.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            SyncWorker.reschedule(this)
            toast(if (on) "Backup every ${prefs.backupInterval}" else "Backup off")
        }
        b.intervalGroup.setOnCheckedChangeListener { _, id ->
            prefs.backupInterval = when (id) {
                R.id.weekly -> "weekly"; R.id.monthly -> "monthly"; else -> "daily"
            }
            SyncWorker.reschedule(this)
            toast("Backup every ${prefs.backupInterval}")
        }
    }

    // ---- settings dialog -----------------------------------------------------

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val url = EditText(this).apply {
            hint = "http://192.168.1.20:8000"; setText(prefs.serverUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = EditText(this).apply {
            hint = "API key (optional)"; setText(prefs.apiKey)
        }
        val pullToggle = CheckBox(this).apply {
            text = "Periodic backup also pulls from server"
            isChecked = prefs.backupPulls
        }
        box.addView(TextView(this).apply { text = "Server URL" })
        box.addView(url)
        box.addView(TextView(this).apply { text = "API key"; setPadding(0, pad, 0, 0) })
        box.addView(key)
        box.addView(pullToggle)

        AlertDialog.Builder(this)
            .setTitle("Server settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.serverUrl = url.text.toString()
                prefs.apiKey = key.text.toString()
                prefs.backupPulls = pullToggle.isChecked
                SyncWorker.reschedule(this)
                testConnection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testConnection() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                Api(prefs.serverUrl, prefs.apiKey).ping()
            }
            toast(if (ok) "Connected to server" else "Could not reach server")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
