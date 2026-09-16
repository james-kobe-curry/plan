package com.tongpin.app

import java.time.LocalDate

/** One optional reflection per calendar day, independent of every task's check-in note. */
data class DailyNote(
    val date: String,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

object DailyNoteRules {
    const val MAX_TEXT_LENGTH = 5_000
    const val MAX_NOTES = 100_000
    private const val MAX_TIMESTAMP = 253_402_300_799_999L

    fun note(note: DailyNote) {
        DomainValidation.date(note.date)
        DomainValidation.text(note.text, "每日心得", MAX_TEXT_LENGTH, multiline = true)
        require(note.updatedAt in 0..MAX_TIMESTAMP) { "心得保存时间无效" }
    }

    fun notes(notes: List<DailyNote>) {
        require(notes.size <= MAX_NOTES) { "每日心得过多，请先导出备份并整理历史" }
        val dates = hashSetOf<String>()
        notes.forEach { note ->
            this.note(note)
            require(dates.add(note.date)) { "同一天只能保存一篇每日心得" }
        }
    }
}

fun dailyNoteFor(data: AppData, date: LocalDate): DailyNote? = data.dailyNotes.firstOrNull { it.date == date.toString() }

/** Whitespace-only input removes that day's reflection; task check-ins are untouched. */
fun setDailyNote(data: AppData, date: LocalDate, text: String, updatedAt: Long = System.currentTimeMillis()): AppData {
    val day = date.toString().also(DomainValidation::date)
    DomainValidation.text(text, "每日心得", DailyNoteRules.MAX_TEXT_LENGTH, allowEmpty = true, multiline = true)
    val clean = text.trim()
    val previous = data.dailyNotes.firstOrNull { it.date == day }
    if (clean.isBlank()) return if (previous == null) data else data.copy(dailyNotes = data.dailyNotes.filterNot { it.date == day })
        .also(DomainValidation::data)
    if (previous?.text == clean) return data
    val note = DailyNote(day, clean, updatedAt).also(DailyNoteRules::note)
    val remaining = data.dailyNotes.filterNot { it.date == day }
    require(remaining.size < DailyNoteRules.MAX_NOTES) { "每日心得过多，请先导出备份并整理历史" }
    return data.copy(dailyNotes = remaining + note).also(DomainValidation::data)
}
