@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.tungsten.fclcore.mod.modinfo

import com.tungsten.fclcore.mod.LocalModFile
import com.tungsten.fclcore.mod.ModDependency
import com.tungsten.fclcore.mod.ModLoaderType
import com.tungsten.fclcore.mod.ModManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import com.tungsten.fclcore.util.io.FileUtils
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class QuiltModMetadata(
    @SerialName("schema_version")
    val schemaVersion: Int = 0,
    @SerialName("quilt_loader")
    val quiltLoader: QuiltLoader
) {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun fromFile(modManager: ModManager, modFile: Path, fs: FileSystem): LocalModFile {
            val path = fs.getPath("quilt.mod.json")
            if (Files.notExists(path))
                throw IOException("File $modFile is not a Quilt mod.")

            val root: QuiltModMetadata = MOD_METADATA_JSON.decodeFromString(FileUtils.readText(path))
            if (root.schemaVersion != 1)
                throw IOException("File $modFile is not a supported Quilt mod.")

            val loader = root.quiltLoader
            val metadata = loader.metadata
            val contributors = metadata.contributors.entries.joinToString(", ") { (name, value) ->
                "$name (${value.jsonPrimitive.content})"
            }
            val file = LocalModFile(
                modManager,
                modManager.getLocalMod(loader.id, ModLoaderType.QUILT),
                modFile,
                metadata.name,
                LocalModFile.Description(metadata.description),
                contributors,
                loader.version,
                "",
                (metadata.contact["homepage"] as? JsonPrimitive)?.content ?: "",
                metadata.icon
            )
            file.setDependencies(dependenciesOf(loader))
            return file
        }

        /**
         * 把 quilt.mod.json 的依赖声明转换成统一模型。
         * Quilt 的 depends/recommends/breaks 是数组，元素可以是字符串或 {id, versions} 对象。
         */
        @JvmStatic
        fun dependenciesOf(loader: QuiltLoader): List<ModDependency> {
            val out = ArrayList<ModDependency>()
            loader.depends.forEach { out.add(ModDependency.required(it.id, it.versions)) }
            loader.recommends.forEach { out.add(ModDependency.optional(it.id, it.versions)) }
            loader.breaks.forEach { out.add(ModDependency.incompatible(it.id, it.versions)) }
            return out
        }
    }
}

@Serializable
data class QuiltLoader(
    val id: String = "",
    val version: String = "",
    val metadata: QuiltMetadata = QuiltMetadata(),
    val depends: List<QuiltDependency> = emptyList(),
    val recommends: List<QuiltDependency> = emptyList(),
    val breaks: List<QuiltDependency> = emptyList()
)

/**
 * Quilt 依赖声明。规范里通常是 {"id": "...", "versions": "..."}，
 * 但社区模组也出现过直接写字符串的简写，两种都要能读。
 */
@Serializable(with = QuiltDependencySerializer::class)
data class QuiltDependency(
    val id: String = "",
    val versions: String = ""
)

internal object QuiltDependencySerializer : KSerializer<QuiltDependency> {
    private val elementSerializer = JsonElement.serializer()

    override val descriptor: SerialDescriptor = elementSerializer.descriptor

    override fun deserialize(decoder: Decoder): QuiltDependency {
        return when (val element = decoder.decodeSerializableValue(elementSerializer)) {
            is JsonPrimitive -> QuiltDependency(element.content, "")
            is JsonObject -> QuiltDependency(
                (element["id"] as? JsonPrimitive)?.content ?: "",
                when (val versions = element["versions"]) {
                    is JsonPrimitive -> versions.content
                    null -> ""
                    else -> versions.toString()
                }
            )
            else -> QuiltDependency()
        }
    }

    override fun serialize(encoder: Encoder, value: QuiltDependency) {
        encoder.encodeSerializableValue(elementSerializer, JsonPrimitive(value.id))
    }
}

@Serializable
data class QuiltMetadata(
    val name: String = "",
    val description: String = "",
    val contributors: Map<String, JsonElement> = emptyMap(),
    val icon: String = "",
    val contact: Map<String, JsonElement> = emptyMap()
)
