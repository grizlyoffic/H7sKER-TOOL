package com.nexbytes.h7skertool.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexbytes.h7skertool.model.CapturedRequest
import com.nexbytes.h7skertool.model.CapturedResponse
import com.nexbytes.h7skertool.model.LogEntry
import com.nexbytes.h7skertool.model.LogLevel
import com.nexbytes.h7skertool.service.ProxyForegroundService
import com.nexbytes.h7skertool.service.ShizukuFileService
import com.nexbytes.h7skertool.session.SessionManager
import com.nexbytes.h7skertool.shizuku.ShizukuManager
import com.nexbytes.h7skertool.utils.ModFile
import com.nexbytes.h7skertool.utils.ModManager
import com.nexbytes.h7skertool.utils.ModType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

data class AppUiState(
    val shizukuAvailable: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val isVerified: Boolean = false,
    val isVerifying: Boolean = false,
    val verifyError: String? = null,
    val clientUrl: String = "",
    val isCapturing: Boolean = false,
    val requests: List<CapturedRequest> = emptyList(),
    val responses: Map<String, CapturedResponse> = emptyMap(),
    val logs: List<LogEntry> = emptyList(),
    val savedMods: Map<String, String> = emptyMap(),
    val searchQuery: String = "",
    val endpointFilter: String? = null,
    val fileWriteStatus: List<String> = emptyList(),
    val errorMessage: String? = null,
    val username: String = ""
) {
    val needsShizuku   get() = !shizukuAvailable || !shizukuPermissionGranted
    val needsPassword  get() = shizukuAvailable && shizukuPermissionGranted && !isVerified
    val needsClientUrl get() = shizukuAvailable && shizukuPermissionGranted && isVerified && clientUrl.isEmpty()
    val filteredRequests: List<CapturedRequest> get() {
        var list = requests
        if (searchQuery.isNotBlank()) list = list.filter { r -> r.endpoint.contains(searchQuery,true)||r.url.contains(searchQuery,true)||r.bodyText?.contains(searchQuery,true)==true }
        if (endpointFilter != null) list = list.filter { it.endpoint == endpointFilter }
        return list
    }
    val allEndpoints: List<String> get() = requests.map { it.endpoint }.distinct().sorted()
}

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "CaptureVM"
    private val session = SessionManager(app)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    // FIX #3: Separate StateFlow for disk-persisted mod files
    private val _savedModFiles = MutableStateFlow<List<ModFile>>(emptyList())
    val savedModFiles: StateFlow<List<ModFile>> = _savedModFiles.asStateFlow()

    private val _selectedMod = MutableStateFlow<ModFile?>(null)
    val selectedMod: StateFlow<ModFile?> = _selectedMod.asStateFlow()

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        val granted = result == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(shizukuPermissionGranted = granted) }
    }
    private var callbacksRegistered = false

    init {
        observeSession()
        startShizukuPoller()
        viewModelScope.launch(Dispatchers.IO) { refreshModFiles() } // FIX #3: eager load
    }

    private fun observeSession() {
        viewModelScope.launch { session.isVerified.collect { v -> _state.update { it.copy(isVerified = v) } } }
        viewModelScope.launch { session.clientUrl.collect { url -> _state.update { it.copy(clientUrl = url) } } }
        viewModelScope.launch { session.username.collect { u -> _state.update { it.copy(username = u) } } }
    }

    private fun setupCallbacks() {
        if (callbacksRegistered) return; callbacksRegistered = true
        ProxyForegroundService.onCapture = { req, res ->
            viewModelScope.launch(Dispatchers.Main) { _state.update { s -> s.copy(requests = (listOf(req)+s.requests).take(1000), responses = s.responses+(req.id to res)) } }
        }
        ProxyForegroundService.onLog = { msg -> viewModelScope.launch(Dispatchers.Main) { log(LogLevel.INFO, "Proxy", msg) } }
    }

    private fun startShizukuPoller() {
        viewModelScope.launch { while (true) { val avail = ShizukuManager.isShizukuAvailable(); val granted = if (avail) ShizukuManager.hasPermission() else false; _state.update { it.copy(shizukuAvailable=avail,shizukuPermissionGranted=granted) }; delay(2000) } }
    }

    fun checkShizuku() { val avail = ShizukuManager.isShizukuAvailable(); _state.update { it.copy(shizukuAvailable=avail,shizukuPermissionGranted=if(avail)ShizukuManager.hasPermission() else false) } }
    fun requestShizukuPermission() { ShizukuManager.requestPermission(permissionListener) }

    fun verifyPassword(password: String) {
        if (password.isBlank()) { _state.update { it.copy(verifyError="Password cannot be empty") }; return }
        _state.update { it.copy(isVerifying=true, verifyError=null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "http://node.mrkalpha.tech:19140/password=$password"
                val response = http.newCall(Request.Builder().url(url).get().build()).execute()
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Password API response: $body")

                if (body.contains("\"result\":\"0\"") || body.contains("\"result\": \"0\"")) {
                    session.setVerified(true, username = password)
                    _state.update { it.copy(isVerifying = false, verifyError = null) }
                    log(LogLevel.INFO, "Auth", "Verified successfully ✓")
                } else {
                    _state.update { it.copy(isVerifying = false, verifyError = "Invalid password. Access denied.") }
                    log(LogLevel.WARNING, "Auth", "Verification failed — response: $body")
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVerifying = false, verifyError = "Network error: ${e.message}") }
                log(LogLevel.ERROR, "Auth", "Verification error: ${e.message}")
            }
        }
    }

    fun setClientUrl(url: String) { viewModelScope.launch { session.setClientUrl(url.trim().trimEnd('/')) } }

    fun startCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { setupCallbacks() }
            val activeMods = _state.value.savedMods.toMutableMap()
            _selectedMod.value?.let { mod -> activeMods["__selected_mod__"] = mod.content }
            val results = ShizukuFileService.writeLocalConfigFiles()
            _state.update { it.copy(fileWriteStatus=results.map { r -> if(r.success)"✓ ${r.path}" else "✗ ${r.path}: ${r.error}" }) }
            ProxyForegroundService.savedMods = activeMods
            withContext(Dispatchers.Main) { ProxyForegroundService.start(getApplication(), _state.value.clientUrl) }
            _state.update { it.copy(isCapturing=true) }
            log(LogLevel.INFO, "Proxy", "Started → ${_state.value.clientUrl}")
        }
    }

    fun stopCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { ProxyForegroundService.stop(getApplication()) }
            ShizukuFileService.removeLocalConfigFiles()
            _state.update { it.copy(isCapturing=false) }
            log(LogLevel.INFO, "Capture", "Stopped.")
        }
    }

    fun clearCaptures() { _state.update { it.copy(requests=emptyList(), responses=emptyMap()) } }
    fun setSearch(q: String) { _state.update { it.copy(searchQuery=q) } }
    fun setEndpointFilter(ep: String?) { _state.update { it.copy(endpointFilter=ep) } }

    // FIX #4: save in-memory + disk + update proxy
    fun saveModification(endpoint: String, body: String) {
        viewModelScope.launch {
            val mods = _state.value.savedMods.toMutableMap()
            mods[endpoint] = body
            _state.update { it.copy(savedMods=mods) }
            ProxyForegroundService.savedMods = mods
            val modName = endpoint.trimStart('/').replace("/","_").take(40).ifEmpty { "mod_${System.currentTimeMillis()}" }
            withContext(Dispatchers.IO) { ModManager.saveMod(getApplication(), modName, body, ModType.REQUEST); refreshModFiles() }
            log(LogLevel.INFO, "Mod", "Saved '$modName' for $endpoint")
        }
    }

    fun clearModifications() { _state.update { it.copy(savedMods=emptyMap()) }; ProxyForegroundService.savedMods = emptyMap() }

    // FIX #3: always re-reads from disk
    fun loadModFiles() { viewModelScope.launch(Dispatchers.IO) { refreshModFiles() } }
    private suspend fun refreshModFiles() { val mods = ModManager.loadMods(getApplication()); Log.d(TAG,"Loaded ${mods.size} mods"); _savedModFiles.value = mods }

    fun saveModFile(name: String, content: String, type: ModType = ModType.RESPONSE) {
        viewModelScope.launch(Dispatchers.IO) { val ok = ModManager.saveMod(getApplication(),name,content,type); if(ok){refreshModFiles();log(LogLevel.INFO,"ModFile","Saved: $name")} }
    }

    fun deleteModFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) { ModManager.deleteMod(getApplication(),name); if(_selectedMod.value?.name==name) _selectedMod.value=null; refreshModFiles() }
    }

    fun selectMod(mod: ModFile?) { _selectedMod.value = mod }

    fun applyModToProxy(mod: ModFile) {
        val mods = _state.value.savedMods.toMutableMap()
        mods["__selected_mod__"] = mod.content
        _state.update { it.copy(savedMods=mods) }
        ProxyForegroundService.savedMods = mods
        log(LogLevel.INFO, "Mod", "Applied: ${mod.name}")
    }

    fun logout() { viewModelScope.launch { if(_state.value.isCapturing) stopCapture(); session.logout(); _state.update { AppUiState() }; _selectedMod.value=null } }
    fun resetAll() { viewModelScope.launch { if(_state.value.isCapturing) stopCapture(); session.resetAll(); _state.update { AppUiState() }; _selectedMod.value=null } }

    private fun log(level: LogLevel, tag: String, msg: String) { _state.update { s -> val logs=(s.logs+LogEntry(level=level,tag=tag,message=msg)).takeLast(500); s.copy(logs=logs) } }

    override fun onCleared() {
        super.onCleared()
        ShizukuManager.removePermissionListener(permissionListener)
        ProxyForegroundService.onCapture = null; ProxyForegroundService.onLog = null; callbacksRegistered = false
    }
}
