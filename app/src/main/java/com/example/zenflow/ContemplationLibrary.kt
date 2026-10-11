package com.example.zenflow

import android.content.Context
import java.util.Calendar
import kotlin.random.Random

object ContemplationLibrary {

    enum class Category(val label: String, val emoji: String) {
        SOUND("With Sound", "🎧"),
        BREATH("Breath", "🫁"),
        MIND("The Mind", "🧠"),
        MANTRA("Mantras", "🕉️"),
        SLEEP("Sleep", "🌙")
    }

    data class Contemplation(
        val text: String,
        val category: Category,
        val tag: String = "",
        val sub: String = "",
        val emoji: String = ""
    )

    val ALL: List<Contemplation> = listOf(
        Contemplation("Each drop arrives, sounds, and is gone.", Category.SOUND, "rain",
            "Notice how no drop stays. Let sounds pass the way the rain does — without holding any of them.", "🌧️"),
        Contemplation("The rain doesn't choose which leaf to fall on.", Category.SOUND, "rain",
            "Rest in that effortlessness. Nothing to fix, nowhere to get to — just falling.", "🌧️"),
        Contemplation("Under the rain, the earth is quietly drinking.", Category.SOUND, "rain",
            "Let the sound soak in the same way. You don't have to do anything to receive it.", "🌧️"),
        Contemplation("Each wave rolls in, breaks, and returns.", Category.SOUND, "ocean",
            "Match your breath to the waves: in as one rises, out as it dissolves.", "🌊"),
        Contemplation("Beneath the waves, the deep is still.", Category.SOUND, "ocean",
            "Thoughts are surface weather. Beneath them is a quiet that doesn't move.", "🌊"),
        Contemplation("The tide never hurries, yet everything arrives.", Category.SOUND, "ocean",
            "Let this session take its own time. You are not behind.", "🌊"),
        Contemplation("The fire burns on its own — you only need to sit near it.", Category.SOUND, "fire",
            "You don't have to feed this moment. Warmth is already happening.", "🔥"),
        Contemplation("Watch each crackle: born, blazing, finished.", Category.SOUND, "fire",
            "Attention works this way too. One clear moment at a time.", "🔥"),
        Contemplation("What the fire releases rises as light.", Category.SOUND, "fire",
            "With each exhale, offer up something you've been carrying.", "🔥"),
        Contemplation("The forest is full of movement, and never rushed.", Category.SOUND, "forest",
            "Leaves shift, birds call, branches creak — and the whole stays calm. You are part of that calm.", "🌲"),
        Contemplation("A tree grows slowly in every direction at once.", Category.SOUND, "forest",
            "Your practice is the same: quiet, rooted, patiently widening.", "🌲"),
        Contemplation("Nothing in the forest asks to be anywhere else.", Category.SOUND, "forest",
            "Try it for one minute: no wish to be elsewhere. Just here.", "🌲"),
        Contemplation("Strike a bowl: the sound is born, swells, and fades.", Category.SOUND, "bowl",
            "Follow one resonance all the way to its end. This is how to be with anything.", "🔔"),
        Contemplation("The ringing continues after the strike.", Category.SOUND, "bowl",
            "What you do now keeps sounding later. Let today's note be a kind one.", "🔔"),
        Contemplation("In the pause between two rings, the silence is loudest.", Category.SOUND, "bowl",
            "Listen for the space between sounds. That's where the mind rests.", "🔔"),
        Contemplation("The tone is steady so you don't have to be.", Category.SOUND, "tone",
            "Lean your attention on the hum like a hand on a railing.", "🌑"),
        Contemplation("Two notes a hair apart become one shimmer.", Category.SOUND, "tone",
            "Where the tones meet, attention sharpens. Rest your focus right there.", "🌌"),
        Contemplation("The sound has no words. Let your mind have none either.", Category.SOUND, "tone",
            "When words arise, hand them to the hum and return.", "🌤️"),
        Contemplation("Silence isn't empty — it's where everything can be heard.", Category.SOUND, "silence",
            "Sit in the open quiet and notice: heartbeat, breath, the room.", "🤫"),
        Contemplation("The quietest sound in the room is your own attention.", Category.SOUND, "silence",
            "Notice listening itself. Who or what is aware right now?", "🤫"),
        Contemplation("In silence, the mind finally hears itself think.", Category.SOUND, "silence",
            "Don't follow the thoughts. Just watch them surface, like fish in still water.", "🤫"),
        Contemplation("This breath is the only one you actually have.", Category.BREATH,
            "The last one is memory, the next is a plan. Come back to this one."),
        Contemplation("Notice the tiny pause between inhale and exhale.", Category.BREATH,
            "That still point happens on its own, over and over. You don't manage it — you notice it."),
        Contemplation("Count ten breaths. If you lose count, begin again — that is the practice.", Category.BREATH,
            "Losing count isn't failure. Beginning again is the whole skill."),
        Contemplation("The body breathes itself while you watch.", Category.BREATH,
            "You don't do the breathing. Let that truth carry some weight off your shoulders."),
        Contemplation("Cool air in, warm air out.", Category.BREATH,
            "The simplest anchor there is. When the mind runs, this doorway is always open."),
        Contemplation("Your breath has been with you since your first moment.", Category.BREATH,
            "It asks nothing and never leaves. Say thank you with one slow exhale."),
        Contemplation("You are the sky, not the weather.", Category.MIND,
            "Thoughts and moods pass through. The awareness watching them doesn't."),
        Contemplation("Label it — 'thinking' — and drop the rest of the sentence.", Category.MIND,
            "One word, gently, then back to breath or sound. No lecture needed."),
        Contemplation("The thought you're chasing is already leaving.", Category.MIND,
            "You never catch one by following it. Sit still and they empty out on their own."),
        Contemplation("Boredom is just the mind asking for a new sound.", Category.MIND,
            "Stay one more minute. On the far side of boredom, stillness opens."),
        Contemplation("You don't have to believe every thought.", Category.MIND,
            "A thought is a suggestion, not an order. Especially during a sit."),
        Contemplation("What remains when you stop producing and stop consuming?", Category.MIND,
            "Sit with that question. Don't answer it in words — answer it by sitting."),
        Contemplation("Om", Category.MANTRA,
            "The primordial hum. Let it vibrate in the chest rather than in the throat."),
        Contemplation("So Hum", Category.MANTRA,
            "\"I am that.\" Silently say So on the inhale, Hum on the exhale."),
        Contemplation("Om Shanti", Category.MANTRA,
            "\"Peace\" — invoke it three times: for the body, the speech, the mind."),
        Contemplation("Sat Nam", Category.MANTRA,
            "\"Truth is my identity.\" With each inhale: Sat. Each exhale: Nam."),
        Contemplation("Om Mani Padme Hum", Category.MANTRA,
            "\"The jewel in the lotus\" — the mantra of compassion, for others and yourself."),
        Contemplation("Om Namah Shivaya", Category.MANTRA,
            "\"I honor the divinity within myself.\""),
        Contemplation("Lokah Samastah Sukhino Bhavantu", Category.MANTRA,
            "\"May all beings everywhere be happy and free.\" Dedicate this sit to someone."),
        Contemplation("Ra Ma Da Sa", Category.MANTRA,
            "A healing mantra — earth, sun, moon, infinity flowing through you."),
        Contemplation("Let the day end. It did its best, and so did you.", Category.SLEEP,
            "Nothing left to solve tonight. Set the day down like a heavy bag."),
        Contemplation("Heavy arms, heavy legs, heavy eyelids.", Category.SLEEP,
            "Let gravity do the work. The body knows how to sink."),
        Contemplation("Tomorrow can wait — it always comes anyway.", Category.SLEEP,
            "You don't need to hold tomorrow in your mind tonight. Sleep will hand it to you fresh."),
        Contemplation("With each exhale, sink one inch deeper.", Category.SLEEP,
            "There's nowhere to get to. Sink anyway."),
        Contemplation("You are allowed to simply rest.", Category.SLEEP,
            "Rest isn't a reward for finishing. It's what you're made of.")
    )

    fun dailyPick(): Contemplation = dailyPick(defaultSeed())

    internal fun dailyPick(seed: Long): Contemplation =
        ALL[Random(seed).nextInt(ALL.size)]

    private fun defaultSeed(): Long {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
    }

    fun contemplationForMix(mixKeys: List<String>): Contemplation {
        val tags = mixKeys.map { if (it.startsWith("tone_")) "tone" else it }
        val pool = ALL.filter { it.tag.isNotEmpty() && it.tag in tags }
        if (pool.isNotEmpty()) return pool.random()
        return ALL.filter { it.tag == "silence" }.random()
    }

    fun isFavorite(context: Context, c: Contemplation) = c.text in favorites(context)

    fun toggleFavorite(context: Context, c: Contemplation): Boolean {
        val favs = favorites(context).toMutableSet()
        val nowFav = if (c.text in favs) { favs.remove(c.text); false } else { favs.add(c.text); true }
        prefs(context).edit().putStringSet(KEY_FAVS, favs).apply()
        return nowFav
    }

    fun allFavorites(context: Context): List<Contemplation> =
        ALL.filter { it.text in favorites(context) }

    fun poolFor(context: Context, category: Category?): List<Contemplation> =
        if (category == null) allFavorites(context) else ALL.filter { it.category == category }

    private fun favorites(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVS, emptySet()) ?: emptySet()

    private fun prefs(context: Context) =
        context.getSharedPreferences("zen_stats", Context.MODE_PRIVATE)

    private const val KEY_FAVS = "contemplation_favs"
}
