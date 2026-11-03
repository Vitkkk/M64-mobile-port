package io.github.zenith.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zenith.data.Account
import io.github.zenith.data.AccountStore
import io.github.zenith.data.GameResources
import io.github.zenith.data.Instance
import io.github.zenith.data.InstanceStore
import io.github.zenith.data.LauncherSettings
import io.github.zenith.game.GameLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O estado que as telas compartilham: instâncias, contas, recursos e mensagens. */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    val settings = LauncherSettings.get(app)

    var instances by mutableStateOf<List<Instance>>(emptyList()); private set
    var accounts by mutableStateOf<List<Account>>(emptyList()); private set
    var instance by mutableStateOf<Instance?>(null); private set
    var account by mutableStateOf<Account?>(null); private set
    var resources by mutableStateOf(GameResources.Status(false, null, null, 0, "Verificando…")); private set
    var theme by mutableStateOf(settings.theme)
    var accent by mutableStateOf(settings.accent)
    var busy by mutableStateOf<String?>(null)
    var progress by mutableStateOf(-1f)
    var message by mutableStateOf<String?>(null)
    var lastExit by mutableStateOf<GameLauncher.LastExit?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val (i, a, r) = withContext(Dispatchers.IO) {
                Triple(InstanceStore.all(), AccountStore.all(), GameResources.status())
            }
            instances = i
            accounts = a
            instance = i.firstOrNull { it.id == settings.selectedInstance } ?: i.firstOrNull()
            account = a.firstOrNull { it.id == settings.selectedAccount } ?: a.firstOrNull()
            resources = r
            lastExit = GameLauncher.lastExit()?.takeIf { it.code != 0 }
        }
    }

    fun selectInstance(i: Instance) {
        settings.selectedInstance = i.id
        instance = i
    }

    fun selectAccount(a: Account) {
        settings.selectedAccount = a.id
        account = a
    }

    fun setThemeMode(v: String) { settings.theme = v; theme = v }
    fun setAccentColor(v: String) { settings.accent = v; accent = v }

    fun toast(text: String) { message = text }

    /** Roda [block] fora da thread principal, com um aviso de "ocupado" e erros na tela. */
    fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        progress = -1f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: GameLauncher.LaunchException) {
                message = e.problem.message
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
                refresh()
            }
        }
    }
}
