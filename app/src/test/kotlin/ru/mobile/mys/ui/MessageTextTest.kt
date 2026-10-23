package ru.mobile.mys.ui

import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Test

class MessageTextTest {
    @Test fun splitsFencesLikeWeb() {
        assertEquals(listOf(false to "до ", true to "val x = 1", false to " после"), splitCode("до ```\nval x = 1\n``` после"))
        assertEquals(listOf(false to "просто текст"), splitCode("просто текст"))
        assertEquals(listOf(false to "незакрытый ```код"), splitCode("незакрытый ```код"))
        assertEquals(listOf(true to "a", true to "b"), splitCode("```a``````b```"))
    }

    @Test fun imageFollowsAspect() {
        assertEquals(280.dp, imageSize(1600, 900).width)       // горизонтальная — во всю ширину
        assertEquals(157.5.dp, imageSize(1600, 900).height)
        assertEquals(360.dp, imageSize(900, 1600).height)      // вертикальная — во всю высоту
        assertEquals(202.5.dp, imageSize(900, 1600).width)
        assertEquals(160.dp, imageSize(100, 2000).width)       // очень узкая — не уже строки автора
    }
}
