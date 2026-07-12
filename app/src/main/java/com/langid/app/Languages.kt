package com.langid.app

/**
 * Bảng tra cứu toàn bộ 99 ngôn ngữ mà Whisper nhận diện được,
 * ánh xạ sang: tên tiếng Việt, các quốc gia sử dụng, và mã BCP-47 để đọc TTS.
 *
 * Bảng này được gửi kèm cho Claude làm tham chiếu, đảm bảo phủ hết
 * mọi nước trên thế giới chứ không chỉ vài ngôn ngữ phổ biến.
 */
data class LangInfo(
    val vi: String,          // tên tiếng Việt
    val countries: String,   // các nước dùng, nước chính đứng đầu
    val tag: String          // BCP-47 cho TextToSpeech
)

object Languages {

    val MAP: Map<String, LangInfo> = mapOf(
        // ── Nam Á ────────────────────────────────────────────────
        "bengali"    to LangInfo("Tiếng Bengali", "Bangladesh (chính), Ấn Độ (Tây Bengal, Tripura)", "bn-BD"),
        "hindi"      to LangInfo("Tiếng Hindi", "Ấn Độ (chính), Nepal, Fiji", "hi-IN"),
        "urdu"       to LangInfo("Tiếng Urdu", "Pakistan (chính), Ấn Độ", "ur-PK"),
        "punjabi"    to LangInfo("Tiếng Punjab", "Pakistan, Ấn Độ", "pa-IN"),
        "tamil"      to LangInfo("Tiếng Tamil", "Ấn Độ (Tamil Nadu), Sri Lanka, Singapore, Malaysia", "ta-IN"),
        "telugu"     to LangInfo("Tiếng Telugu", "Ấn Độ (Andhra Pradesh, Telangana)", "te-IN"),
        "marathi"    to LangInfo("Tiếng Marathi", "Ấn Độ (Maharashtra)", "mr-IN"),
        "gujarati"   to LangInfo("Tiếng Gujarat", "Ấn Độ (Gujarat)", "gu-IN"),
        "kannada"    to LangInfo("Tiếng Kannada", "Ấn Độ (Karnataka)", "kn-IN"),
        "malayalam"  to LangInfo("Tiếng Malayalam", "Ấn Độ (Kerala)", "ml-IN"),
        "sindhi"     to LangInfo("Tiếng Sindhi", "Pakistan (Sindh), Ấn Độ", "sd-PK"),
        "nepali"     to LangInfo("Tiếng Nepal", "Nepal (chính), Ấn Độ (Sikkim), Bhutan", "ne-NP"),
        "sinhala"    to LangInfo("Tiếng Sinhala", "Sri Lanka", "si-LK"),
        "pashto"     to LangInfo("Tiếng Pashto", "Afghanistan (chính), Pakistan", "ps-AF"),
        "assamese"   to LangInfo("Tiếng Assam", "Ấn Độ (Assam)", "as-IN"),
        "sanskrit"   to LangInfo("Tiếng Phạn", "Ấn Độ (nghi lễ, học thuật)", "hi-IN"),

        // ── Đông Nam Á ───────────────────────────────────────────
        "vietnamese" to LangInfo("Tiếng Việt", "Việt Nam", "vi-VN"),
        "thai"       to LangInfo("Tiếng Thái", "Thái Lan", "th-TH"),
        "indonesian" to LangInfo("Tiếng Indonesia", "Indonesia", "id-ID"),
        "malay"      to LangInfo("Tiếng Mã Lai", "Malaysia, Brunei, Singapore", "ms-MY"),
        "tagalog"    to LangInfo("Tiếng Tagalog", "Philippines", "fil-PH"),
        "khmer"      to LangInfo("Tiếng Khmer", "Campuchia", "km-KH"),
        "lao"        to LangInfo("Tiếng Lào", "Lào", "lo-LA"),
        "myanmar"    to LangInfo("Tiếng Miến Điện", "Myanmar", "my-MM"),
        "burmese"    to LangInfo("Tiếng Miến Điện", "Myanmar", "my-MM"),
        "javanese"   to LangInfo("Tiếng Java", "Indonesia (đảo Java)", "id-ID"),
        "sundanese"  to LangInfo("Tiếng Sunda", "Indonesia (Tây Java)", "id-ID"),

        // ── Đông Á ───────────────────────────────────────────────
        "chinese"    to LangInfo("Tiếng Trung", "Trung Quốc, Đài Loan, Singapore, Malaysia", "zh-CN"),
        "japanese"   to LangInfo("Tiếng Nhật", "Nhật Bản", "ja-JP"),
        "korean"     to LangInfo("Tiếng Hàn", "Hàn Quốc, Triều Tiên", "ko-KR"),
        "cantonese"  to LangInfo("Tiếng Quảng Đông", "Hồng Kông, Ma Cao, Trung Quốc (Quảng Đông)", "yue-HK"),
        "mongolian"  to LangInfo("Tiếng Mông Cổ", "Mông Cổ, Trung Quốc (Nội Mông)", "mn-MN"),
        "tibetan"    to LangInfo("Tiếng Tây Tạng", "Trung Quốc (Tây Tạng), Nepal, Ấn Độ", "bo-CN"),

        // ── Trung Đông & Trung Á ─────────────────────────────────
        "arabic"     to LangInfo("Tiếng Ả Rập", "Ai Cập, Ả Rập Xê Út, UAE, Iraq, Syria, Jordan, Palestine, Maroc, Algeria, Sudan...", "ar-SA"),
        "hebrew"     to LangInfo("Tiếng Do Thái", "Israel", "he-IL"),
        "persian"    to LangInfo("Tiếng Ba Tư (Farsi)", "Iran (chính), Afghanistan (Dari), Tajikistan", "fa-IR"),
        "turkish"    to LangInfo("Tiếng Thổ Nhĩ Kỳ", "Thổ Nhĩ Kỳ, Síp", "tr-TR"),
        "azerbaijani" to LangInfo("Tiếng Azerbaijan", "Azerbaijan, Iran", "az-AZ"),
        "armenian"   to LangInfo("Tiếng Armenia", "Armenia", "hy-AM"),
        "georgian"   to LangInfo("Tiếng Gruzia", "Georgia", "ka-GE"),
        "kazakh"     to LangInfo("Tiếng Kazakh", "Kazakhstan", "kk-KZ"),
        "uzbek"      to LangInfo("Tiếng Uzbek", "Uzbekistan", "uz-UZ"),
        "turkmen"    to LangInfo("Tiếng Turkmen", "Turkmenistan", "tk-TM"),
        "tajik"      to LangInfo("Tiếng Tajik", "Tajikistan", "tg-TJ"),
        "kurdish"    to LangInfo("Tiếng Kurd", "Iraq, Thổ Nhĩ Kỳ, Iran, Syria", "ku-TR"),
        "yiddish"    to LangInfo("Tiếng Yiddish", "Israel, Mỹ (cộng đồng Do Thái)", "yi"),

        // ── Châu Âu ──────────────────────────────────────────────
        "english"    to LangInfo("Tiếng Anh", "Mỹ, Anh, Úc, Canada, Ấn Độ, Nigeria, Philippines, Nam Phi... (rất rộng)", "en-US"),
        "spanish"    to LangInfo("Tiếng Tây Ban Nha", "Tây Ban Nha, Mexico, Argentina, Colombia, Peru, Chile...", "es-ES"),
        "french"     to LangInfo("Tiếng Pháp", "Pháp, Canada (Québec), Bỉ, Thụy Sĩ, Senegal, Côte d'Ivoire, Congo...", "fr-FR"),
        "german"     to LangInfo("Tiếng Đức", "Đức, Áo, Thụy Sĩ", "de-DE"),
        "italian"    to LangInfo("Tiếng Ý", "Ý, Thụy Sĩ, San Marino", "it-IT"),
        "portuguese" to LangInfo("Tiếng Bồ Đào Nha", "Brazil (đông nhất), Bồ Đào Nha, Angola, Mozambique", "pt-BR"),
        "russian"    to LangInfo("Tiếng Nga", "Nga, Belarus, Kazakhstan, Ukraine, Kyrgyzstan", "ru-RU"),
        "ukrainian"  to LangInfo("Tiếng Ukraina", "Ukraina", "uk-UA"),
        "polish"     to LangInfo("Tiếng Ba Lan", "Ba Lan", "pl-PL"),
        "dutch"      to LangInfo("Tiếng Hà Lan", "Hà Lan, Bỉ (Flanders), Suriname", "nl-NL"),
        "romanian"   to LangInfo("Tiếng Romania", "Romania, Moldova", "ro-RO"),
        "greek"      to LangInfo("Tiếng Hy Lạp", "Hy Lạp, Síp", "el-GR"),
        "czech"      to LangInfo("Tiếng Séc", "Cộng hòa Séc", "cs-CZ"),
        "slovak"     to LangInfo("Tiếng Slovak", "Slovakia", "sk-SK"),
        "hungarian"  to LangInfo("Tiếng Hungary", "Hungary", "hu-HU"),
        "swedish"    to LangInfo("Tiếng Thụy Điển", "Thụy Điển, Phần Lan", "sv-SE"),
        "norwegian"  to LangInfo("Tiếng Na Uy", "Na Uy", "nb-NO"),
        "nynorsk"    to LangInfo("Tiếng Na Uy (Nynorsk)", "Na Uy", "nn-NO"),
        "danish"     to LangInfo("Tiếng Đan Mạch", "Đan Mạch", "da-DK"),
        "finnish"    to LangInfo("Tiếng Phần Lan", "Phần Lan", "fi-FI"),
        "icelandic"  to LangInfo("Tiếng Iceland", "Iceland", "is-IS"),
        "estonian"   to LangInfo("Tiếng Estonia", "Estonia", "et-EE"),
        "latvian"    to LangInfo("Tiếng Latvia", "Latvia", "lv-LV"),
        "lithuanian" to LangInfo("Tiếng Litva", "Litva", "lt-LT"),
        "bulgarian"  to LangInfo("Tiếng Bulgaria", "Bulgaria", "bg-BG"),
        "serbian"    to LangInfo("Tiếng Serbia", "Serbia, Bosnia, Montenegro", "sr-RS"),
        "croatian"   to LangInfo("Tiếng Croatia", "Croatia, Bosnia", "hr-HR"),
        "bosnian"    to LangInfo("Tiếng Bosnia", "Bosnia và Herzegovina", "bs-BA"),
        "slovenian"  to LangInfo("Tiếng Slovenia", "Slovenia", "sl-SI"),
        "macedonian" to LangInfo("Tiếng Macedonia", "Bắc Macedonia", "mk-MK"),
        "albanian"   to LangInfo("Tiếng Albania", "Albania, Kosovo", "sq-AL"),
        "belarusian" to LangInfo("Tiếng Belarus", "Belarus", "be-BY"),
        "catalan"    to LangInfo("Tiếng Catalan", "Tây Ban Nha (Catalonia), Andorra", "ca-ES"),
        "galician"   to LangInfo("Tiếng Galicia", "Tây Ban Nha (Galicia)", "gl-ES"),
        "basque"     to LangInfo("Tiếng Basque", "Tây Ban Nha, Pháp (vùng Basque)", "eu-ES"),
        "welsh"      to LangInfo("Tiếng Wales", "Anh (xứ Wales)", "cy-GB"),
        "maltese"    to LangInfo("Tiếng Malta", "Malta", "mt-MT"),
        "luxembourgish" to LangInfo("Tiếng Luxembourg", "Luxembourg", "lb-LU"),
        "breton"     to LangInfo("Tiếng Breton", "Pháp (Bretagne)", "br-FR"),
        "faroese"    to LangInfo("Tiếng Faroe", "Quần đảo Faroe (Đan Mạch)", "fo-FO"),
        "occitan"    to LangInfo("Tiếng Occitan", "Pháp (miền Nam)", "oc-FR"),

        // ── Châu Phi ─────────────────────────────────────────────
        "swahili"    to LangInfo("Tiếng Swahili", "Tanzania, Kenya, Uganda, Congo", "sw-KE"),
        "amharic"    to LangInfo("Tiếng Amhara", "Ethiopia", "am-ET"),
        "somali"     to LangInfo("Tiếng Somali", "Somalia, Ethiopia, Djibouti", "so-SO"),
        "yoruba"     to LangInfo("Tiếng Yoruba", "Nigeria, Benin", "yo-NG"),
        "hausa"      to LangInfo("Tiếng Hausa", "Nigeria, Niger", "ha-NG"),
        "shona"      to LangInfo("Tiếng Shona", "Zimbabwe", "sn-ZW"),
        "afrikaans"  to LangInfo("Tiếng Afrikaans", "Nam Phi, Namibia", "af-ZA"),
        "malagasy"   to LangInfo("Tiếng Malagasy", "Madagascar", "mg-MG"),
        "lingala"    to LangInfo("Tiếng Lingala", "Congo, CHDC Congo", "ln-CD"),
        "wolof"      to LangInfo("Tiếng Wolof", "Senegal, Gambia", "wo-SN"),
        "nyanja"     to LangInfo("Tiếng Chichewa", "Malawi, Zambia", "ny-MW"),
        "bashkir"    to LangInfo("Tiếng Bashkir", "Nga (Bashkortostan)", "ba-RU"),
        "tatar"      to LangInfo("Tiếng Tatar", "Nga (Tatarstan)", "tt-RU"),

        // ── Khác ─────────────────────────────────────────────────
        "haitian creole" to LangInfo("Tiếng Creole Haiti", "Haiti", "ht-HT"),
        "maori"      to LangInfo("Tiếng Maori", "New Zealand", "mi-NZ"),
        "hawaiian"   to LangInfo("Tiếng Hawaii", "Mỹ (Hawaii)", "haw-US"),
        "latin"      to LangInfo("Tiếng Latinh", "Vatican (nghi lễ)", "la"),
        "esperanto"  to LangInfo("Tiếng Esperanto", "Không thuộc nước nào (ngôn ngữ nhân tạo)", "eo")
    )

    fun lookup(whisperLang: String): LangInfo? =
        MAP[whisperLang.trim().lowercase()]

    /** Dòng tham chiếu gửi kèm cho Claude khi đã biết ngôn ngữ. */
    fun hint(whisperLang: String): String {
        val i = lookup(whisperLang)
        return if (i != null)
            "Tham chiếu nội bộ: \"$whisperLang\" = ${i.vi}. Các nước sử dụng: ${i.countries}. Mã TTS: ${i.tag}"
        else
            "Tham chiếu nội bộ: không có trong bảng — hãy tự xác định các nước sử dụng ngôn ngữ \"$whisperLang\"."
    }
}
