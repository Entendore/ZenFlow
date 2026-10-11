package com.example.zenflow

import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zenflow.audio.KeyStat
import com.example.zenflow.audio.SoundStore
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Audits and manages the whole sound database: clips, tones, imports, presets. */
class SoundLibraryActivity : AppCompatActivity() {

    private data class Row(
        val sound: AmbientSound,
        val isTone: Boolean,
        val clips: Int,
        val bytes: Long,
        val avgMs: Long = 0,
        val imported: Boolean = false
    )

    private class RowState {
        var nextIndex = 0
        var playingClip = 0
    }

    private lateinit var adapter: LibraryAdapter
    private val states = mutableMapOf<String, RowState>()

    private var previewPlayer: MediaPlayer? = null
    private var previewTone: ToneLayer? = null
    private var previewKey: String? = null

    private val prefs by lazy { getSharedPreferences("zen_stats", MODE_PRIVATE) }

    private val importFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importSound(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound_library)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)

        PresetStore.seedIfFirstRun(this)
        loadRows()

        findViewById<TextView>(R.id.btnImport).setOnClickListener {
            importFile.launch(arrayOf("audio/*"))
        }
        findViewById<TextView>(R.id.btnRepair).setOnClickListener { v ->
            v.isEnabled = false
            lifecycleScope.launch {
                val report = runCatching { SoundStore.healthCheck(applicationContext) }.getOrNull()
                findViewById<TextView>(R.id.tvHealth).text =
                    report?.summary ?: getString(R.string.repair_failed)
                report?.repaired?.takeIf { it.isNotEmpty() }?.let { fixed ->
                    Toast.makeText(
                        this@SoundLibraryActivity,
                        getString(R.string.repair_done, fixed.joinToString()),
                        Toast.LENGTH_LONG
                    ).show()
                }
                v.isEnabled = true
            }
        }
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val rows = runCatching {
                SoundStore.warmUp(applicationContext)
                buildRows()
            }.getOrDefault(emptyList())

            adapter = LibraryAdapter(rows)
            findViewById<RecyclerView>(R.id.recycler).adapter = adapter
            buildPresetChips()

            findViewById<TextView>(R.id.tvStats).text =
                if (rows.isEmpty()) getString(R.string.library_empty_index) else statsLine(rows)
            findViewById<TextView>(R.id.tvHealth).text =
                getString(R.string.library_files_fmt, rows.sumOf { it.clips })
        }
    }

    private suspend fun buildRows(): List<Row> {
        val stats = SoundStore.statsByKey(applicationContext)
        val built = AMBIENT_SOUNDS.map { s ->
            val st = stats[s.key] ?: KeyStat(0, 0, 0)
            Row(s, SoundCatalog.isTone(s.key), st.clips, st.bytes, st.avgDurationMs)
        }
        val imported = SoundStore.imports(applicationContext).map { imp ->
            val st = stats[imp.key] ?: KeyStat(0, 0, 0)
            Row(
                AmbientSound(imp.key, imp.name, "🎧"), isTone = false,
                clips = 1, bytes = st.bytes, avgMs = st.avgDurationMs, imported = true
            )
        }
        return built + imported
    }

    private fun statsLine(rows: List<Row>): String {
        val clips = rows.sumOf { it.clips }
        val bytes = rows.sumOf { it.bytes }
        val tones = rows.count { it.isTone }
        return "${rows.size} sounds · $clips clips · ${formatBytes(bytes)} · $tones synthesized in-app"
    }

    /* ---------- import & delete ---------- */

    private fun importSound(uri: Uri) {
        lifecycleScope.launch {
            SoundStore.importFromUri(applicationContext, uri)
                .onSuccess { imp ->
                    Toast.makeText(
                        this@SoundLibraryActivity,
                        getString(R.string.import_done, imp.name), Toast.LENGTH_SHORT
                    ).show()
                    loadRows()
                }
                .onFailure { e ->
                    Toast.makeText(
                        this@SoundLibraryActivity,
                        getString(R.string.import_failed, e.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun confirmDeleteImport(row: Row) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_import_title, row.sound.label))
            .setMessage(R.string.delete_import_msg)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                lifecycleScope.launch {
                    if (previewKey == row.sound.key) stopPreview()
                    SoundStore.deleteImport(applicationContext, row.sound.key)
                    Toast.makeText(
                        this@SoundLibraryActivity,
                        getString(R.string.import_deleted, row.sound.label), Toast.LENGTH_SHORT
                    ).show()
                    loadRows()
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    /* ---------- preview (one instance at a time) ---------- */

    private fun togglePreview(row: Row) {
        if (previewKey == row.sound.key) {
            stopPreview()
            adapter.notifyDataSetChanged()
            return
        }
        stopPreview()
        previewKey = row.sound.key

        if (row.isTone) {
            val spec = ToneLibrary.TONES.first { it.key == row.sound.key }
            previewTone = ToneLayer(ToneLibrary.samples(spec)).also {
                it.setVolume(0.9f)
                it.start()
            }
        } else if (row.imported) {
            val path = SoundStore.importedFilePath(this, row.sound.key) ?: return
            previewPlayer = MediaPlayer.create(this, Uri.fromFile(File(path)))?.apply {
                isLooping = true
                start()
            }
        } else {
            val state = states.getOrPut(row.sound.key) { RowState() }
            val ids = SoundCatalog.variants(this, row.sound.key)
            val idx = state.nextIndex % ids.size
            state.playingClip = idx
            state.nextIndex = idx + 1
            previewPlayer = MediaPlayer.create(this, ids[idx])?.apply {
                isLooping = true
                start()
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPreview() {
        previewPlayer?.let { p -> runCatching { p.stop() }; p.release() }
        previewPlayer = null
        previewTone?.release()
        previewTone = null
        previewKey = null
    }

    /* ---------- mix management ---------- */

    private fun mixMap(): LinkedHashMap<String, Int> {
        val map = LinkedHashMap<String, Int>()
        prefs.getString(PlayerActivity.PREF_MIX, null)?.let { raw ->
            raw.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) map[parts[0]] = parts[1].toIntOrNull() ?: 60
            }
        }
        return map
    }

    private fun toggleMix(key: String) {
        val map = mixMap()
        if (key in map) {
            map.remove(key)
        } else {
            if (!SoundCatalog.hasSounds(this, key)) {
                Toast.makeText(
                    this, getString(R.string.toast_no_clips, key), Toast.LENGTH_LONG
                ).show()
                return
            }
            if (map.size >= PlayerActivity.MAX_MIX_LAYERS) {
                Toast.makeText(
                    this, getString(R.string.toast_cap, PlayerActivity.MAX_MIX_LAYERS),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            map[key] = prefs.getInt("vol_$key", 60)
        }
        val ordered = SoundCatalog.orderedKeys(this).filter { it in map }
        val out = LinkedHashMap<String, Int>()
        ordered.forEach { out[it] = map.getValue(it) }
        prefs.edit()
            .putString(
                PlayerActivity.PREF_MIX,
                out.entries.joinToString(",") { "${it.key}:${it.value}" }
            )
            .apply()
        adapter.notifyDataSetChanged()
    }

    /* ---------- presets ---------- */

    private fun buildPresetChips() {
        val row = findViewById<LinearLayout>(R.id.presetChipRow) ?: return
        row.removeAllViews()
        PresetStore.list(this).forEach { preset ->
            row.addView(TextView(this).apply {
                text = preset.name
                textSize = 12f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                background = ContextCompat.getDrawable(
                    this@SoundLibraryActivity, R.drawable.bg_pill
                )
                setTextColor(ContextCompat.getColor(this@SoundLibraryActivity, R.color.primary))
                setOnClickListener { applyPreset(preset) }
                setOnLongClickListener {
                    showPresetManageDialog(
                        preset, { mixMap() }, { applyPreset(it) }, { buildPresetChips() }
                    )
                    true
                }
            })
        }
        row.addView(TextView(this).apply {
            text = "＋ Save"
            textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = ContextCompat.getDrawable(this@SoundLibraryActivity, R.drawable.bg_pill)
            setTextColor(ContextCompat.getColor(this@SoundLibraryActivity, R.color.primary))
            setOnClickListener { showPresetSaveDialog(mixMap()) { buildPresetChips() } }
        })
    }

    private fun applyPreset(preset: MixPreset) {
        val (encoded, skipped) = PresetStore.encodeMix(this, preset.mix)
        prefs.edit().putString(PlayerActivity.PREF_MIX, encoded).apply()
        adapter.notifyDataSetChanged()
        Toast.makeText(
            this, "${preset.name} applied — the player picks it up on return",
            Toast.LENGTH_SHORT
        ).show()
        if (skipped.isNotEmpty()) {
            Toast.makeText(this, "No clips yet: ${skipped.joinToString()}", Toast.LENGTH_LONG).show()
        }
    }

    /* ---------- helpers ---------- */

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes > 0 -> "${(bytes + 999) / 1000} KB"
        else -> "0 KB"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        stopPreview()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        super.onPause()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    /* ---------- adapter ---------- */

    private inner class LibraryAdapter(private val rows: List<Row>) :
        RecyclerView.Adapter<LibraryAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val medallion: View = view.findViewById(R.id.medallion)
            val tvEmoji: TextView = view.findViewById(R.id.tvEmoji)
            val tvLabel: TextView = view.findViewById(R.id.tvLabel)
            val tvDetails: TextView = view.findViewById(R.id.tvDetails)
            val tvMixState: TextView = view.findViewById(R.id.tvMixState)
            val btnMix: TextView = view.findViewById(R.id.btnMix)
            val btnPreview: TextView = view.findViewById(R.id.btnPreview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sound_library, parent, false)
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val state = states.getOrPut(row.sound.key) { RowState() }
            val previewing = previewKey == row.sound.key

            holder.tvEmoji.text = row.sound.emoji
            holder.medallion.backgroundTintList = ColorStateList.valueOf(
                if (row.isTone) 0xFFDCEFEA.toInt() else 0xFFEDEBFF.toInt()
            )
            holder.tvLabel.text = row.sound.label

            val base = when {
                row.imported -> getString(
                    R.string.library_imported_sub,
                    formatBytes(row.bytes),
                    if (row.avgMs > 0) "${row.avgMs / 1000}s" else "—"
                )
                row.isTone -> "synthesized in-app · ${formatBytes(row.bytes)} seamless loop" +
                    if (row.avgMs > 0) " · ${row.avgMs / 1000.0}s" else ""
                row.clips == 0 -> "no clips yet — add ${row.sound.key}_01.mp3 …"
                else -> "${row.clips} clips · ${formatBytes(row.bytes)}" +
                    if (row.avgMs > 0) " · avg ${row.avgMs / 1000}s" else ""
            }
            holder.tvDetails.text =
                if (previewing && !row.isTone && !row.imported && row.clips > 0)
                    "$base · clip ${state.playingClip + 1}/${row.clips}" else base

            val mix = mixMap()
            val inMix = row.sound.key in mix
            holder.tvMixState.text =
                if (inMix) "In the current mix · ${mix[row.sound.key]}%" else "Not in the mix"
            holder.tvMixState.setTextColor(
                if (inMix) ColorStateList.valueOf(ContextCompat.getColor(this@SoundLibraryActivity, R.color.primary))
                else ColorStateList.valueOf(ContextCompat.getColor(this@SoundLibraryActivity, R.color.text_gray))
            )

            holder.btnMix.text = if (inMix) "In mix ✓" else "＋ Mix"
            holder.btnMix.background = ContextCompat.getDrawable(
                this@SoundLibraryActivity, if (inMix) R.drawable.bg_chip_selected else R.drawable.bg_pill
            )
            holder.btnMix.setTextColor(
                if (inMix) android.graphics.Color.WHITE
                else ContextCompat.getColor(this@SoundLibraryActivity, R.color.primary)
            )
            holder.btnMix.setOnClickListener { toggleMix(row.sound.key) }

            val canPreview = row.isTone || row.clips > 0 || row.imported
            holder.btnPreview.text = if (previewing) "■" else "▶"
            holder.btnPreview.alpha = if (canPreview) 1f else 0.35f
            holder.btnPreview.setOnClickListener { if (canPreview) togglePreview(row) }

            holder.itemView.setOnLongClickListener {
                if (row.imported) confirmDeleteImport(row)
                else Toast.makeText(
                    this@SoundLibraryActivity,
                    SoundCatalog.describe(this@SoundLibraryActivity, row.sound.key),
                    Toast.LENGTH_LONG
                ).show()
                true
            }
        }
    }
}
