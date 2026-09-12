package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

private val MCP_NAME_REGEX = Regex("[A-Za-z0-9]+")

/** Lets the model manage the app-level MCP server list. */
fun createMcpManageTools(
    mcpManager: McpManager,
    settingsStore: SettingsStore,
): List<Tool> = listOf(
    Tool(
        name = "manage_mcp_server",
        description = """
            Manage the app's MCP server registrations.
            action=list lists id, name, URL, transport and enabled state without request headers.
            action=save adds a server, or updates one by id or name. Required for a new server: name, url and transport.
            action=delete removes a server by id or name and requires user confirmation.
            transport must be streamable_http or sse. headers is an optional JSON object of HTTP header key-value pairs.
            A saved server is not automatically assigned to every assistant.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list")
                            add("save")
                            add("delete")
                        })
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Server UUID; use it to update or delete")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Server name; ASCII letters and numbers only")
                    })
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "Remote MCP endpoint URL")
                    })
                    put("transport", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("streamable_http")
                            add("sse")
                        })
                        put("description", "Remote transport type")
                    })
                    put("headers", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional request headers as string key-value pairs")
                    })
                    put("enable", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether the server is enabled; defaults to true for a new server")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input ->
            input.jsonObject["action"]?.jsonPrimitive?.contentOrNull == "delete"
        },
        execute = { input ->
            val objectInput = input.jsonObject
            when (objectInput["action"]?.jsonPrimitive?.contentOrNull) {
                "list" -> listOf(UIMessagePart.Text(renderServers(currentServers(settingsStore))))
                "save" -> saveServer(objectInput, mcpManager, settingsStore)
                "delete" -> deleteServer(objectInput, mcpManager, settingsStore)
                else -> listOf(UIMessagePart.Text("action must be list, save, or delete"))
            }
        },
    ),
)

private suspend fun currentServers(settingsStore: SettingsStore): List<McpServerConfig> =
    settingsStore.settingsFlowRaw.first().mcpServers

private suspend fun saveServer(
    input: JsonObject,
    mcpManager: McpManager,
    settingsStore: SettingsStore,
): List<UIMessagePart> {
    val existing = currentServers(settingsStore)
    val idText = input.stringValue("id")
    val target = when {
        idText != null -> {
            val id = runCatching { Uuid.parse(idText) }
                .getOrElse { return errorResult("id must be a valid MCP server UUID") }
            existing.firstOrNull { it.id == id }
                ?: return errorResult("No MCP server found with id $idText")
        }
        else -> input.stringValue("name")?.let { name ->
            existing.firstOrNull { it.commonOptions.name == name }
        }
    }

    val name = input.stringValue("name") ?: target?.commonOptions?.name.orEmpty()
    if (name.isBlank()) return errorResult("name is required when adding an MCP server")
    if (!MCP_NAME_REGEX.matches(name)) {
        return errorResult("name must contain only ASCII letters and numbers")
    }
    val duplicate = existing.firstOrNull { it.id != target?.id && it.commonOptions.name == name }
    if (duplicate != null) return errorResult("An MCP server named '$name' already exists")

    val url = input.stringValue("url") ?: target?.serverUrl.orEmpty()
    if (url.isBlank()) return errorResult("url is required when adding an MCP server")
    val transport = input.stringValue("transport") ?: target?.transportName() ?: "streamable_http"
    if (transport != "sse" && transport != "streamable_http") {
        return errorResult("transport must be streamable_http or sse")
    }

    val enable = input.booleanValue("enable") ?: target?.commonOptions?.enable ?: true
    val headers = try {
        input["headers"]?.toHeaderPairs() ?: target?.commonOptions?.headers.orEmpty()
    } catch (e: IllegalArgumentException) {
        return errorResult(e.message ?: "headers must be a JSON object of string values")
    }
    val commonOptions = McpCommonOptions(
        enable = enable,
        name = name,
        headers = headers,
        tools = target?.commonOptions?.tools.orEmpty(),
        oauth = target?.commonOptions?.oauth,
    )
    val id = target?.id ?: Uuid.random()
    val config = when (transport) {
        "sse" -> McpServerConfig.SseTransportServer(id, commonOptions, url)
        "streamable_http" -> McpServerConfig.StreamableHTTPServer(id, commonOptions, url)
        else -> error("unreachable transport")
    }

    settingsStore.update { settings ->
        settings.copy(
            mcpServers = if (target == null) {
                settings.mcpServers + config
            } else {
                settings.mcpServers.map { server -> if (server.id == id) config else server }
            },
        )
    }
    val syncError = try {
        if (config.commonOptions.enable) {
            mcpManager.addClient(config)
        } else {
            mcpManager.removeClient(config)
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
    val operation = if (target == null) "Added" else "Updated"
    val result = "${operation} MCP server '${name}' (${transport}), enabled=${enable}"
    return listOf(
        UIMessagePart.Text(
            if (syncError == null) result
            else "$result, but connection sync failed: ${syncError.message ?: syncError.javaClass.simpleName}",
        )
    )
}

private suspend fun deleteServer(
    input: JsonObject,
    mcpManager: McpManager,
    settingsStore: SettingsStore,
): List<UIMessagePart> {
    val idText = input.stringValue("id")
    val name = input.stringValue("name")
    val servers = currentServers(settingsStore)
    val target = when {
        idText != null -> {
            val id = runCatching { Uuid.parse(idText) }
                .getOrElse { return errorResult("id must be a valid MCP server UUID") }
            servers.firstOrNull { it.id == id }
        }
        !name.isNullOrBlank() -> servers.firstOrNull { it.commonOptions.name == name }
        else -> null
    } ?: return errorResult("No matching MCP server found")

    mcpManager.removeClient(target)
    settingsStore.update { settings ->
        settings.copy(
            mcpServers = settings.mcpServers.filter { it.id != target.id },
            assistants = settings.assistants.map { assistant ->
                assistant.copy(mcpServers = assistant.mcpServers - target.id)
            },
        )
    }
    return listOf(UIMessagePart.Text("Deleted MCP server '${target.commonOptions.name}'"))
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitiveOrNull?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.booleanValue(key: String): Boolean? = when (val value = this[key]) {
    null -> null
    is JsonPrimitive -> if (value.isString) {
        when (value.content.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    } else {
        value.booleanOrNull ?: value.intOrNull?.let { it != 0 }
    }
    else -> null
}

private fun JsonElement.toHeaderPairs(): List<Pair<String, String>> {
    val objectValue = this as? JsonObject
        ?: throw IllegalArgumentException("headers must be a JSON object")
    return objectValue.map { (key, value) ->
        val headerValue = value.jsonPrimitiveOrNull?.contentOrNull
            ?: throw IllegalArgumentException("header '$key' must be a string")
        if (key.isBlank()) throw IllegalArgumentException("header names cannot be blank")
        key to headerValue
    }
}

private fun McpServerConfig.transportName(): String = when (this) {
    is McpServerConfig.SseTransportServer -> "sse"
    is McpServerConfig.StreamableHTTPServer -> "streamable_http"
}

private fun renderServers(servers: List<McpServerConfig>): String {
    if (servers.isEmpty()) return "No MCP servers registered."
    return buildString {
        appendLine("MCP servers (${servers.size}):")
        servers.forEach { server ->
            appendLine(
                "- id=${server.id} | name=${server.commonOptions.name} | " +
                    "url=${server.serverUrl} | transport=${server.transportName()} | " +
                    "enabled=${server.commonOptions.enable}",
            )
        }
    }
}

private fun errorResult(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text("MCP error: $message"))
