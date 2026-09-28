// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.usecase.AddToRecentlyPlayedUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationByIdUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import com.nolansoftware.airadio.player.RadioPlayerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val getStationByIdUseCase: GetStationByIdUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase,
    private val addToRecentlyPlayedUseCase: AddToRecentlyPlayedUseCase
) : AndroidViewModel(context as Application) {

    private var playerService: RadioPlayerService? = null
    private var isServiceBound = false

    private val _playerState = MutableLiveData<PlayerState>(PlayerState.Idle)
    val playerState: LiveData<PlayerState> = _playerState

    var currentStation by mutableStateOf<Station?>(null)
        private set

    fun isFavorite(stationId: String): StateFlow<Boolean> {
        return isFavoriteUseCase(stationId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as RadioPlayerService.RadioPlayerBinder
            playerService = binder.getService()
            isServiceBound = true

            viewModelScope.launch {
                playerService?.playerState?.collect { state ->
                    _playerState.postValue(state)
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
            playerService = null
        }
    }

    init {
        bindPlayerService()
    }

    fun loadStation(stationId: String) {
        viewModelScope.launch {
            currentStation = getStationByIdUseCase(stationId)
        }
    }

    fun playStation(station: Station) {
        currentStation = station
        val intent = RadioPlayerService.newPlayIntent(getApplication(), station)
        getApplication<Application>().startForegroundService(intent)

        viewModelScope.launch {
            addToRecentlyPlayedUseCase(station.stationuuid)
        }
    }

    fun pause() {
        val intent = RadioPlayerService.newPauseIntent(getApplication())
        getApplication<Application>().startService(intent)
    }

    fun stop() {
        val intent = RadioPlayerService.newStopIntent(getApplication())
        getApplication<Application>().startService(intent)
    }

    fun toggleFavorite(station: Station) {
        viewModelScope.launch {
            toggleFavoriteUseCase(station.stationuuid)
        }
    }

    private fun bindPlayerService() {
        Intent(getApplication(), RadioPlayerService::class.java).also { intent ->
            getApplication<Application>().bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}