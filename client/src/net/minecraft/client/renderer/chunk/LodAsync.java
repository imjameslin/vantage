package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.metal.MetalFarPrograms;
import com.mojang.renderpearl.backend.metal.MetalRenderPipeline;
import com.mojang.renderpearl.backend.metal.TerrainShaderVariants;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

/** Prompt 5 coordinator, under construction and default off. No draw replacement yet. */
final class LodAsync {
    private LodAsync() { }

    /** Called outside every render pass. Warm only the actual selected built-in opaque pipelines;
     * native compilation happens on the far worker, never in this call. The synchronous renderer
     * remains active until capture, reprojection and conservative fallback are all connected.
     */
    static void preparePrograms(ChunkSectionsToRender.DrawIndirect sections) {
        if (!MetalFarPrograms.REQUESTED || !TerrainShaderVariants.hasBuiltinTerrainSources()) return;
        int seen = 0;
        for (TerrainClusters.Draw draw : sections.lodDrawsForReuse()) {
            int code = draw.layer();
            if ((code & ~9) != 0 || (code & 7) > 1 || (seen & (1 << code)) != 0) continue;
            seen |= 1 << code;
            RenderPipeline pipeline = RenderPipelines.lodPipeline(code);
            if (pipeline == null) continue;
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(pipeline);
            if (compiled instanceof FrontendRenderPipeline front && !compiled.isClosed()
                && front.backendRenderPipeline() instanceof MetalRenderPipeline metal) {
                MetalFarPrograms.request(metal, code);
            }
        }
    }
}
