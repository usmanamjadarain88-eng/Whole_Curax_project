package com.curax.app

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale

/**
 * Built-in tones: one-shot [MediaStore] queries only (cursor closed before return) — no
 * [RingtoneManager.getCursor]. Library: [Prefs] + OpenDocument. Long-press pins a built-in to your list.
 */
class StandaloneAlertSoundPickerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var recycler: RecyclerView

    private val rows = mutableListOf<SoundRow>()
    private var previewRingtone: Ringtone? = null

    /** Highlights the row matching prefs / last tap ([rowRevealKey]). */
    private var selectedRowKey: String? = null

    private var scrollToUriKeyAfterRebuild: String? = null

    private val requestReadAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) rebuildRows()
    }

    private val requestRecordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showRecordSoundDialog()
        } else {
            CuraxFeedback.warn(this, getString(R.string.standalone_alert_record_need_permission))
        }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        val uriStr = uri.toString()
        if (!prefs.addStandaloneAlertSoundToLibrary(uriStr)) {
            window.decorView.post {
                CuraxFeedback.warn(this@StandaloneAlertSoundPickerActivity, getString(R.string.standalone_alert_sound_duplicate))
            }
            return@registerForActivityResult
        }
        scrollToUriKeyAfterRebuild = prefs.normalizeStandaloneSoundUriKey(uriStr)
        window.decorView.post {
            CuraxFeedback.success(this@StandaloneAlertSoundPickerActivity, R.string.standalone_alert_sound_added)
            rebuildRows()
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private val recordStopHandler = Handler(Looper.getMainLooper())
    private var recordAutoStopRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_standalone_alert_sound_picker)
        prefs = Prefs(this)
        prefs.applyStandaloneSoundLibraryInstallGuard(this)
        prefs.runOneTimeStandaloneSoundLibraryResetIfNeeded()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarStandaloneSoundPicker)
        toolbar.inflateMenu(R.menu.menu_standalone_sound_picker)
        toolbar.menu.findItem(R.id.action_sound_picker_add)?.let { item ->
            tintMenuIconWhite(item)
        }
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            if (item.itemId == R.id.action_sound_picker_add) {
                pickAudioLauncher.launch(arrayOf("audio/*"))
                true
            } else {
                false
            }
        }

        toolbar.setNavigationOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        recycler = findViewById(R.id.rvStandaloneSounds)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SoundAdapter()

        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, 0) {
                override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return 0
                    val row = rows.getOrNull(pos) ?: return 0
                    return if (row is SoundRow.Tone) ItemTouchHelper.LEFT else 0
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return
                    val row = rows.getOrNull(pos) ?: return
                    recycler.adapter?.notifyItemChanged(pos)
                    if (row !is SoundRow.Tone) return
                    stopPreview()
                    if (row.deletable) {
                        AlertDialog.Builder(this@StandaloneAlertSoundPickerActivity)
                            .setMessage(R.string.standalone_alert_confirm_delete_sound_message)
                            .setPositiveButton(R.string.delete) { _, _ -> removeTone(row) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        AlertDialog.Builder(this@StandaloneAlertSoundPickerActivity)
                            .setMessage(R.string.standalone_alert_remove_builtin_tone_message)
                            .setPositiveButton(R.string.standalone_alert_remove_builtin_tone_confirm) { _, _ ->
                                hideToneFromPickerList(row)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            },
        ).attachToRecyclerView(recycler)

        findViewById<MaterialButton>(R.id.btnStandaloneUseSound).setOnClickListener {
            confirmSelectedSound()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestReadAudioPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }

        rebuildRows()
    }

    private fun tintMenuIconWhite(item: MenuItem) {
        val icon: Drawable = item.icon ?: return
        val wrapped = DrawableCompat.wrap(icon.mutate())
        DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, android.R.color.white))
        item.icon = wrapped
    }

    override fun onPause() {
        stopPreview()
        cancelRecordingAutoStop()
        stopRecordingQuietly()
        super.onPause()
    }

    override fun onDestroy() {
        cancelRecordingAutoStop()
        stopRecordingQuietly()
        super.onDestroy()
    }

    private fun stopPreview() {
        previewRingtone?.stop()
        previewRingtone = null
    }

    private fun playPreview(row: SoundRow) {
        stopPreview()
        when (row) {
            SoundRow.Default -> {
                val u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                playUri(u)
            }
            SoundRow.Silent -> { /* no sound */ }
            SoundRow.RecordOwn -> { /* opens recorder instead */ }
            is SoundRow.Tone -> {
                try {
                    playUri(Uri.parse(row.uriStr))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun playUri(uri: Uri?) {
        if (uri == null) return
        try {
            val rt = RingtoneManager.getRingtone(applicationContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rt.isLooping = false
            }
            rt.play()
            previewRingtone = rt
        } catch (_: Exception) {
        }
    }

    /**
     * Safe listing: query + copy rows + close cursor in one shot. Never hold [RingtoneManager] cursors.
     */
    private fun builtInToneCatalogSnapshot(): List<Pair<Uri, String>> {
        val merged = LinkedHashMap<String, Pair<Uri, String>>()
        fun addPair(uri: Uri?, label: String) {
            if (uri == null || label.isBlank()) return
            val key = prefs.normalizeStandaloneSoundUriKey(uri.toString())
            if (key.isEmpty()) return
            if (!merged.containsKey(key)) merged[key] = uri to label.trim()
        }

        addPair(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), getString(R.string.tone_builtin_default_alarm))
        addPair(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), getString(R.string.tone_builtin_default_notification))
        addPair(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), getString(R.string.tone_builtin_default_ringtone))

        for ((u, t) in queryMediaStoreRingtoneLikeTones()) {
            addPair(u, t)
        }

        val list = merged.values.toMutableList()
        list.sortWith(
            compareByDescending<Pair<Uri, String>> { calmReminderSortScore(it.second) }
                .thenBy { it.second.lowercase(Locale.US) },
        )
        return list.take(32)
    }

    /** Prefer gentler / reminder-style names when showing mixed OEM tones (best-effort). */
    private fun calmReminderSortScore(title: String): Int {
        val t = title.lowercase(Locale.US)
        var s = 0
        for (w in listOf(
            "calm", "soft", "gentle", "light", "classic", "digital", "crystal",
            "chime", "simple", "peace", "zen", "alert", "note", "bell", "tone",
            "med", "remind", "pulse",
        )) {
            if (t.contains(w)) s += 3
        }
        return s
    }

    private fun queryMediaStoreRingtoneLikeTones(): List<Pair<Uri, String>> {
        val resolver = applicationContext.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val sel =
            "(" +
                "${MediaStore.Audio.Media.IS_ALARM}!=0 OR " +
                "${MediaStore.Audio.Media.IS_NOTIFICATION}!=0 OR " +
                "${MediaStore.Audio.Media.IS_RINGTONE}!=0" +
                ")"

        val out = ArrayList<Pair<Uri, String>>()
        val bases = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bases.add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        }
        bases.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        bases.add(MediaStore.Audio.Media.INTERNAL_CONTENT_URI)

        for (base in bases) {
            if (out.size >= 40) break
            try {
                resolver.query(
                    base,
                    projection,
                    sel,
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
                )?.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (idCol < 0) return@use
                    val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val dispCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    while (c.moveToNext() && out.size < 40) {
                        val id = c.getLong(idCol)
                        val title = when {
                            titleCol >= 0 -> c.getString(titleCol)?.trim()
                            else -> null
                        } ?: when {
                            dispCol >= 0 -> c.getString(dispCol)?.trim()
                            else -> null
                        } ?: continue
                        val uri = ContentUris.withAppendedId(base, id)
                        out.add(uri to title)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun rowRevealKey(row: SoundRow): String = when (row) {
        SoundRow.Default -> "default"
        SoundRow.Silent -> "silent"
        SoundRow.RecordOwn -> "record_own"
        is SoundRow.Tone -> prefs.normalizeStandaloneSoundUriKey(row.uriStr)
    }

    private fun syncSelectionFromPrefs() {
        val raw = prefs.standaloneLocalAlertSoundUri.trim()
        val key = when {
            raw.isEmpty() -> "default"
            raw.equals("silent", ignoreCase = true) -> "silent"
            else -> prefs.normalizeStandaloneSoundUriKey(raw)
        }
        selectedRowKey = if (rows.any { rowRevealKey(it) == key }) key else null
    }

    private fun rebuildRows() {
        rows.clear()
        rows.add(SoundRow.Default)
        rows.add(SoundRow.Silent)

        val hidden = prefs.getHiddenStandaloneToneKeys()
        val hiddenTitles = prefs.getHiddenStandaloneToneTitles()
        val seen = mutableSetOf<String>()
        seen.add("")
        seen.add("silent")

        for ((uri, title) in builtInToneCatalogSnapshot()) {
            val uriStr = uri.toString()
            val key = prefs.normalizeStandaloneSoundUriKey(uriStr)
            if (hidden.contains(key)) continue
            if (hiddenTitles.contains(prefs.normalizeStandaloneToneTitleKey(title))) continue
            if (!seen.add(key)) continue
            val inLibrary = prefs.isStandaloneAlertSoundInLibrary(uriStr)
            rows.add(SoundRow.Tone(uriStr, title, deletable = inLibrary))
        }

        for (libUri in prefs.getStandaloneAlertSoundLibrary()) {
            val key = prefs.normalizeStandaloneSoundUriKey(libUri)
            if (hidden.contains(key)) continue
            if (!seen.add(key)) continue
            val shortTitle = libUri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: libUri
            rows.add(SoundRow.Tone(libUri, shortTitle, deletable = true))
        }

        rows.add(SoundRow.RecordOwn)

        recycler.adapter?.notifyDataSetChanged()
        syncSelectionFromPrefs()

        val scrollKey = scrollToUriKeyAfterRebuild
        scrollToUriKeyAfterRebuild = null
        if (scrollKey != null) {
            val idx = rows.indexOfFirst { rowRevealKey(it) == scrollKey }
            if (idx >= 0) {
                recycler.post { recycler.smoothScrollToPosition(idx) }
            }
        }
    }

    private fun confirmSelectedSound() {
        val key = selectedRowKey ?: run {
            CuraxFeedback.warn(this, getString(R.string.standalone_alert_pick_tone_first))
            return
        }
        if (key == "record_own") {
            CuraxFeedback.warn(this, getString(R.string.standalone_alert_pick_tone_first))
            return
        }
        val row = rows.firstOrNull { rowRevealKey(it) == key } ?: run {
            CuraxFeedback.warn(this, getString(R.string.standalone_alert_pick_tone_first))
            return
        }
        applyPick(row)
    }

    private fun applyPick(row: SoundRow) {
        when (row) {
            SoundRow.RecordOwn -> return
            SoundRow.Default -> prefs.standaloneLocalAlertSoundUri = ""
            SoundRow.Silent -> prefs.standaloneLocalAlertSoundUri = "silent"
            is SoundRow.Tone -> {
                prefs.standaloneLocalAlertSoundUri = row.uriStr
            }
        }
        selectedRowKey = null
        setResult(RESULT_OK)
        finish()
    }

    private fun beginRecordSoundFlow() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                showRecordSoundDialog()
            else ->
                requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showRecordSoundDialog() {
        val dir = File(filesDir, "alert_recordings").apply { mkdirs() }
        val outFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_standalone_record_sound, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvRecordSoundStatus)
        val btnStart = dialogView.findViewById<MaterialButton>(R.id.btnRecordSoundStart)
        val btnStop = dialogView.findViewById<MaterialButton>(R.id.btnRecordSoundStop)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnRecordSoundSave)

        tvStatus.text = getString(R.string.standalone_alert_record_status_idle)
        btnStart.text = getString(R.string.standalone_alert_record_start)
        btnStop.text = getString(R.string.standalone_alert_record_stop)
        btnSave.text = getString(R.string.standalone_alert_record_save)

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.standalone_alert_record_own_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelRecordingAutoStop()
                stopRecordingQuietly()
                if (outFile.exists()) outFile.delete()
            }
            .create()

        btnStart.setOnClickListener {
            if (!startRecordingToFile(outFile)) {
                CuraxFeedback.warn(this, getString(R.string.standalone_alert_record_failed))
                return@setOnClickListener
            }
            tvStatus.text = getString(R.string.standalone_alert_record_status_active)
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            btnSave.isEnabled = false
            scheduleRecordingAutoStop {
                tvStatus.text = getString(R.string.standalone_alert_record_status_ready)
                btnStart.isEnabled = false
                btnStop.isEnabled = false
                btnSave.isEnabled = outFile.exists() && outFile.length() > 32L
            }
        }
        btnStop.setOnClickListener {
            cancelRecordingAutoStop()
            stopRecordingQuietly()
            tvStatus.text = getString(R.string.standalone_alert_record_status_ready)
            btnStart.isEnabled = false
            btnStop.isEnabled = false
            btnSave.isEnabled = outFile.exists() && outFile.length() > 32L
        }
        btnSave.setOnClickListener {
            cancelRecordingAutoStop()
            stopRecordingQuietly()
            if (!outFile.exists() || outFile.length() < 32L) {
                CuraxFeedback.warn(this, getString(R.string.standalone_alert_record_failed))
                return@setOnClickListener
            }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
            val uriStr = uri.toString()
            if (!prefs.addStandaloneAlertSoundToLibrary(uriStr)) {
                CuraxFeedback.warn(this, getString(R.string.standalone_alert_sound_duplicate))
            } else {
                scrollToUriKeyAfterRebuild = prefs.normalizeStandaloneSoundUriKey(uriStr)
                CuraxFeedback.success(this, R.string.standalone_alert_sound_added)
                setResult(RESULT_OK)
                rebuildRows()
            }
            dlg.dismiss()
        }
        dlg.setOnDismissListener {
            cancelRecordingAutoStop()
            stopRecordingQuietly()
        }
        dlg.show()
    }

    private fun scheduleRecordingAutoStop(afterStopUi: () -> Unit) {
        cancelRecordingAutoStop()
        val r = Runnable {
            cancelRecordingAutoStop()
            stopRecordingQuietly()
            runOnUiThread { afterStopUi() }
        }
        recordAutoStopRunnable = r
        recordStopHandler.postDelayed(r, 45_000L)
    }

    private fun cancelRecordingAutoStop() {
        recordAutoStopRunnable?.let { recordStopHandler.removeCallbacks(it) }
        recordAutoStopRunnable = null
    }

    private fun startRecordingToFile(out: File): Boolean {
        stopRecordingQuietly()
        return try {
            out.parentFile?.mkdirs()
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(out.absolutePath)
                    prepare()
                    start()
                }
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(out.absolutePath)
                    prepare()
                    start()
                }
            }
            mediaRecorder = r
            true
        } catch (_: Exception) {
            stopRecordingQuietly()
            false
        }
    }

    private fun stopRecordingQuietly() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun removeTone(row: SoundRow.Tone) {
        prefs.removeStandaloneAlertSoundFromLibrary(row.uriStr)
        val sel = prefs.standaloneLocalAlertSoundUri.trim()
        if (prefs.normalizeStandaloneSoundUriKey(sel) == prefs.normalizeStandaloneSoundUriKey(row.uriStr)) {
            prefs.standaloneLocalAlertSoundUri = ""
        }
        CuraxFeedback.info(this, getString(R.string.standalone_alert_sound_removed))
        setResult(RESULT_OK)
        rebuildRows()
    }

    /** Built-in / catalog row: hide URI from this screen (same as earlier device-only swipe). */
    private fun hideToneFromPickerList(row: SoundRow.Tone) {
        prefs.addHiddenStandaloneToneKey(prefs.normalizeStandaloneSoundUriKey(row.uriStr))
        prefs.addHiddenStandaloneToneTitle(row.titleShort)
        val sel = prefs.standaloneLocalAlertSoundUri.trim()
        if (prefs.normalizeStandaloneSoundUriKey(sel) == prefs.normalizeStandaloneSoundUriKey(row.uriStr)) {
            prefs.standaloneLocalAlertSoundUri = ""
        }
        selectedRowKey = null
        CuraxFeedback.info(this, getString(R.string.standalone_alert_tone_removed_from_list))
        setResult(RESULT_OK)
        rebuildRows()
    }

    private fun pinToneToLibrary(row: SoundRow.Tone) {
        if (prefs.isStandaloneAlertSoundInLibrary(row.uriStr)) {
            CuraxFeedback.warn(this, getString(R.string.standalone_alert_sound_duplicate))
            return
        }
        if (!prefs.addStandaloneAlertSoundToLibrary(row.uriStr)) {
            CuraxFeedback.warn(this, getString(R.string.standalone_alert_sound_duplicate))
            return
        }
        CuraxFeedback.success(this, R.string.standalone_alert_tone_saved_to_list)
        setResult(RESULT_OK)
        rebuildRows()
    }

    private fun applyRowHighlight(card: MaterialCardView, row: SoundRow) {
        val selected = selectedRowKey != null && rowRevealKey(row) == selectedRowKey
        val green = ContextCompat.getColor(this, R.color.button_primary_bg)
        val muted = ContextCompat.getColor(this, R.color.standalone_inventory_row_stroke)
        card.strokeColor = if (selected) green else muted
        card.strokeWidth = resources.getDimensionPixelSize(
            if (selected) {
                R.dimen.standalone_sound_card_stroke_selected
            } else {
                R.dimen.standalone_sound_card_stroke
            },
        )
    }

    private sealed class SoundRow {
        object Default : SoundRow()
        object Silent : SoundRow()
        object RecordOwn : SoundRow()
        data class Tone(val uriStr: String, val titleShort: String, val deletable: Boolean) : SoundRow()
    }

    private inner class SoundAdapter : RecyclerView.Adapter<SoundAdapter.Vh>() {
        inner class Vh(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardSoundRow)
            val title: TextView = view.findViewById(R.id.tvSoundRowTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_standalone_alert_sound_row, parent, false)
            return Vh(v)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Vh, position: Int) {
            val row = rows[position]

            when (row) {
                SoundRow.Default -> {
                    holder.title.text = getString(R.string.standalone_alert_sound_default_summary)
                    holder.itemView.setOnClickListener {
                        playPreview(row)
                        selectedRowKey = rowRevealKey(row)
                        notifyDataSetChanged()
                    }
                }
                SoundRow.Silent -> {
                    holder.title.text = getString(R.string.standalone_alert_sound_silent)
                    holder.itemView.setOnClickListener {
                        playPreview(row)
                        selectedRowKey = rowRevealKey(row)
                        notifyDataSetChanged()
                    }
                }
                SoundRow.RecordOwn -> {
                    holder.title.text =
                        "${getString(R.string.standalone_alert_record_own_title)}\n${getString(R.string.standalone_alert_record_own_row_subtitle)}"
                    holder.itemView.setOnClickListener {
                        stopPreview()
                        beginRecordSoundFlow()
                    }
                }
                is SoundRow.Tone -> {
                    holder.title.text = row.titleShort
                    holder.itemView.setOnClickListener {
                        playPreview(row)
                        selectedRowKey = rowRevealKey(row)
                        notifyDataSetChanged()
                    }
                    holder.itemView.setOnLongClickListener {
                        if (!row.deletable) {
                            pinToneToLibrary(row)
                            true
                        } else {
                            false
                        }
                    }
                    bindToneTitleAsync(holder, row)
                }
            }

            applyRowHighlight(holder.card, row)
        }

        private fun bindToneTitleAsync(holder: Vh, row: SoundRow.Tone) {
            val uri = try {
                Uri.parse(row.uriStr)
            } catch (_: Exception) {
                return
            }
            val appCtx = applicationContext
            Thread {
                val label = try {
                    RingtoneManager.getRingtone(appCtx, uri)?.getTitle(appCtx)?.trim()?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || label == null) return@Thread
                runOnUiThread {
                    if (holder.bindingAdapterPosition == pos) {
                        holder.title.text = label
                    }
                }
            }.start()
        }
    }
}
