package com.tongpin.app

internal data class CheckInDraftFields(
    val mode: CheckInAmountMode,
    val addAmount: String,
    val totalAmount: String,
    val checked: Boolean,
    val note: String,
    val operationId: String,
) {
    companion object {
        fun read(values: Map<String, String>): CheckInDraftFields {
            fun text(key: String, max: Int) = requireNotNull(values[key]).also { require(it.length <= max) }
            return CheckInDraftFields(CheckInAmountMode.valueOf(text("mode", 10)), text("addAmount", 12), text("totalAmount", 12),
                text("checked", 5).toBooleanStrict(), text("note", 1000), text("operationId", 64).also(DomainValidation::id))
        }
    }
}
