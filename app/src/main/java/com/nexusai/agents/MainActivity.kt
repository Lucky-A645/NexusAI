package com.nexusai.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ChatMsg(
    val id: String,
    val role: String,
    val content: String
)

data class AgentProfile(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,
    val color: Color
)

val AGENTS = listOf(
    AgentProfile(
        id = "general",
        name = "Nexus General",
        icon = "✨",
        description = "Balanced multi-purpose AI assistant for general tasks and brainstorming.",
        systemPrompt = "You are Nexus AI, a helpful, precise, and polite assistant.",
        color = Color(0xFF38BDF8)
    ),
    AgentProfile(
        id = "coder",
        name = "Elite Coder Agent",
        icon = "💻",
        description = "Specialized in software engineering, debugging, architecture, and writing clean scripts.",
        systemPrompt = "You are an elite software engineering agent. Write production-grade code with clean explanations and robust error handling.",
        color = Color(0xFF8B5CF6)
    ),
    AgentProfile(
        id = "researcher",
        name = "Research & Strategy",
        icon = "🔬",
        description = "Deep analytical thinking, content structuring, and strategic planning.",
        systemPrompt = "You are a deep-thinking research and strategy agent. Provide comprehensive, structured, and insightful breakdowns.",
        color = Color(0xFF10B981)
    ),
    AgentProfile(
        id = "creative",
        name = "Creative & Writer",
        icon = "🎨",
        description = "Storytelling, scripts, marketing copy, and creative ideation.",
        systemPrompt = "You are a creative writing and storytelling agent. Craft engaging, high-retention, and imaginative content.",
        color = Color(0xFFF59E0B)
    )
)

data class SavedConnector(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>
)

val KNOWN_PROVIDERS = listOf(
    ProviderTemplate("auto", "⚡ Auto-Detect Key", "", "Paste Groq, OpenRouter, Gemini, NVIDIA, xAI, OpenAI key"),
    ProviderTemplate("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-v1-..."),
    ProviderTemplate("groq", "GroqCloud", "https://api.groq.com/openai/v1", "gsk_..."),
    ProviderTemplate("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "sk-..."),
    ProviderTemplate("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "AIzaSy..."),
    ProviderTemplate("openai", "OpenAI", "https://api.openai.com/v1", "sk-..."),
    ProviderTemplate("custom", "🌐 Custom Website", "", "Enter Base URL + API Key", isCustom = true)
)

data class ProviderTemplate(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyHint: String,
    val isCustom: Boolean = false
)

fun detectProviderFromKey(key: String): Pair<String, String>? {
    val t = key.trim()
    return when {
        t.startsWith("gsk_") -> "GroqCloud" to "https://api.groq.com/openai/v1"
        t.startsWith("sk-or-") -> "OpenRouter" to "https://openrouter.ai/api/v1"
        t.startsWith("AIza") -> "Google Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai"
        t.startsWith("sk-") -> "OpenAI" to "https://api.openai.com/v1"
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF07090E),
                    surface = Color(0xFF0F141F),
                    primary = Color(0xFF8B5CF6),
                    secondary = Color(0xFF38BDF8)
                )
            ) {
                NexusWorkspace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexusWorkspace() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nexus_store", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var connectors by remember { mutableStateOf(loadConnectors(prefs)) }
    var activeConnectorId by remember {
        mutableStateOf(prefs.getString("active_connector_id", connectors.firstOrNull()?.id ?: "") ?: "")
    }
    var activeModel by remember {
        mutableStateOf(prefs.getString("active_model", "Select Model") ?: "Select Model")
    }
    var activeAgent by remember {
        mutableStateOf(AGENTS.first())
    }

    val activeConnector = connectors.find { it.id == activeConnectorId } ?: connectors.firstOrNull()

    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMsg(
                    id = "welcome",
                    role = "assistant",
                    content = "👋 **Welcome to NexusAI v3.2.0**\n\n- Tap **⚙️ Provider** to link your API key.\n- Switch between specialized **Multi-Tasking Agents** (Coder, Researcher, Creative) instantly above.\n- Enjoy smooth streaming and clean code formatting!"
                )
            )
        )
    }
    var userInput by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }

    val bgDark = Color(0xFF07090E)
    val panelDark = Color(0xFF0F141F)
    val borderDark = Color(0xFF1E293B)
    val purpleAccent = Color(0xFF8B5CF6)
    val cyanAccent = Color(0xFF38BDF8)

    Scaffold(
        containerColor = bgDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelDark)
            ) {
                // Top Header Bar (Gemini / Claude Style)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showAgentPicker = true }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(activeAgent.color.copy(alpha = 0.2f))
                                .border(1.dp, activeAgent.color, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(activeAgent.icon, fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = activeAgent.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = activeConnector?.name ?: "No Provider Connected",
                                color = if (activeConnector != null) cyanAccent else Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showProviderSheet = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = purpleAccent, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Provider", color = purpleAccent, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            messages = listOf(messages.first())
                            Toast.makeText(context, "Chat Cleared", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                }

                // Model Selector Ribbon Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF07090E))
                        .clickable {
                            if (activeConnector == null) showProviderSheet = true
                            else showModelPicker = true
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (activeConnector != null) cyanAccent else Color.Red)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (activeConnector != null) "MODEL: $activeModel" else "Tap to link API Key & fetch models",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = purpleAccent, modifier = Modifier.size(16.dp))
                }
                HorizontalDivider(color = borderDark, thickness = 1.dp)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }

                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(250)) + slideInVertically(
                            initialOffsetY = { it / 3 },
                            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
                        )
                    ) {
                        NexusMessageCard(msg = msg, agent = activeAgent, context = context)
                    }
                }

                if (isStreaming) {
                    item {
                        NexusStreamingIndicator(purpleAccent)
                    }
                }
            }

            // Bottom Gemini-Style Input Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelDark)
            ) {
                HorizontalDivider(color = borderDark, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        placeholder = {
                            Text(
                                "Ask ${activeAgent.name} anything...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = purpleAccent,
                            unfocusedBorderColor = borderDark,
                            focusedContainerColor = bgDark,
                            unfocusedContainerColor = bgDark
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = {
                            if (userInput.isBlank() || isStreaming) return@FloatingActionButton
                            if (activeConnector == null) {
                                showProviderSheet = true
                                Toast.makeText(context, "Link an API Key first!", Toast.LENGTH_SHORT).show()
                                return@FloatingActionButton
                            }

                            val prompt = userInput.trim()
                            userInput = ""
                            val userMsg = ChatMsg(
                                id = System.currentTimeMillis().toString(),
                                role = "user",
                                content = prompt
                            )
                            val aiMsgId = (System.currentTimeMillis() + 1).toString()
                            val history = messages.filter { it.id != "welcome" } + userMsg

                            messages = messages + userMsg + ChatMsg(aiMsgId, "assistant", "")
                            isStreaming = true

                            scope.launch {
                                listState.animateScrollToItem(messages.size - 1)
                                streamNexusCompletion(
                                    baseUrl = activeConnector.baseUrl,
                                    apiKey = activeConnector.apiKey,
                                    model = activeModel,
                                    systemPrompt = activeAgent.systemPrompt,
                                    history = history,
                                    onChunk = { delta ->
                                        messages = messages.map {
                                            if (it.id == aiMsgId) it.copy(content = it.content + delta) else it
                                        }
                                    },
                                    onFinish = {
                                        isStreaming = false
                                    }
                                )
                            }
                        },
                        containerColor = if (isStreaming) Color.DarkGray else purpleAccent,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }
        }

        // 1. Agent Switcher Modal
        if (showAgentPicker) {
            AlertDialog(
                onDismissRequest = { showAgentPicker = false },
                containerColor = panelDark,
                title = { Text("🤖 Select Multi-Tasking Agent", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AGENTS.forEach { agent ->
                            val isSelected = agent.id == activeAgent.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF1E293B) else bgDark)
                                    .border(1.dp, if (isSelected) agent.color else borderDark, RoundedCornerShape(12.dp))
                                    .clickable {
                                        activeAgent = agent
                                        showAgentPicker = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(agent.icon, fontSize = 24.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(agent.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(agent.description, color = Color.Gray, fontSize = 11.sp, maxLines = 2)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAgentPicker = false }) { Text("Close", color = cyanAccent) }
                }
            )
        }

        // 2. Provider Manager Sheet
        if (showProviderSheet) {
            NexusProviderDialog(
                savedConnectors = connectors,
                activeConnectorId = activeConnector?.id ?: "",
                onSelect = { conn ->
                    activeConnectorId = conn.id
                    activeModel = conn.models.firstOrNull() ?: "gpt-4o-mini"
                    prefs.edit()
                        .putString("active_connector_id", activeConnectorId)
                        .putString("active_model", activeModel)
                        .apply()
                    showProviderSheet = false
                    showModelPicker = true
                },
                onDelete = { id ->
                    val updated = connectors.filterNot { it.id == id }
                    connectors = updated
                    saveConnectors(prefs, updated)
                    if (activeConnectorId == id) {
                        activeConnectorId = updated.firstOrNull()?.id ?: ""
                        activeModel = updated.firstOrNull()?.models?.firstOrNull() ?: "Select Model"
                    }
                },
                onAdded = { newConn ->
                    val updated = connectors.filterNot { it.name == newConn.name } + newConn
                    connectors = updated
                    saveConnectors(prefs, updated)
                    activeConnectorId = newConn.id
                    activeModel = newConn.models.firstOrNull() ?: "default"
                    prefs.edit()
                        .putString("active_connector_id", activeConnectorId)
                        .putString("active_model", activeModel)
                        .apply()
                    showProviderSheet = false
                    showModelPicker = true
                    Toast.makeText(context, "✅ Connected! Loaded ${newConn.models.size} models.", Toast.LENGTH_LONG).show()
                },
                onDismiss = { showProviderSheet = false }
            )
        }

        // 3. Model Picker Sheet
        if (showModelPicker && activeConnector != null) {
            NexusModelPicker(
                connector = activeConnector,
                currentModel = activeModel,
                onSelectModel = { m ->
                    activeModel = m
                    prefs.edit().putString("active_model", m).apply()
                    showModelPicker = false
                },
                onRefresh = {
                    scope.launch {
                        val refreshed = fetchModels(activeConnector.baseUrl, activeConnector.apiKey)
                        if (refreshed.isNotEmpty()) {
                            val updatedConn = activeConnector.copy(models = refreshed)
                            val list = connectors.map { if (it.id == activeConnector.id) updatedConn else it }
                            connectors = list
                            saveConnectors(prefs, list)
                            Toast.makeText(context, "Refreshed ${refreshed.size} models!", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { showModelPicker = false }
            )
        }
    }
}

@Composable
fun NexusMessageCard(msg: ChatMsg, agent: AgentProfile, context: Context) {
    val isUser = msg.role == "user"
    val bgCard = if (isUser) Color(0xFF1E293B) else Color(0xFF0F141F)
    val borderCol = if (isUser) Color(0xFF8B5CF6).copy(alpha = 0.4f) else Color(0xFF1E293B)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgCard)
            .border(1.dp, borderCol, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isUser) "YOU" else "${agent.icon}${agent.name.uppercase()}",
                    color = if (isUser) Color(0xFF38BDF8) else agent.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (msg.content.isNotEmpty()) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("NexusAI", msg.content))
                            Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                        }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Code block formatting
        val segments = remember(msg.content) { msg.content.split("```") }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEachIndexed { idx, part ->
                    if (idx % 2 == 1) {
                        val lines = part.lines()
                        val lang = lines.firstOrNull()?.trim() ?: "code"
                        val codeBody = if (lines.size > 1) lines.drop(1).joinToString("\n") else part

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF04060A))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0B101D))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(lang, color = Color(0xFF38BDF8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Row(
                                    modifier = Modifier.clickable {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("Code", codeBody))
                                        Toast.makeText(context, "Code Copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.ContentCopy, null, tint = Color.LightGray, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                            Text(
                                text = codeBody.trim(),
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    } else if (part.isNotBlank()) {
                        Text(
                            text = part.trim(),
                            color = Color(0xFFF1F5F9),
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NexusStreamingIndicator(accentColor: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accentColor.copy(alpha = alpha)))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Nexus Agent is thinking...", color = accentColor.copy(alpha = alpha), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun NexusProviderDialog(
    savedConnectors: List<SavedConnector>,
    activeConnectorId: String,
    onSelect: (SavedConnector) -> Unit,
    onDelete: (String) -> Unit,
    onAdded: (SavedConnector) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTemplate by remember { mutableStateOf(KNOWN_PROVIDERS.first()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var isFetching by remember { mutableStateOf(false) }

    val autoDetected = remember(apiKeyInput) { detectProviderFromKey(apiKeyInput) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F141F),
        title = { Text("⚙️ Nexus API Gateway", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (savedConnectors.isNotEmpty()) {
                    Text("Linked Connectors:", color = Color(0xFF8B5CF6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    savedConnectors.forEach { conn ->
                        val isSel = conn.id == activeConnectorId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) Color(0xFF1E293B) else Color(0xFF07090E))
                                .border(1.dp, if (isSel) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                .clickable { onSelect(conn) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conn.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("${conn.models.size} models loaded", color = Color(0xFF10B981), fontSize = 11.sp)
                            }
                            IconButton(onClick = { onDelete(conn.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, null, tint = Color.Gray)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1E293B))
                }

                Text("Select Provider (or Auto-Detect from Key):", color = Color.LightGray, fontSize = 12.sp)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KNOWN_PROVIDERS.forEach { p ->
                        FilterChip(
                            selected = selectedTemplate.id == p.id,
                            onClick = { selectedTemplate = p },
                            label = { Text(p.name, fontSize = 11.sp) }
                        )
                    }
                }

                if (selectedTemplate.id == "auto" && autoDetected != null) {
                    Text("✨ Detected: ${autoDetected.first}", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                if (selectedTemplate.isCustom) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Provider Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Base URL (e.g. https://api.../v1)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key Only") },
                    placeholder = { Text(selectedTemplate.keyHint, color = Color.Gray, fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.Gray)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                if (isFetching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching models from server...", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isFetching && apiKeyInput.isNotBlank(),
                onClick = {
                    val key = apiKeyInput.trim()
                    val name: String
                    val url: String

                    when {
                        selectedTemplate.isCustom -> {
                            name = customName.ifBlank { "Custom" }.trim()
                            url = customUrl.trim().removeSuffix("/")
                        }
                        selectedTemplate.id == "auto" -> {
                            val d = detectProviderFromKey(key)
                            if (d != null) {
                                name = d.first
                                url = d.second
                            } else {
                                Toast.makeText(context, "Key not recognized. Select provider chip.", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                        }
                        else -> {
                            name = selectedTemplate.name
                            url = selectedTemplate.baseUrl
                        }
                    }

                    isFetching = true
                    scope.launch {
                        val models = fetchModels(url, key)
                        isFetching = false
                        val finalModels = if (models.isNotEmpty()) models else listOf("gpt-4o-mini", "deepseek-chat", "llama-3.3-70b-versatile")
                        onAdded(SavedConnector(System.currentTimeMillis().toString(), name, url, key, finalModels))
                    }
                }
            ) {
                Text(if (isFetching) "Connecting..." else "Link & Fetch Models")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.Gray) }
        }
    )
}

@Composable
fun NexusModelPicker(
    connector: SavedConnector,
    currentModel: String,
    onSelectModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var typedModel by remember { mutableStateOf("") }
    val filtered = remember(connector.models, query) {
        if (query.isBlank()) connector.models else connector.models.filter { it.contains(query.trim(), true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F141F),
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Select Model", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${connector.name} (${connector.models.size} models)", color = Color(0xFF10B981), fontSize = 11.sp)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = Color(0xFF38BDF8)) }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search models...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered) { m ->
                        val isSel = m == currentModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFF1E293B) else Color(0xFF07090E))
                                .border(1.dp, if (isSel) Color(0xFF8B5CF6) else Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .clickable { onSelectModel(m) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(m, color = if (isSel) Color(0xFF38BDF8) else Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            if (isSel) Icon(Icons.Default.Check, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Color(0xFF38BDF8)) } }
    )
}

suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/models").addHeader("Authorization", "Bearer $apiKey").get().build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            val json = JSONObject(res.body?.string() ?: return@withContext emptyList())
            val arr = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
                if (id.isNotBlank()) list.add(id.removePrefix("models/"))
            }
            list.sorted()
        }
    } catch (_: Exception) { emptyList() }
}

suspend fun streamNexusCompletion(
    baseUrl: String,
    apiKey: String,
    model: String,
    systemPrompt: String,
    history: List<ChatMsg>,
    onChunk: (String) -> Unit,
    onFinish: () -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(14).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val payload = JSONObject().put("model", model).put("messages", messages).put("stream", true)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                withContext(Dispatchers.Main) { onChunk("⚠️ API Error (${res.code})") }
                return@withContext
            }
            val reader = BufferedReader(InputStreamReader(res.body!!.byteStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line!!.trim()
                if (!raw.startsWith("data:")) continue
                val data = raw.removePrefix("data:").trim()
                if (data == "[DONE]") break
                try {
                    val token = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "") ?: ""
                    if (token.isNotEmpty()) withContext(Dispatchers.Main) { onChunk(token) }
                } catch (_: Exception) {}
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { onChunk("\n⚠️ Error: ${e.localizedMessage}") }
    } finally {
        withContext(Dispatchers.Main) { onFinish() }
    }
}

fun saveConnectors(prefs: android.content.SharedPreferences, list: List<SavedConnector>) {
    val arr = JSONArray()
    list.forEach { c ->
        val mArr = JSONArray()
        c.models.forEach { mArr.put(it) }
        arr.put(JSONObject().put("id", c.id).put("name", c.name).put("baseUrl", c.baseUrl).put("apiKey", c.apiKey).put("models", mArr))
    }
    prefs.edit().putString("connectors_json", arr.toString()).apply()
}

fun loadConnectors(prefs: android.content.SharedPreferences): List<SavedConnector> {
    val raw = prefs.getString("connectors_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        val list = mutableListOf<SavedConnector>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ma = o.optJSONArray("models") ?: JSONArray()
            val models = mutableListOf<String>()
            for (j in 0 until ma.length()) models.add(ma.getString(j))
            list.add(SavedConnector(o.getString("id"), o.getString("name"), o.getString("baseUrl"), o.getString("apiKey"), models))
        }
        list
    } catch (_: Exception) { emptyList() }
}
