package ru.mobile.mys.ui

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.Test
import ru.mobile.mys.storage.Message

class ChatExtrasTest {
    private fun msg(id: Long, at: Long, body: String = "т$id", dir: String = "in", sender: String? = "bob",
                    replyAuthor: String? = null, replySnippet: String? = null, kind: String = "text") =
        Message(id, 1, dir, body.toByteArray(), "received", kind, null, null, sender, id, null, at, at, null, replyAuthor, replySnippet)

    private val day = 86_400_000L

    @Test fun dayHeadersBeforeFirstMessageOfEachDay() {
        val e = chatEntries(listOf(msg(1, 0), msg(2, 1000), msg(3, day + 5)), ZoneOffset.UTC)
        assertEquals(listOf("day-1970-01-01", 1L, 2L, "day-1970-01-02", 3L), e.map { it.key })
    }

    @Test fun dayLabels() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("Сегодня", dayLabel(today, today))
        assertEquals("Вчера", dayLabel(today.minusDays(1), today))
        assertEquals("12 октября", dayLabel(LocalDate.of(2026, 10, 12), today))
        assertEquals("31 декабря 2025", dayLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test fun quoteFindsOriginalByAuthorAndStart() {
        val ms = listOf(msg(1, 0, "привет, как дела"), msg(2, 1, "другое"), msg(3, 2, "привет снова"),
            msg(4, 3, "ответ", dir = "out", sender = "me", replyAuthor = "bob", replySnippet = "привет, как"))
        assertEquals(1L, quoteTarget(ms, ms[3], "me")?.id)
        val own = listOf(msg(5, 0, "моё", dir = "out", sender = null), msg(6, 1, "re", replyAuthor = "me", replySnippet = "моё"))
        assertEquals(5L, quoteTarget(own, own[1], "me")?.id)
    }
}
