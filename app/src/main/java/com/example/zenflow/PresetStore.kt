package com.example.zenflow

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

data class MixPreset(val name: String, val mix: Map<String, Int>)

object PresetStore {

    private const val KEY_PRESETS = "mixer_presets"
    private const val KEY_SEEDED = "presets_seeded"

    private fun prefs(context: Context) =
        context.getSharedPreferences("zen_stats", Context.MODE_PRIVATE)

    fun list(context: Context): List<MixPreset> {
        val raw = prefs(context).getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val mix = LinkedHashMap<String, Int>()
                val m = o.getJSONObject("mix")
                val keys = m.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    mix[k] = m.optInt(k, 60)
                }
                MixPreset(o.getString("name"), mix)
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, name: String, mix: Map<String, Int>): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || mix.isEmpty()) return false
        val all = list(context).filter { it.name != clean }.toMutableList()
        all.add(MixPreset(clean, LinkedHashMap(mix)))
        persist(context, all)
        return true
    }

    fun rename(context: Context, oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val all = list(context)
        if (all.any { it.name == clean && it.name != oldName }) return false
        persist(context, all.map { if (it.name == oldName) it.copy(name = clean) else it })
        return true
    }

    fun delete(context: Context, name: String) =
        persist(context, list(context).filter { it.name != name })

    private fun persist(context: Context, presets: List<MixPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            val mix = JSONObject()
            p.mix.forEach { (k, v) -> mix.put(k, v) }
            arr.put(JSONObject().put("name", p.name).put("mix", mix))
        }
        prefs(context).edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun encodeMix(context: Context, mix: Map<String, Int>): Pair<String, List<String>> {
        val out = LinkedHashMap<String, Int>()
        val skipped = mutableListOf<String>()
        SoundCatalog.orderedKeys(context).forEach { key ->
            val vol = mix[key]
            when {
                vol == null -> {}
                SoundCatalog.hasSounds(context, key) -> out[key] = vol
                else -> skipped.add(SoundCatalog.labelFor(context, key))
            }
        }
        return out.entries.joinToString(",") { "${it.key}:${it.value}" } to skipped
    }

    fun seedIfFirstRun(context: Context) {
        if (prefs(context).getBoolean(KEY_SEEDED, false)) return
        prefs(context).edit().putBoolean(KEY_SEEDED, true).apply()
        save(context, "🌙 Deep Sleep", mapOf("tone_delta" to 70))
        save(context, "🌌 Meditate", mapOf("tone_theta" to 70))
        save(context, "🎯 Deep Focus", mapOf("tone_focus" to 60))
    }
}

fun AppCompatActivity.showPresetSaveDialog(mix: Map<String, Int>, onSaved: () -> Unit) {
    if (mix.isEmpty()) {
        Toast.makeText(this, "Select at least one sound first", Toast.LENGTH_SHORT).show()
        return
    }
    val input = EditText(this).apply {
        hint = "e.g. Rainy Night"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setSingleLine(true)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(10), dp(24), 0)
        addView(input)
    }
    AlertDialog.Builder(this)
        .setTitle("Save mix as preset 💾")
        .setMessage("Saves the sounds and slider levels. Saving an existing name updates it.")
        .setView(box)
        .setPositiveButton("Save") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@setPositiveButton
            val existed = PresetStore.list(this).any { it.name == name }
            if (PresetStore.save(this, name, mix)) {
                Toast.makeText(
                    this,
                    if (existed) "Preset “$name” updated" else "Preset “$name” saved 💾",
                    Toast.LENGTH_SHORT
                ).show()
                onSaved()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

fun AppCompatActivity.showPresetManageDialog(
    preset: MixPreset,
    currentMixProvider: () -> Map<String, Int>,
    onApply: (MixPreset) -> Unit,
    onChanged: () -> Unit
) {
    val summary = preset.mix.entries.joinToString("   ") {
        "${SoundCatalog.emojiFor(it.key)} ${it.value}%"
    }
    AlertDialog.Builder(this)
        .setTitle(preset.name)
        .setMessage(summary.ifBlank { "Empty mix" })
        .setItems(arrayOf("Apply", "Overwrite with current mix", "Rename", "Delete")) { _, which ->
            when (which) {
                0 -> onApply(preset)
                1 -> {
                    val mix = currentMixProvider()
                    if (mix.isEmpty()) {
                        Toast.makeText(this, "Select at least one sound first", Toast.LENGTH_SHORT).show()
                    } else if (PresetStore.save(this, preset.name, mix)) {
                        Toast.makeText(this, "“${preset.name}” updated", Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }
                2 -> showPresetRenameDialog(preset) { if (it) onChanged() }
                3 -> AlertDialog.Builder(this)
                    .setTitle("Delete “${preset.name}”?")
                    .setMessage("This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        PresetStore.delete(this, preset.name)
                        Toast.makeText(this, "Preset deleted", Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

private fun AppCompatActivity.showPresetRenameDialog(
    preset: MixPreset,
    onDone: (Boolean) -> Unit
) {
    val input = EditText(this).apply {
        setText(preset.name)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setSingleLine(true)
        setSelection(preset.name.length)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(10), dp(24), 0)
        addView(input)
    }
    AlertDialog.Builder(this)
        .setTitle("Rename preset")
        .setView(box)
        .setPositiveButton("Rename") { _, _ ->
            val name = input.text.toString().trim()
            when {
                name.isEmpty() -> onDone(false)
                PresetStore.rename(this, preset.name, name) -> {
                    Toast.makeText(this, "Renamed to “$name”", Toast.LENGTH_SHORT).show()
                    onDone(true)
                }
                else -> {
                    Toast.makeText(this, "That name is already in use", Toast.LENGTH_SHORT).show()
                    onDone(false)
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
