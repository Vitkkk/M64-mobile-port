package io.github.zenith

import io.github.zenith.data.Account
import io.github.zenith.data.AccountStore
import io.github.zenith.data.LauncherSettings

/**
 * Chaves de desenvolvimento. TEMPORÁRIO: remover antes de distribuir o app.
 *
 * Para tirar a conta de teste: mude [TEST_ACCOUNT] para false (o botão some da
 * aba Conta e do Início) ou apague este arquivo e os usos de `DevFlags`.
 */
object DevFlags {
    /** Botão "Conta de teste (offline)": joga sem conta Microsoft, só para testar o port. */
    const val TEST_ACCOUNT = true

    /**
     * Cria (ou reaproveita) uma conta offline de teste, liga a chave que pula a
     * verificação de posse do jogo e seleciona essa conta.
     */
    fun createTestAccount(settings: LauncherSettings, name: String): Account {
        settings.ownerGateBypass = true
        val account = AccountStore.all().firstOrNull { !it.isMicrosoft && it.name == name } ?: AccountStore.addOffline(name)
        settings.selectedAccount = account.id
        return account
    }
}
