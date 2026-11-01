@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.tungsten.fclcore.mod.modinfo

import com.tungsten.fclcore.mod.LocalModFile
import com.tungsten.fclcore.mod.ModDependency
import com.tungsten.fclcore.mod.ModLoaderType
import com.tungsten.fclcore.mod.ModManager
import com.tungsten.fclcore.util.StringUtils
import com.tungsten.fclcore.util.io.FileUtils
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ForgeOldModMetadata(
    val modid: String = "",
    val name: String = "",
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val logoFile: String = "",
    val mcversion: String = "",
    val url: String = "",
    val updateUrl: String = "",
    val credits: String = "",
    val authorList: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    /** 老版本 mcmod.info 偶尔会写 dependencies，只包含 mod id，没有版本范围 */
    @Serializable(with = LenientStringListSerializer::class)
    val dependencies: List<String> = emptyList()
) {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun fromFile(modManager: ModManager, modFile: Path, fs: FileSystem): LocalModFile {
            val mcmod = fs.getPath("mcmod.info")
            if (Files.notExists(mcmod))
                throw IOException("File $modFile is not a Forge mod.")

            // mcmod.info 顶层兼容数组与 {modList: [...]} 对象两种形态，取第一条
            val element = MOD_METADATA_JSON.parseToJsonElement(FileUtils.readText(mcmod))
            val metadata: ForgeOldModMetadata = when (element) {
                is JsonArray -> MOD_METADATA_JSON.decodeFromJsonElement<List<ForgeOldModMetadata>>(element).firstOrNull()
                else -> MOD_METADATA_JSON.decodeFromJsonElement<ForgeOldModMetadataLst>(element).modList.firstOrNull()
            } ?: throw IOException("Mod $modFile `mcmod.info` is malformed")

            var authors = metadata.author
            if (authors.isBlank() && metadata.authors.isNotEmpty())
                authors = metadata.authors.joinToString(", ")
            if (authors.isBlank() && metadata.authorList.isNotEmpty())
                authors = metadata.authorList.joinToString(", ")
            if (authors.isBlank())
                authors = metadata.credits

            val file = LocalModFile(
                modManager, modManager.getLocalMod(metadata.modid, ModLoaderType.FORGE), modFile,
                metadata.name, LocalModFile.Description(metadata.description),
                authors, metadata.version, metadata.mcversion,
                if (metadata.url.isBlank()) metadata.updateUrl else metadata.url,
                metadata.logoFile
            )
            file.setDependencies(metadata.dependencies.map { ModDependency.required(it, "") })
            return file
        }
    }
}

/**
 * mcmod.info 的 dependencies 通常是字符串数组，手写文件也可能只有一个字符串。
 * 非字符串元素直接忽略，不能因为依赖字段写坏就让整个模组解析失败。
 */
internal object LenientStringListSerializer : KSerializer<List<String>> {
    private val elementSerializer = JsonElement.serializer()

    override val descriptor: SerialDescriptor = elementSerializer.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        return when (val element = decoder.decodeSerializableValue(elementSerializer)) {
            is JsonPrimitive -> listOf(element.content)
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeSerializableValue(
            elementSerializer,
            JsonArray(value.map { JsonPrimitive(it) })
        )
    }
}
