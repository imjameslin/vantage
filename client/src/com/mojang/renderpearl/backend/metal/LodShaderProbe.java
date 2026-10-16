package com.mojang.renderpearl.backend.metal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cost attribution only: gray RGB with the original geometry, sampling, alpha and discard rules.
 * Never a quality mode or a release optimization. Applied after every normal paired shader rewrite. */
public final class LodShaderProbe {
    public static volatile boolean rgb = "rgb".equals(System.getProperty("mcmetal.lodProbe", ""));
    public static final AtomicInteger BUILT = new AtomicInteger();
    private static final Pattern FINAL_COLOR = Pattern.compile("(?m)^    out\\.fragColor = [^\\n]+;\\n");
    private static final Pattern VERTEX_COLOR = Pattern.compile("(?m)^    out\\.vertexColor = (half4\\()?in\\.Color \\* sample_lightmap\\(Sampler2, Sampler2Smplr, param_2\\)\\)?;\\n");

    private LodShaderProbe() {}

    static String[] apply(String name, String vs, String fs) {
        if (!rgb || !(name.contains("_lod_compact_") || name.contains("_lod_greedy_"))) return new String[]{vs, fs};
        Matcher fin = FINAL_COLOR.matcher(fs);
        Matcher col = VERTEX_COLOR.matcher(vs);
        if (!fin.find() || !col.find() || !vs.contains("mcmtl_fetch") || !fs.contains("float4 color = ")) {
            throw new IllegalStateException("LOD RGB probe cannot recognize " + name);
        }
        fs = fin.replaceFirst("    out.fragColor = float4(0.5, 0.5, 0.5, color.w);\n");
        String cast = col.group(1) == null ? "float4" : "half4";
        // Preserve the exact multiplication and (if present) half conversion of alpha.
        vs = col.replaceFirst("    out.vertexColor = " + cast
            + "(float4(0.5, 0.5, 0.5, (in.Color * sample_lightmap(Sampler2, Sampler2Smplr, param_2)).w));\n");
        if (vs.contains("mcmtlFogVis")) {
            String before = "float2(mcmtl_vis * (1.0 - mcmtl_f), mix(mcFog.FogColor.w, 1.0, mcmtl_vis))";
            if (!vs.contains(before)) throw new IllegalStateException("LOD RGB probe: unexpected vertex fog in " + name);
            vs = vs.replace(before, "float2(0.0, mix(mcFog.FogColor.w, 1.0, mcmtl_vis))");
        } else {
            for (String field : new String[]{"sphericalVertexDistance", "cylindricalVertexDistance"}) {
                vs = vs.replaceAll("(?m)^    float " + field + " \\[\\[user\\(locn[01]\\)\\]\\];\\n", "")
                    .replaceAll("(?m)^    out\\." + field + " = [^\\n]+;\\n", "");
                fs = fs.replaceAll("(?m)^    float " + field + " \\[\\[user\\(locn[01]\\)\\]\\];\\n", "");
                if (fs.contains("in." + field)) throw new IllegalStateException("LOD RGB probe: live fog input in " + name);
            }
        }
        BUILT.incrementAndGet();
        return new String[]{vs, fs};
    }
}
