@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.tungsten.fclcore.mod.modinfo

import com.tungsten.fclcore.mod.LocalModFile
import com.tungsten.fclcore.mod.ModDependency
import com.tungsten.fclcore.mod.ModLoaderType
import com.tungsten.fclcore.mod.ModManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.tungsten.fclcore.util.io.FileUtils
import kotlinx.serialization.json.decodeFromStream
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class FabricModMetadata(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val icon: String = "",
    val description: String = "",
    @Serializable(with = FabricModAuthorListSerializer::class)
    val authors: List<FabricModAuthor> = emptyList(),
    val contact: Map<String, String> = emptyMap(),
    /** 必需前置：mod id -> 版本范围 */
    @Serializable(with = LenientStringMapSerializer::class)
    val depends: Map<String, String> = emptyMap(),
    /** 可选联动：mod id -> 版本范围 */
    @Serializable(with = LenientStringMapSerializer::class)
    val recommends: Map<String, String> = emptyMap(),
    /** 声明为不兼容：mod id -> 版本范围 */
    @Serializable(with = LenientStringMapSerializer::class)
    val breaks: Map<String, String> = emptyMap(),
    /** 老版本 fabric.mod.json 也用过 conflicts 这个名字 */
    @Serializable(with = LenientStringMapSerializer::class)
    val conflicts: Map<String, String> = emptyMap()
) {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun fromFile(modManager: ModManager, modFile: Path, fs: FileSystem): LocalModFile {
            val mcmod = fs.getPath("fabric.mod.json")
            if (Files.notExists(mcmod))
                throw IOException("File $modFile is not a Fabric mod.")
            val metadata: FabricModMetadata = MOD_METADATA_JSON.decodeFromString(FileUtils.readText(mcmod))
            val authors = metadata.authors.joinToString(", ") { it.name }
            val file = LocalModFile(
                modManager, modManager.getLocalMod(metadata.id, ModLoaderType.FABRIC), modFile,
                metadata.name, LocalModFile.Description(metadata.description),
                authors, metadata.version, "", metadata.contact["homepage"] ?: "", metadata.icon
            )
            file.setDependencies(dependenciesOf(metadata))
            return file
        }

        /**
         * 把 fabric.mod.json 的依赖声明转换成统一模型。纯函数，便于单元测试。
         */
        @JvmStatic
        fun dependenciesOf(metadata: FabricModMetadata): List<ModDependency> {
            val out = ArrayList<ModDependency>()
            metadata.depends.forEach { (id, range) -> out.add(ModDependency.required(id, range)) }
            metadata.recommends.forEach { (id, range) -> out.add(ModDependency.optional(id, range)) }
            metadata.breaks.forEach { (id, range) -> out.add(ModDependency.incompatible(id, range)) }
            metadata.conflicts.forEach { (id, range) -> out.add(ModDependency.incompatible(id, range)) }
            return out
        }
    }
}

@Serializable
data class FabricModAuthor(val name: String = "")

/** authors 数组元素兼容字符串与 {"name": ...} 对象两种 JSON 形态 */
internal object FabricModAuthorSerializer : KSerializer<FabricModAuthor> {
    private val elementSerializer = JsonElement.serializer()

    override val descriptor: SerialDescriptor = elementSerializer.descriptor

    override fun deserialize(decoder: Decoder): FabricModAuthor {
        val element = decoder.decodeSerializableValue(elementSerializer)
        return when (element) {
            is JsonPrimitive -> FabricModAuthor(element.content)
            is JsonObject -> FabricModAuthor((element["name"] as? JsonPrimitive)?.content ?: "")
            else -> FabricModAuthor("")
        }
    }

    override fun serialize(encoder: Encoder, value: FabricModAuthor) {
        encoder.encodeSerializableValue(elementSerializer, JsonPrimitive(value.name))
    }
}

internal object FabricModAuthorListSerializer : KSerializer<List<FabricModAuthor>> by ListSerializer(FabricModAuthorSerializer)

/**
 * 依赖表按规范是 {"modId": "版本范围"} 对象；但手改过的 jar 也可能写成数组或字符串。
 * 这里只接受对象，其它形态一律当空表——不能因为一个依赖字段写坏就让整个模组解析失败。
 */
internal object LenientStringMapSerializer : KSerializer<Map<String, String>> {
    private val elementSerializer = JsonElement.serializer()

    override val descriptor: SerialDescriptor = elementSerializer.descriptor

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val element = decoder.decodeSerializableValue(elementSerializer)
        if (element !is JsonObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in element) {
            out[key] = when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        }
        return out
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        val json = LinkedHashMap<String, JsonElement>()
        for ((key, item) in value) {
            json[key] = JsonPrimitive(item)
        }
        encoder.encodeSerializableValue(elementSerializer, JsonObject(json))
    }
}
