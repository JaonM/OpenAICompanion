package com.openai.companion.kmp

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class MessageMarkdownRenderTest {
    @Test
    fun narrowBubbleRendersMarkdownAndCompletesAnOpenStreamedFence() = runComposeUiTest {
        val content = mutableStateOf("# 标题\n\n**加粗**与*斜体*\n\n- 第一项\n- 第二项\n\n> 引用\n\n```kotlin\nval answer = 42")
        setContent {
            MaterialTheme {
                Surface(Modifier.width(320.dp)) {
                    MessageMarkdown(content.value, Modifier.testTag("markdown-bubble"))
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("val answer = 42", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("标题").assertExists()
        onNodeWithText("第一项", substring = true).assertExists()
        runOnIdle { content.value += "\n```\n\n| 项目 | 值 |\n| --- | --- |\n| 结果 | 42 |\n\n[链接](https://example.com)" }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("结果", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("链接", substring = true).assertExists()
        onNodeWithTag("markdown-bubble").assertExists()
        System.getenv("COMPANION_MARKDOWN_SCREENSHOT")?.let { path ->
            val image = onNodeWithTag("markdown-bubble").captureToImage()
            val png = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
            Files.write(Path.of(path), png.bytes)
        }
    }
}
