package mint.app.resolution.spotify

import android.os.Build
import java.text.Normalizer
import kotlin.math.abs

object MusicMatcher {

    data class Target(
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int?,
    )

    private data class Scored(
        val candidate: MusicCandidate,
        val score: Double,
        val titleScore: Double,
        val artistScore: Double,
        val durationScore: Double?,
        val albumScore: Double?,
        val unexpectedAlternates: Set<String>,
    )

    private data class VersionMarker(val name: String, val pattern: Regex, val hardReject: Boolean)

    private val FEAT = Regex("""\s*[\(\[]\s*(feat|ft)\..*?[\)\]]""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{Nd}\s]""")
    private val SPACES = Regex("""\s+""")
    private val COMBINING = Regex("""\p{Mn}+""")

    private val transliterator by lazy {
        if (Build.VERSION.SDK_INT < 29) {
            null
        } else {
            runCatching { android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
        }
    }

    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    private fun markerPattern(terms: String): Regex = Regex("""(^|\s)($terms)(\s|$)""", RegexOption.IGNORE_CASE)

    private val MARKERS = listOf(
        VersionMarker("remix", markerPattern("""re\s*mix|rmx|club mix|dance mix|dub mix|vip mix|ремикс|рмикс"""), true),
        VersionMarker("alternate", markerPattern("""alternative|alternate|alt version|demo|demo version|unreleased|rough mix|early version|альтернатив\w*|демо|неиздан\w*|чернов\w*"""), true),
        VersionMarker("sped up", markerPattern("""sped\s*up|speed\s*up|fast version|ускоренн\w*|быстрая версия"""), true),
        VersionMarker("slowed", markerPattern("""slowed|slowed reverb|slow version|замедленн\w*|медленная версия"""), true),
        VersionMarker("nightcore", markerPattern("""nightcore|daycore"""), true),
        VersionMarker("live", markerPattern("""live|concert|session|performance|лайв|концерт|с концерта|выступлен\w*"""), true),
        VersionMarker("acoustic", markerPattern("""acoustic|unplugged|piano version|guitar version|акустик\w*|пианино|гитар\w*"""), true),
        VersionMarker("cover", markerPattern("""cover|covered by|tribute|кавер|трибьют"""), true),
        VersionMarker("karaoke", markerPattern("""karaoke|minus one|караоке|минусовка"""), true),
        VersionMarker("instrumental", markerPattern("""instrumental|no vocals|инструментал|без вокала"""), true),
        VersionMarker("mashup", markerPattern("""mashup|mash up|bootleg|rework|flip|мешап|мэшап|бутлег"""), true),
        VersionMarker("fan edit", markerPattern("""fan edit|fanmade|right version|edit audio|перезалив|перезалит\w*"""), true),
        VersionMarker("extended", markerPattern("""extended mix|extended version|12 inch|12"""), false),
        VersionMarker("radio edit", markerPattern("""radio edit|single edit|edit version"""), false),
        VersionMarker("remaster", markerPattern("""remaster|remastered|anniversary edition"""), false),
    )

    private val HARD_MARKERS = MARKERS.filter { it.hardReject }.map { it.name }.toSet()

    fun best(candidates: List<MusicCandidate>, target: Target): MusicCandidate? {
        if (candidates.isEmpty()) return null
        val scored = candidates.map { score(it, target) }
        val accepted = scored.filter { acceptable(it) }
        if (accepted.isNotEmpty()) return accepted.maxByOrNull { it.score }?.candidate
        return scored.filter { it.titleScore >= 0.6 }.maxByOrNull { it.score }?.candidate
    }

    fun bigramSimilarity(a: String, b: String): Double {
        fun variants(value: String): List<String> = listOfNotNull(
            value,
            foldDiacritics(value),
            transliterateCyrillic(value),
            if (Build.VERSION.SDK_INT >= 29) transliterator?.transliterate(value) else null,
        ).map(::normalized).filter { it.isNotBlank() }.distinct()

        fun score(na: String, nb: String): Double {
            if (na == nb) return 1.0
            if (na.length < 2 || nb.length < 2) return 0.0
            val aBigrams = na.windowed(2).toSet()
            val bBigrams = nb.windowed(2).toSet()
            if (aBigrams.isEmpty() || bBigrams.isEmpty()) return 0.0
            val intersection = aBigrams.count { it in bBigrams }
            return (2.0 * intersection) / (aBigrams.size + bBigrams.size)
        }

        return variants(a).maxOf { va -> variants(b).maxOf { vb -> score(va, vb) } }
    }

    private fun score(candidate: MusicCandidate, target: Target): Scored {
        val titleScore = bigramSimilarity(candidate.title, target.title)
        val artistScore = bigramSimilarity(candidate.artist, target.artist)

        val expectedSec = target.durationSec?.toDouble()?.takeIf { it > 0 }
        val candidateSec = candidate.durationSec?.takeIf { it > 0 }?.toDouble()
        val durationScore = if (expectedSec != null && candidateSec != null) {
            (1.0 - abs(candidateSec - expectedSec) * 2.0 / expectedSec).coerceIn(0.0, 1.0)
        } else {
            null
        }

        val albumScore = if (target.album.isNotBlank() && candidate.album.isNotBlank()) {
            bigramSimilarity(candidate.album, target.album)
        } else {
            null
        }

        val expectedMarkers = versionMarkers(target.title)
        val candidateMarkers = versionMarkers(listOf(candidate.title, candidate.album).joinToString(" "))
        val unexpected = candidateMarkers - expectedMarkers
        val hardUnexpected = unexpected.count { it in HARD_MARKERS }
        val softUnexpected = unexpected.size - hardUnexpected
        val penalty = hardUnexpected * 1.75 + softUnexpected * 0.65

        val parts = mutableListOf(titleScore, artistScore)
        durationScore?.let { parts += it * 5.0 }
        albumScore?.let { parts += it }
        val base = parts.average() * 2.0

        return Scored(
            candidate = candidate,
            score = (base - penalty).coerceAtLeast(0.0),
            titleScore = titleScore,
            artistScore = artistScore,
            durationScore = durationScore,
            albumScore = albumScore,
            unexpectedAlternates = unexpected,
        )
    }

    private fun acceptable(scored: Scored): Boolean {
        val durationStrong = scored.durationScore?.let { it >= 0.94 } ?: false
        val albumUseful = scored.albumScore?.let { it >= 0.45 } ?: false
        val minScore = if (scored.durationScore != null) 2.25 else 1.35
        val hasHardAlternate = scored.unexpectedAlternates.any { it in HARD_MARKERS }
        return scored.score >= minScore &&
            !hasHardAlternate &&
            scored.titleScore >= 0.45 &&
            (
                scored.artistScore >= 0.32 ||
                    (albumUseful && scored.artistScore >= 0.18) ||
                    (durationStrong && scored.artistScore >= 0.25)
                )
    }

    private fun versionMarkers(value: String): Set<String> {
        val text = normalized(value)
        return MARKERS.filter { it.pattern.containsMatchIn(text) }.map { it.name }.toSet()
    }

    private fun normalized(value: String): String = value.lowercase()
        .replace(FEAT, "")
        .replace(NON_ALNUM, " ")
        .replace(SPACES, " ")
        .trim()

    private fun foldDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(COMBINING, "")

    private fun transliterateCyrillic(value: String): String = buildString {
        value.lowercase().forEach { ch -> append(CYRILLIC[ch] ?: ch) }
    }
}
