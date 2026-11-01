/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.mod;

import java.util.Locale;

/**
 * 一个模组在 jar 元数据里声明的依赖关系。
 *
 * <p>这里只描述“声明”，不保证目标已经安装。目标解析、缺失前置节点和度数统计
 * 由 {@code com.tungsten.fclcore.mod.graph.ModGraphBuilder} 负责。</p>
 *
 * <p>模组 id 统一小写：Forge 的 modId 大小写不敏感，Fabric 规范本身要求小写，
 * 玩家手动改过的 jar 偶尔会出现大小写混用，统一后才能和依赖声明对上。</p>
 */
public record ModDependency(String targetId, Type type, String versionRange) {

    public enum Type {
        /** 必需前置，缺失时游戏通常无法启动 */
        REQUIRED,
        /** 可选联动，目标缺失不影响启动 */
        OPTIONAL,
        /** 声明为不兼容 */
        INCOMPATIBLE,
        /** 内置（jar-in-jar）依赖 */
        EMBEDDED
    }

    public ModDependency {
        targetId = targetId == null ? "" : targetId.trim().toLowerCase(Locale.ROOT);
        type = type == null ? Type.REQUIRED : type;
        versionRange = versionRange == null ? "" : versionRange;
    }

    public static ModDependency required(String targetId, String versionRange) {
        return new ModDependency(targetId, Type.REQUIRED, versionRange);
    }

    public static ModDependency optional(String targetId, String versionRange) {
        return new ModDependency(targetId, Type.OPTIONAL, versionRange);
    }

    public static ModDependency incompatible(String targetId, String versionRange) {
        return new ModDependency(targetId, Type.INCOMPATIBLE, versionRange);
    }

    public static ModDependency embedded(String targetId, String versionRange) {
        return new ModDependency(targetId, Type.EMBEDDED, versionRange);
    }
}
