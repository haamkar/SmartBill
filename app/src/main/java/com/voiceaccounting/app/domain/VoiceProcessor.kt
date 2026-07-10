package com.voiceaccounting.app.domain

import com.voiceaccounting.app.data.model.Transaction
import java.util.Calendar

class VoiceProcessor {

    private val exchangePattern = """(خریدم|فروختم)\s+([\d۰-۹]+)\s+(دلار|یورو|درهم)\s+(?:در|با\s+نرخ)\s+([\d۰-۹]+)\s+(?:از|به)\s+(\S+)\s+(تحویل\s+شد|تحویل\s+نشد)""".toRegex()
    private val tomanPattern = """(گرفتم|دادم)\s+([\d۰-۹]+)\s+(?:میلیون\s+)?(تومان)\s+(?:از|به)\s+(\S+)""".toRegex()

    fun processVoiceText(text: String, counterpartyIdProvider: (String) -> Long?): Transaction? {
        val normalizedText = normalizePersianDigits(text.trim())

        exchangePattern.find(normalizedText)?.let { match ->
            val action = match.groupValues[1]
            val amount = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val currency = match.groupValues[3]
            var rate = match.groupValues[4].toDoubleOrNull() ?: 0.0
            val personName = match.groupValues[5]
            val deliveryStatus = match.groupValues[6]

            val cpId = counterpartyIdProvider(personName) ?: return null

            if (rate < 1000) rate *= 1000

            val type = if (action == "خریدم") "BUY" else "SELL"
            val isDelivered = deliveryStatus == "تحویل شد"
            val tomanAmount = amount * rate

            return Transaction(
                type = type,
                counterpartyId = cpId,
                amount = amount,
                currencyType = currency,
                exchangeRate = rate,
                tomanAmount = tomanAmount,
                isDelivered = isDelivered,
                timestamp = Calendar.getInstance().timeInMillis
            )
        }

        tomanPattern.find(normalizedText)?.let { match ->
            val action = match.groupValues[1]
            var amount = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val currency = match.groupValues[3]
            val personName = match.groupValues[4]

            val cpId = counterpartyIdProvider(personName) ?: return null

            if (text.contains("میلیون")) {
                amount *= 1000000
            }

            val type = if (action == "گرفتم") "RECEIVE_TOMAN" else "PAY_TOMAN"

            return Transaction(
                type = type,
                counterpartyId = cpId,
                amount = amount,
                currencyType = currency,
                exchangeRate = 1.0,
                tomanAmount = amount,
                isDelivered = true,
                timestamp = Calendar.getInstance().timeInMillis
            )
        }

        return null
    }

    private fun normalizePersianDigits(input: String): String {
        var result = input
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        for (i in 0..9) {
            result = result.replace(persianDigits[i], ('0' + i))
        }
        return result
    }
}
