package com.nexbytes.h7skertool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexbytes.h7skertool.model.CapturedRequest
import com.nexbytes.h7skertool.model.CapturedResponse
import com.nexbytes.h7skertool.ui.theme.*
import com.nexbytes.h7skertool.utils.DecodeUtils
import com.nexbytes.h7skertool.utils.HexUtils
import com.nexbytes.h7skertool.utils.ProtoField
import com.nexbytes.h7skertool.utils.ProtoModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** FIX #2: parse API response safely regardless of field name or plain text */
private fun parseDecodeApiResponse(raw: String?): String {
    if (raw.isNullOrBlank()) return "⚠️ Empty response from decode API"
    val t = raw.trim()
    return try {
        val j = JSONObject(t)
        listOf("decoded","result","data","output","text")
            .mapNotNull { k -> j.optString(k).ifEmpty { null } }
            .firstOrNull()
            ?.let { DecodeUtils.prettyPrintJson(it).ifEmpty { it } }
            ?: DecodeUtils.prettyPrintJson(t).ifEmpty { t }
    } catch (_: Exception) { t }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PROTO FIELD EDITOR COMPOSABLE
//  Shows each protobuf field as an editable row.  User sets new values, then
//  taps "CREATE MOD" which builds a JSON override map for ProtoModifier.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ProtoFieldEditor(
    fields: List<ProtoField>,
    requestHeaders: Map<String, String>,
    onCreateMod: (fieldJson: String) -> Unit,
    onCreateHeaderMod: (headerJson: String) -> Unit
) {
    // field overrides: fieldNum → new value string
    val edits = remember(fields) { mutableStateMapOf<Int, String>() }
    // header overrides: key → new value ("" means delete)
    val headerEdits = remember(requestHeaders) { mutableStateMapOf<String, String>() }
    var newHdrKey by remember { mutableStateOf("") }
    var newHdrVal by remember { mutableStateOf("") }
    var editorTab by remember { mutableIntStateOf(0) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var modName by remember { mutableStateOf("") }
    var pendingModJson by remember { mutableStateOf("") }
    var pendingModType by remember { mutableStateOf("field") }

    val changedCount = edits.count { (_, v) -> v.isNotEmpty() }
    val headerChangedCount = headerEdits.count { (_, v) -> v.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        // ── editor sub-tabs: FIELDS | HEADERS ──────────────────────────────
        TabRow(
            selectedTabIndex = editorTab,
            containerColor = CardBlack, contentColor = NeonGreen,
            indicator = { tp -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tp[editorTab]), color = NeonGreen) }
        ) {
            Tab(selected = editorTab == 0, onClick = { editorTab = 0 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.DataObject, null, modifier = Modifier.size(14.dp))
                    Text("FIELDS", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (changedCount > 0) Badge(containerColor = NeonGreen.copy(0.15f)) {
                        Text("$changedCount", color = NeonGreen, fontSize = 9.sp)
                    }
                }
            })
            Tab(selected = editorTab == 1, onClick = { editorTab = 1 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Http, null, modifier = Modifier.size(14.dp))
                    Text("HEADERS", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (headerChangedCount > 0) Badge(containerColor = ElectricBlue.copy(0.15f)) {
                        Text("$headerChangedCount", color = ElectricBlue, fontSize = 9.sp)
                    }
                }
            })
        }

        when (editorTab) {

            // ── FIELDS TAB ────────────────────────────────────────────────
            0 -> {
                if (fields.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.SearchOff, null, tint = TextDim, modifier = Modifier.size(36.dp))
                            Text("No protobuf fields found", color = TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Text("Body may not be protobuf encoded", color = TextDim, fontSize = 11.sp)
                        }
                    }
                } else {
                    // Column header
                    Row(
                        Modifier.fillMaxWidth().background(ElevatedBlack).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("FIELD", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        Text("TYPE", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                        Text("ORIGINAL VALUE", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("NEW VALUE", color = NeonGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(color = DividerGray, thickness = 0.5.dp)

                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(fields, key = { it.fieldNum }) { field ->
                            val isEdited = edits[field.fieldNum]?.isNotEmpty() == true
                            val typeColor = when (field.wireTypeName) {
                                "varint"  -> NeonGreen.copy(0.8f)
                                "bytes/str" -> ElectricBlue.copy(0.8f)
                                "fixed32", "fixed64" -> Amber.copy(0.8f)
                                else -> TextSecondary
                            }

                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (isEdited) NeonGreen.copy(0.05f) else Color.Transparent)
                                    .border(
                                        if (isEdited) 0.5.dp else 0.dp,
                                        if (isEdited) NeonGreen.copy(0.2f) else Color.Transparent,
                                        RoundedCornerShape(0.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Field number badge
                                Box(
                                    Modifier.width(56.dp).clip(RoundedCornerShape(5.dp))
                                        .background(NeonGreen.copy(0.1f))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    Alignment.Center
                                ) {
                                    Text(
                                        "#${field.fieldNum}", color = NeonGreen,
                                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                // Wire type
                                Text(
                                    field.wireTypeName, color = typeColor,
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(64.dp)
                                )
                                // Original value
                                Text(
                                    field.rawValue.take(30) + if (field.rawValue.length > 30) "…" else "",
                                    color = TextSecondary, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                // New value text field
                                OutlinedTextField(
                                    value = edits[field.fieldNum] ?: "",
                                    onValueChange = { edits[field.fieldNum] = it },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    placeholder = { Text("override…", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextBright),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NeonGreen,
                                        unfocusedBorderColor = if (isEdited) NeonGreen.copy(0.4f) else DividerGray,
                                        cursorColor = NeonGreen,
                                        focusedTextColor = TextBright,
                                        unfocusedTextColor = TextBright
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    singleLine = true
                                )
                                if (isEdited) {
                                    IconButton(onClick = { edits.remove(field.fieldNum) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, null, tint = AlertRed, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            HorizontalDivider(color = DividerGray.copy(0.5f), thickness = 0.3.dp)
                        }
                    }

                    // CREATE MOD button
                    HorizontalDivider(color = DividerGray)
                    Row(
                        Modifier.fillMaxWidth().background(ElevatedBlack).padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (changedCount == 0) "Edit field values above, then create mod"
                            else "$changedCount field(s) changed",
                            color = if (changedCount == 0) TextDim else NeonGreen,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val changed = edits.filter { (_,v) -> v.isNotEmpty() }
                                if (changed.isEmpty()) return@Button
                                // Build JSON: {"1":"val","4":"name",...}
                                val json = "{" + changed.entries.joinToString(",") { (k,v) ->
                                    val escaped = v.replace("\"","\\\"")
                                    "\"$k\":\"$escaped\""
                                } + "}"
                                pendingModJson = json
                                pendingModType = "field"
                                modName = "mod_${System.currentTimeMillis()}"
                                showSaveDialog = true
                            },
                            enabled = changedCount > 0,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, disabledContainerColor = DividerGray)
                        ) {
                            Icon(Icons.Default.Build, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("CREATE MOD", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ── HEADERS TAB ───────────────────────────────────────────────
            1 -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    // Existing headers list
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(requestHeaders.entries.toList(), key = { it.key }) { (key, origVal) ->
                            val override = headerEdits[key]
                            val isEdited = override != null
                            val isDeleted = override == ""

                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(when { isDeleted -> AlertRed.copy(0.06f); isEdited -> ElectricBlue.copy(0.06f); else -> ElevatedBlack })
                                    .border(1.dp, when { isDeleted -> AlertRed.copy(0.3f); isEdited -> ElectricBlue.copy(0.3f); else -> DividerGray }, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text(key, color = if (isDeleted) AlertRed.copy(0.5f) else ElectricBlue, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Row {
                                        if (!isDeleted) {
                                            IconButton(onClick = { headerEdits[key] = "" }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.Delete, null, tint = AlertRed, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                        if (isEdited) {
                                            IconButton(onClick = { headerEdits.remove(key) }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.Refresh, null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                }
                                if (isDeleted) {
                                    Text("⛔ Will be removed", color = AlertRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                } else {
                                    Text("Original: $origVal", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    OutlinedTextField(
                                        value = override ?: "",
                                        onValueChange = { headerEdits[key] = it },
                                        placeholder = { Text(origVal, color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextBright),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElectricBlue, unfocusedBorderColor = DividerGray, cursorColor = ElectricBlue),
                                        shape = RoundedCornerShape(6.dp), singleLine = true
                                    )
                                }
                            }
                        }
                        // Add new header
                        item {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ElevatedBlack).border(1.dp, PurpleAccent.copy(0.25f), RoundedCornerShape(10.dp)).padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("+ ADD HEADER", color = PurpleAccent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = newHdrKey, onValueChange = { newHdrKey = it },
                                        placeholder = { Text("Header-Name", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextBright),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PurpleAccent, unfocusedBorderColor = DividerGray, cursorColor = PurpleAccent), shape = RoundedCornerShape(6.dp), singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = newHdrVal, onValueChange = { newHdrVal = it },
                                        placeholder = { Text("value", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextBright),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PurpleAccent, unfocusedBorderColor = DividerGray, cursorColor = PurpleAccent), shape = RoundedCornerShape(6.dp), singleLine = true
                                    )
                                    IconButton(onClick = {
                                        if (newHdrKey.isNotBlank()) { headerEdits[newHdrKey.trim()] = newHdrVal.trim(); newHdrKey = ""; newHdrVal = "" }
                                    }, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(PurpleAccent.copy(0.1f))) {
                                        Icon(Icons.Default.Add, null, tint = PurpleAccent)
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }

                    // SAVE HEADER MOD button
                    HorizontalDivider(color = DividerGray)
                    Row(
                        Modifier.fillMaxWidth().background(ElevatedBlack).padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (headerChangedCount == 0) "Modify headers above" else "$headerChangedCount header(s) changed",
                            color = if (headerChangedCount == 0) TextDim else ElectricBlue,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val changed = headerEdits.filter { (_, v) -> v.isNotEmpty() }
                                if (changed.isEmpty()) return@Button
                                val json = "{" + changed.entries.joinToString(",") { (k,v) -> "\"$k\":\"${v.replace("\"","\\\"")}\"" } + "}"
                                pendingModJson = json
                                pendingModType = "header"
                                modName = "headers_${System.currentTimeMillis()}"
                                showSaveDialog = true
                            },
                            enabled = headerChangedCount > 0,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue, disabledContainerColor = DividerGray)
                        ) {
                            Icon(Icons.Default.Http, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("SAVE HDR MOD", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Name dialog before saving
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false }, containerColor = ElevatedBlack, shape = RoundedCornerShape(16.dp),
            title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Build, null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                Text("Name your mod", color = NeonGreen, fontWeight = FontWeight.Bold)
            }},
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mod content:\n${pendingModJson.take(120)}…", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = modName, onValueChange = { modName = it },
                        label = { Text("Mod name", color = TextSecondary, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonGreen, unfocusedBorderColor = DividerGray, focusedTextColor = TextBright, unfocusedTextColor = TextPrimary, cursorColor = NeonGreen),
                        shape = RoundedCornerShape(8.dp), singleLine = true
                    )
                }
            },
            confirmButton = { TextButton(onClick = {
                if (modName.isNotBlank()) {
                    if (pendingModType == "field") onCreateMod(pendingModJson)
                    else onCreateHeaderMod(pendingModJson)
                    showSaveDialog = false
                }
            }) { Text("SAVE", color = NeonGreen, fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel", color = TextSecondary) } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  MAIN OVERLAY
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingDecodeOverlay(
    request: CapturedRequest,
    response: CapturedResponse?,
    onDismiss: () -> Unit,
    onSaveMod: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var tabIdx by remember { mutableIntStateOf(0) }
    // viewMode: 0=TEXT  1=HEX  2=DECODED(api result)  3=EDIT(proto field editor)
    var viewMode by remember { mutableIntStateOf(0) }
    var isDecoding by remember { mutableStateOf(false) }
    var decodedResult by remember { mutableStateOf<String?>(null) }
    var decodeError by remember { mutableStateOf<String?>(null) }

    // Proto fields parsed from the current bytes (for the field editor)
    var protoFields by remember { mutableStateOf<List<ProtoField>>(emptyList()) }
    var isParsingFields by remember { mutableStateOf(false) }

    val http = remember {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    val reqBytes  = request.body
    val respBytes = response?.body
    val reqHexClean  = remember(request)  { request.body?.let  { HexUtils.toCleanHex(it) } ?: "" }
    val respHexClean = remember(response) { response?.body?.let { HexUtils.toCleanHex(it) } ?: "" }
    val reqHexDump   = remember(request)  { request.body?.let  { HexUtils.toHexDump(it)  } ?: request.bodyHex ?: "" }
    val respHexDump  = remember(response) { response?.body?.let { HexUtils.toHexDump(it) } ?: response?.bodyHex ?: "" }

    val currentBytes    = if (tabIdx == 0) reqBytes    else respBytes
    val currentCleanHex = if (tabIdx == 0) reqHexClean else respHexClean
    val currentHexDump  = if (tabIdx == 0) reqHexDump  else respHexDump
    val currentText     = if (tabIdx == 0) request.bodyText else response?.bodyText

    fun decodeViaApi() {
        val hex = currentCleanHex.ifEmpty { return }
        isDecoding = true; decodedResult = null; decodeError = null
        scope.launch {
            try {
                val url = if (tabIdx == 0) "http://node.mrkalpha.tech:19140/request" else "http://node.mrkalpha.tech:19140/response"
                val jsonBody = JSONObject().put("hex", hex).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val resp = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).post(jsonBody).build()).execute()
                }
                val rawBody = withContext(Dispatchers.IO) { resp.body?.string() }
                withContext(Dispatchers.Main) {
                    isDecoding = false
                    decodedResult = parseDecodeApiResponse(rawBody)
                    viewMode = 2
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isDecoding = false; decodeError = "Network error: ${e.message}" }
            }
        }
    }

    fun enterEditMode() {
        viewMode = 3
        val bytes = currentBytes
        if (bytes != null && bytes.isNotEmpty()) {
            isParsingFields = true
            scope.launch(Dispatchers.Default) {
                val fields = ProtoModifier.parseFields(bytes)
                withContext(Dispatchers.Main) { protoFields = fields; isParsingFields = false }
            }
        } else {
            protoFields = emptyList()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.95f)
                .clip(RoundedCornerShape(18.dp))
                .background(SheetBlack)
                .border(1.dp, NeonGreen.copy(0.25f), RoundedCornerShape(18.dp))
        ) {
            // ── Drag handle ─────────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(DividerGray))
            }

            // ── Top bar ─────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                Arrangement.SpaceBetween, Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DECODE WINDOW", color = NeonGreen, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(request.endpoint, color = TextSecondary, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    // FIX #1: Decode API button only in HEX view
                    if (viewMode == 1) {
                        IconButton(onClick = ::decodeViaApi, modifier = Modifier.size(36.dp)) {
                            if (isDecoding) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber)
                            else Icon(Icons.Default.Api, "Decode", tint = Amber, modifier = Modifier.size(18.dp))
                        }
                    }
                    // Edit / Back button
                    IconButton(onClick = { if (viewMode == 3) viewMode = 1 else enterEditMode() }, modifier = Modifier.size(36.dp)) {
                        Icon(if (viewMode == 3) Icons.Default.ArrowBack else Icons.Default.Edit,
                            null, tint = if (viewMode == 3) NeonGreen else TextSecondary,
                            modifier = Modifier.size(18.dp))
                    }
                    // Copy
                    IconButton(onClick = {
                        val txt = when (viewMode) { 1 -> currentHexDump; 2 -> decodedResult ?: ""; else -> currentText ?: "" }
                        clipboard.setText(AnnotatedString(txt))
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ContentCopy, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = DividerGray, thickness = 0.5.dp)

            // ── REQUEST / RESPONSE main tabs ─────────────────────────────────
            TabRow(selectedTabIndex = tabIdx, containerColor = SheetBlack, contentColor = NeonGreen,
                indicator = { tp -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tp[tabIdx]), color = NeonGreen) }) {
                listOf("REQUEST","RESPONSE").forEachIndexed { i, t ->
                    Tab(selected = tabIdx == i, onClick = {
                        tabIdx = i; decodedResult = null; decodeError = null
                        if (viewMode == 3) viewMode = 1 else if (viewMode != 2) viewMode = 0
                    }, text = { Text(t, fontSize = 11.sp, fontFamily = FontFamily.Monospace) })
                }
            }

            // ── View mode chips (only shown when NOT in edit mode) ────────────
            if (viewMode != 3) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("TEXT","HEX","DECODED").forEachIndexed { i, m ->
                        val enabled = i != 2 || decodedResult != null
                        FilterChip(
                            selected = viewMode == i, onClick = { if (enabled) viewMode = i }, enabled = enabled,
                            label = { Text(m, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonGreen.copy(0.12f), selectedLabelColor = NeonGreen,
                                containerColor = ElevatedBlack, labelColor = TextSecondary),
                            border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = viewMode == i,
                                selectedBorderColor = NeonGreen.copy(0.4f), borderColor = DividerGray)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // FIX #1: DECODE API button ONLY in HEX chip row
                    if (viewMode == 1) {
                        if (isDecoding) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Amber)
                        else TextButton(
                            onClick = ::decodeViaApi,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Api, null, tint = Amber, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("DECODE API", color = Amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Edit (opens field editor)
                    TextButton(
                        onClick = ::enterEditMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("EDIT FIELDS", color = NeonGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = DividerGray, thickness = 0.5.dp)
            } else {
                // Edit mode header strip
                Row(
                    Modifier.fillMaxWidth().background(NeonGreen.copy(0.06f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                    Text("FIELD EDITOR", color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    if (isParsingFields) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = NeonGreen)
                        Spacer(Modifier.width(6.dp))
                        Text("Parsing…", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        Text("${protoFields.size} fields", color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = NeonGreen.copy(0.15f))
            }

            // ── Error banner ─────────────────────────────────────────────────
            decodeError?.let { err ->
                Row(
                    Modifier.fillMaxWidth().background(AlertRed.copy(0.08f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Error, null, tint = AlertRed, modifier = Modifier.size(14.dp))
                    Text(err, color = AlertRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = { decodeError = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = AlertRed, modifier = Modifier.size(12.dp))
                    }
                }
            }

            // ── Content ──────────────────────────────────────────────────────
            when (viewMode) {
                3 -> {
                    // Proto field editor
                    ProtoFieldEditor(
                        fields = protoFields,
                        requestHeaders = if (tabIdx == 0) request.headers else response?.headers ?: emptyMap(),
                        onCreateMod = { json -> onSaveMod(json) },
                        onCreateHeaderMod = { json -> onSaveMod(json) }
                    )
                }
                else -> {
                    val displayContent = when (viewMode) {
                        1 -> currentHexDump.ifEmpty { "(no hex data)" }
                        2 -> decodedResult ?: "(tap DECODE API while in HEX view)"
                        else -> currentText ?: "(empty body)"
                    }
                    val textColor = when {
                        viewMode == 1 -> Amber.copy(0.9f)
                        viewMode == 2 -> NeonGreen.copy(0.9f)
                        tabIdx == 0 -> NeonGreen.copy(0.85f)
                        else -> ElectricBlue.copy(0.85f)
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(12.dp)
                    ) {
                        Text(displayContent, color = textColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}
