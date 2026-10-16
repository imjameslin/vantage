# RenderPearl 02: Shaders and Pipelines (for the Metal backend)

Scope: how Minecraft Java 26.3's RenderPearl turns GLSL into pipelines, what the frontend guarantees, how the OpenGL and Vulkan backends consume the result, and a concrete plan for translating to MSL with SPIRV-Cross (lwjgl-spvc 3.4.3) on macOS 27 / Metal 4 / Apple M5.

All paths are relative to `src/vanilla/` unless stated. `RP/` means `com/mojang/renderpearl/`.

Some claims were checked with scratch probes rather than by reading code alone. Each one is marked **[verified]**. The probes are listed in §16.

---

## 0. Summary

* **Shader language and compile.** Shaders are written in Vulkan-flavoured GLSL: `#version 330`, `GL_ARB_separate_shader_objects`, explicit `layout(location=N)` on every in/out, `gl_VertexIndex`, and uniform blocks/samplers with no `binding=`. The frontend compiles them once to **SPIR-V 1.5 (Vulkan 1.2 environment)** with shaderc (`RP/frontend/shaders/GlslCompiler.java:74-94`).
* **Binding numbers.** Every resource is placed in **descriptor set 0**. Bindings are **dense, 0..N-1, per pipeline**, assigned by the frontend in shader-reflection order (vertex stage first, then fragment; within a stage UBOs come before sampled images). The frontend writes these numbers **into the SPIR-V words in place** (`PipelineBuilder.java:283-299`, `SPIRVModule.java:247-292`).
  * Binding index = `setUniform(index, …)` index = position in `CreateInfo.uniforms()`.
  * The backend never sees uniform names at draw time.
* **Uniform kinds.** There are exactly three: `UNIFORM_BUFFER` (std140 UBO), `COMBINED_IMAGE_SAMPLER` (`sampler2D`, `samplerCube`), and `TEXEL_BUFFER` (`isamplerBuffer`; only `CloudFaces`, format `R8_SINT`).
  * Push constants are allowed: one block, ≤128 B. **No vanilla pipeline uses push constants.**
  * Storage buffers and images, separate samplers/images, 1D textures and 3D textures are all rejected.
* **Vertex attribute locations** come from the shader's explicit `layout(location)`. They are matched to `VertexFormat` elements **by name** (`PipelineBuilder.java:87-178`).
* **How each backend consumes SPIR-V.**
  * **OpenGL** does **not** use `GL_ARB_gl_spirv`. It **cross-compiles SPIR-V back to GLSL 330 with SPIRV-Cross** on a worker thread, then compiles and links on the render thread in `finishCompile` (`GlPipelineRecompiler.java:45-127`, `GlDevice.java:355-367`).
  * **Vulkan** feeds the SPIR-V directly to `vkCreateShaderModule`. It uses a push-descriptor set layout and dynamic rendering, and builds **two VkPipelines** (with and without a D32 depth attachment) (`VulkanRenderPipeline.java:80-376`).
* **Coordinate conventions.**
  * The game uses **reverse-Z**: depth clears to 0, the default test is `GREATER_THAN_OR_EQUAL` (`DepthStencilState.java:4`), and projections are built with near and far swapped (`Projection.java:67-73`).
  * It builds 0..1 clip-Z projections when `DeviceInfo.isZZeroToOne()` is true. **Metal must report `isZZeroToOne = true`**.
  * The memory layout matches GL and Vulkan: NDC y = -1 lands on texture row 0. Vulkan flips only at present time (`VulkanGpuSurface.java:410-419`) and uses `frontFace = CLOCKWISE` (`VulkanRenderPipeline.java:270`).
  * On Metal, use SPIRV-Cross `FLIP_VERTEX_Y=true`, `setFrontFacingWinding(MTLWindingClockwise)`, `setCullMode(Back)` when `cull`, and do a Y-flipped final present. **[verified]**
* **Asynchronous compile.**
  * `GpuDeviceBackend.compilePipeline(CreateInfo)` runs on a **background worker thread**.
  * The returned `Pending.finishCompile()` runs on the reload executor or the calling (render) thread.
  * SPIR-V buffers are freed right after `finishCompile` (`PipelineBuilder.java:451`).
  * Metal can do *all* work, including `MTLRenderPipelineState` creation, inside `compilePipeline` on the worker, which leaves `finishCompile` trivial.
* **Pipeline counts.**
  * The game defines 195 static `RenderPipeline` objects: 100 single pipelines and 31 OIT sets × 3. 190 are required plus 2 optional (wireframe) and are precompiled at resource reload.
  * 3 are **never precompiled** because of duplicate locations, so they compile synchronously on the render thread on first use.
  * Post-effect chains add 24 more (one per pass), also compiled synchronously on first load.
  * The 195 static pipelines contain only **125 unique (vs, fs, defines) programs**, 120 unique vertex+defines and 121 unique fragment+defines, and 175 unique full states. **[verified]**
  * Shader files: 29 core `.vsh`, 34 core `.fsh`, 1 post `.vsh`, 8 post `.fsh`, 17 includes.
* **SPIRV-Cross to MSL works.** The option set in §13 produces MSL that compiles with the macOS 27 runtime Metal compiler. A terrain pipeline built with a vertex descriptor and RGBA8+D32F attachments works. **[verified]**

---

## 1. Source map

| Concern | File (lines) |
|---|---|
| GLSL to SPIR-V (shaderc) | `RP/frontend/shaders/GlslCompiler.java` (37-52 includes, 66-72 compiler pool, 74-94 options, 96-180 compile) |
| Reflection and SPIR-V patching (spvc) | `RP/frontend/shaders/SPIRVModule.java` (65-153 reflect, 155-225 interface vars, 227-312 descriptors, 314-352 push constants, 354-380 types) |
| spvc enums and helpers | `RP/frontend/shaders/SpvUtil.java` (11 descriptor types, 43-49 UniformType to spvc resource type) |
| Validation, binding assignment, CreateInfo | `RP/frontend/shaders/PipelineBuilder.java` (65-368 `generateBackendCreateInfo`, 370-469 `compilePipeline`, 481-538 slot maps) |
| Pipeline description API | `RP/api/pipeline/*.java` |
| Backend pipeline contract | `RP/backend/api/BackendRenderPipeline.java`, `RP/backend/api/SpvModule.java`, `RP/backend/api/GpuDeviceBackend.java:40` |
| Runtime binding contract | `RP/frontend/FrontendRenderPass.java` (104-186, 504-634), `RP/frontend/FrontendRenderPipeline.java` |
| GL consumer | `RP/backend/opengl/GlPipelineRecompiler.java`, `GlProgram.java`, `GlRenderPipeline.java`, `GlDevice.java:355-367`, `VertexArray.java` |
| Vulkan consumer | `RP/backend/vulkan/VulkanRenderPipeline.java`, `VulkanRenderPass.java:127-375`, `VulkanConst.java:170-268`, `VulkanDevice.java:265-268` |
| Game pipeline definitions | `net/minecraft/client/renderer/RenderPipelines.java`, `BindGroupLayouts.java`, `oit/OitPipelineSet.java`, `PostChain.java:91-131` |
| Pipeline compile and cache driver | `net/minecraft/client/renderer/ShaderManager.java:64-236`, `com/mojang/blaze3d/pipeline/PipelineCache.java`, `com/mojang/blaze3d/systems/RenderSystem.java:88-123`, `net/minecraft/client/renderer/GameRenderer.java:255-287` |
| Shader sources | `assets/minecraft/shaders/{core,post,include}/` |

---

## 2. Pipeline description API (`RP/api/pipeline`)

### 2.1 `RenderPipeline` (`RenderPipeline.java`)

Immutable fields (`23-35`):

* `location` (Identifier)
* `shaders` (`EnumMap<ShaderType, Identifier>`, iteration order VERTEX then FRAGMENT)
* `shaderDefines`
* `bindGroupLayouts`
* `depthStencilState` (nullable)
* `polygonMode`
* `cull`
* `colorTargetStates` (list of nullable entries)
* `vertexFormatPerBuffer` (16 nullable slots)
* `primitiveTopology`
* `pushConstantSize`
* `sortKey` (creation order; used by the GUI renderer, not by backends)

`Builder.build()` validation (`358-424`):

* location, vertex shader, fragment shader and topology are required (`359-373`);
* all non-null color targets with a blend must use the **same** `BlendFunction` (`380-393`), which lets GL use one global blend func;
* ≤16 vertex attributes summed over all bound vertex formats (`396-405`);
* `pushConstantSize ≤ 128` (`406-407`);
* defaults: `PolygonMode.FILL`, `cull = true`, no depth state (`412-418`);
* `colorTargetStates` is truncated to `activeColorTargetStateCount` (max 8, `149`, `379`).

Snippets (`withSnippet`, `282-340`) merge shaders, defines, bind groups, depth state, cull, color targets (only into empty slots), vertex formats, topology, polygon mode, and the max push size. `bindGroupLayouts` is collected in an `ObjectOpenHashSet` (`145`, `214-219`). Its **order is not deterministic across JVM runs**, but nothing downstream depends on that order (bindings come from SPIR-V reflection; see §6).

`wantsDepthTexture()` returns `depthStencilState != null` (`122-124`).

### 2.2 `BindGroupLayout` and `UniformType`

* `BindGroupLayout(List<UniformDescription>)`. `UniformDescription(name, type, @Nullable gpuFormat)`. A texel buffer **must** carry a `GpuFormat`; other kinds must not (`BindGroupLayout.java:49-83`).
* `flattenUniforms` concatenates the layouts (`19-27`). `ensureCompatible` (duplicate-name check, `29-41`) is **never called** anywhere.
* `UniformType = {COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER}` (`UniformType.java`).
* The game's layouts are in `BindGroupLayouts.java`:
  * UBOs: `DynamicTransforms`, `Projection`, `ChunkSection`, `TerrainUniform`, `Fog`, `Globals`, `Lighting`, `CloudInfo`, `LightmapInfo`, `SpriteAnimationInfo`, plus post-chain `SamplerInfo` and per-pass config blocks.
  * Samplers: `Sampler0/1/2`, `InSampler`, `DissolveMaskSampler`, `Sprite`, `CurrentSprite`, `NextSprite`, `GlintSampler`, `DepthBoundsSampler`, `Coeff0..Coeff{N-1}`.
  * Texel buffer: `CloudFaces` (`R8_SINT`, `BindGroupLayouts.java:31-34`).
  * OIT: `OIT_COEFFICIENT_COUNT = 8` and `OIT_TRANSMITTANCE_TARGET_COUNT = 2` (`LevelRenderer.java:110-111`), so `Coeff0`, `Coeff1`.

### 2.3 Shader identity and sources

* `ShaderType {VERTEX(".vsh"), FRAGMENT(".fsh")}`, path prefix `shaders/` (`ShaderType.java`).
* `ShaderSource` provides `getShader(id, type)` and `getInclude(id)`. Includes are pre-built `ShadercIncludeResult` structs (`CachedIncludeSource`, `ShaderSource.java:16-37`).
* Implementations:
  * `ShaderManager.Configs` (`ShaderManager.java:297-318`). It loads every `shaders/**.vsh|.fsh`, and includes from `shaders/include/*.glsl` under id `minecraft:<name>.glsl` (`183-185`).
  * The early-UI fallback source (`GameRenderer.java:258-280`).

### 2.4 Fixed-function state types

| Type | Values / meaning |
|---|---|
| `ColorTargetState(Optional<BlendFunction>, GpuFormat format, int writeMask)` | `writeMask` bits **R=1, G=2, B=4, A=8** (`ColorTargetState.java:11-17`). `DEFAULT` = no blend, RGBA8_UNORM, 15. Max 8 targets. A `null` entry means an unused target. |
| `BlendFunction(BlendEquation color, BlendEquation alpha)` | Named presets: LIGHTNING, GLINT, OVERLAY, TRANSLUCENT, TRANSLUCENT_PREMULTIPLIED_ALPHA, ADDITIVE, ENTITY_OUTLINE_BLIT, INVERT, MAX (`BlendFunction.java:4-20`). |
| `BlendEquation(src, dst, op)` | `BlendFactor` has 15 values incl. CONSTANT_* and SRC_ALPHA_SATURATE. `BlendOp` = ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX. No blend-constant setter exists in the API. |
| `DepthStencilState(CompareOp depthTest, boolean writeDepth, float depthBiasScaleFactor, float depthBiasConstant)` | `DEFAULT = (GREATER_THAN_OR_EQUAL, true)`, i.e. **reverse-Z**. **No stencil** at all. `null` means no depth test and no write. |
| `CompareOp` | ALWAYS_PASS, LESS_THAN, LESS_THAN_OR_EQUAL, EQUAL, NOT_EQUAL, GREATER_THAN_OR_EQUAL, GREATER_THAN, NEVER_PASS |
| `PolygonMode` | FILL, WIREFRAME |
| `PrimitiveTopology` | LINES, DEBUG_LINES, DEBUG_LINE_STRIP, POINTS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS (`PrimitiveTopology.java`). **LINES and QUADS are drawn as indexed triangle lists** (`indexCount = n/4*6`, `23-29`). |
| `IndexType` | SHORT(2), INT(4) |

How the topologies are actually drawn:

* `LINES` uses index pattern `i, i+1, i+2, i+3, i+2, i+1` and screen-space expansion in `rendertype_lines.vsh`.
* `QUADS` uses `i, i+1, i+2, i+2, i+3, i` (`RenderSystem.java:49-65`).
* Both GL and Vulkan map them to triangle lists (`GlConst.java:188-198`, `VulkanConst.java:220-231`).

---

## 3. The game's pipelines (inventory)

Where they are defined:

* `RenderPipelines.java`: 100 `register(RenderPipeline)` calls, 31 `register(OitPipelineSet)` calls (each creates depth-bounds, transmittance and accumulate pipelines, `OitPipelineSet.java:63-74`), and 2 `registerOptional` calls (`pipeline/wireframe`, `pipeline/wireframe_multidraw`).
* `PostChain.createPass` (`PostChain.java:91-131`): one pipeline per post pass. Built from `POST_PROCESSING_SNIPPET` with a bind group made of `<input>Sampler` + `SamplerInfo` + per-pass uniform blocks, and one `ColorTargetState.DEFAULT`.

Counts from loading the class from `mc/versions/26.3/26.3.jar` **[verified]**:

| Metric | Value |
|---|---|
| Static `RenderPipeline` objects | 195 |
| `requiredPipelines()` (keyed by location in a HashMap) | **190** |
| `optionalPipelines()` | 2 |
| Never precompiled (location collision) | 3: `pipeline/item_cutout` appears 3× (`RenderPipelines.java:696-717`; only `ITEM_CUTOUT_GLINT_SPECIAL` survives), `pipeline/item_translucent_glint` appears 2× (`730-744`) |
| Post-effect pass pipelines | 24 (blur 6, creeper 2, entity_outline 4, invert 2, spider 10) |
| Unique vertex-shader+defines / fragment-shader+defines | 120 / 121 |
| Unique programs (vs+fs+defines) | **125** |
| Unique full pipeline states | 175 |
| Distinct shader files referenced | 28 vsh / 33 fsh (`core/position.*` unused) |
| Topologies | QUADS 161, TRIANGLES 15, LINES 8, TRIANGLE_FAN 6, POINTS 4, TRIANGLE_STRIP 1 (DEBUG_LINES/DEBUG_LINE_STRIP unused) |
| Polygon mode | FILL 193, WIREFRAME 2 |
| Cull | true 132, false 63 |
| Color target count | 0: 3 (`blit_depth`, `integrate_depth`, `oit_water_mask`); 1: 161; 2: 31 (OIT transmittance) |
| Color formats used | RGBA8_UNORM, RGBA16_FLOAT (additive OIT), RGBA32_FLOAT (OIT depth bounds, MAX blend) |
| Depth states (distinct) | (GE,write), (GE,no write), (EQUAL,no write) ×2 (`glint`, `armor_decal_cutout_no_cull`), (ALWAYS,write) ×4, null ×30, plus biased variants (below) |
| Depth-bias pipelines | `crumbling` (GE,nw, slope 1, const 10), `text_polygon_offset` and `text_grayscale_polygon_offset` (1,10), `lines_depth_bias` (1,1), `world_border` (3,3) |
| Max uniforms in a pipeline's bind groups | 12 |
| Max uniforms actually referenced | 12, e.g. OIT accumulate + glint entity: 5 UBO + 7 textures **[verified]** |
| Push constants | none (`withPushConstantSize` never called) |
| Texel-buffer pipelines | the 8 cloud pipelines (`CloudFaces`) |
| Triangle-fan pipelines | `sky`, `sunrise_sunset`, `debug_triangle_fan`, and 3 OIT debug_triangle_fan |
| Point pipelines | `debug_points` and its 3 OIT variants (`debug_point.vsh` writes `gl_PointSize`) |
| Wireframe pipelines | `pipeline/wireframe`, `pipeline/wireframe_multidraw` (optional) |

How precompilation works:

* `ShaderManager.reload` (`64-93`) compiles all required and optional pipelines in parallel on `taskExecutor` (`compilePipelines`, `95-110`). It calls `finishCompile` on `reloadExecutor` (`106`).
* In `apply()` (`187-236`), any failed **required** pipeline throws `RuntimeException("Failed to load required shader programs")` (`203-213`). Failed optional pipelines are only logged (`225-227`).
* Pipelines not in the cache are compiled lazily by `PipelineCache.get`, which calls `device.compilePipeline(...).join().finishCompile()` **on the calling (render) thread** (`PipelineCache.java:27-40`).
* The early UI pipelines `GUI`, `GUI_TEXTURED` and `TRACY_BLIT` are built through the fallback cache (`GameRenderer.java:281-286`, `RenderSystem.java:102-115`).

---

## 4. Shader source dialect and defines

* Every stage starts with `#version 330` + `#extension GL_ARB_separate_shader_objects : require`. All interface variables carry explicit `layout(location = N)`.
  * Example: `core/terrain.vsh:13-30`.
  * Fragment output arrays exist: `oit_add_transmittance.glsl:4` `out vec4 coeff[OIT_COEFF_ATTACHMENT_COUNT]` spans locations 0..1.
* UBOs are `layout(std140) uniform Name { ... };` **without instance names** (e.g. `include/globals.glsl:4-12`). Samplers are plain `uniform sampler2D X;`. Nothing carries `binding=` or `set=`.
* Vulkan built-ins are used directly:
  * `gl_VertexIndex` in `screenquad.vsh:7`, `clouds.vsh:89-90`, `rendertype_lines.vsh:40`, `animate_sprite.vsh:19-20`, `post/rotscale.vsh`.
  * `gl_InstanceIndex` and `gl_BaseInstance` are never used.
* Other built-ins:
  * `gl_FragDepth` is written by `blit_depth.fsh`, `integrate_depth.fsh`, `oit_composite.fsh` and `oit_depth_bounds_cull.fsh`, and by OIT includes under `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`.
  * `gl_FragCoord` (`.xy` as texel coordinates, `.z`, `1/.w`) is used by OIT and beacon shaders.
  * `gl_PointSize` is written by `debug_point.vsh`.
  * There is no `gl_FrontFacing`, no `invariant`, and no `flat` except `rendertype_leash` (`flat in/out vec4 vertexColor`).
* Texture types used: `sampler2D`, `samplerCube` (`panorama.fsh:4`), `isamplerBuffer` (`clouds.vsh:20`).
  * Functions: `texture`, `textureLod`, `textureGrad` (`texture_sampling.glsl`), `texelFetch` on 2D and buffer textures.
  * A depth texture is sampled with plain `sampler2D` in `blit_depth.fsh` (`texture(InSampler, uv).r`).
* Includes use `#include <minecraft:name.glsl>`. shaderc enables `GL_GOOGLE_include_directive` itself. The resolver parses the string as an `Identifier` and looks it up in `ShaderSource.getInclude` (`GlslCompiler.java:37-52`). The include type and the requesting file are ignored. Missing or malformed includes return an error result: "not found" or "malformed id".
* Defines come from `RenderPipeline.getShaderDefines()`. `values` are passed as `NAME=value`: floats via `String.valueOf`, e.g. `ALPHA_CUTOUT=0.1`; ints e.g. `OIT_COEFF_COUNT=8`. `flags` are passed as `NAME=""`. See `GlslCompiler.java:105-111` and `ShaderDefines.java:86-98`.
* Backend-driven macros (`GlslCompiler.java:81-91`):

| Macro | Condition | Used in |
|---|---|---|
| `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` | `DeviceInfo.isZZeroToOne()` | `include/oit_common.glsl:17-19` (device to linear depth) |
| `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` | `hintsAndWorkarounds().isExplicitDepthRequired()` (read via `RenderSystem.getDevice()`!) | `oit_sample.glsl:101-103`, `oit_add_transmittance.glsl:46-48` (writes `gl_FragDepth = gl_FragCoord.z`) |
| `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` | `features().shaderDrawParameters()` | nothing in vanilla assets |

Both GL and Vulkan set `isExplicitDepthRequired = Util.isAppleSiliconMac(renderer)`, i.e. `renderer.startsWith("Apple")` (`GlHeuristics.java:114-116`, `VulkanDevice.java:116`, `Util.java:604-606`). **Report it as `true` on Metal for M5** to match the shipped behaviour on Apple GPUs. Section 15 covers the performance trade-off.

---

## 5. GLSL to SPIR-V (`GlslCompiler`)

`compileToSpv(name, source, type, defines, shaderSource)` (`96-180`):

* `shaderc_compile_into_spv(compiler, source, kind, filename = id.toString(), entry = "main", options)`, where kind is `shaderc_glsl_vertex_shader = 0` or `shaderc_glsl_fragment_shader = 1` (`99`, `126`).
* Base options (`74-94`):
  * `set_target_env(shaderc_target_env_vulkan = 0, shaderc_env_version_vulkan_1_2 = 4202496)`, which gives **SPIR-V 1.5**;
  * `set_auto_bind_uniforms(true)`, so glslang invents bindings (overwritten later);
  * `set_preserve_bindings(false)`;
  * `set_generate_debug_info()`, which keeps OpName (required: reflection is by name);
  * `set_optimization_level(0)`, i.e. **no optimization**;
  * no `auto_map_locations`, so explicit locations are mandatory.
* Compiler handles come from a synchronized pool (`66-72`). Options are created per call. **Any worker thread may call this concurrently.**
* Failure: a non-zero status throws `ShaderCompileException("Couldn't parse GLSL: …")` (`147-150`). `PipelineBuilder` logs it and returns `Pending.NULL` (`363-367`, `403-405`).
* The output bytes are copied into a `MemoryUtil.memCalloc` buffer in native byte order, owned by `SPIRVModule` (`152-155`). `SPIRVModule.close()` frees it with `memFree` (`SPIRVModule.java:34-39`).

The frontend is built once per device. `new GlslCompiler(isZZeroToOne, shaderDrawParameters)` is created in the `PipelineBuilder` ctor (`PipelineBuilder.java:54-58`), constructed by `FrontendGpuDevice` (`FrontendGpuDevice.java:51`). **These two `DeviceInfo` values must be final when the backend is constructed.**

---

## 6. Reflection and the binding model (`SPIRVModule`, `SpvUtil`, `PipelineBuilder`)

### 6.1 What reflection captures

`SPIRVModule.doReflection` (`65-153`) creates an spvc context, parses the SPIR-V, and creates a compiler with `SPVC_BACKEND_NONE`, capture COPY (`97`). It lists:

* stage inputs (`SPVC_RESOURCE_TYPE_STAGE_INPUT = 3`) and outputs (`= 4`) (`101-102`);
* descriptors for spvc resource types `{1 UBO, 2 SSBO, 6 storage image, 7 sampled image, 10 separate image, 11 separate sampler}` (`SpvUtil.java:11`, `SPIRVModule.java:105-110`);
* push constants (`9`, `314-352`), with size taken from `spvc_compiler_get_declared_struct_size`.

**Patching in place.** For every interface variable and descriptor, reflection records the *word offset* of its `Location` (30), `DescriptorSet` (34) and `Binding` (33) decorations via `spvc_compiler_get_binary_offset_for_decoration` (`175`, `247`, `252`).

* The setters `location(int)`, `binding(int)` and `descriptorSetIndex(int)` write directly into `spv.asIntBuffer()`. **The `ByteBuffer` handed to the backend is the patched SPIR-V.**
* The cached spvc IR inside `SPIRVModule` is **not** updated:
  * `Descriptor.binding()` reads the patched word;
  * `InterfaceVariable.decoration(n)` queries the stale IR (fine for `Component`/`Flat`).
* **The backend must re-parse `module.spv()`**, as GL does (`GlPipelineRecompiler.java:47-58`).

**Type info** (`TypeHandle`, `354-380`):

* `baseType` uses `SPVC_BASETYPE_*`: 7 int32, 8 uint32, 13 fp32, 16 image, 17 sampled image.
* `dimensions()` returns `SpvDim` for image types (0 1D, 1 2D, 2 3D, 3 Cube, 4 Rect, 5 Buffer) and `Integer.MAX_VALUE` otherwise.

### 6.2 Binding assignment (who decides binding numbers)

The frontend decides them in `PipelineBuilder.generateBackendCreateInfo` (`259-301`):

```
uniformBindings = new Object2IntOpenHashMap<String>()   // name -> binding
for shader in shaders (VERTEX, then FRAGMENT):
  for descriptor in reflect().descriptors()   // grouped by type 1,2,6,7,10,11; spvc order inside a type
    require pipeline bind groups contain descriptor.name()      // else ShaderCompileException
    b = uniformBindings.computeIfAbsent(name, size())           // first-seen order => dense 0..N-1
    if new: record resourceType, dimension, UniformDescription(name,type,gpuFormat) at index b
    else:   require same resourceType and dimension across stages
    descriptor.binding(b); descriptor.descriptorSetIndex(0)     // patch SPIR-V
```

Invariants for the backend:

* **One descriptor set (0)** for both stages.
* **Bindings are dense 0..N-1.** `CreateInfo.uniforms().get(b)` describes binding `b` (`PipelineBuilder.java:284-288`, `350-362`).
* **Binding numbers depend only on the declared resources of the two SPIR-V modules, not on the BindGroupLayouts.** Two pipelines with the same (vs, fs, defines) therefore get byte-identical patched SPIR-V. That makes it safe to cache MSL, `MTLLibrary` and `MTLFunction` objects by a SPIR-V hash.
* **Declared-but-unused resources still get bindings.** At `-O0` glslang keeps them, and `get_shader_resources()` returns all declared resources. In the terrain vertex shader, `Fog` is declared via `#include fog.glsl` but unused; it still takes binding 3. **[verified]** SPIRV-Cross MSL only emits *used* resources, so per-stage usage masks come from `spvc_compiler_msl_is_resource_used`.
* UBOs of a stage come before its samplers. A fragment-only UBO comes after the vertex stage's samplers.

Example **[verified]**: `solid_terrain_multidraw` gets `{Globals=0, Projection=1, TerrainUniform=2, Fog=3, Sampler2=4 (vertex lightmap), Sampler0=5}`.

Type validation (`303-331`):

* The reflected resource type must equal `SpvUtil.resourceType(type)`: UBO to 1; COMBINED_IMAGE_SAMPLER and TEXEL_BUFFER to 7, since `isamplerBuffer` is a sampled image.
* The image dimension must agree: 2D, Cube and Rect mean COMBINED_IMAGE_SAMPLER; Buffer means TEXEL_BUFFER; 1D and 3D are rejected.
* Not checked: arrayed and shadow (`Depth`) images. Neither is used in vanilla.
* Effectively unsupported (they fail the type check): SSBOs, storage images, separate images and samplers.

Push constants (`333-348`): at most one block per stage, and its size must be ≤ `pipeline.pushConstantSize()`.

### 6.3 Vertex attribute locations (`87-178`)

For each non-null `VertexFormat` at buffer slot `i`:

* emit `VertexBuffer(slot = i, stride = vertexSize, stepRate)`;
* for each element whose **name** matches a vertex-shader input, emit `AttribBinding(slot, location, offset, format)`;
* `location` = the shader input's `layout(location)`; an element repeating the previous name (matrix columns, `VertexFormat.Builder.addAttribute(name, fmt, columnCount)`) gets `previousLocation + 1`;
* format elements the shader doesn't use are skipped silently.

Checks on every shader input:

* no `Component` decoration;
* a matching element must exist;
* base type must match: UNORM/SNORM/FLOAT formats need an fp32 input, UINT a uint32 input, SINT an int32 input;
* the shader vector size must be ≤ the format's component count.

Vertex formats in vanilla (`DefaultVertexFormat.java:17-26`):

| Element | Format |
|---|---|
| Position | RGB32_FLOAT |
| Color | RGBA8_UNORM |
| UV0, UV3 | RG32_FLOAT |
| UV1, UV2 | RG16_SINT (`ivec2` in shader) |
| Normal | RGBA8_SNORM |
| LineWidth | R32_FLOAT |
| ChunkPosition | RGB32_SINT, instanced via `CHUNK_DATA_INSTANCED`, stepRate 1, slot 1 |
| ChunkVisibility | R32_FLOAT, instanced, slot 1 |

* `VertexFormat.build()` enforces a stride that is a multiple of 4. Element offsets are aligned to the component size (`VertexFormat.java:84-86`, `138-145`).
* `stepRate == 0` means per-vertex. `>0` means per-instance with that divisor (`VulkanRenderPipeline.java:222-234`).

### 6.4 Interface matching (`181-257`, `481-538`)

* Every fragment input location must be produced by the vertex shader, with the same base type, the same vector length, and the same `Flat` decoration.
* `Component` decorations are rejected.
* 64-bit, struct and fp64 interface types are rejected.
* Array interface variables occupy consecutive locations.
* **This is exactly the contract MSL needs:** SPIRV-Cross matches varyings by `[[user(locnN)]]`.

### 6.5 What the backend receives (`BackendRenderPipeline.CreateInfo`, `BackendRenderPipeline.java:16-37`)

```
name                 = pipeline location string (use for labels)
shaders              = [Shader(name=shader id, entryPoint="main", module=SpvModule)]  // VERTEX then FRAGMENT, both always present
vertexBuffers        = [VertexBuffer(bufferSlot, stride, stepRate)]                   // one per non-null VertexFormat
attribBindings       = [AttribBinding(bufferSlot, location, offset, GpuFormat)]
uniforms             = [UniformDescription(name, type, gpuFormat)]  // index == binding
pushConstantsSize    = pipeline.pushConstantSize()
depthStencilState    = nullable
polygonMode, cull, colorTargetStates (nullable entries, size == active count), primitiveTopology
```

---

## 7. Compile lifecycle, threading, `Pending.finishCompile`

```
GpuDevice.compilePipeline(pipeline, shaderSource, executor)                  FrontendGpuDevice.java:206-210
 └ PipelineBuilder.compilePipeline                                            PipelineBuilder.java:370-469
    ├ if WIREFRAME && !features.wireframeFillMode -> completed(Pending.NULL)  373-375
    └ CompletableFuture.supplyAsync(on executor = worker thread):
        createInfo = generateBackendCreateInfo(...)   // shaderc + spvc reflect + patch; null => Pending.NULL (modules already closed)
        backendPending = backendDevice.compilePipeline(createInfo)            // <-- BACKEND, WORKER THREAD   411
        return () -> {                                                       // CompiledRenderPipeline.Pending
            backendPipeline = backendPending.finishCompile()                  // <-- BACKEND, caller's thread  434
            executor.execute(close all SpvModules)                            // SPIR-V freed right after      451
            return backendPipeline == null ? null : new FrontendRenderPipeline(name, backendPipeline,
                     vertexFormats, uniformIndices(name->binding), flattenUniforms(bindGroups), colorTargetStates,
                     wantsDepthTexture, pushConstantSize)                    454-463
        }
```

Where `finishCompile` runs:

* during resource reload, on `reloadExecutor` (`ShaderManager.java:106`);
* during lazy compiles, on the thread calling `RenderSystem.getCompiledPipeline` (render thread; `PipelineCache.java:33` blocks it with `.join()`).

What the backends do in each phase:

* **GL** does SPIRV-Cross decompile in `compilePipeline` (worker) and all GL calls in `finishCompile` (`GlDevice.java:355-367`).
* **Vulkan** does everything in `compilePipeline`, including `vkCreateGraphicsPipelines`, and `finishCompile` just returns the object (`VulkanDevice.java:265-268`).

Backend contract:

* `compilePipeline` must be **thread-safe** and must not touch per-frame command state. Many pipelines compile concurrently.
* **Do not retain `module.spv()` past `finishCompile`.** The frontend frees it asynchronously.
* Failure means returning `BackendRenderPipeline.Pending.NULL` or having `finishCompile()` return `null`. **Do not throw.** An exception escapes `supplyAsync`, fails the whole reload future in `Util.sequence`, and leaks the SPIR-V modules.
* `BackendRenderPipeline.close()` is called on the render thread. This happens when the `PipelineCache` is cleared, e.g. after a reload swaps caches (`ShaderManager.java:229-234`), and when a post chain is closed. The GPU may still reference the pipeline: Vulkan defers via `queueForDestroy` (`VulkanRenderPipeline.java:72-78`).
* `isClosed()` must reflect `close()`.

---

## 8. Runtime contract (`FrontendRenderPass`, `FrontendRenderPipeline`)

`setPipeline` (`FrontendRenderPass.java:104-128`):

* The pipeline color target count must equal the render pass color attachment count.
* For every non-null attachment, the color target must be non-null with **exactly the same `GpuFormat`**.
* It then calls `backend.setPipeline(...)`, **replays every uniform currently set in the pass** (`this.uniforms.forEach(this::setUniform)`, `125`; HashMap order), and resets `constantsPushed`.
* The depth attachment is not checked here. `wantsDepthTexture` is checked in strict mode only (`628-632`).

`setUniform(name, value)` (`159-172`):

* stores or removes `name -> value` in the pass;
* if a pipeline is bound and `uniformIndices` contains the name, calls `backend.setUniform(binding, value)`;
* names the pipeline doesn't use are ignored.

Value types:

| UniformType | Value passed to `backend.setUniform` |
|---|---|
| UNIFORM_BUFFER | `GpuBufferSlice`; the offset is validated against `limits().minUniformOffsetAlignment()` (`150-157`) |
| TEXEL_BUFFER | `GpuBufferSlice`; must be the whole buffer, with usage `USAGE_UNIFORM_TEXEL_BUFFER (256)` (strict only, `563-581`) |
| COMBINED_IMAGE_SAMPLER | `TextureViewAndSampler(view, sampler)` |
| any | `null`, meaning cleared |

`pushConstants(ByteBuffer)` (`175-186`): requires a bound pipeline and `remaining() ≥ pipeline.pushConstantSize`.

Draw validation (`504-634`) only runs when `FrontendGpuDevice.STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE` (`FrontendGpuDevice.java:36`, `SharedConstants.java:128`). **In a normal build none of it runs:** missing uniforms, missing vertex buffers, missing push constants, and a missing depth attachment for a depth pipeline are all unchecked. The Metal backend must decide what to do when a slot is unset. Vulkan throws `IllegalStateException` (`VulkanRenderPass.java:298, 310, 322`).

---

## 9. How the OpenGL backend consumes SPIR-V

It uses **SPIRV-Cross to GLSL 330**, not `GL_ARB_gl_spirv`.

`GlPipelineRecompiler.decompileShader` (`45-127`) runs on the worker inside `GlDevice.compilePipeline`. It creates an spvc compiler with backend `SPVC_BACKEND_GLSL = 1` and sets:

| Option (value) | Setting | Line |
|---|---|---|
| `SPVC_COMPILER_OPTION_GLSL_VERSION` (33554440) | 330 | `64` |
| `GLSL_ENABLE_420PACK_EXTENSION` (33554439) | false, so no `layout(binding)` in the output | `65` |
| `GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER` (33554465) | true | `66` |
| `GLSL_SUPPORT_NONZERO_BASE_INSTANCE` (33554437) | `drawParametersSupported` | `67` |
| `FORCE_ZERO_INITIALIZED_VARIABLES` (16777270) | true, non-debug only | `68-71` |
| `FLATTEN_MULTIDIMENSIONAL_ARRAYS` (16777218) | true, non-debug only | `68-71` |

It also renames interface variables and descriptors:

* vertex inputs become `_vert_input_%02d(location)` and fragment outputs `_frag_output_%02d` (`75-83`);
* varyings become `_interface_variable_%02d(location)`, because GLSL 330 links by name (`85-89`); a RenderDoc debug build on non-mac uses `GLSL_SEPARATE_SHADER_OBJECTS` instead;
* UBOs become block `_uniform_%02d_%02d(set, binding)` with instance `_uniform_instance_…`; samplers become `_uniform_00_<binding>`; the push constant block becomes `_push_constants` (`164-234`);
* the entry point is set with execution model 0 or 4 (`98-101`).

In `finishCompile` (render thread), `GlProgram.link` then `setupBindGroupLayouts(createInfo.uniforms())` (`GlProgram.java:59-110`) runs:

* UBO block bindings are assigned sequentially, push constants first;
* sampler and texel-buffer texture units are assigned sequentially via `glUniform1i`;
* the lookup by name `_uniform_00_%02d` turns `createInfo.uniforms()` index `i` into a `Uniform` (`Ubo(blockBinding)`, `Sampler(unit)`, or `Utb(unit, format, glTexture)`);
* `getUniform(index)` is the binding index (`118-120`).

Fixed-function state is re-applied on every `GlRenderPipeline.bind()` (`113-157`):

* depth test, func, mask and polygon offset (`_polygonOffset(scale, constant)`);
* cull enable;
* per-target color mask and blend enable, with a single global `blendFuncSeparate` and `blendEquationSeparate`;
* `glPolygonMode(GL_FRONT_AND_BACK, mode)`.

Vertex input comes from `VertexArray` with `glVertexAttrib[I]Format` / `[I]Pointer`, binding divisors, and `isInteger` from the format (`VertexArray.java:58-140`). Clip control is `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)` only if `GL_ARB_clip_control` is present (`GlDevice.java:149-152`), and `isZZeroToOne = GL_ARB_clip_control` (`GlHeuristics.java:91`).

**On macOS GL 4.1 there is no clip control, so OpenGL on Mac runs reverse-Z in the -1..1 range,** which is precision-poor, and without draw parameters. Metal fixes both.

---

## 10. How the Vulkan backend consumes SPIR-V (`VulkanRenderPipeline.compile`, `80-376`)

* **Descriptor set layout** (`85-112`): one binding per `createInfo.uniforms()[i]` at `binding = i`, `descriptorCount = 1`, `stageFlags = VERTEX|FRAGMENT (17)`.
  * Types: UBO `6` (UNIFORM_BUFFER), sampler `1` (COMBINED_IMAGE_SAMPLER), texel buffer `4` (UNIFORM_TEXEL_BUFFER).
  * `flags = 1` is **PUSH_DESCRIPTOR_BIT_KHR**.
* **Pipeline layout** (`132-147`): that set, plus one push range `{stageFlags = 0x7FFFFFFF, offset 0, size = pushConstantsSize}` if the size is non-zero.
* **Shaders**: `vkCreateShaderModule(pCode = module.spv())` with `pName = "main"` (`173-211`).
* **Vertex input** (`214-261`): bindings `{binding = slot, stride, inputRate = stepRate > 0 ? INSTANCE : VERTEX}` plus `VK_EXT_vertex_attribute_divisor` for the divisor; attributes `{location, binding = slot, offset, format}`.
* **Raster**: `polygonMode`, `cullMode = cull ? BACK : NONE`, **`frontFace = CLOCKWISE (1)`**, `lineWidth 1`.
* **Depth bias**: enabled if either factor ≠ 0, with `constantFactor = depthBiasConstant` and `slopeFactor = depthBiasScaleFactor` (`266-281`).
* **Depth test**: enabled iff the state is non-null (otherwise all zero, i.e. disabled), with `depthWriteEnable = writeDepth` and `compareOp`.
* **Blend** (`283-300`): per attachment, `colorWriteMask` (0 for null targets) and blend from that target's `BlendFunction`.
* **Dynamic state**: VIEWPORT and SCISSOR (`309-311`).
* **Dynamic rendering** (`312-321`): color formats per target (UNDEFINED for null) and **depth = D32_SFLOAT (126)**.
* **Two pipelines** (`336-354`): `withDepthPipeline` (D32) is always built. `withoutDepthPipeline` (depth format UNDEFINED) is built only when `depthStencilState == null`. `VulkanRenderPass.setPipeline` binds the one matching `hasDepth` (`VulkanRenderPass.java:127-138`).

At draw time, `pushDescriptors` (`VulkanRenderPass.java:280-375`) re-pushes **all** descriptors when any is dirty. It creates and destroys a `VkBufferView` for texel buffers on every push (`337`). Viewport is `{0, 0, w, h, 0..1}` with no negative height (`80-88`).

---

## 11. Coordinate conventions (and what Metal must do)

| Topic | GL / Vulkan behaviour in RenderPearl | Metal plan |
|---|---|---|
| Clip-space Z | Game builds projections with `zZeroToOne = isZZeroToOne()` (`Projection.java:69-73`, culling projection `Camera.java:188`). Vulkan reports `true` (`VulkanDevice.java:91`). | Report `isZZeroToOne = true`. Metal clip Z is 0..1, so **no** `FIXUP_DEPTH_CONVENTION`. |
| Depth direction | **Reverse-Z**: near/far swapped (`Projection.java:67-68`), default test `GREATER_THAN_OR_EQUAL`, `integrate_depth.fsh` treats `0.0` as "no depth". Depth formats are D32_FLOAT everywhere (`LevelRenderer.java:212`, `GameRenderer.java:179`, GUI atlases). | `MTLPixelFormatDepth32Float`. Clear depth comes from the game (0.0). Keep `MTLDepthClipModeClip`. |
| NDC Y to memory row | GL: NDC y=-1 maps to window y=0, which is texture row 0. Vulkan (positive-height viewport): NDC y=-1 maps to framebuffer y=0, also row 0. **Same memory layout.** Vulkan flips only in the present blit (`VulkanGpuSurface.java:410-419`: dst y from `copyHeight` to 0). | Metal maps NDC y=+1 to row 0 (opposite). Use **SPIRV-Cross `FLIP_VERTEX_Y = true`** (appends `gl_Position.y = -gl_Position.y`). **[verified]**: screen-quad uv.v=0 lands on row 0, `[[position]].y = row + 0.5`. Present to the CAMetalLayer drawable with a **Y-flipped** full-screen draw. A plain `MTLBlitCommandEncoder` copy cannot flip. |
| Front face | GL default CCW-front in window coords (y-up). Vulkan uses `frontFace = CLOCKWISE` because of the y-down framebuffer with the same memory layout. | With FLIP_VERTEX_Y: `setFrontFacingWinding(MTLWindingClockwise)`, `setCullMode(MTLCullModeBack)` if `cull`, else `None`. **[verified]**: the GL-CCW screen-quad survives back-face culling with Clockwise and is culled with CounterClockwise. |
| `gl_FragCoord` | Row-index based (+0.5) in both APIs, used as `texelFetch(…, ivec2(gl_FragCoord.xy))` on same-size targets (`oit_*`, `integrate_depth`). | `[[position]]` is upper-left origin, so it is consistent after the flip. **[verified]** |
| Texture origin / uploads | Row 0 of uploaded data is the first row; `uv (0,0)` is row 0. | Same in Metal; upload as-is, no flipping. |
| Scissor / render area | Given in row-index space (`GlCommandEncoder.java:156-157`, `VulkanRenderPass.java:174-178`). | `MTLScissorRect` uses the same values, no flip. |
| Derivatives | `dFdy` along increasing rows. | Same after the flip. |
| `vertex_id` / `instance_id` | Vulkan `VertexIndex` includes base vertex / first vertex. `InstanceIndex` includes base instance. | Metal `[[vertex_id]]` and `[[instance_id]]` include the base, so it matches. Define `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` (`shaderDrawParameters = true`). |

---

## 12. lwjgl-spvc 3.4.3: Java API available for MSL

The jar is `mc/libraries/org/lwjgl/lwjgl-spvc/3.4.3/lwjgl-spvc-3.4.3.jar` plus `…-natives-macos-arm64.jar` (`libspirv-cross.dylib`, SPIRV-Cross git `6c09849fe88c48eaed08413aa022aaa136a3a057`). The C API version is 0.68.0 (`SPVC_C_API_VERSION_MINOR = 68`). It knows `SPVC_RESOURCE_TYPE_TENSOR = 16`, so it is a 2025+ SPIRV-Cross.

Core flow (`org.lwjgl.util.spvc.Spvc`):

* `spvc_context_create(PointerBuffer)`
* `spvc_context_set_error_callback(long ctx, SpvcErrorCallbackI, long ud)`
* `spvc_context_get_last_error_string(long)`
* `spvc_context_parse_spirv(long ctx, IntBuffer spirv, long wordCount, PointerBuffer ir)`
* `spvc_context_create_compiler(long ctx, int backend /*SPVC_BACKEND_MSL=3*/, long ir, int captureMode /*COPY=0, TAKE_OWNERSHIP=1*/, PointerBuffer compiler)`
* `spvc_compiler_create_compiler_options(long, PointerBuffer)`
* `spvc_compiler_options_set_bool(long opts, int option, boolean)`
* `spvc_compiler_options_set_uint(long opts, int option, int)`
* `spvc_compiler_install_compiler_options(long compiler, long opts)`
* `spvc_compiler_compile(long, PointerBuffer source)`
* `spvc_context_destroy(long)`
* `spvc_context_release_allocations(long)`

Entry points:

* `spvc_compiler_set_entry_point(long, CharSequence, int model)`
* `spvc_compiler_rename_entry_point(long, CharSequence from, CharSequence to, int model)`
* `spvc_compiler_get_cleansed_entry_point_name(long, CharSequence, int model)`
* `spvc_compiler_get_execution_model(long)`

`"main"` becomes `main0` in MSL. **[verified]**

MSL-specific functions:

* Resource and attribute remapping:
  * `spvc_compiler_msl_add_resource_binding(long, SpvcMslResourceBinding)`
  * `spvc_compiler_msl_add_resource_binding_2(long, SpvcMslResourceBinding2)`
  * `spvc_compiler_msl_add_vertex_attribute(long, SpvcMslVertexAttribute)`
  * `spvc_compiler_msl_add_shader_input(long, SpvcMslShaderInterfaceVar)`, `spvc_compiler_msl_add_shader_input_2(long, SpvcMslShaderInterfaceVar2)`
  * `spvc_compiler_msl_add_shader_output(long, SpvcMslShaderInterfaceVar)`, `spvc_compiler_msl_add_shader_output_2(long, SpvcMslShaderInterfaceVar2)`
* Argument buffers and dynamic buffers:
  * `spvc_compiler_msl_add_discrete_descriptor_set(long, int)`
  * `spvc_compiler_msl_set_argument_buffer_device_address_space(long, int, boolean)`
  * `spvc_compiler_msl_add_dynamic_buffer(long, int set, int binding, int index)`
  * `spvc_compiler_msl_add_inline_uniform_block(long, int, int)`
* Usage queries:
  * `spvc_compiler_msl_is_resource_used(long, int model, int set, int binding)`
  * `spvc_compiler_msl_is_vertex_attribute_used(long, int location)`
  * `spvc_compiler_msl_is_shader_input_used(long, int)`, `spvc_compiler_msl_is_shader_output_used(long, int)`
  * `spvc_compiler_msl_get_automatic_resource_binding(long, int id)`, `spvc_compiler_msl_get_automatic_resource_binding_secondary(long, int id)`
* Auxiliary-buffer queries (assert all are false):
  * `spvc_compiler_msl_needs_swizzle_buffer`, `spvc_compiler_msl_needs_buffer_size_buffer`, `spvc_compiler_msl_needs_output_buffer`
  * `spvc_compiler_msl_needs_patch_output_buffer`, `spvc_compiler_msl_needs_input_threadgroup_mem`
  * `spvc_compiler_msl_is_rasterization_disabled`
* Samplers and outputs:
  * `spvc_compiler_msl_remap_constexpr_sampler(long, int id, SpvcMslConstexprSampler)`, `spvc_compiler_msl_remap_constexpr_sampler_by_binding(long, int set, int binding, SpvcMslConstexprSampler)`, plus `_ycbcr` variants
  * `spvc_compiler_msl_set_fragment_output_components(long, int location, int components)`
  * `spvc_compiler_msl_set_combined_sampler_suffix(long, CharSequence)`, `spvc_compiler_msl_get_combined_sampler_suffix(long)`
  * `spvc_compiler_mask_stage_output_by_location(long, int location, int component)`, `spvc_compiler_mask_stage_output_by_builtin(long, int builtin)`
* Struct initialisers:
  * `spvc_msl_resource_binding_init(SpvcMslResourceBinding)`, `spvc_msl_resource_binding_init_2(…2)`
  * `spvc_msl_vertex_attribute_init`, `spvc_msl_shader_interface_var_init`, `spvc_msl_shader_interface_var_init_2`
  * `spvc_msl_constexpr_sampler_init`, `spvc_msl_sampler_ycbcr_conversion_init`
  * `spvc_msl_get_aux_buffer_struct_version()`
* Decorations, for invariance and reflection:
  * `spvc_compiler_set_decoration(long, int id, int decoration, int arg)`, `spvc_compiler_set_member_decoration(long, int typeId, int member, int decoration, int arg)`
  * `spvc_compiler_get_decoration`, `spvc_compiler_has_decoration`, `spvc_compiler_unset_decoration`
  * `spvc_compiler_get_binary_offset_for_decoration(long, int id, int decoration, IntBuffer wordOffset)`
  * `spvc_compiler_create_shader_resources(long, PointerBuffer)`, `spvc_compiler_create_shader_resources_for_active_variables`
  * `spvc_resources_get_resource_list_for_type(long res, int type, PointerBuffer list, PointerBuffer count)`, `spvc_resources_get_builtin_resource_list_for_type(…)`
  * `spvc_compiler_update_active_builtins`, `spvc_compiler_has_active_builtin(long, int builtin, int storage)`

Struct setters (`set(...)` argument order verified from bytecode):

* `SpvcMslResourceBinding.set(stage, desc_set, binding, msl_buffer, msl_texture, msl_sampler)`
* `SpvcMslResourceBinding2.set(stage, desc_set, binding, count, msl_buffer, msl_texture, msl_sampler)`
* `SpvcMslVertexAttribute.set(location, msl_buffer, msl_offset, msl_stride, per_instance, format, builtin)`
* `SpvcMslShaderInterfaceVar2.set(location, format, builtin, vecsize, rate)`
* `SpvcMslConstexprSampler`: `coord`, `min_filter`, `mag_filter`, `mip_filter`, `s/t/r_address`, `compare_func`, `border_color`, `lod_clamp_min/max`, `max_anisotropy`, `compare_enable`, `lod_clamp_enable`, `anisotropy_enable`

Constants:

* Backends and stages: `SPVC_BACKEND_MSL = 3`; `SPVC_MSL_PLATFORM_IOS = 0`, `SPVC_MSL_PLATFORM_MACOS = 1`; `Spv.SpvExecutionModelVertex = 0`, `SpvExecutionModelFragment = 4`.
* Special bindings: `SPVC_MSL_PUSH_CONSTANT_DESC_SET = -1` (~0u), `SPVC_MSL_PUSH_CONSTANT_BINDING = 0`, `SPVC_MSL_SWIZZLE_BUFFER_BINDING = -2`, `SPVC_MSL_BUFFER_SIZE_BUFFER_BINDING = -3`, `SPVC_MSL_ARGUMENT_BUFFER_BINDING = -4`.
* Errors: `SPVC_SUCCESS = 0`, `SPVC_ERROR_INVALID_SPIRV = -1`, `…UNSUPPORTED_SPIRV = -2`, `…OUT_OF_MEMORY = -3`, `…INVALID_ARGUMENT = -4`.

`SPVC_COMPILER_OPTION_*` values:

* COMMON:
  * FORCE_TEMPORARY 16777217
  * FLATTEN_MULTIDIMENSIONAL_ARRAYS 16777218
  * **FIXUP_DEPTH_CONVENTION 16777219**
  * **FLIP_VERTEX_Y 16777220**
  * EMIT_LINE_DIRECTIVES 16777253
  * ENABLE_STORAGE_IMAGE_QUALIFIER_DEDUCTION 16777268
  * **FORCE_ZERO_INITIALIZED_VARIABLES 16777270**
  * RELAX_NAN_CHECKS 16777294
* MSL:
  * **MSL_VERSION 134217745**
  * MSL_TEXEL_BUFFER_TEXTURE_WIDTH 134217746
  * MSL_SWIZZLE_BUFFER_INDEX / MSL_AUX_BUFFER_INDEX 134217747
  * MSL_INDIRECT_PARAMS_BUFFER_INDEX 134217748
  * MSL_SHADER_OUTPUT_BUFFER_INDEX 134217749
  * MSL_SHADER_PATCH_OUTPUT_BUFFER_INDEX 134217750
  * MSL_SHADER_TESS_FACTOR_OUTPUT_BUFFER_INDEX 134217751
  * MSL_SHADER_INPUT_WORKGROUP_INDEX 134217752
  * MSL_ENABLE_POINT_SIZE_BUILTIN 134217753
  * MSL_DISABLE_RASTERIZATION 134217754
  * MSL_CAPTURE_OUTPUT_TO_BUFFER 134217755
  * MSL_SWIZZLE_TEXTURE_SAMPLES 134217756
  * **MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS 134217757**
  * MSL_TESS_DOMAIN_ORIGIN_LOWER_LEFT 134217758
  * **MSL_PLATFORM 134217759**
  * **MSL_ARGUMENT_BUFFERS 134217760**
  * **MSL_TEXTURE_BUFFER_NATIVE 134217762**
  * MSL_BUFFER_SIZE_BUFFER_INDEX 134217764
  * MSL_MULTIVIEW 134217766
  * MSL_VIEW_MASK_BUFFER_INDEX 134217767
  * MSL_DEVICE_INDEX 134217768
  * MSL_VIEW_INDEX_FROM_DEVICE_INDEX 134217769
  * MSL_DISPATCH_BASE 134217770
  * MSL_DYNAMIC_OFFSETS_BUFFER_INDEX 134217771
  * MSL_TEXTURE_1D_AS_2D 134217772
  * MSL_ENABLE_BASE_INDEX_ZERO 134217773
  * MSL_(IOS_)FRAMEBUFFER_FETCH_SUBPASS 134217774
  * **MSL_INVARIANT_FP_MATH 134217775**
  * MSL_EMULATE_CUBEMAP_ARRAY 134217776
  * **MSL_ENABLE_DECORATION_BINDING 134217777**
  * MSL_FORCE_ACTIVE_ARGUMENT_BUFFER_RESOURCES 134217778
  * MSL_FORCE_NATIVE_ARRAYS 134217779
  * MSL_ENABLE_FRAG_OUTPUT_MASK 134217784
  * MSL_ENABLE_FRAG_DEPTH_BUILTIN 134217785
  * MSL_ENABLE_FRAG_STENCIL_REF_BUILTIN 134217786
  * MSL_ENABLE_CLIP_DISTANCE_USER_VARYING 134217787
  * MSL_MULTI_PATCH_WORKGROUP 134217789
  * MSL_SHADER_INPUT_BUFFER_INDEX 134217790
  * MSL_SHADER_INDEX_BUFFER_INDEX 134217791
  * MSL_VERTEX_FOR_TESSELLATION 134217792
  * MSL_VERTEX_INDEX_TYPE 134217793
  * MSL_MULTIVIEW_LAYERED_RENDERING 134217795
  * MSL_ARRAYED_SUBPASS_INPUT 134217796
  * MSL_R32UI_LINEAR_TEXTURE_ALIGNMENT 134217797
  * MSL_R32UI_ALIGNMENT_CONSTANT_ID 134217798
  * MSL_IOS_USE_SIMDGROUP_FUNCTIONS 134217800
  * MSL_EMULATE_SUBGROUPS 134217801
  * MSL_FIXED_SUBGROUP_SIZE 134217802
  * MSL_FORCE_SAMPLE_RATE_SHADING 134217803
  * MSL_IOS_SUPPORT_BASE_VERTEX_INSTANCE 134217804
  * MSL_RAW_BUFFER_TESE_INPUT 134217807
  * MSL_SHADER_PATCH_INPUT_BUFFER_INDEX 134217808
  * MSL_MANUAL_HELPER_INVOCATION_UPDATES 134217809
  * MSL_CHECK_DISCARDED_FRAG_STORES 134217810
  * MSL_ARGUMENT_BUFFERS_TIER 134217812
  * MSL_SAMPLE_DREF_LOD_ARRAY_AS_GRAD 134217813
  * MSL_READWRITE_TEXTURE_FENCES 134217814
  * MSL_REPLACE_RECURSIVE_INPUTS 134217815
  * MSL_AGX_MANUAL_CUBE_GRAD_FIXUP 134217816
  * MSL_FORCE_FRAGMENT_WITH_SIDE_EFFECTS_EXECUTION 134217817
  * MSL_AUTO_DISABLE_RASTERIZATION 134217820
  * MSL_ENABLE_POINT_SIZE_DEFAULT 134217821
* GLSL options used by the GL backend:
  * GLSL_SUPPORT_NONZERO_BASE_INSTANCE 33554437
  * GLSL_SEPARATE_SHADER_OBJECTS 33554438
  * GLSL_ENABLE_420PACK_EXTENSION 33554439
  * GLSL_VERSION 33554440
  * GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER 33554465

Other enums:

* Resource types: `SPVC_RESOURCE_TYPE_*` UNIFORM_BUFFER 1, STORAGE_BUFFER 2, STAGE_INPUT 3, STAGE_OUTPUT 4, SUBPASS_INPUT 5, STORAGE_IMAGE 6, SAMPLED_IMAGE 7, ATOMIC_COUNTER 8, PUSH_CONSTANT 9, SEPARATE_IMAGE 10, SEPARATE_SAMPLERS 11.
* Decorations: Location 30, Component 31, Binding 33, DescriptorSet 34, Flat 14, Invariant 18, BuiltIn 11.
* Built-ins: Position 0, PointSize 1, VertexIndex 42, InstanceIndex 43.

---

## 13. SPIR-V to MSL translation plan (SPIRV-Cross)

### 13.1 Where it runs

All of it runs in `MetalDevice.compilePipeline(CreateInfo)`, on the worker thread. One spvc context is created per stage. It must not be shared across threads, and it is destroyed right after `spvc_compiler_compile`, once the string has been copied.

### 13.2 Options (validated on M5 / macOS 27, see §16)

| Option | Value | Why |
|---|---|---|
| `MSL_VERSION` | `30200` (MSL 3.2; encoding `major*10000+minor*100+patch`) | Default is 1.2. Needs ≥2.1 for `texture_buffer`. 3.2 compiled fine with `MTLLanguageVersion3_2`. 4.0 is possible (see §16, open questions). |
| `MSL_PLATFORM` | `SPVC_MSL_PLATFORM_MACOS (1)` | macOS. |
| `MSL_TEXTURE_BUFFER_NATIVE` | `true` | `isamplerBuffer` becomes `texture_buffer<int>`, instead of emulation through a 2D texture (`MSL_TEXEL_BUFFER_TEXTURE_WIDTH`). **[verified]** |
| `MSL_ARGUMENT_BUFFERS` | `false` | Discrete `[[buffer/texture/sampler(n)]]` indices map 1:1 onto Metal 3 `set*` calls **and** onto Metal 4 `MTL4ArgumentTable` slots. ≤12 resources per pipeline, so no bindless needed. |
| `MSL_ENABLE_DECORATION_BINDING` | `false` | Use explicit `msl_add_resource_binding` for dense per-kind indices (below). |
| `FLIP_VERTEX_Y` | `true` **for the vertex stage** | Matches the GL/Vulkan memory layout (§11). **[verified]** |
| `FIXUP_DEPTH_CONVENTION` | `false` | Report `isZZeroToOne = true`. Only set this to `true` if some reason forces `false`; reverse-Z precision needs 0..1. |
| `MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS` | `true` | Safety for fewer-component outputs; MoltenVK does the same. All vanilla outputs are vec4. |
| `FORCE_ZERO_INITIALIZED_VARIABLES` | `true` | Parity with the GL path (`GlPipelineRecompiler.java:69`). Cost is negligible. |
| `MSL_ENABLE_POINT_SIZE_BUILTIN` | `true` (default) | `debug_point.vsh` writes `gl_PointSize`, which becomes `[[point_size]]`. **[verified]** |
| `MSL_ENABLE_FRAG_DEPTH_BUILTIN` | `true` (default) | `gl_FragDepth` becomes `[[depth(any)]]`. **[verified]** |
| `MSL_ENABLE_BASE_INDEX_ZERO` | `false` (default) | Base vertex/instance semantics are already correct. |
| `RELAX_NAN_CHECKS` | `true` (optional) | Smaller code for unordered float compares. GLSL gives no NaN guarantees. |
| `MSL_INVARIANT_FP_MATH` | `false`, unless the invariance fix in §14.6 is applied | Only affects outputs decorated `Invariant`. |
| `MSL_AGX_MANUAL_CUBE_GRAD_FIXUP` | `false` | Only for `textureGrad` on cube maps, which vanilla doesn't do. |

After compiling, assert that `msl_needs_swizzle_buffer`, `msl_needs_buffer_size_buffer`, `msl_needs_output_buffer` and `msl_is_rasterization_disabled` are all `false`. **[verified for all probed shaders]**

### 13.3 Resource index layout (both stages use the same mapping)

Per pipeline, walk `createInfo.uniforms()` in binding order and assign dense per-kind Metal indices:

```
ubo  -> [[buffer(u++)]]                    u in 0..13   (vanilla max 5)
CIS  -> [[texture(t)]] + [[sampler(s)]]   t++, s++    (vanilla max 7; Metal discrete sampler limit is 16/stage)
UTB  -> [[texture(t++)]]                   (texture_buffer<int>; SPIRV-Cross emits no sampler)
push -> [[buffer(14)]]  via desc_set = SPVC_MSL_PUSH_CONSTANT_DESC_SET(-1), binding = 0
vertex buffer slot s (0..15) -> Metal buffer index 30 - s   (30..15; never collides with 0..14)
```

```java
for (int i = 0; i < uniforms.size(); i++) {
  SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
  Spvc.spvc_msl_resource_binding_init(b);
  b.stage(model /*0 vertex, 4 fragment*/).desc_set(0).binding(i);
  switch (uniforms.get(i).type()) {
    case UNIFORM_BUFFER         -> b.msl_buffer(map.buffer[i]);
    case COMBINED_IMAGE_SAMPLER -> b.msl_texture(map.texture[i]).msl_sampler(map.sampler[i]);
    case TEXEL_BUFFER           -> b.msl_texture(map.texture[i]);
  }
  Spvc.spvc_compiler_msl_add_resource_binding(compiler, b);
}
// push constants (only if createInfo.pushConstantsSize() > 0)
b.stage(model).desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING).msl_buffer(14);
...
Spvc.spvc_compiler_compile(compiler, pp);  String msl = MemoryUtil.memUTF8(pp.get(0));
String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model); // "main0"
for (i) usedMask[stage] |= spvc_compiler_msl_is_resource_used(compiler, model, 0, i) ? 1L<<i : 0;
```

Example of the generated entry point for terrain multidraw **[verified]**:

```
vertex   main0_out main0(main0_in in [[stage_in]], constant Globals& [[buffer(0)]], constant Projection& [[buffer(1)]],
                         constant TerrainUniform& [[buffer(2)]], texture2d<float> Sampler2 [[texture(0)]], sampler Sampler2Smplr [[sampler(0)]])
fragment main0_out main0(main0_in in [[stage_in]], constant Globals& [[buffer(0)]], constant TerrainUniform& [[buffer(2)]],
                         constant Fog& [[buffer(3)]], texture2d<float> Sampler0 [[texture(1)]], sampler Sampler0Smplr [[sampler(1)]])
```

Notes:

* Vertex inputs become `[[attribute(location)]]` inside a `[[stage_in]]` struct.
* Varyings become `[[user(locnN)]]`; `flat` becomes `[[flat]]`.
* std140 blocks are emitted with `packed_float3` / `packed_int3` to honour offsets.
* Fragment output arrays are split into `[[color(0)]]`, `[[color(1)]]`, …
* A vertex-stage `texture()` is emitted as `.sample(s, uv, level(0))`.
* `samplerCube` becomes `texturecube<float>`.

### 13.4 Vertex input

No `spvc_compiler_msl_add_vertex_attribute` is needed when using `[[stage_in]]` with an `MTLVertexDescriptor`.

* For each `AttribBinding(slot, location, offset, fmt)`: `attributes[location] = {format = toMTLVertexFormat(fmt), offset, bufferIndex = 30 - slot}`.
* For each `VertexBuffer(slot, stride, stepRate)` that has at least one attribute: `layouts[30 - slot] = {stride, stepFunction = stepRate > 0 ? PerInstance : PerVertex, stepRate = max(1, stepRate)}`.
* **[verified]** pipeline creation with BLOCK + CHUNK_DATA_INSTANCED (per-instance `int3`/`float`).

`GpuFormat` to `MTLVertexFormat`:

| GpuFormat | MTLVertexFormat |
|---|---|
| R/RG/RGB/RGBA8_UNORM | UCharNormalized / UChar2Normalized / UChar3Normalized / UChar4Normalized |
| *8_SNORM | Char…Normalized |
| *8_UINT | UChar… |
| *8_SINT | Char… |
| *16_UNORM | UShort…Normalized |
| *16_SNORM | Short…Normalized |
| *16_UINT | UShort… |
| *16_SINT | Short… (UV1/UV2 = `Short2`, feeding `int2`) |
| *16_FLOAT | Half… |
| *32_UINT | UInt… |
| *32_SINT | Int… (ChunkPosition = `Int3`) |
| *32_FLOAT | Float… |
| RGB10A2_UNORM | UInt1010102Normalized |
| RG11B10_FLOAT | FloatRG11B10 |

The frontend already guarantees that integer formats feed int/uint inputs and float/normalized formats feed float inputs.

### 13.5 Texel buffers (`TEXEL_BUFFER`)

* MSL argument: `texture_buffer<int> CloudFaces [[texture(t)]]`. `texelFetch` becomes `.read(uint(i))`.
* Bind an `MTLTexture` created from the `MTLBuffer`:
  * `[buffer newTextureWithDescriptor:[MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:MTLPixelFormatR8Sint width:size/bpp resourceOptions:… usage:MTLTextureUsageShaderRead] offset:0 bytesPerRow:alignUp(size, minimumTextureBufferAlignmentForPixelFormat)]`.
  * The measured alignment on M5 for `R8Sint` is 16. **[verified]**
* Cache this view on the buffer object, keyed by format. The frontend requires the whole buffer, so the offset is always 0.
* Do **not** recreate it per draw. Vulkan recreates a `VkBufferView` per descriptor push (`VulkanRenderPass.java:330-341`), which is wasteful.

### 13.6 Push constants

* No vanilla pipeline uses them. Support them anyway.
* Metal 3: `setVertexBytes` / `setFragmentBytes(ptr, len ≤ 128, atIndex: 14)`, only for stages whose push block is used.
* Metal 4 has no `setBytes`. Write the bytes into the per-frame transient ring buffer and `MTL4ArgumentTable.setAddress(gpuAddress + off, 14)`.
* `pushConstants` must be re-applied after every `setPipeline`. The frontend resets `constantsPushed` there.

### 13.7 Entry points and libraries

* Compile **one `MTLLibrary` per stage source**. Both entry points are `main0`, and the generated helper code (e.g. `spvUnsafeArray`) would collide in a combined source.
* Cache by `SHA-256(patched SPIR-V words) + stage + optionsVersion`, using `ConcurrentHashMap<Key, CompletableFuture<MTLFunction>>` to dedupe concurrent compiles.
* Expect about 120 vertex and 121 fragment libraries instead of 390.
* The runtime compiler already caches identical source in-process: a second identical screenquad source compiled in 0.0 ms. **[verified]**

---

## 14. Metal pipeline objects (mapping)

### 14.1 What goes in `MTLRenderPipelineState` vs encoder state

| RenderPearl | Metal |
|---|---|
| shaders | `vertexFunction` / `fragmentFunction` = `main0` from the cached libraries |
| vertex buffers / attribs | `MTLVertexDescriptor` (§13.4) |
| `colorTargetStates[i]` non-null | `colorAttachments[i].pixelFormat` = format (RGBA8_UNORM to RGBA8Unorm, RGBA16_FLOAT to RGBA16Float, RGBA32_FLOAT to RGBA32Float); `blendingEnabled = blendFunction.isPresent()`; src/dst factors and ops per §14.3; **`writeMask` with reversed bits** (§14.3) |
| `colorTargetStates[i] == null` | `pixelFormat = MTLPixelFormatInvalid`. Vanilla never passes null targets or null attachments (`withUnusedColorAttachment` / `withUnusedColorTargetState` are unused). |
| depth attachment format | `depthAttachmentPixelFormat`. Build `Depth32Float` always, plus an `Invalid` variant iff `depthStencilState == null` (mirrors Vulkan `withDepth` / `withoutDepth`, `VulkanRenderPipeline.java:336-354`). Pick by `renderPass.hasDepth` at `setPipeline`. |
| multisample | `rasterSampleCount = 1`, `alphaToCoverageEnabled = false` |
| topology class | `inputPrimitiveTopology` may stay Unspecified; set Point for POINTS for clarity |
| `depthStencilState` | separate `MTLDepthStencilState(compare, depthWriteEnabled)`. `null` means `{Always, write = false}`. Only 5 distinct objects exist in vanilla: (GE,w), (GE,nw), (EQ,nw), (ALWAYS,w), (ALWAYS,nw). Cache them on the device. |
| `cull` | encoder: `setCullMode(cull ? Back : None)` + `setFrontFacingWinding(Clockwise)` |
| `polygonMode` | encoder: `setTriangleFillMode(FILL ? Fill : Lines)`. Report `features.wireframeFillMode = true`. |
| depth bias | encoder: `setDepthBias(depthBiasConstant, slopeScale: depthBiasScaleFactor, clamp: 0)` when non-zero, otherwise `(0, 0, 0)`. Same semantics as Vulkan (constant times minimum resolvable difference). Values are **positive** because of reverse-Z. |
| topology | stored in the pipeline object and used as the `MTLPrimitiveType` of each draw (§14.4) |

Track the last-applied encoder state (cull, winding, fill, bias, depth-stencil state, PSO) and skip redundant calls. GL re-applies everything on every bind.

### 14.2 Compile-time work split

* In `compilePipeline` (worker): spvc for both stages, `newLibraryWithSource:options:error:` (or the cache), `newFunctionWithName:@"main0"`, build the descriptor, `newRenderPipelineStateWithDescriptor:error:` for one or two variants, and look up the depth-stencil state.
* Return `() -> pipeline`.
* `MTLDevice` creation calls are thread-safe.
* Measured on M5:
  * library from source: 4-170 ms each; the first compile includes compiler warm-up;
  * render pipeline state: about 64 ms for terrain.
* On failure, log the MSL and the NSError and return `Pending.NULL`.
* Keep `MTLFunction` and library objects alive, plus a lightweight copy of the descriptor inputs (formats, vertex descriptor), so unexpected variants can be built lazily. **Do not keep the SPIR-V.**

### 14.3 Enum tables

**BlendFactor to MTLBlendFactor**

| BlendFactor | MTLBlendFactor |
|---|---|
| ZERO | Zero |
| ONE | One |
| SRC_COLOR | SourceColor |
| ONE_MINUS_SRC_COLOR | OneMinusSourceColor |
| SRC_ALPHA | SourceAlpha |
| ONE_MINUS_SRC_ALPHA | OneMinusSourceAlpha |
| DST_COLOR | DestinationColor |
| ONE_MINUS_DST_COLOR | OneMinusDestinationColor |
| DST_ALPHA | DestinationAlpha |
| ONE_MINUS_DST_ALPHA | OneMinusDestinationAlpha |
| SRC_ALPHA_SATURATE | SourceAlphaSaturated |
| CONSTANT_COLOR | BlendColor |
| ONE_MINUS_CONSTANT_COLOR | OneMinusBlendColor |
| CONSTANT_ALPHA | BlendAlpha |
| ONE_MINUS_CONSTANT_ALPHA | OneMinusBlendAlpha |

No blend-color API exists, so the default (0,0,0,0) applies, matching GL/VK defaults.

**BlendOp to MTLBlendOperation**: ADD to Add, SUBTRACT to Subtract, REVERSE_SUBTRACT to ReverseSubtract, MIN to Min, MAX to Max. **[verified]** RGBA32Float with MAX blending builds a valid pipeline on M5 (OIT depth bounds).

**CompareOp to MTLCompareFunction**: ALWAYS_PASS to Always, NEVER_PASS to Never, LESS_THAN to Less, LESS_THAN_OR_EQUAL to LessEqual, EQUAL to Equal, NOT_EQUAL to NotEqual, GREATER_THAN to Greater, GREATER_THAN_OR_EQUAL to GreaterEqual.

**Write mask (pitfall).** RenderPearl uses R=1, G=2, B=4, A=8. `MTLColorWriteMask` uses Red=8, Green=4, Blue=2, Alpha=1. Convert with:

```
mtl = ((m&1)<<3) | ((m&2)<<1) | ((m&4)>>1) | ((m&8)>>3)
```

Vanilla uses 15, 7 (RGB; `entity_outline_blit`) and 0 (`water_mask`, `blit_depth_during_depth_bounds`).

### 14.4 Primitive topology

| RenderPearl | MTLPrimitiveType | Note |
|---|---|---|
| TRIANGLES | Triangle | |
| QUADS | Triangle | drawn indexed with the quad index buffer |
| LINES | Triangle | drawn indexed, lines are expanded quads |
| TRIANGLE_STRIP | TriangleStrip | Metal always enables primitive restart for strips. Vanilla's `IndexType.least` never produces 0xFFFF / 0xFFFFFFFF indices. |
| DEBUG_LINES | Line | |
| DEBUG_LINE_STRIP | LineStrip | |
| POINTS | Point | vertex shader writes `[[point_size]]` |
| **TRIANGLE_FAN** | **none in Metal** | Emulate. |

How to emulate TRIANGLE_FAN:

* All 6 fan pipelines draw `drawIndexed` with `RenderSystem.getSequentialBuffer(TRIANGLE_FAN) = sharedSequential` (identity indices; `RenderSystem.java:300-307`, `SkyRenderer.java:88-95,340`).
* **Backend option:** for fan pipelines, read the bound index range on the CPU (unified memory, shared storage), emit `(i0, ik, ik+1)` triangles into transient memory, and draw as Triangle with count `(n-2)*3`. Cache by (buffer, version, first, count). Winding is preserved.
* **Game option (cheaper):** emit triangle lists for sky, sunrise and debug fans.

### 14.5 Binding at draw time (from `setUniform(index, value)`)

* Keep `Object[] slots` sized to `uniforms.size()` and a dirty bitmask.
* At draw time, for each dirty `i`, using the precomputed `(kind, metalIndex, stageMask)`:
  * UBO: `setVertexBuffer` / `setFragmentBuffer(mtlBuf, offset, idx)`. If only the offset changed for the same buffer, use **`setVertexBufferOffset` / `setFragmentBufferOffset`**. This is the common case for per-draw `DynamicTransforms` slices.
  * CIS: `setVertexTexture` / `setFragmentTexture` + `setVertexSamplerState` / `setFragmentSamplerState`.
  * UTB: cached texture-buffer view, then `setVertexTexture`.
* Bind only to stages in `usedMask`. Example: `Fog` in terrain is fragment-only, `Sampler2` vertex-only.
* `setPipeline` will replay all uniforms right after (§8). Just mark them dirty; don't bind eagerly.
* Missing slots: in release builds the frontend does not validate (§8). Either bind a 256-byte zero buffer / 1×1 texture / default sampler as fallbacks, or throw like Vulkan. Never draw with a never-bound index.
* Metal 4 equivalent: one `MTL4ArgumentTable` per stage with
  * `maxBufferBindCount = 31` (UBOs 0..13, push 14, vertex buffers 15..30),
  * `maxTextureBindCount ≥ 8`, `maxSamplerStateBindCount ≥ 8`,
  * `setAddress(buf.gpuAddress + offset, i)`, `setTexture(tex.gpuResourceID, i)`, `setSamplerState(smp.gpuResourceID, i)`.
  * Samplers need `supportArgumentBuffers = YES`.
  * All buffers and textures must be in a residency set.

### 14.6 Pitfalls checklist

* **Depth textures sampled as `sampler2D`** (`blit_depth.fsh`) become `texture2d<float>` in MSL. **[verified]** Binding a `Depth32Float` texture there passes the Metal API validation layer and returns the depth value. No R32F view or `depth2d` is needed.
* **Depth-equal passes** (`glint`, `armor_decal_cutout_no_cull` use `EQUAL`; OIT passes re-test the same geometry): all shaders compute `ProjMat * ModelViewMat * vec4(Position,1)` identically (`entity.vsh:51`, `glint.vsh:16`, `item.vsh:39`). If z-fighting shows up on Metal (fast-math or FMA differences between separately compiled shaders):
  * mark Position invariant: `spvc_compiler_set_member_decoration(compiler, perVertexTypeId, 0 /*gl_Position*/, 18 /*Invariant*/, 0)`, or decorate the builtin var;
  * set `MSL_INVARIANT_FP_MATH = true`;
  * set `MTLCompileOptions.preserveInvariance = YES`.
* **`RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`** (OIT) writes `gl_FragDepth`. That forces late-Z and disables Apple HSR for OIT transmittance and accumulate draws. The shipped GL and Vulkan paths enable it on Apple GPUs; keep it until the Metal backend is shown to be consistent without it.
* **Color attachment and pipeline format must match exactly** in Metal validation. The frontend enforces format equality for non-null attachments, but not the *depth* attachment format. Key variants by depth format if non-D32 depth is ever used.
* **Constant-buffer offset alignment.**
  * UBO slices are aligned to `DeviceInfo.limits().minUniformOffsetAlignment()`.
  * MSL `constant T&` reads `float4`/`float4x4`, so report ≥16. 256 is conservative; MoltenVK uses 16 on Apple GPUs. This affects transient-allocator memory use (see the buffers doc).
* **Vertex stride and offsets.** Metal requires the stride to be a multiple of 4 (guaranteed by `VertexFormat.build`). All vanilla offsets are 4-byte aligned.
* **Point size.** Drawing `Point` without `[[point_size]]` is undefined. Only the debug_point shaders draw points and they write it. Optionally enable `MSL_ENABLE_POINT_SIZE_DEFAULT` for robustness.
* **Lifetime.** Metal 3 command buffers retain the PSOs and resources they bind by default. With Metal 4 (`MTL4CommandBuffer`, no automatic retention) or unretained command buffers, `close()` must defer the release until the GPU timeline passes, as Vulkan does with `queueForDestroy`.
* **Labels.** Set `descriptor.label = createInfo.name()` only when debugging is enabled (`isDebuggingEnabled`).

---

## 15. Performance opportunities

### Backend

1. **Do the entire Metal compile on the worker thread.** That includes `MTLRenderPipelineState` creation. `finishCompile` then just returns. GL does the driver compile and link on the render thread in `finishCompile`.
2. **Dedupe libraries and PSOs.**
   * 195 static pipelines have only 125 unique programs and 175 unique states.
   * Post chains create one pipeline per pass with identical shaders: blur has 6 passes of `box_blur`, spider has 10 passes.
   * Key `MTLFunction` by SPIR-V hash and PSO by (functions, vertex descriptor, attachments/blend, depth format).
3. **Persist across launches.**
   * Metal 3: `MTLBinaryArchive`. Use `addRenderPipelineFunctionsWithDescriptor:` then `serializeToURL:`. On the next launch set `descriptor.binaryArchives` and use `MTLPipelineOptionFailOnBinaryArchiveMiss` as a fast path.
   * Metal 4: `MTL4Compiler` + `MTL4Archive` + `MTL4PipelineDataSetSerializer`.
   * Also cache the MSL text keyed by SPIR-V hash.
   * The OS Metal shader cache already helps on the second launch.
4. **Metal 4 flexible (unspecialized) pipelines.**
   * Compile vertex and fragment code once with unspecialized color-attachment state.
   * Specialize per blend, write mask or pixel format with `newRenderPipelineStateBySpecializationWithDescriptor:pipeline:`, which is cheap.
   * Many vanilla pipelines differ only in blend or depth state.
5. **Cheap per-draw binding.**
   * Use dirty bitmasks, per-stage usage masks, and `set*BufferOffset` when only the offset changes.
   * Avoid Vulkan's "re-push every descriptor whenever any is dirty" and per-draw texel-view creation.
   * `FrontendRenderPass.setPipeline` replays all pass uniforms through `backend.setUniform`, so `setUniform` must be O(1) with no Metal calls.
6. **Minimal encoder state churn.** Cache depth-stencil states (5 in total) and track cull, winding, fill mode and bias.
7. **Precompile post chains at reload.** For example, warm the `PipelineCache` with the blur chain, which the menu background uses.

### Game side (we can patch it)

1. **Fix the duplicate pipeline locations** (`RenderPipelines.java:696-717`, `730-744`). `item_cutout` (plain and glint) and `item_translucent_glint` are not precompiled and compile **synchronously on the render thread** the first time items render (`PipelineCache.java:33` `.join()`). Give them unique locations.
2. Precompile post-effect pipelines (`PostChain.createPass` → `RenderSystem.getCompiledPipelineNullable`, `PostChain.java:111`) asynchronously at reload, instead of during the first frame that uses the effect.
3. Replace TRIANGLE_FAN usage (sky, sunrise, debug) with triangle lists, which removes the need for fan emulation.
4. `terrain.fsh` chooses RGSS or nearest sampling with a runtime uniform branch (`UseRgss`, `terrain.fsh:36`). The RGSS path does 8 `textureLod` + derivative math, and register allocation is sized for the worst path. Compiling a define-based variant per texture-filtering option could raise occupancy for the most expensive pass.
5. OIT ("Improved Transparency", `Options.java:224-226`, default off) triples translucent geometry work: 3 pipelines per translucent type, RGBA32F MAX blending and 2× RGBA16F targets. Leave it off by default for max FPS. When it is on, `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` additionally forces late-Z.
6. Shaders are compiled at `-O0` with debug info (`GlslCompiler.java:79-80`). This has no GPU cost on Metal, because the Metal compiler re-optimizes, but it produces larger MSL with more temporaries. It is irrelevant to frame time.

---

## 16. Verification probes (scratch) and open questions

Scratch probes live in the session scratchpad and are not deliverables:

* `MslProbe.java` replicates GlslCompiler + PipelineBuilder binding assignment and cross-compiles to MSL with §13.2.
* `mslcheck.swift` compiles every generated MSL with `MTLDevice.makeLibrary(source:)` (13 shader pairs, all OK), builds the terrain PSO, and an RGBA32F MAX-blend PSO.
* `depthcheck.swift`: Depth32Float bound as `texture2d<float>` under `MTL_DEBUG_LAYER=1`. OK, value correct.
* `flipcheck.swift`: FLIP_VERTEX_Y row mapping and winding.
* `PipelineCount.java` loads `RenderPipelines` from the client jar to count pipelines.
* Device facts reported by the probe: `Apple M5`, supports `MTLGPUFamily.apple9`/`mac2`, argument buffers Tier 2, `supports32BitFloatFiltering = true`, texture-buffer alignment for R8Sint = 16.

Open questions:

1. MSL language version: 3.2 is verified. Does using `MTL4Compiler` / `MTL4ArgumentTable` require or benefit from MSL 4.0 (`msl_version 40000`), and does this SPIRV-Cross build accept it?
2. Do EQUAL-depth passes (glint, armor decals) z-fight under Metal fast-math without invariance? This needs an in-game test.
3. Can `isExplicitDepthRequired` be `false` on the native Metal backend, so OIT keeps early-Z / HSR? It needs a visual check of OIT, because the original Apple-GPU issue that motivated it is not documented in source.
4. Choose a definitive `minUniformOffsetAlignment` for Apple GPUs: 16 or 256. This affects the transient UBO allocator (buffers doc).
5. Fan emulation: CPU expansion in the backend, or a game patch? Is the sequential index buffer always CPU-visible in the chosen buffer storage design?
6. Metal 4 flexible pipelines: how many of the 175 unique states collapse into specializations of the 125 programs, and is specialization measurably cheaper than full PSO creation on M5?
