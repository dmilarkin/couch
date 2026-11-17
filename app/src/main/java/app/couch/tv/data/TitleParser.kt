package app.couch.tv.data

import java.text.Normalizer
import java.util.Calendar

/**
 * IPTV providers stuff a lot into names: "EN - The Italian Job (1969) [4K]",
 * "|FR| Les Bronzés FHD", "UK: BBC One HD". This pulls out a clean title plus
 * language, year and quality so the app can build shelves and merge duplicates.
 */
object TitleParser {

    data class Parsed(val title: String, val year: Int, val lang: String?, val quality: String?)

    private val CODES: Map<String, String> = mapOf(
        "EN" to "English", "ENG" to "English", "UK" to "English", "US" to "English", "USA" to "English",
        "GB" to "English", "AU" to "English", "NZ" to "English", "IE" to "English",
        "FR" to "French", "FRA" to "French", "FRE" to "French", "VF" to "French", "VFF" to "French",
        "VFQ" to "French", "VOSTFR" to "French", "QC" to "French", "BE" to "French",
        "DE" to "German", "GER" to "German", "DEU" to "German", "AT" to "German",
        "ES" to "Spanish", "ESP" to "Spanish", "SPA" to "Spanish", "LAT" to "Spanish", "LATAM" to "Spanish", "MX" to "Spanish",
        "IT" to "Italian", "ITA" to "Italian",
        "PT" to "Portuguese", "POR" to "Portuguese", "BR" to "Portuguese", "PTBR" to "Portuguese",
        "NL" to "Dutch", "NLD" to "Dutch",
        "PL" to "Polish", "POL" to "Polish",
        "TR" to "Turkish", "TUR" to "Turkish",
        "AR" to "Arabic", "ARA" to "Arabic", "ARAB" to "Arabic",
        "HI" to "Hindi", "HIN" to "Hindi", "IN" to "Hindi", "IND" to "Hindi",
        "RU" to "Russian", "RUS" to "Russian",
        "SE" to "Swedish", "SV" to "Swedish", "SWE" to "Swedish",
        "NO" to "Norwegian", "NOR" to "Norwegian",
        "DK" to "Danish", "DA" to "Danish", "DAN" to "Danish",
        "FI" to "Finnish", "FIN" to "Finnish",
        "GR" to "Greek", "GRE" to "Greek", "EL" to "Greek",
        "RO" to "Romanian", "ROM" to "Romanian",
        "HU" to "Hungarian", "HUN" to "Hungarian",
        "CZ" to "Czech", "CS" to "Czech",
        "AL" to "Albanian", "ALB" to "Albanian",
        "KR" to "Korean", "KO" to "Korean", "KOR" to "Korean",
        "JP" to "Japanese", "JA" to "Japanese", "JAP" to "Japanese", "JPN" to "Japanese",
        "CN" to "Chinese", "ZH" to "Chinese", "CHI" to "Chinese",
        "IR" to "Persian", "FA" to "Persian", "PER" to "Persian",
        "KU" to "Kurdish", "KUR" to "Kurdish",
        "PK" to "Urdu", "UR" to "Urdu",
        "BG" to "Bulgarian", "HR" to "Croatian", "RS" to "Serbian", "SR" to "Serbian",
        "EXYU" to "Ex-Yu", "YU" to "Ex-Yu",
        "IL" to "Hebrew", "HE" to "Hebrew", "TH" to "Thai", "VN" to "Vietnamese", "VI" to "Vietnamese",
        "PH" to "Filipino", "TL" to "Filipino",
    )

    private val WORDS: Map<String, String> = mapOf(
        "ENGLISH" to "English", "FRENCH" to "French", "FRANCE" to "French", "FRANCAIS" to "French",
        "QUEBEC" to "French", "GERMAN" to "German", "DEUTSCH" to "German", "GERMANY" to "German",
        "SPANISH" to "Spanish", "ESPANOL" to "Spanish", "SPAIN" to "Spanish", "LATINO" to "Spanish",
        "ITALIAN" to "Italian", "ITALIA" to "Italian", "ITALY" to "Italian",
        "PORTUGUESE" to "Portuguese", "PORTUGAL" to "Portuguese", "BRAZIL" to "Portuguese", "BRASIL" to "Portuguese",
        "DUTCH" to "Dutch", "NETHERLANDS" to "Dutch", "POLISH" to "Polish", "POLSKA" to "Polish",
        "TURKISH" to "Turkish", "TURKIYE" to "Turkish", "TURKEY" to "Turkish",
        "ARABIC" to "Arabic", "HINDI" to "Hindi", "BOLLYWOOD" to "Hindi", "RUSSIAN" to "Russian",
        "SWEDISH" to "Swedish", "NORWEGIAN" to "Norwegian", "DANISH" to "Danish", "FINNISH" to "Finnish",
        "GREEK" to "Greek", "ROMANIAN" to "Romanian", "HUNGARIAN" to "Hungarian", "CZECH" to "Czech",
        "ALBANIAN" to "Albanian", "KOREAN" to "Korean", "KDRAMA" to "Korean", "JAPANESE" to "Japanese",
        "CHINESE" to "Chinese", "PERSIAN" to "Persian", "KURDISH" to "Kurdish", "URDU" to "Urdu",
        "PAKISTAN" to "Urdu", "TAMIL" to "Tamil", "TELUGU" to "Telugu", "MALAYALAM" to "Malayalam",
        "PUNJABI" to "Punjabi", "BENGALI" to "Bengali", "HEBREW" to "Hebrew", "THAI" to "Thai",
        "VIETNAMESE" to "Vietnamese", "FILIPINO" to "Filipino",
    )

    /** ISO 639-1 codes for the language names above (used for audio and subtitle preferences). */
    val ISO: Map<String, String> = mapOf(
        "English" to "en", "French" to "fr", "German" to "de", "Spanish" to "es", "Italian" to "it",
        "Portuguese" to "pt", "Dutch" to "nl", "Polish" to "pl", "Turkish" to "tr", "Arabic" to "ar",
        "Hindi" to "hi", "Russian" to "ru", "Swedish" to "sv", "Norwegian" to "no", "Danish" to "da",
        "Finnish" to "fi", "Greek" to "el", "Romanian" to "ro", "Hungarian" to "hu", "Czech" to "cs",
        "Albanian" to "sq", "Korean" to "ko", "Japanese" to "ja", "Chinese" to "zh", "Persian" to "fa",
        "Kurdish" to "ku", "Urdu" to "ur", "Bulgarian" to "bg", "Croatian" to "hr", "Serbian" to "sr",
        "Hebrew" to "he", "Thai" to "th", "Vietnamese" to "vi", "Filipino" to "tl", "Tamil" to "ta",
        "Telugu" to "te", "Malayalam" to "ml", "Punjabi" to "pa", "Bengali" to "bn",
    )

    private val QUALITY: Map<String, String> = mapOf(
        "4K" to "4K", "UHD" to "4K", "2160P" to "4K", "2160" to "4K",
        "FHD" to "1080p", "1080P" to "1080p", "1080" to "1080p",
        "HD" to "720p", "720P" to "720p", "720" to "720p",
        "SD" to "SD", "480P" to "SD", "576P" to "SD",
    )

    /** Tags that are safe to drop at the end of a title ("Alien FHD HEVC"). Kept strict so real words survive. */
    private val TAIL_JUNK = setOf(
        "MULTI", "MULTISUB", "HEVC", "H265", "X265", "H264", "X264", "HDR", "HDR10", "ATMOS",
        "REMUX", "10BIT", "WEBDL", "WEBRIP", "BLURAY", "BRRIP", "HDRIP", "DVDRIP", "AAC", "AC3", "VOSTFR",
    )

    /** Tags that may also appear as a leading prefix or inside brackets. */
    private val JUNK = TAIL_JUNK + setOf(
        "VOD", "MOVIE", "MOVIES", "FILM", "FILMS", "NEW", "TOP", "SUB", "SUBS", "DUB", "DUAL", "VIP",
        "DV", "DOLBY", "3D", "IMAX", "VO", "VOST", "STFR", "CAM", "TS", "WEB", "DTS",
    )

    private val GENRES: List<Pair<String, List<String>>> = listOf(
        "Action" to listOf("ACTION"),
        "Adventure" to listOf("ADVENTURE", "AVENTURE", "AVENTURA"),
        "Animation" to listOf("ANIMATION", "ANIME", "CARTOON", "CARTOONS", "ANIMATED", "ANIMACION"),
        "Comedy" to listOf("COMEDY", "COMEDIE", "COMEDIES", "COMEDIA", "COMMEDIA", "KOMODIE", "HUMOR", "HUMOUR"),
        "Crime" to listOf("CRIME", "POLICIER", "GANGSTER", "CRIMEN"),
        "Documentary" to listOf("DOCUMENTARY", "DOCUMENTARIES", "DOCUMENTAIRE", "DOCUMENTAIRES", "DOCU", "DOKU", "DOCUMENTAL"),
        "Drama" to listOf("DRAMA", "DRAME", "DRAMAS", "DRAMES"),
        "Family" to listOf("FAMILY", "FAMILLE", "KIDS", "ENFANTS", "ENFANT", "JEUNESSE", "KINDER", "CHILDREN", "INFANTIL"),
        "Fantasy" to listOf("FANTASY", "FANTASTIQUE", "FANTASIA"),
        "Horror" to listOf("HORROR", "HORREUR", "EPOUVANTE", "TERROR"),
        "Music" to listOf("MUSIC", "MUSICAL", "MUSIQUE", "CONCERT", "CONCERTS"),
        "Mystery" to listOf("MYSTERY", "MYSTERE"),
        "Romance" to listOf("ROMANCE", "ROMANTIC", "ROMANTIQUE"),
        "Sci-Fi" to listOf("SCIFI", "SCI", "SCIENCE"),
        "Thriller" to listOf("THRILLER", "THRILLERS", "SUSPENSE"),
        "War" to listOf("WAR", "GUERRE", "MILITARY", "BELICO"),
        "Western" to listOf("WESTERN", "WESTERNS"),
        "History" to listOf("HISTORY", "HISTORICAL", "HISTOIRE", "HISTORIQUE"),
        "Sport" to listOf("SPORT", "SPORTS"),
        "Holiday" to listOf("CHRISTMAS", "NOEL", "XMAS", "HOLIDAY", "NAVIDAD"),
        "Stand-up" to listOf("STANDUP"),
    )

    val GENRE_NAMES: List<String> = GENRES.map { it.first }
    private val GENRE_LOOKUP: Map<String, String> = buildMap {
        GENRES.forEach { (name, words) -> words.forEach { put(it, name) } }
    }

    private val MAX_YEAR = Calendar.getInstance().get(Calendar.YEAR) + 1

    private val PREFIX = Regex("^\\s*[\\[(|]?\\s*([A-Za-z0-9+]{2,7})\\s*[\\])|:\\-–—]+\\s*")
    private val BRACKET = Regex("[\\[(]([^\\[\\]()]{1,40})[\\])]")
    private val YEAR_ONLY = Regex("^\\s*((?:19|20)\\d{2})\\s*$")
    private val YEAR_TAIL = Regex("\\s+[-–|]\\s*((?:19|20)\\d{2})\\s*$")
    private val TAIL_TOKEN = Regex("[\\s\\-|]+([A-Za-z0-9.+]{2,7})\\s*$")
    private val SPLIT = Regex("[^A-Z0-9]+")

    private fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun tokens(s: String): List<String> =
        stripAccents(s).uppercase().split(SPLIT).filter { it.isNotEmpty() }

    /** Lowercase, accent-free, punctuation-free version used for search and duplicate matching. */
    fun normalize(s: String): String =
        stripAccents(s).lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun parse(raw: String, catLang: String? = null): Parsed {
        var s = raw.replace('_', ' ').trim()
        var lang: String? = null
        var quality: String? = null
        var year = 0

        // 1. Leading tags such as "EN - ", "|FR| ", "[4K] ", "UK: "
        for (i in 0 until 3) {
            val m = PREFIX.find(s) ?: break
            val tok = m.groupValues[1]
            val up = tok.uppercase()
            val end = m.range.last + 1
            when {
                tok == up && CODES.containsKey(up) -> {
                    if (lang == null) lang = CODES[up]
                    s = s.substring(end)
                }
                QUALITY.containsKey(up) -> {
                    if (quality == null) quality = QUALITY[up]
                    s = s.substring(end)
                }
                JUNK.contains(up) -> s = s.substring(end)
                else -> break
            }
        }

        // 2. Bracketed bits: (1969) [4K] (MULTI) [FR]
        s = BRACKET.replace(s) { mr ->
            val inner = mr.groupValues[1].trim()
            val up = stripAccents(inner).uppercase()
            val y = YEAR_ONLY.matchEntire(inner)?.groupValues?.get(1)?.toIntOrNull()
            val parts = up.split(SPLIT).filter { it.isNotEmpty() }
            when {
                y != null && y in 1900..MAX_YEAR -> {
                    if (year == 0) year = y
                    ""
                }
                QUALITY.containsKey(up) -> {
                    if (quality == null) quality = QUALITY[up]
                    ""
                }
                inner == inner.uppercase() && CODES.containsKey(up) -> {
                    if (lang == null) lang = CODES[up]
                    ""
                }
                parts.isNotEmpty() && parts.all { JUNK.contains(it) || QUALITY.containsKey(it) || CODES.containsKey(it) } -> {
                    if (quality == null) quality = parts.firstNotNullOfOrNull { QUALITY[it] }
                    ""
                }
                else -> mr.value
            }
        }

        // 3. Trailing " - 1969"
        YEAR_TAIL.find(s)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: 0
            if (y in 1900..MAX_YEAR) {
                if (year == 0) year = y
                s = s.substring(0, m.range.first)
            }
        }

        // 4. Trailing quality / junk tokens: "Inception 4K", "Alien FHD HEVC"
        for (i in 0 until 3) {
            val m = TAIL_TOKEN.find(s) ?: break
            val up = m.groupValues[1].uppercase().replace(".", "")
            when {
                QUALITY.containsKey(up) -> {
                    if (quality == null) quality = QUALITY[up]
                    s = s.substring(0, m.range.first)
                }
                TAIL_JUNK.contains(up) -> s = s.substring(0, m.range.first)
                else -> break
            }
        }

        var title = s.replace(Regex("\\s{2,}"), " ").trim().trim('-', '|', ':', '.', ' ', '–', '—', '•')
        if (title.isBlank()) title = raw.trim()
        return Parsed(title, year, lang ?: catLang, quality)
    }

    fun categoryLang(name: String): String? {
        val toks = tokens(name)
        toks.forEach { t -> WORDS[t]?.let { return it } }
        val first = toks.firstOrNull() ?: return null
        // Only trust a short code when it's the leading tag ("EN | Action", "FR - Films", "UK: Movies")
        // or a bracketed tag anywhere ("NETFLIX (EN)", "Kids [FR]").
        if (Regex("^\\s*[\\[(|]?\\s*[A-Za-z]{2,5}\\s*[\\])|:\\-–]").containsMatchIn(name)) CODES[first]?.let { return it }
        Regex("[\\[(|]\\s*([A-Z]{2,4})\\s*[\\])|]").findAll(name).forEach { m -> CODES[m.groupValues[1]]?.let { return it } }
        return null
    }

    fun qualityFrom(name: String?): String? {
        if (name == null) return null
        return tokens(name).firstNotNullOfOrNull { t -> if (t == "HD" || t == "SD") null else QUALITY[t] }
    }

    fun genres(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return tokens(text).mapNotNull { GENRE_LOOKUP[it] }.distinct()
    }

    fun isAdult(name: String): Boolean {
        val up = name.uppercase()
        return up.contains("XXX") || up.contains("ADULT") || up.contains("18+") || up.contains("PORN") || up.contains("+18")
    }

    fun qualityRank(q: String?): Int = when (q) {
        "4K" -> 4
        "1080p" -> 3
        "720p" -> 2
        "SD" -> 1
        else -> 2
    }

    fun yearFrom(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val m = Regex("(?:19|20)\\d{2}").find(s) ?: return null
        return m.value.toIntOrNull()?.takeIf { it in 1900..MAX_YEAR }
    }

    fun rating(r: String?, r5: String?): Double {
        val v = r?.let { Regex("\\d+(?:[.,]\\d+)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() } ?: 0.0
        val fixed = when {
            v > 10.0 && v <= 100.0 -> v / 10.0
            v > 100.0 -> 0.0
            else -> v
        }
        if (fixed > 0.0) return fixed
        val five = r5?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        return if (five in 0.0..5.0) five * 2.0 else 0.0
    }
}
