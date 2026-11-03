package io.github.zenith.auth

import io.github.zenith.data.Account
import io.github.zenith.data.AccountSecrets
import io.github.zenith.data.AccountStore
import io.github.zenith.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Login Microsoft pelo fluxo de código de dispositivo, como o launcher do Horizon
 * (docs/microsoft-account.md): Microsoft → Xbox Live → XSTS → Minecraft →
 * conferência de posse do Java Edition → perfil (nome, UUID, skin).
 *
 * Precisa de um Client ID do Azure próprio, aprovado pela Mojang para a API do
 * Minecraft. Nunca use o Client ID de outro aplicativo.
 */
class MicrosoftAuth(private val clientId: String) {

    class AuthException(message: String) : IOException(message)

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val deviceCode: String,
        val interval: Int,
        val expiresIn: Int,
        val message: String,
    )

    data class Profile(val uuid: String, val name: String, val skinUrl: String, val skinModel: String, val capeUrl: String)

    private val tenant = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

    init {
        if (clientId.isBlank()) throw AuthException(
            "Este build não tem um Client ID do Azure. Informe o seu em Ajustes → Conta Microsoft."
        )
    }

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        val o = Http.postForm("$tenant/devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
        DeviceCode(
            userCode = o.getString("user_code"),
            verificationUri = o.optString("verification_uri", "https://www.microsoft.com/link"),
            deviceCode = o.getString("device_code"),
            interval = o.optInt("interval", 5),
            expiresIn = o.optInt("expires_in", 900),
            message = o.optString("message"),
        )
    }

    /** Espera o jogador digitar o código no navegador; devolve os tokens Microsoft. */
    suspend fun pollToken(code: DeviceCode): Pair<String, String> {
        var interval = code.interval.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(
                        Http.postForm(
                            "$tenant/token", mapOf(
                                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                                "client_id" to clientId,
                                "device_code" to code.deviceCode,
                            )
                        )
                    )
                } catch (e: Http.HttpError) {
                    Result.failure(e)
                }
            }
            result.onSuccess { return it.getString("access_token") to it.optString("refresh_token") }
            val error = (result.exceptionOrNull() as? Http.HttpError)?.body
                ?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
            when (error) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                "authorization_declined" -> throw AuthException("O login foi recusado.")
                "expired_token" -> throw AuthException("O código expirou. Tente de novo.")
                "bad_verification_code" -> throw AuthException("Código inválido.")
                else -> throw (result.exceptionOrNull() ?: AuthException("Falha no login"))
            }
        }
        throw AuthException("O código expirou. Tente de novo.")
    }

    /** Do token Microsoft até a conta salva, conferindo a posse do jogo. */
    suspend fun completeSignIn(msAccess: String, msRefresh: String): Account = withContext(Dispatchers.IO) {
        val (mcToken, expiresIn, xuid) = minecraftLogin(msAccess)
        val owns = ownsJava(mcToken)
        if (!owns) throw AuthException("Esta conta não tem o Minecraft: Java Edition.")
        val profile = profile(mcToken)
        val account = Account(
            id = "msa-${profile.uuid}",
            type = Account.Type.MICROSOFT,
            name = profile.name,
            uuid = profile.uuid,
            skinUrl = profile.skinUrl,
            skinModel = profile.skinModel,
            capeUrl = profile.capeUrl,
        )
        AccountStore.save(account)
        AccountStore.putSecrets(
            account.id,
            AccountSecrets(msRefresh, mcToken, System.currentTimeMillis() + expiresIn * 1000L, owns, System.currentTimeMillis(), xuid)
        )
        account
    }

    /**
     * Antes de cada partida: renova o token do Minecraft se falta menos de uma hora
     * e pergunta de novo pela posse. Sem rede, vale a última resposta.
     */
    suspend fun refresh(account: Account): AccountSecrets = withContext(Dispatchers.IO) {
        val old = AccountStore.secrets(account.id) ?: throw AuthException("Entre de novo com a conta ${account.name}.")
        try {
            var token = old.minecraftToken
            var expires = old.expiresAt
            var refreshToken = old.refreshToken
            var xuid = old.xuid
            if (expires - System.currentTimeMillis() < 3_600_000L) {
                val o = try {
                    Http.postForm(
                        "$tenant/token", mapOf(
                            "grant_type" to "refresh_token", "client_id" to clientId,
                            "refresh_token" to old.refreshToken, "scope" to SCOPE,
                        )
                    )
                } catch (e: Http.HttpError) {
                    if (e.body.contains("invalid_grant")) throw AuthException("A sessão de ${account.name} expirou. Entre de novo.")
                    throw e
                }
                refreshToken = o.optString("refresh_token", refreshToken)
                val (mc, exp, x) = minecraftLogin(o.getString("access_token"))
                token = mc; expires = System.currentTimeMillis() + exp * 1000L; xuid = x
            }
            val owns = ownsJava(token)
            val secrets = AccountSecrets(refreshToken, token, expires, owns, System.currentTimeMillis(), xuid)
            AccountStore.putSecrets(account.id, secrets)
            runCatching {
                val p = profile(token)
                AccountStore.save(account.copy(name = p.name, skinUrl = p.skinUrl, skinModel = p.skinModel, capeUrl = p.capeUrl))
            }
            secrets
        } catch (e: AuthException) {
            throw e
        } catch (e: IOException) {
            // Sem rede: a última resposta vale, e o jogo abre sem sessão online.
            old
        }
    }

    private fun minecraftLogin(msAccess: String): Triple<String, Long, String> {
        val xbl = Http.postJson(
            "https://user.auth.xboxlive.com/user/authenticate",
            JSONObject()
                .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=$msAccess"))
                .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT")
        )
        val xblToken = xbl.getString("Token")
        val uhs = xbl.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        val xsts = try {
            Http.postJson(
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                JSONObject()
                    .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(xblToken)))
                    .put("RelyingParty", "rp://api.minecraftservices.com/").put("TokenType", "JWT")
            )
        } catch (e: Http.HttpError) {
            val xerr = runCatching { JSONObject(e.body).optLong("XErr") }.getOrDefault(0L)
            throw AuthException(xboxError(xerr) ?: "Falha no Xbox Live (${e.code}).")
        }
        val xuid = runCatching {
            xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).optString("xid")
        }.getOrDefault("")
        val mc = try {
            Http.postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                JSONObject().put("identityToken", "XBL3.0 x=$uhs;${xsts.getString("Token")}")
            )
        } catch (e: Http.HttpError) {
            if (e.code == 403) throw AuthException("Este Client ID ainda não foi aprovado pela Mojang para a API do Minecraft.")
            throw e
        }
        return Triple(mc.getString("access_token"), mc.optLong("expires_in", 86400), xuid)
    }

    private fun ownsJava(token: String): Boolean {
        val o = Http.getJson("https://api.minecraftservices.com/entitlements/mcstore", bearer(token))
        val items = o.optJSONArray("items") ?: return false
        return (0 until items.length()).any {
            items.getJSONObject(it).optString("name") in setOf("game_minecraft", "product_minecraft")
        }
    }

    private fun profile(token: String): Profile {
        val o = try {
            Http.getJson("https://api.minecraftservices.com/minecraft/profile", bearer(token))
        } catch (e: Http.HttpError) {
            if (e.code == 404) throw AuthException("A conta tem o jogo, mas ainda não tem perfil. Crie o seu em minecraft.net.")
            throw e
        }
        fun active(name: String) = o.optJSONArray(name)?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("state") == "ACTIVE" }
        }
        val skin = active("skins")
        val cape = active("capes")
        return Profile(
            uuid = o.getString("id"),
            name = o.getString("name"),
            skinUrl = skin?.optString("url").orEmpty(),
            skinModel = if (skin?.optString("variant") == "SLIM") "slim" else "classic",
            capeUrl = cape?.optString("url").orEmpty(),
        )
    }

    /** Troca a skin da conta na Mojang (aparece para todos, em qualquer launcher). */
    suspend fun uploadSkin(account: Account, png: File, slim: Boolean) = withContext(Dispatchers.IO) {
        val secrets = refresh(account)
        Http.postMultipart(
            "https://api.minecraftservices.com/minecraft/profile/skins",
            bearer(secrets.minecraftToken),
            mapOf("variant" to if (slim) "slim" else "classic"),
            "file", png, "image/png",
        )
        val p = profile(secrets.minecraftToken)
        val updated = account.copy(skinUrl = p.skinUrl, skinModel = p.skinModel)
        AccountStore.save(updated)
        updated
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun xboxError(code: Long): String? = when (code) {
        2148916233 -> "Esta conta ainda não tem perfil Xbox. Crie um em xbox.com."
        2148916235 -> "O Xbox Live não está disponível no seu país."
        2148916236, 2148916237 -> "A conta precisa de verificação de idade (Coreia do Sul)."
        2148916238 -> "Conta de criança: precisa ser adicionada a uma família Microsoft."
        else -> null
    }

    companion object {
        const val SCOPE = "XboxLive.signin offline_access"

        /** O JSON de sessão da Launch API do Horizon (`--session-file`). */
        fun sessionJson(account: Account, secrets: AccountSecrets): String = JSONObject()
            .put("name", account.name)
            .put("uuid", account.uuid)
            .put("access_token", secrets.minecraftToken)
            .put("user_type", "msa")
            .apply { if (secrets.xuid.isNotBlank()) put("xuid", secrets.xuid) }
            .toString()
    }
}
