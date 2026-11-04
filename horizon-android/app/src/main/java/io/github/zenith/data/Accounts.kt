package io.github.zenith.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Uma conta: Microsoft (pode jogar online) ou nome offline (só joga atrás de uma
 * conta dona do jogo, como no launcher do Horizon).
 *
 * `accounts.json` guarda só dados públicos (nome, UUID, skin). Tokens ficam em
 * `account-secrets.bin`, cifrados com uma chave AES do Android Keystore.
 */
data class Account(
    val id: String,
    val type: Type,
    val name: String,
    val uuid: String,
    val skinUrl: String = "",
    val capeUrl: String = "",
    /** Skin escolhida no celular (PNG 64x64), para nomes offline. */
    val localSkin: String = "",
    /** "classic" ou "slim". */
    val skinModel: String = "classic",
    val localCape: String = "",
    val added: Long = System.currentTimeMillis(),
) {
    enum class Type { MICROSOFT, OFFLINE }

    val isMicrosoft get() = type == Type.MICROSOFT
    val skinFile: File? get() = localSkin.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    fun toJson() = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("uuid", uuid)
        .put("skinUrl", skinUrl).put("capeUrl", capeUrl).put("localSkin", localSkin)
        .put("skinModel", skinModel).put("localCape", localCape).put("added", added)

    companion object {
        fun fromJson(o: JSONObject) = Account(
            id = o.getString("id"),
            type = runCatching { Type.valueOf(o.getString("type")) }.getOrDefault(Type.OFFLINE),
            name = o.getString("name"),
            uuid = o.getString("uuid"),
            skinUrl = o.optString("skinUrl", ""),
            capeUrl = o.optString("capeUrl", ""),
            localSkin = o.optString("localSkin", ""),
            skinModel = o.optString("skinModel", "classic"),
            localCape = o.optString("localCape", ""),
            added = o.optLong("added", 0),
        )

        /** O UUID que o Java dá a um nome offline: nameUUIDFromBytes("OfflinePlayer:" + nome). */
        fun offlineUuid(name: String): String =
            UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString().replace("-", "")

        /** Nomes do Minecraft: 3 a 16 letras, números e _. */
        fun validName(name: String) = Regex("^[A-Za-z0-9_]{3,16}$").matches(name)
    }
}

/** Os segredos de uma conta Microsoft. */
data class AccountSecrets(
    val refreshToken: String,
    val minecraftToken: String,
    /** Quando o token do Minecraft vence (ms desde a época). */
    val expiresAt: Long,
    /** A última resposta: a conta tem o Java Edition? */
    val owns: Boolean,
    val checkedAt: Long,
    val xuid: String = "",
) {
    fun toJson() = JSONObject()
        .put("refreshToken", refreshToken).put("minecraftToken", minecraftToken)
        .put("expiresAt", expiresAt).put("owns", owns).put("checkedAt", checkedAt).put("xuid", xuid)

    companion object {
        fun fromJson(o: JSONObject) = AccountSecrets(
            refreshToken = o.optString("refreshToken"),
            minecraftToken = o.optString("minecraftToken"),
            expiresAt = o.optLong("expiresAt"),
            owns = o.optBoolean("owns"),
            checkedAt = o.optLong("checkedAt"),
            xuid = o.optString("xuid", ""),
        )
    }
}

object AccountStore {
    private val lock = Any()
    private const val KEY_ALIAS = "zenith-account-secrets"

    fun all(): List<Account> = synchronized(lock) {
        val file = Paths.accountsFile
        if (!file.isFile) return emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { Account.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(id: String?): Account? = all().firstOrNull { it.id == id }

    fun selected(settings: LauncherSettings): Account? =
        get(settings.selectedAccount) ?: all().firstOrNull()

    fun save(account: Account) = synchronized(lock) {
        val list = all().toMutableList()
        val at = list.indexOfFirst { it.id == account.id }
        if (at >= 0) list[at] = account else list.add(account)
        writeAccounts(list)
    }

    fun remove(account: Account) = synchronized(lock) {
        writeAccounts(all().filter { it.id != account.id })
        val secrets = readSecrets()
        secrets.remove(account.id)
        writeSecrets(secrets)
        account.skinFile?.delete()
    }

    fun addOffline(name: String): Account {
        val uuid = Account.offlineUuid(name)
        val account = Account(id = "offline-$uuid", type = Account.Type.OFFLINE, name = name, uuid = uuid)
        save(account)
        return account
    }

    // --- Posse do jogo (o "owner gate" do Horizon) ---

    /** Alguma conta Microsoft já respondeu que tem o Minecraft: Java Edition. */
    fun anyOwner(): Boolean = all().any { it.isMicrosoft && secrets(it.id)?.owns == true }

    // --- Segredos ---

    fun secrets(id: String): AccountSecrets? = synchronized(lock) { readSecrets()[id] }

    fun putSecrets(id: String, secrets: AccountSecrets) = synchronized(lock) {
        val all = readSecrets()
        all[id] = secrets
        writeSecrets(all)
    }

    private fun readSecrets(): MutableMap<String, AccountSecrets> {
        val file = Paths.secretsFile
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val o = JSONObject(decrypt(file.readText()))
            o.keys().asSequence().associateWith { AccountSecrets.fromJson(o.getJSONObject(it)) }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun writeSecrets(map: Map<String, AccountSecrets>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v.toJson()) }
        Paths.secretsFile.writeText(encrypt(o.toString()))
    }

    private fun writeAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Paths.accountsFile.writeText(arr.toString(2))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String {
        val (iv, data) = text.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
