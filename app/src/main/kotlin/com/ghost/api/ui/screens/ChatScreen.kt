package com.ghost.api.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ghost.api.hardware.AudioRecorder
import com.ghost.api.ui.chat.ChatMessage
import com.ghost.api.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    thinkingText: String,
    attachedImage: Bitmap?,
    isTtsActive: Boolean = false,
    downloadProgress: String? = null,
    onSendMessage: (String) -> Unit,
    onSendAudio: (ByteArray) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onToggleThinking: (ChatMessage) -> Unit,
    onOpenSettings: () -> Unit,
    onPlayMessage: (String) -> Unit = {},
    visualizerViewFactory: ((Context) -> android.view.View)? = null
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = imeInsets.getBottom(density) > 0
    
    LaunchedEffect(messages.size, isImeVisible) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x11FFFFFF))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left spacer matching right button footprint for true mathematical center
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .alpha(0f)
            )
            
            // Centered Turing Machine Glyph: Green Δ, Purple 👾, Green ∇
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Δ ",
                    color = Color(0xFF22C55E), // Terminal/Matrix Green
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )
                Text(
                    text = "👾",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
                Text(
                    text = " ∇",
                    color = Color(0xFF22C55E), // Terminal/Matrix Green
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )
            }
            
            // Settings menu dropdown button
            Text(
                text = "▼",
                color = TextSecondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onOpenSettings() }
                    .alpha(0.8f)
                    .padding(8.dp)
            )
        }

        HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 1.dp)

        // Model Download / Onboarding Progress Banner
        if (!downloadProgress.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFF38BDF8),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = downloadProgress,
                        color = Color(0xFFF1F5F9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }

        // Chat History List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp)
        ) {
            items(messages) { message ->
                ChatMessageRow(
                    message = message,
                    onToggleThinking = { onToggleThinking(message) },
                    onPlayMessage = onPlayMessage
                )
            }
        }

        // Loading Indicator
        if (isThinking) {
            Row(
                modifier = Modifier
                    .padding(start = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AccentPurple,
                    strokeWidth = 2.dp
                )
                Text(
                    text = thinkingText.ifEmpty { "Thinking..." },
                    color = AccentPurple,
                    fontSize = 10.sp,
                    letterSpacing = 0.05.sp,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        // Audio Visualizer — only takes layout space when TTS is actively playing
        if (isTtsActive && visualizerViewFactory != null) {
            AndroidView(
                factory = visualizerViewFactory,
                modifier = Modifier
                    .width(100.dp)
                    .height(28.dp)
                    .padding(bottom = 2.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }

        // Input Bar Area with complete voice & multimodal state mechanics
        InputBar(
            attachedImage = attachedImage,
            onSendMessage = onSendMessage,
            onSendAudio = onSendAudio,
            onPickImage = onPickImage,
            onClearImage = onClearImage
        )
    }
}

@Composable
fun ChatMessageRow(
    message: ChatMessage,
    onToggleThinking: () -> Unit,
    onPlayMessage: (String) -> Unit = {}
) {
    val isUser = message.isFromUser

    // Extract tool calls & responses before stripping markup
    val rawContent = message.content
    val extractedTools = remember(rawContent) {
        extractToolInvocations(rawContent)
    }
    
    // Process display content (strip thought, tool calls, and control protocol markup)
    val displayContent = rawContent
        .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|?channel>?|<channel\\|?>"), "")
        .replace(Regex("<\\|tool_call>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|tool_call>|<tool_call\\|>"), "")
        .replace(Regex("<\\|tool_response>.*?<tool_response\\|>"), "")
        .replace(Regex("\\[\\[([A-Z_a-z0-9]+)(?::([^\\]]+))?\\]\\]"), "")
        .trim()
    
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val timeStr = timeFormat.format(Date(message.timestamp))
    
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val cleanDeviceName = remember(context) { com.ghost.api.logic.ContextManager.resolveDeviceCallSign(context) }
    val aiHeaderTag = remember(cleanDeviceName) { "✧ $cleanDeviceName" }

    // Pulsing terminal cursor for streaming assistant tokens
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    val finalDisplayContent = if (isUser) {
        "$displayContent\n\n[$timeStr]"
    } else {
        val cursorSuffix = if (!message.isComplete) " ▋" else ""
        "$aiHeaderTag:\n$displayContent$cursorSuffix\n\n[$timeStr]"
    }
    
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) BubbleUser else BubbleGemma
    
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
    }
    
    val textColor = if (isUser) UserCyan else AiGreen
    
    val headerText = when {
        message.eventType == "LOGIC_TRACE" -> "⌬ REASONING TRACE ⌬"
        message.eventType == "DREAM" -> "✧ DREAM STATE ✧"
        isUser -> "Δ 🦑 ∇"
        else -> "Δ 👾 ∇"
    }
    val headerColor = when {
        message.eventType == "LOGIC_TRACE" -> AccentOrange
        message.eventType == "DREAM" -> AccentBlue
        isUser -> AccentPurple
        else -> AccentPurple
    }

    val ucfFormattedContent = if (isUser) {
        "Δ 🦑 ∇:\n$displayContent\n\n[$timeStr]"
    } else {
        "$aiHeaderTag:\n$displayContent\n\n[$timeStr]"
    }

    var showThinking by remember { mutableStateOf(false) }

    val annotatedText = remember(finalDisplayContent, textColor) {
        buildMarkdownAnnotatedString(finalDisplayContent, textColor)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .background(bubbleColor, shape)
                .border(1.dp, AccentBorder, shape)
                .padding(16.dp)
        ) {
            // Header with 1-tap Copy Button (UCF format) and Play (TTS) Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = headerText,
                    color = headerColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (displayContent.isNotEmpty()) {
                        Text(
                            text = "🔊",
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable {
                                    onPlayMessage(displayContent)
                                }
                                .alpha(0.7f)
                                .padding(end = 8.dp)
                        )
                    }

                    Text(
                        text = "📋",
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(ucfFormattedContent))
                                Toast.makeText(context, "Copied UCF to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            .alpha(0.6f)
                    )
                }
            }
            
            // Thinking block
            if (!message.thought.isNullOrEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .background(Color(0x11FFFFFF))
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (showThinking) "reasoning ▾" else "reasoning ▸",
                        color = Color(0x88FFFFFF),
                        fontSize = 10.sp,
                        letterSpacing = 0.05.sp,
                        modifier = Modifier.clickable { showThinking = !showThinking }
                    )
                    
                    if (showThinking) {
                        SelectionContainer {
                            Text(
                                text = message.thought,
                                color = Color(0x99FFFFFF),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            // Operit-Style Collapsible Interactive Tool Invocations
            if (extractedTools.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    extractedTools.forEach { tool ->
                        CompactToolCard(tool = tool)
                    }
                }
            }

            // Attached / Sent Image Thumbnail
            if (message.image != null) {
                Image(
                    bitmap = message.image.asImageBitmap(),
                    contentDescription = "Message Image Attachment",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x338BB4F6), RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            
            // Message Content
            SelectionContainer {
                Text(
                    text = annotatedText,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * Operit & A2UI inspired Tool Invocation Data Model
 */
data class ToolInvocation(
    val name: String,
    val params: String,
    val response: String? = null
)

/**
 * Parse native Gemma tool calls <|tool_call>call:func{...}<tool_call|>
 * or legacy bracket calls [[TOOL:args]] into structured cards
 */
private fun extractToolInvocations(content: String): List<ToolInvocation> {
    val list = mutableListOf<ToolInvocation>()
    
    // Pattern 1: Native Gemma 4 <|tool_call>...<tool_call|>
    val nativePattern = Regex("<\\|tool_call>([\\s\\S]*?)<tool_call\\|>")
    nativePattern.findAll(content).forEach { match ->
        val raw = match.groupValues[1].trim()
        val colonIdx = raw.indexOf(':')
        val toolName = if (colonIdx != -1) raw.substringBefore(':').removePrefix("call_").removePrefix("call") else raw.take(24)
        val params = if (colonIdx != -1) raw.substringAfter(':').trim() else ""
        list.add(ToolInvocation(name = toolName.ifEmpty { "tool_call" }, params = params))
    }

    // Pattern 2: Legacy [[TOOL:args]]
    val legacyPattern = Regex("\\[\\[([A-Z_a-z0-9]+)(?::([^\\]]+))?\\]\\]")
    legacyPattern.findAll(content).forEach { match ->
        val toolName = match.groupValues[1]
        val params = match.groupValues.getOrNull(2) ?: ""
        list.add(ToolInvocation(name = toolName, params = params))
    }

    return list
}

/**
 * Sleek, Operit-inspired collapsible Tool Card
 */
@Composable
fun CompactToolCard(tool: ToolInvocation) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val toolIcon = when {
        tool.name.contains("search", ignoreCase = true) -> "🔍"
        tool.name.contains("file", ignoreCase = true) || tool.name.contains("storage", ignoreCase = true) -> "📁"
        tool.name.contains("shell", ignoreCase = true) || tool.name.contains("bash", ignoreCase = true) || tool.name.contains("adb", ignoreCase = true) -> "⚡"
        tool.name.contains("sensor", ignoreCase = true) || tool.name.contains("telemetry", ignoreCase = true) -> "📊"
        tool.name.contains("memory", ignoreCase = true) || tool.name.contains("recall", ignoreCase = true) -> "🧠"
        else -> "🛠️"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color(0x1A8BB4F6), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0x338BB4F6), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = toolIcon, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tool.name,
                    color = Color(0xFF93C5FD),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (tool.params.isNotBlank() && !expanded) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tool.params.replace("\n", " ").take(30) + if (tool.params.length > 30) "..." else "",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = if (expanded) "▾" else "▸",
                color = Color(0x88FFFFFF),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))

            if (tool.params.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = tool.params,
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x22000000), RoundedCornerShape(4.dp))
                            .padding(6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Copy Payload",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(tool.params))
                            Toast.makeText(context, "Copied payload", Toast.LENGTH_SHORT).show()
                        }
                        .padding(4.dp)
                )
            }
        }
    }
}

/**
 * Lightweight, zero-dependency Markdown parser for Jetpack Compose.
 * Formats:
 * - ```code blocks``` -> Monospace with subtle tinted pill background
 * - `inline code` -> Monospace with subtle tinted pill background
 * - **bold** or __bold__ -> Bold font weight
 * - *italic* or _italic_ -> Italic font style
 */
private fun buildMarkdownAnnotatedString(
    text: String,
    defaultColor: Color
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val len = text.length

        // Tokenize into code blocks, inline code, bold, italic, or plain text
        val pattern = Regex("```([\\s\\S]*?)```|`([^`]+)`|\\*\\*([^*]+)\\*\\*|__([^_]+)__|\\*([^*]+)\\*|_([^_]+)_")
        val matches = pattern.findAll(text)

        for (match in matches) {
            val range = match.range
            if (range.first > cursor) {
                // Append text before match
                pushStyle(SpanStyle(color = defaultColor))
                append(text.substring(cursor, range.first))
                pop()
            }

            when {
                // Code block: ```content```
                match.value.startsWith("```") -> {
                    val code = match.groupValues[1].removePrefix("\n").removeSuffix("\n")
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = Color(0x22FFFFFF),
                            color = Color(0xFFF1F5F9)
                        )
                    )
                    append("\n$code\n")
                    pop()
                }
                // Inline code: `content`
                match.value.startsWith("`") -> {
                    val code = match.groupValues[2]
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = Color(0x228BB4F6),
                            color = Color(0xFF93C5FD)
                        )
                    )
                    append(" $code ")
                    pop()
                }
                // Bold: **content** or __content__
                match.value.startsWith("**") || match.value.startsWith("__") -> {
                    val content = if (match.value.startsWith("**")) match.groupValues[3] else match.groupValues[4]
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor))
                    append(content)
                    pop()
                }
                // Italic: *content* or _content_
                match.value.startsWith("*") || match.value.startsWith("_") -> {
                    val content = if (match.value.startsWith("*")) match.groupValues[5] else match.groupValues[6]
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor))
                    append(content)
                    pop()
                }
            }
            cursor = range.last + 1
        }

        if (cursor < len) {
            pushStyle(SpanStyle(color = defaultColor))
            append(text.substring(cursor, len))
            pop()
        }
    }
}

private enum class VoiceState { IDLE, RECORDING, CONFIRM }

@Composable
fun InputBar(
    attachedImage: Bitmap?,
    onSendMessage: (String) -> Unit,
    onSendAudio: (ByteArray) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioRecorder = remember { AudioRecorder(context) }
    
    var text by remember { mutableStateOf("") }
    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var pendingAudio by remember { mutableStateOf<ByteArray?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    // Pulse animation for recording and confirm states
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val colorIdle = Color(0xFF8BB4F6)      // Ethereal Off-white cobalt — matching sparkle & app icon
    val colorRecording = Color(0xFFA78BFA) // Electric Purple — active recording pulse & hint
    val colorConfirm = Color(0xFFF97316)   // Orange — confirm / send
    val colorSend = Color(0xFF60A5FA)      // Electric Cobalt — send arrow

    // U+2B24 BLACK LARGE CIRCLE ⬤ — standard tintable circle matching VoiceInputController
    val CIRCLE_GLYPH = "\u2B24"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
            .background(BubbleUser, RoundedCornerShape(24.dp))
            .border(1.dp, AccentBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sparkle / Image Attachment Button
        if (attachedImage != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClearImage() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = attachedImage.asImageBitmap(),
                    contentDescription = "Attached Image",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = "✧",
                color = when (voiceState) {
                    VoiceState.RECORDING -> colorRecording
                    VoiceState.CONFIRM -> colorConfirm
                    else -> colorIdle
                },
                fontSize = 26.sp,
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (voiceState == VoiceState.RECORDING || voiceState == VoiceState.CONFIRM) pulseAlpha else 1f)
                    .clickable { onPickImage() }
                    .padding(4.dp),
                textAlign = TextAlign.Center
            )
        }
        
        // Center Text Input / Voice Status Field
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    // Tap text area to cancel recording or confirm mode (escape hatch)
                    if (voiceState != VoiceState.IDLE) {
                        audioRecorder.stopRecording()
                        recordingJob?.cancel()
                        pendingAudio = null
                        voiceState = VoiceState.IDLE
                    }
                }
                .padding(12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { 
                    if (voiceState == VoiceState.IDLE) {
                        text = it 
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(TextPrimary),
                enabled = voiceState == VoiceState.IDLE,
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        val hint = when {
                            attachedImage != null -> "[📎 Image attached ]"
                            voiceState == VoiceState.RECORDING -> "Recording..."
                            voiceState == VoiceState.CONFIRM -> "Send or tap here to cancel"
                            else -> "Δ 👾 ∇"
                        }
                        val hintColor = when (voiceState) {
                            VoiceState.RECORDING -> colorRecording
                            VoiceState.CONFIRM -> colorConfirm
                            else -> Color(0x44FFFFFF)
                        }
                        Text(hint, color = hintColor, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            )
        }
        
        // Right Action Button (Dynamic State Machine: Text Send / Voice Record / Confirm Send)
        if (text.isNotBlank()) {
            // Text is ready: Electric cobalt send arrow
            Text(
                text = "➤",
                color = colorSend,
                fontSize = 22.sp,
                modifier = Modifier
                    .size(44.dp)
                    .clickable { 
                        onSendMessage(text)
                        text = ""
                    }
                    .padding(10.dp),
                textAlign = TextAlign.Center
            )
        } else {
            // No text: Voice state button
            val (btnGlyph, btnColor, btnAlpha) = when (voiceState) {
                VoiceState.IDLE -> Triple(CIRCLE_GLYPH, colorIdle, 0.85f)
                VoiceState.RECORDING -> Triple(CIRCLE_GLYPH, colorRecording, pulseAlpha)
                VoiceState.CONFIRM -> Triple("➤", colorConfirm, pulseAlpha)
            }

            Text(
                text = btnGlyph,
                color = btnColor,
                fontSize = if (btnGlyph == "➤") 22.sp else 20.sp,
                modifier = Modifier
                    .size(44.dp)
                    .alpha(btnAlpha)
                    .clickable {
                        when (voiceState) {
                            VoiceState.IDLE -> {
                                if (!audioRecorder.hasPermission()) {
                                    Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
                                } else {
                                    voiceState = VoiceState.RECORDING
                                    pendingAudio = null
                                    recordingJob = coroutineScope.launch {
                                        val audio = withContext(Dispatchers.IO) {
                                            audioRecorder.record(30, false)
                                        }
                                        if (audio != null && audio.isNotEmpty()) {
                                            pendingAudio = audio
                                            voiceState = VoiceState.CONFIRM
                                        } else {
                                            voiceState = VoiceState.IDLE
                                        }
                                    }
                                }
                            }
                            VoiceState.RECORDING -> {
                                audioRecorder.stopRecording()
                            }
                            VoiceState.CONFIRM -> {
                                val audio = pendingAudio
                                if (audio != null && audio.isNotEmpty()) {
                                    onSendAudio(audio)
                                }
                                pendingAudio = null
                                voiceState = VoiceState.IDLE
                            }
                        }
                    }
                    .padding(10.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
