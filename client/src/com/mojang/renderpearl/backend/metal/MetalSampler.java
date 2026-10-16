package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link GpuSampler} (spec 2.2, 2.11). OWNER: I3 (java-device).
 *
 * <p>Native {@code MTLSamplerState}s are deduplicated by key with refcounts (spec 2.2); every
 * {@code createSampler} still returns a distinct {@code MetalSampler} whose getters return the creation
 * parameters and whose {@code close()} drops its reference (deferred native release when the count
 * reaches 0; the terrain sampler is closed and recreated mid-frame while still bound in recorded work).
 * The key is the Metal descriptor actually built (address modes, filters, mip filter, anisotropy, LOD clamp),
 * so RenderPearl parameter combinations that map to the same Metal state share it.
 *
 * <p>Cross-module API: I4 writes {@link #handle()} into SET_TEXTURE records.
 */
public final class MetalSampler implements GpuSampler {
    private final long handle;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    /** Shared native state this sampler references; null for samplers built with the public constructor (tests). */
    private final Cache.@Nullable State state;
    private boolean closed;

    /** Wraps {@code handle} without owning it (headless tests); {@link #close()} only marks the sampler closed. */
    public MetalSampler(
        final long handle,
        final AddressMode addressModeU,
        final AddressMode addressModeV,
        final FilterMode minFilter,
        final FilterMode magFilter,
        final int maxAnisotropy,
        final OptionalDouble maxLod
    ) {
        this(handle, null, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    private MetalSampler(
        final long handle,
        final Cache.@Nullable State state,
        final AddressMode addressModeU,
        final AddressMode addressModeV,
        final FilterMode minFilter,
        final FilterMode magFilter,
        final int maxAnisotropy,
        final OptionalDouble maxLod
    ) {
        this.handle = handle;
        this.state = state;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    /**
     * mcmtl_sampler_create (or a cached state for the same key): s/t address from {@link MetalFormats#addressMode},
     * r = Repeat, min/mag {@link MetalFormats#minMagFilter}, mip {@link MetalFormats#mipFilter(OptionalDouble)},
     * lodMinClamp 0, lodMaxClamp {@link MetalFormats#lodMaxClamp(OptionalDouble)}, maxAnisotropy, compare Never,
     * flags SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS.
     */
    public static MetalSampler create(
        final MetalDevice device,
        final AddressMode addressModeU,
        final AddressMode addressModeV,
        final FilterMode minFilter,
        final FilterMode magFilter,
        final int maxAnisotropy,
        final OptionalDouble maxLod
    ) {
        if (maxAnisotropy < 1 || maxAnisotropy > 16) {
            throw new IllegalArgumentException("maxAnisotropy must be in 1..16, was " + maxAnisotropy);
        }
        int sAddress = MetalFormats.addressMode(addressModeU);
        int tAddress = MetalFormats.addressMode(addressModeV);
        int min = MetalFormats.minMagFilter(minFilter);
        int mag = MetalFormats.minMagFilter(magFilter);
        int mip = MetalFormats.mipFilter(maxLod);
        float lodMax = MetalFormats.lodMaxClamp(maxLod);
        Cache.State state = device.samplerCache().acquire(sAddress, tAddress, min, mag, mip, maxAnisotropy, lodMax);
        return new MetalSampler(state.handle(), state, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    /** Native sampler-state handle written into SET_TEXTURE records. */
    public long handle() {
        return this.handle;
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    /** Idempotent; drops the reference on the shared native state (deferred release at 0). */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.state != null) {
                this.state.release();
            }
        }
    }

    /**
     * Device-owned cache of native sampler states keyed by the Metal descriptor (render thread). A state is
     * removed from the map and its release deferred when the last sampler using it closes; a later request
     * for the same key creates a new state (the old one may still be bound in recorded work).
     */
    static final class Cache {
        private final MetalDevice device;
        private final Long2ObjectOpenHashMap<State> states = new Long2ObjectOpenHashMap<>();
        private boolean closed;

        Cache(final MetalDevice device) {
            this.device = device;
        }

        /** Packs the Metal descriptor into a key: 4+4+1+1+2+5 bits + the float bits of lodMaxClamp. */
        static long key(final int sAddress, final int tAddress, final int min, final int mag, final int mip, final int maxAnisotropy, final float lodMax) {
            long bits = (sAddress & 0xF) | (tAddress & 0xF) << 4 | (min & 0x1) << 8 | (mag & 0x1) << 9 | (mip & 0x3) << 10 | (maxAnisotropy & 0x1F) << 12;
            return bits | (long)Float.floatToIntBits(lodMax) << 32;
        }

        State acquire(final int sAddress, final int tAddress, final int min, final int mag, final int mip, final int maxAnisotropy, final float lodMax) {
            if (this.closed) {
                throw new IllegalStateException("Metal device is closed");
            }
            long key = key(sAddress, tAddress, min, mag, mip, maxAnisotropy, lodMax);
            State state = this.states.get(key);
            if (state == null) {
                long handle = this.createNative(sAddress, tAddress, min, mag, mip, maxAnisotropy, lodMax);
                state = new State(this, key, handle);
                this.states.put(key, state);
            }
            state.references++;
            return state;
        }

        private long createNative(final int sAddress, final int tAddress, final int min, final int mag, final int mip, final int maxAnisotropy, final float lodMax) {
            if (this.device.handle() == 0L) {
                return MetalBuffer.Headless.nextHandle();
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long desc = stack.ncalloc(8, 1, (int)MtlNative.SAMPLER_DESC_SIZEOF);
                long out = stack.ncalloc(8, 1, (int)MtlNative.OBJECT_RESULT_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_STRUCT_SIZE, (int)MtlNative.SAMPLER_DESC_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_S_ADDRESS_MODE, sAddress);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_T_ADDRESS_MODE, tAddress);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_R_ADDRESS_MODE, MetalFormats.SAMPLER_ADDRESS_REPEAT);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_MIN_FILTER, min);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_MAG_FILTER, mag);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_MIP_FILTER, mip);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_MAX_ANISOTROPY, maxAnisotropy);
                MemoryUtil.memPutFloat(desc + MtlNative.SAMPLER_DESC_LOD_MIN_CLAMP, 0.0F);
                MemoryUtil.memPutFloat(desc + MtlNative.SAMPLER_DESC_LOD_MAX_CLAMP, lodMax);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_COMPARE_FUNCTION, MetalFormats.COMPARE_NEVER);
                MemoryUtil.memPutInt(desc + MtlNative.SAMPLER_DESC_FLAGS, MtlNative.SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS);
                MemoryUtil.memPutInt(out + MtlNative.OBJECT_RESULT_STRUCT_SIZE, (int)MtlNative.OBJECT_RESULT_SIZEOF);
                MtlNative.check(MtlNative.samplerCreate(this.device.handle(), desc, out), "mcmtl_sampler_create");
                long handle = MemoryUtil.memGetLong(out + MtlNative.OBJECT_RESULT_HANDLE);
                if (handle == 0L) {
                    throw new IllegalStateException("mcmtl_sampler_create returned no handle");
                }
                return handle;
            }
        }

        /** Number of distinct live native states (tests). */
        int size() {
            return this.states.size();
        }

        /** Device shutdown (GPU idle): releases every live state; samplers closed later do nothing natively. */
        void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            for (State state : this.states.values()) {
                state.released = true;
                this.device.timeline().deferRelease(state.handle);
            }
            this.states.clear();
        }

        /** One native sampler state shared by every open MetalSampler with the same key. */
        static final class State {
            private final Cache cache;
            private final long key;
            private final long handle;
            private int references;
            private boolean released;

            State(final Cache cache, final long key, final long handle) {
                this.cache = cache;
                this.key = key;
                this.handle = handle;
            }

            long handle() {
                return this.handle;
            }

            int references() {
                return this.references;
            }

            void release() {
                if (this.released) {
                    return;
                }
                if (--this.references == 0) {
                    this.released = true;
                    if (this.cache.states.get(this.key) == this) {
                        this.cache.states.remove(this.key);
                    }
                    this.cache.device.timeline().deferRelease(this.handle);
                }
            }
        }
    }
}
