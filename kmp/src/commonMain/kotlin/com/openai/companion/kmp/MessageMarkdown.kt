package com.openai.companion.kmp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Shared by mobile and desktop, including incomplete streamed Markdown. */
@Composable
fun MessageMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember { MarkdownParser(flavour) }
    val links = remember { ReferenceLinkHandlerImpl() }
    val parsed by rememberMarkdownState(content, flavour = flavour, parser = parser,
        referenceLinkHandler = links).state.collectAsState()
    var previous by remember { mutableStateOf<State.Success?>(null) }
    val success = parsed as? State.Success
    SideEffect { if (success != null) previous = success }
    // Keep formatted text visible while the next streamed chunk parses off the UI thread.
    val display = if (parsed is State.Loading) previous?.takeIf { content.startsWith(it.content) } ?: parsed else parsed
    SelectionContainer {
        Markdown(
            state = display,
            modifier = modifier.fillMaxWidth(),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                h4 = MaterialTheme.typography.titleSmall,
                h5 = MaterialTheme.typography.labelLarge,
                h6 = MaterialTheme.typography.labelMedium,
                text = style, paragraph = style, ordered = style, bullet = style,
                list = style, quote = style, table = style,
            ),
            loading = { Text(content, style = style, modifier = it) },
            error = { Text(content, style = style, modifier = it) },
        )
    }
}
