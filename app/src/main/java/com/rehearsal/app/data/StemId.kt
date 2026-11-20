package com.rehearsal.app.data

/**
 * Четыре выхода HT-Demucs. Гитары в открытой 4-stem модели нет:
 * она сидит в [OTHER] («Музыка»). 6-stem (guitar/piano) на телефоне не ставим.
 */
enum class StemId(val fileName: String, val labelRu: String) {
    VOCALS("vocals.wav", "Вокал"),
    DRUMS("drums.wav", "Ударные"),
    BASS("bass.wav", "Бас"),
    OTHER("other.wav", "Музыка"),
}
