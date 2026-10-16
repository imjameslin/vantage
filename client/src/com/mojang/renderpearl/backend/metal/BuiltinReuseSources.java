package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/** Lazy, render-thread-only guard; normal terrain rendering never initializes it. */
final class BuiltinReuseSources {
    // Same installed-source/cache identity approach as HudVignetteSkip. Checking the live ResourceManager instead
    // would race ahead of the shaders actually installed during a reload, including one that eventually fails.
    private static final @Nullable MethodHandle CURRENT_CACHE;
    private static final @Nullable MethodHandle CACHE_SOURCE;
    private static WeakReference<PipelineCache> checkedCache = new WeakReference<>(null);
    private static boolean checkedBuiltin;
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern INCLUDE = Pattern.compile("#\\s*(?:include|moj_import)\\b([^\\r\\n]*)");
    private static final Pattern LITERAL = Pattern.compile("<([^>]+)>|\"([^\"]+)\"");

    static {
        MethodHandle current = null;
        MethodHandle source = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            current = MethodHandles.privateLookupIn(RenderSystem.class, lookup)
                .findStaticGetter(RenderSystem.class, "currentPipelineCache", PipelineCache.class);
            source = MethodHandles.privateLookupIn(PipelineCache.class, lookup)
                .findGetter(PipelineCache.class, "shaderSource", ShaderSource.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            current = source = null;
        }
        CURRENT_CACHE = current;
        CACHE_SOURCE = source;
    }

    static boolean check() {
        if (CURRENT_CACHE == null || CACHE_SOURCE == null) {
            return false;
        }
        try {
            PipelineCache cache = (PipelineCache)CURRENT_CACHE.invokeExact();
            if (cache == null) {
                return false;
            }
            if (cache != checkedCache.get()) {
                checkedCache = new WeakReference<>(cache);
                // Cache failures too, and never leave a previous success behind if source access throws.
                checkedBuiltin = false;
                ShaderSource source = (ShaderSource)CACHE_SOURCE.invokeExact(cache);
                checkedBuiltin = matches(source, BuiltinReuseSources::builtin);
            }
            return checkedBuiltin;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean matches(final ShaderSource source, final Function<String, String> builtins) {
        ArrayDeque<Identifier> pending = new ArrayDeque<>();
        if (!matchesText(source.getShader(Identifier.withDefaultNamespace("core/screenquad"), ShaderType.VERTEX),
                builtins.apply("assets/minecraft/shaders/core/screenquad.vsh"), pending)
            || !matchesText(source.getShader(Identifier.withDefaultNamespace("core/lod_reuse"), ShaderType.FRAGMENT),
                builtins.apply("assets/minecraft/shaders/core/lod_reuse.fsh"), pending)) {
            return false;
        }
        Set<Identifier> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Identifier id = pending.removeFirst();
            if (!seen.add(id)) {
                continue;
            }
            if (seen.size() > 128) {
                return false;
            }
            ShaderSource.CachedIncludeSource cached = source.getInclude(id);
            if (cached == null || cached.includeResultPtr() == 0L) {
                return false;
            }
            ShadercIncludeResult include = ShadercIncludeResult.create(cached.includeResultPtr());
            // Error results have an empty source name. Read the explicit UTF-8 byte lengths, not a NUL terminator.
            if (!id.toString().equals(MemoryUtil.memUTF8(include.source_name()))
                || !matchesText(MemoryUtil.memUTF8(include.content()),
                    builtins.apply("assets/" + id.getNamespace() + "/shaders/include/" + id.getPath()), pending)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesText(final @Nullable String actual, final @Nullable String builtin,
                                       final ArrayDeque<Identifier> pending) {
        if (builtin == null || !builtin.equals(actual)) {
            return false;
        }
        String directives = COMMENTS.matcher(builtin.replace("\\\r\n", "").replace("\\\n", "")).replaceAll(" ");
        Matcher includes = INCLUDE.matcher(directives);
        while (includes.find()) {
            Matcher literal = LITERAL.matcher(includes.group(1).trim());
            if (!literal.matches()) {
                return false; // Macro/unknown includes cannot be proven safe.
            }
            // GlslCompiler resolves both quoted and angle includes directly as Identifiers, without relative paths.
            Identifier id = Identifier.tryParse(literal.group(1) != null ? literal.group(1) : literal.group(2));
            if (id == null) {
                return false;
            }
            pending.addLast(id);
        }
        return true;
    }

    private static @Nullable String builtin(final String path) {
        try (InputStream in = BuiltinReuseSources.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
