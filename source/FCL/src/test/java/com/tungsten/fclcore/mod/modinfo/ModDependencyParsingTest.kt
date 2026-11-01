package com.tungsten.fclcore.mod.modinfo

import com.tungsten.fclcore.mod.ModDependency
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tomlj.Toml

/**
 * 元数据 → 统一依赖模型的回归测试。
 * 这些函数是纯函数，不需要真实 jar，也不依赖 Android 环境。
 */
class ModDependencyParsingTest {

    @Test
    fun fabricDependsRecommendsBreaks() {
        val metadata = MOD_METADATA_JSON.decodeFromString<FabricModMetadata>(
            """
            {
              "id": "owner",
              "name": "Owner",
              "version": "1.0",
              "depends": {"dep-a": ">=1.0"},
              "recommends": {"dep-b": "*"},
              "breaks": {"bad-mod": "<2.0"}
            }
            """.trimIndent()
        )
        val deps = FabricModMetadata.dependenciesOf(metadata)
        assertEquals(3, deps.size)
        assertTrue(deps.contains(ModDependency("dep-a", ModDependency.Type.REQUIRED, ">=1.0")))
        assertTrue(deps.contains(ModDependency("dep-b", ModDependency.Type.OPTIONAL, "*")))
        assertTrue(deps.contains(ModDependency("bad-mod", ModDependency.Type.INCOMPATIBLE, "<2.0")))
    }

    @Test
    fun forgeMandatoryAndTypeMapping() {
        val toml = Toml.parse(
            """
            [[mods]]
            modId = "owner"
            displayName = "Owner"

            [[dependencies.owner]]
            modId = "dep-a"
            mandatory = true
            versionRange = "[1,)"

            [[dependencies.owner]]
            modId = "dep-b"
            mandatory = false

            [[dependencies.owner]]
            modId = "bad-mod"
            type = "incompatible"

            [[dependencies.owner]]
            modId = "opt-by-type"
            type = "optional"
            """.trimIndent()
        )
        val deps = ForgeNewModMetadata.dependenciesOf(toml, "owner")
        assertEquals(4, deps.size)
        assertTrue(deps.contains(ModDependency("dep-a", ModDependency.Type.REQUIRED, "[1,)")))
        assertTrue(deps.contains(ModDependency("dep-b", ModDependency.Type.OPTIONAL, "")))
        assertTrue(deps.contains(ModDependency("bad-mod", ModDependency.Type.INCOMPATIBLE, "")))
        assertTrue(deps.contains(ModDependency("opt-by-type", ModDependency.Type.OPTIONAL, "")))
    }

    @Test
    fun forgeNonStandardDependenciesArrayStillDecodesMods() {
        // 社区里出现过 [[dependencies]]（没有 owner 分组）的写法。依赖读不到没关系，
        // 但 metadata 本身必须还能反序列化，否则整个模组会从列表里消失。
        val toml = Toml.parse(
            """
            [[mods]]
            modId = "owner"
            displayName = "Owner"

            [[dependencies]]
            modId = "something"
            """.trimIndent()
        )
        val metadata = MOD_METADATA_JSON.decodeFromString<ForgeNewModMetadata>(toml.toJson())
        assertEquals("owner", metadata.mods.first().modId)
    }

    @Test
    fun quiltStringAndObjectDependencies() {
        val root = MOD_METADATA_JSON.decodeFromString<QuiltModMetadata>(
            """
            {
              "schema_version": 1,
              "quilt_loader": {
                "id": "quilt-owner",
                "version": "1.0",
                "metadata": {"name": "Quilt Owner", "description": ""},
                "depends": ["string-dep", {"id": "object-dep", "versions": ">=2"}],
                "recommends": [{"id": "optional-dep"}],
                "breaks": ["bad-dep"]
              }
            }
            """.trimIndent()
        )
        val deps = QuiltModMetadata.dependenciesOf(root.quiltLoader)
        assertEquals(4, deps.size)
        assertTrue(deps.contains(ModDependency("string-dep", ModDependency.Type.REQUIRED, "")))
        assertTrue(deps.contains(ModDependency("object-dep", ModDependency.Type.REQUIRED, ">=2")))
        assertTrue(deps.contains(ModDependency("optional-dep", ModDependency.Type.OPTIONAL, "")))
        assertTrue(deps.contains(ModDependency("bad-dep", ModDependency.Type.INCOMPATIBLE, "")))
    }
}
