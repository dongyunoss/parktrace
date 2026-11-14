package com.parktrace.core

/**
 * Extracts parking-garage codes from OCR text read off pillars / wall signs.
 *
 * Typical Korean garage signage:
 *   "B2"  "C15"      (floor + pillar on separate lines)
 *   "B2-C15"         (combined)
 *   "지하2층 A-3"
 *   "B3 가12"
 *   "2F D7"
 *
 * Pure Kotlin: unit-tested on the JVM (SignTextParserTest).
 */
object SignTextParser {

    data class Observation(
        /** Negative = basement, positive = above ground. null if this line had no floor code. */
        val floorLevel: Int?,
        /** "C-15", "가-12" ... null if no pillar code. */
        val spot: String?,
        val raw: String,
    )

    private const val ZONES = "가나다라마바사아자차카타파하"

    private val FLOOR_PATTERNS: List<Pair<Regex, (MatchResult) -> Int>> = listOf(
        Regex("지하\\s*(\\d{1,2})\\s*층?") to { m: MatchResult -> -m.groupValues[1].toInt() },
        Regex("(?<![A-Z0-9])B\\s*-?\\s*(\\d)\\s*(?:층|F)") to { m: MatchResult -> -m.groupValues[1].toInt() },
        // "B2" alone or "B2-C15": a single digit after B is treated as a floor (pillars rarely go 1..9 only with B zone)
        Regex("(?<![A-Z0-9])B(\\d)(?![0-9])") to { m: MatchResult -> -m.groupValues[1].toInt() },
        Regex("(?<![0-9A-Z])(\\d{1,2})\\s*(?:층|F)(?![A-Z])") to { m: MatchResult -> m.groupValues[1].toInt() },
    )

    private val SPOT_PATTERN = Regex("(?<![A-Z0-9가-힣])([A-Z]|[$ZONES])\\s*-?\\s*(\\d{1,3})(?![0-9])")

    fun parse(ocrText: String): List<Observation> =
        ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { parseLine(it) }

    fun parseLine(line: String): Observation? {
        var s = normalize(line)
        var floor: Int? = null
        for ((re, toLevel) in FLOOR_PATTERNS) {
            val m = re.find(s) ?: continue
            val lvl = toLevel(m)
            if (lvl == 0 || lvl > 30 || lvl < -9) continue
            floor = lvl
            s = s.removeRange(m.range).let { " $it " }
            break
        }
        val spotMatch = SPOT_PATTERN.find(s)
        val spot = spotMatch?.let { "${it.groupValues[1]}-${it.groupValues[2].trimStart('0').ifEmpty { "0" }}" }
        if (floor == null && spot == null) return null
        return Observation(floor, spot, line)
    }

    /** Uppercase + fix common OCR confusions inside codes (O→0, I/L→1, S→5 when next to digits). */
    fun normalize(line: String): String {
        var s = line.uppercase()
            .replace('–', '-').replace('—', '-').replace('_', '-')
            .replace(Regex("[|:;.,]"), " ")
        // letter immediately followed by an OCR-confused digit-like char, e.g. "CI5" -> "C15", "BO3" -> "B03"
        s = s.replace(Regex("(?<=[A-Z0-9])([OILS])(?=[0-9])|(?<=[0-9])([OILS])(?=[0-9]|\\b)")) { m ->
            when (m.value) { "O" -> "0"; "I", "L" -> "1"; "S" -> "5"; else -> m.value }
        }
        return " " + s.replace(Regex("\\s+"), " ").trim() + " "
    }
}
