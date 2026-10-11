package com.example.zenflow

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ContemplationActivity : AppCompatActivity() {

    private lateinit var tvHeading: TextView
    private lateinit var tvEmoji: TextView
    private lateinit var tvText: TextView
    private lateinit var tvSub: TextView
    private lateinit var btnFav: TextView
    private lateinit var tvCounter: TextView
    private lateinit var contentBox: View
    private lateinit var bgA: View
    private lateinit var bgB: View
    private var bgFront = true

    private var currentCategory: ContemplationLibrary.Category? =
        ContemplationLibrary.Category.SOUND
    private var currentItem: ContemplationLibrary.Contemplation? = null
    private val bag = ArrayDeque<ContemplationLibrary.Contemplation>()
    private var shown = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contemplations)

        bgA = findViewById(R.id.bgA)
        bgB = findViewById(R.id.bgB)
        contentBox = findViewById(R.id.contentBox)
        tvHeading = findViewById(R.id.tvHeading)
        tvEmoji = findViewById(R.id.tvEmoji)
        tvText = findViewById(R.id.tvText)
        tvSub = findViewById(R.id.tvSub)
        btnFav = findViewById(R.id.btnFav)
        tvCounter = findViewById(R.id.tvCounter)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { showNext() }
        btnFav.setOnClickListener { currentItem?.let { toggleFav(it) } }
        tvText.setOnClickListener { showNext() }

        buildChips()
        showNext(animate = false)
    }

    private fun buildChips() {
        val row = findViewById<LinearLayout>(R.id.categoryChips)
        ContemplationLibrary.Category.values().forEach { cat ->
            row.addView(chip("${cat.emoji} ${cat.label}") { selectCategory(cat) })
        }
        row.addView(chip("❤️ " + getString(R.string.favorites_title)) { selectCategory(null) })
        refreshChips()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun refreshChips() {
        val row = findViewById<LinearLayout>(R.id.categoryChips)
        val cats = ContemplationLibrary.Category.values()
        cats.forEachIndexed { index, cat ->
            styleChip(row.getChildAt(index) as TextView, cat == currentCategory)
        }
        styleChip(row.getChildAt(cats.size) as TextView, currentCategory == null)
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = ContextCompat.getDrawable(
            this, if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
        )
        chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#C2CBEF"))
    }

    private fun selectCategory(category: ContemplationLibrary.Category?) {
        if (category == currentCategory) return
        currentCategory = category
        refreshChips()
        bag.clear()
        shown = 0
        showNext(animate = true)
    }

    private fun currentPool(): List<ContemplationLibrary.Contemplation> =
        ContemplationLibrary.poolFor(this, currentCategory)

    private fun showNext(animate: Boolean = true) {
        val pool = currentPool()
        if (pool.isEmpty()) {
            currentItem = null
            tvHeading.text = getString(R.string.favorites_title)
            applyEmptyState()
            return
        }
        if (bag.isEmpty()) {
            val shuffled = pool.shuffled().toMutableList()
            if (shuffled.size > 1 && shuffled.first().text == currentItem?.text) {
                val j = (1 until shuffled.size).random()
                val tmp = shuffled[0]; shuffled[0] = shuffled[j]; shuffled[j] = tmp
            }
            bag.addAll(shuffled)
            shown = 0
        }
        val item = bag.removeFirst()
        currentItem = item
        shown++
        tvHeading.text = currentCategory?.label ?: getString(R.string.favorites_title)
        render(item, animate)
    }

    private fun render(item: ContemplationLibrary.Contemplation, animate: Boolean) {
        applyGradient(item.category)
        if (!animate) { applyContent(item); return }
        contentBox.animate()
            .alpha(0f).translationY(-24f).setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                applyContent(item)
                contentBox.translationY = 28f
                contentBox.animate()
                    .alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }.start()
    }

    private fun applyContent(item: ContemplationLibrary.Contemplation) {
        tvEmoji.text = item.emoji.ifEmpty { item.category.emoji }
        tvText.text = item.text
        tvText.textSize = if (item.category == ContemplationLibrary.Category.MANTRA) 30f else 21f
        tvSub.text = item.sub
        tvSub.visibility = if (item.sub.isBlank()) View.GONE else View.VISIBLE
        tvCounter.text = "$shown / ${currentPool().size}"
        updateFavButton()
    }

    private fun applyEmptyState() {
        applyGradient(ContemplationLibrary.Category.MIND)
        tvEmoji.text = "🤍"
        tvText.text = getString(R.string.no_favorites)
        tvText.textSize = 22f
        tvSub.text = getString(R.string.no_favorites_hint)
        tvSub.visibility = View.VISIBLE
        tvCounter.text = ""
        updateFavButton()
    }

    private fun updateFavButton() {
        val item = currentItem
        if (item == null) {
            btnFav.text = "🤍"
            btnFav.alpha = 0.4f
            return
        }
        btnFav.alpha = 1f
        btnFav.text = if (ContemplationLibrary.isFavorite(this, item)) "❤️" else "🤍"
    }

    private fun toggleFav(item: ContemplationLibrary.Contemplation) {
        ContemplationLibrary.toggleFavorite(this, item)
        updateFavButton()
        if (currentCategory == null && !ContemplationLibrary.isFavorite(this, item)) {
            bag.clear()
            showNext()
        }
    }

    private fun applyGradient(category: ContemplationLibrary.Category) {
        val (start, end, status) = when (category) {
            ContemplationLibrary.Category.SOUND ->
                Triple(0xFF4B6CB7.toInt(), 0xFF182848.toInt(), 0xFF182848.toInt())
            ContemplationLibrary.Category.BREATH ->
                Triple(0xFF5BC9B0.toInt(), 0xFF27766F.toInt(), 0xFF226A64.toInt())
            ContemplationLibrary.Category.MIND ->
                Triple(0xFF8A7BFF.toInt(), 0xFF5A50E8.toInt(), 0xFF4A42C4.toInt())
            ContemplationLibrary.Category.MANTRA ->
                Triple(0xFFF0A35E.toInt(), 0xFF9C5518.toInt(), 0xFF8C4A15.toInt())
            ContemplationLibrary.Category.SLEEP ->
                Triple(0xFF3E4E88.toInt(), 0xFF14162E.toInt(), 0xFF101227.toInt())
        }
        val incoming = if (bgFront) bgB else bgA
        val outgoing = if (bgFront) bgA else bgB
        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.visibility = View.VISIBLE
        incoming.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)
        )
        incoming.animate().alpha(1f).setDuration(350).start()
        outgoing.animate().alpha(0f).setDuration(350)
            .withEndAction { outgoing.visibility = View.GONE }
            .start()
        bgFront = !bgFront
        window.statusBarColor = status
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
