package me.rerere.rikkahub.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.storageGrantDataStore by preferencesDataStore(name = "storage_volume_grants")

class StorageVolumeGrantStore(private val context: Context) {
    data class Grant(val contentUri: String, val displayName: String, val authority: String)

    private val store = context.storageGrantDataStore
    private val grantsKey = stringPreferencesKey("grants")

    suspend fun loadAll(): List<Grant> = store.data.map { decode(it[grantsKey].orEmpty()) }.first()

    suspend fun add(grant: Grant) {
        store.edit { prefs ->
            val current = decode(prefs[grantsKey].orEmpty()).filterNot { it.contentUri == grant.contentUri }
            prefs[grantsKey] = encode(current + grant)
        }
    }

    suspend fun reconcile(): List<Grant> {
        val held = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val current = loadAll()
        val survivors = current.filter { it.contentUri in held }
        if (survivors.size != current.size) {
            store.edit { it[grantsKey] = encode(survivors) }
        }
        return survivors
    }

    private fun encode(grants: List<Grant>) = grants.joinToString("\u001E") {
        listOf(it.contentUri, it.displayName, it.authority).joinToString("\u001F")
    }

    private fun decode(raw: String): List<Grant> = raw.split("\u001E").mapNotNull { line ->
        val p = line.split("\u001F")
        if (p.size == 3) Grant(p[0], p[1], p[2]) else null
    }.filter { it.contentUri.isNotBlank() }
}
