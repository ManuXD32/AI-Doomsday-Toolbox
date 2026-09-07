package com.example.llamadroid.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(context: Context) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
    
    private val _threads = MutableStateFlow(prefs.getInt("threads", 4))
    val threads = _threads.asStateFlow()

    private val _contextSize = MutableStateFlow(prefs.getInt("context_size", 8192))
    val contextSize = _contextSize.asStateFlow()
    
    private val _temperature = MutableStateFlow(prefs.getFloat("temperature", 0.8f))
    val temperature = _temperature.asStateFlow()
    
    fun setThreads(value: Int) {
        _threads.value = value
        prefs.edit().putInt("threads", value).apply()
    }
    
    fun setContextSize(value: Int) {
        _contextSize.value = value
        prefs.edit().putInt("context_size", value).apply()
    }
    
    fun setTemperature(value: Float) {
        _temperature.value = value
        prefs.edit().putFloat("temperature", value).apply()
    }
}
