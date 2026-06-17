package com.nexbytes.h7skertool.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexbytes.h7skertool.model.CapturedRequest
import com.nexbytes.h7skertool.model.CapturedResponse
import com.nexbytes.h7skertool.ui.components.FloatingDecodeOverlay
import com.nexbytes.h7skertool.ui.theme.*
import com.nexbytes.h7skertool.utils.DecodeUtils
import com.nexbytes.h7skertool.utils.HexUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private fun parseDecodeApiResponse(raw: String?): String {
    if (raw.isNullOrBlank()) return "⚠️ Empty response from decode API"
    val t = raw.trim()
    return try {
        val j = JSONObject(t)
        listOf("decoded","result","data","output","text")
            .mapNotNull { k -> j.optString(k).ifEmpty { null } }
            .firstOrNull()?.let { DecodeUtils.prettyPrintJson(it).ifEmpty { it } }
            ?: DecodeUtils.prettyPrintJson(t).ifEmpty { t }
    } catch (_: Exception) { t }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailScreen(
    request: CapturedRequest,
    response: CapturedResponse?,
    onBack: () -> Unit,
    onSaveMod: (String, String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val http = remember { OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build() }

    var mainTab by remember { mutableIntStateOf(0) }
    // subTab: 0=BODY 1=HEADERS 2=HEX
    var subTab by remember { mutableIntStateOf(0) }
    // FIX #1: overlay only launched from HEX subTab
    var showDecodeOverlay by remember { mutableStateOf(false) }
    var splitDecoded by remember { mutableStateOf<String?>(null) }
    var isSplitDecoding by remember { mutableStateOf(false) }
    var splitError by remember { mutableStateOf<String?>(null) }
    var snack by remember { mutableStateOf<String?>(null) }

    val reqHexClean = remember(request) { request.body?.let { HexUtils.toCleanHex(it) } ?: "" }
    val respHexClean = remember(response) { response?.body?.let { HexUtils.toCleanHex(it) } ?: "" }
    fun currentHex() = if (mainTab == 0) reqHexClean else respHexClean

    fun decodeSplit() {
        val hex = currentHex().ifEmpty { snack = "No hex data"; return }
        isSplitDecoding = true; splitError = null; splitDecoded = null
        scope.launch {
            try {
                val url = if (mainTab == 0) "http://node.mrkalpha.tech:19140/request" else "http://node.mrkalpha.tech:19140/response"
                val body = JSONObject().put("hex",hex).toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val resp = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(url).post(body).build()).execute() }
                val rawBody = withContext(Dispatchers.IO) { resp.body?.string() }
                withContext(Dispatchers.Main) { isSplitDecoding = false; splitDecoded = parseDecodeApiResponse(rawBody) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { isSplitDecoding = false; splitError = "Decode failed: ${e.message}" } }
        }
    }

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(request.endpoint, color = TextBright, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(request.method, color = when(request.method){"GET"->MethodGET;"POST"->MethodPOST;"PUT"->MethodPUT;"DELETE"->MethodDELETE;else->TextSecondary}, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            response?.let { r -> Text("${r.statusCode}", color = when{r.statusCode in 200..299->NeonGreen;r.statusCode in 400..499->Amber;else->AlertRed}, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text("${r.durationMs}ms", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = TextPrimary) } },
                actions = {
                    // FIX #1: only show decode buttons in HEX sub-tab
                    if (subTab == 2) {
                        IconButton(onClick = { showDecodeOverlay = true }) {
                            Icon(Icons.Default.Code, "Open decode overlay", tint = Amber)
                        }
                        IconButton(onClick = ::decodeSplit) {
                            if (isSplitDecoding) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ElectricBlue)
                            else Icon(Icons.Default.VerticalSplit, "Split", tint = ElectricBlue)
                        }
                    }
                    // MOD always available
                    IconButton(onClick = {
                        val body = if (mainTab == 0) request.bodyText ?: "" else response?.bodyText ?: ""
                        onSaveMod(request.endpoint, body); snack = "Mod saved!"
                    }) {
                        Box(Modifier.clip(RoundedCornerShape(5.dp)).background(NeonGreen.copy(0.12f)).border(1.dp,NeonGreen.copy(0.3f),RoundedCornerShape(5.dp)).padding(horizontal=6.dp,vertical=3.dp)) {
                            Text("MOD", color = NeonGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardBlack)
            )
        }
    ) { pv ->
        Column(Modifier.fillMaxSize().padding(pv)) {
            if (splitDecoded != null) {
                Column(Modifier.weight(0.4f).fillMaxWidth().background(ElevatedBlack)) {
                    Row(Modifier.fillMaxWidth().background(CardBlack).padding(horizontal=12.dp,vertical=6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) { Icon(Icons.Default.Api,null,tint=NeonGreen,modifier=Modifier.size(14.dp)); Text("DECODED",color=NeonGreen,fontSize=10.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold) }
                        Row { IconButton(onClick={clipboard.setText(AnnotatedString(splitDecoded?:""));snack="Copied!"},modifier=Modifier.size(28.dp)){Icon(Icons.Default.ContentCopy,null,tint=NeonGreen,modifier=Modifier.size(14.dp))}
                            IconButton(onClick={splitDecoded=null;splitError=null},modifier=Modifier.size(28.dp)){Icon(Icons.Default.Close,null,tint=TextSecondary,modifier=Modifier.size(14.dp))} }
                    }
                    splitError?.let{e->Row(Modifier.fillMaxWidth().background(AlertRed.copy(0.08f)).padding(8.dp)){Text(e,color=AlertRed,fontSize=11.sp,fontFamily=FontFamily.Monospace)}}
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)){Text(splitDecoded?:"",color=NeonGreen.copy(0.9f),fontSize=11.sp,fontFamily=FontFamily.Monospace,lineHeight=17.sp)}
                }
                HorizontalDivider(color=NeonGreen,thickness=2.dp)
                Column(Modifier.weight(0.6f).fillMaxWidth()){DetailBody(request,response,mainTab,subTab,{mainTab=it;subTab=0;splitDecoded=null},{subTab=it},onSaveMod,clipboard){snack=it}}
            } else {
                DetailBody(request,response,mainTab,subTab,{mainTab=it;subTab=0},{subTab=it},onSaveMod,clipboard){snack=it}
            }
        }
    }

    if (showDecodeOverlay) {
        FloatingDecodeOverlay(request=request, response=response, onDismiss={showDecodeOverlay=false},
            onSaveMod={body->onSaveMod(request.endpoint,body);showDecodeOverlay=false;snack="Mod saved!"})
    }

    snack?.let{msg->LaunchedEffect(msg){kotlinx.coroutines.delay(1800);snack=null};Box(Modifier.fillMaxSize().padding(bottom=16.dp),Alignment.BottomCenter){Snackbar(containerColor=ElevatedBlack){Text(msg,color=NeonGreen,fontSize=12.sp)}}}
}

@Composable
private fun DetailBody(
    request: CapturedRequest, response: CapturedResponse?,
    mainTab: Int, subTab: Int,
    onMainTab: (Int)->Unit, onSubTab: (Int)->Unit,
    onSaveMod: (String,String)->Unit,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onSnack: (String)->Unit
) {
    val isReq = mainTab == 0
    TabRow(selectedTabIndex=mainTab,containerColor=CardBlack,contentColor=NeonGreen,indicator={tp->TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tp[mainTab]),color=NeonGreen)}) {
        listOf("REQUEST","RESPONSE").forEachIndexed { i,t -> Tab(selected=mainTab==i,onClick={onMainTab(i)},text={
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Text(t,fontSize=11.sp,fontFamily=FontFamily.Monospace)
                if(i==1&&response!=null){val c=when{response.statusCode in 200..299->NeonGreen;response.statusCode in 400..499->Amber;else->AlertRed};Badge(containerColor=c.copy(0.15f)){Text("${response.statusCode}",color=c,fontSize=9.sp)}}
            }
        })}
    }
    ScrollableTabRow(selectedTabIndex=subTab,containerColor=ElevatedBlack,contentColor=ElectricBlue,edgePadding=0.dp,divider={},
        indicator={tp->TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tp[subTab]),color=ElectricBlue,height=1.5.dp)}) {
        listOf("BODY","HEADERS","HEX").forEachIndexed { i,t -> Tab(selected=subTab==i,onClick={onSubTab(i)},text={Text(t,fontSize=11.sp,fontFamily=FontFamily.Monospace)}) }
    }
    HorizontalDivider(color=DividerGray,thickness=0.5.dp)

    // Action row — no decode here at all (Fix #1)
    Row(Modifier.fillMaxWidth().background(ElevatedBlack).padding(horizontal=10.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val content = when(subTab){1->if(isReq)request.headersAsString() else response?.headersAsString()?:"";2->if(isReq)request.body?.let{HexUtils.toHexDump(it)}?:"" else response?.body?.let{HexUtils.toHexDump(it)}?:"";else->if(isReq)request.bodyText?:"" else response?.bodyText?:""}
        TextButton(onClick={clipboard.setText(AnnotatedString(content));onSnack("Copied!")},colors=ButtonDefaults.textButtonColors(contentColor=NeonGreen)){Icon(Icons.Default.ContentCopy,null,modifier=Modifier.size(13.dp));Spacer(Modifier.width(4.dp));Text("Copy",fontSize=11.sp)}
        if(isReq&&subTab==0&&(request.bodyText?.isNotEmpty()==true)){
            TextButton(onClick={onSaveMod(request.endpoint,request.bodyText??"");onSnack("Mod saved!")},colors=ButtonDefaults.textButtonColors(contentColor=NeonGreen)){Icon(Icons.Default.Save,null,modifier=Modifier.size(13.dp));Spacer(Modifier.width(4.dp));Text("Save Mod",fontSize=11.sp)}
        }
    }
    HorizontalDivider(color=DividerGray,thickness=0.5.dp)

    val displayContent = when(subTab){1->if(isReq)request.headersAsString() else response?.headersAsString()??"(no response)";2->if(isReq)request.body?.let{HexUtils.toHexDump(it)}??"(no hex)" else response?.body?.let{HexUtils.toHexDump(it)}??"(no hex)";else->if(isReq)request.bodyText??"(empty)" else response?.bodyText??"(waiting…)"}
    val textColor = when{subTab==2->Amber.copy(0.85f);subTab==1->TextSecondary;isReq->NeonGreen.copy(0.9f);else->ElectricBlue.copy(0.9f)}
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)){Text(displayContent,color=textColor,fontSize=11.sp,fontFamily=FontFamily.Monospace,lineHeight=17.sp)}
}
