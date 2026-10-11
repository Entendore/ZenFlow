package com.example.zenflow

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val sessions = SessionStore.getSessions(this)
        val recycler = findViewById<RecyclerView>(R.id.recyclerHistory)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)
        recycler.adapter = HistoryAdapter(sessions)

        findViewById<TextView>(R.id.tvEmpty).visibility =
            if (sessions.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class HistoryAdapter(private val items: List<SessionRecord>) :
        RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val cal = Calendar.getInstance()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvMood: TextView = view.findViewById(R.id.tvMood)
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvDate: TextView = view.findViewById(R.id.tvDate)
            val tvMix: TextView = view.findViewById(R.id.tvMix)
            val tvNote: TextView = view.findViewById(R.id.tvNote)
            val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            holder.tvMood.text = r.mood.ifEmpty { "🧘" }
            holder.tvTitle.text = r.title
            holder.tvDate.text = dateLabel(r.timestamp)
            holder.tvDuration.text = getString(R.string.chip_minutes_fmt, r.minutes)

            val mix = r.mix.split(",").filter { it.isNotBlank() }
            holder.tvMix.text = if (mix.isEmpty()) getString(R.string.in_silence)
            else "🎚 " + mix.joinToString(" ") { SoundCatalog.emojiFor(it) }

            if (r.note.isNotBlank()) {
                holder.tvNote.visibility = View.VISIBLE
                holder.tvNote.text = "“${r.note}”"
            } else holder.tvNote.visibility = View.GONE
        }

        private fun startOfDay(ms: Long): Long {
            cal.timeInMillis = ms
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private fun dateLabel(ts: Long): String {
            val todayStart = startOfDay(System.currentTimeMillis())
            val dayStart = startOfDay(ts)
            val dayPart = when (dayStart) {
                todayStart -> getString(R.string.today)
                todayStart - 86_400_000L -> getString(R.string.yesterday)
                else -> dayFormat.format(Date(ts))
            }
            return "$dayPart · ${timeFormat.format(Date(ts))}"
        }
    }
}
