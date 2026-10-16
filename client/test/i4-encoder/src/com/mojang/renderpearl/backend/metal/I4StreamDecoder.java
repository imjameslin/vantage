package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SequenceLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * I4 test support: an independent Java decoder of recorded command streams. It reads every field through the
 * {@link StreamOps.Layouts} struct layouts (with the field's own width, so an int written into a long field or
 * a truncated handle shows up) and validates the structural rules of CONTRACT.md 4.1/4.2 on every record:
 * 16-byte alignment, size rules per op, zero padding, zero reserved fields, pass nesting, init-stream ops.
 */
final class I4StreamDecoder {
    private I4StreamDecoder() {
    }

    /** A decoded record (or a sub-struct of one, e.g. a BEGIN_PASS color attachment). */
    static final class Rec {
        final MemorySegment seg;
        final long base;
        final StructLayout layout;
        final int op;
        final int size;
        final int arg;
        final long offset;

        Rec(final MemorySegment seg, final long base, final StructLayout layout, final int op, final int size, final int arg) {
            this.seg = seg;
            this.base = base;
            this.layout = layout;
            this.op = op;
            this.size = size;
            this.arg = arg;
            this.offset = base;
        }

        private long at(final String field) {
            return this.base + this.layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
        }

        private MemoryLayout member(final String field) {
            return this.layout.select(MemoryLayout.PathElement.groupElement(field));
        }

        int i(final String field) {
            MemoryLayout m = this.member(field);
            if (!(m instanceof ValueLayout.OfInt)) {
                throw new AssertionError(this.layout.name().orElse("?") + "." + field + " is not a 32-bit int field: " + m);
            }
            return this.seg.get(JAVA_INT_UNALIGNED, this.at(field));
        }

        long l(final String field) {
            MemoryLayout m = this.member(field);
            if (!(m instanceof ValueLayout.OfLong)) {
                throw new AssertionError(this.layout.name().orElse("?") + "." + field + " is not a 64-bit field: " + m);
            }
            return this.seg.get(JAVA_LONG_UNALIGNED, this.at(field));
        }

        float f(final String field) {
            MemoryLayout m = this.member(field);
            if (!(m instanceof ValueLayout.OfFloat)) {
                throw new AssertionError(this.layout.name().orElse("?") + "." + field + " is not a float field: " + m);
            }
            return this.seg.get(JAVA_FLOAT_UNALIGNED, this.at(field));
        }

        float[] fa(final String field) {
            MemoryLayout m = this.member(field);
            if (!(m instanceof SequenceLayout s) || !(s.elementLayout() instanceof ValueLayout.OfFloat)) {
                throw new AssertionError(this.layout.name().orElse("?") + "." + field + " is not a float array: " + m);
            }
            float[] out = new float[(int)s.elementCount()];
            long start = this.at(field);
            for (int k = 0; k < out.length; k++) {
                out[k] = this.seg.get(JAVA_FLOAT_UNALIGNED, start + 4L * k);
            }
            return out;
        }

        /** BEGIN_PASS color attachment {@code index}. */
        Rec attachment(final int index) {
            if (this.op != StreamOps.OP_BEGIN_PASS) {
                throw new AssertionError("not a BEGIN_PASS");
            }
            return new Rec(this.seg, this.base + StreamOps.BEGIN_PASS_SIZEOF + (long)index * StreamOps.COLOR_ATTACHMENT_SIZEOF,
                StreamOps.Layouts.COLOR_ATTACHMENT, -1, StreamOps.SIZE_COLOR_ATTACHMENT, 0);
        }

        /** Inline payload bytes (labels, push constants) starting at {@code from} for {@code n} bytes. */
        byte[] payload(final long from, final int n) {
            byte[] out = new byte[n];
            MemorySegment.copy(this.seg, java.lang.foreign.ValueLayout.JAVA_BYTE, this.base + from, out, 0, n);
            return out;
        }

        @Override
        public String toString() {
            return opName(this.op) + "@" + this.base + "(size " + this.size + ", arg " + this.arg + ")";
        }
    }

    /** Snapshot of a live writer's bytes (decoding never touches the writer). */
    static byte[] snapshot(final StreamWriter writer) {
        int n = (int)writer.size();
        byte[] out = new byte[n];
        if (n > 0) {
            MemorySegment.copy(MemorySegment.ofAddress(writer.address()).reinterpret(n), java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, out, 0, n);
        }
        return out;
    }

    static byte[] copyOf(final long address, final long length) {
        byte[] out = new byte[(int)length];
        if (length > 0) {
            MemorySegment.copy(MemorySegment.ofAddress(address).reinterpret(length), java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, out, 0, (int)length);
        }
        return out;
    }

    static List<Rec> parse(final StreamWriter writer, final boolean initStream) {
        return parse(snapshot(writer), initStream);
    }

    /** Decodes and validates a whole stream; throws AssertionError describing the first violation. */
    static List<Rec> parse(final byte[] bytes, final boolean initStream) {
        MemorySegment seg = MemorySegment.ofArray(bytes);
        List<Rec> out = new ArrayList<>();
        long off = 0;
        boolean inPass = false;
        if (bytes.length % 16 != 0) {
            throw new AssertionError("stream length " + bytes.length + " is not a multiple of 16");
        }
        while (off < bytes.length) {
            int op = seg.get(JAVA_SHORT_UNALIGNED, off) & 0xFFFF;
            int size = seg.get(JAVA_SHORT_UNALIGNED, off + 2) & 0xFFFF;
            int arg = seg.get(JAVA_INT_UNALIGNED, off + 4);
            String where = opName(op) + " at offset " + off;
            if (off % 16 != 0) {
                throw new AssertionError(where + ": record not 16-byte aligned");
            }
            if (size % 16 != 0 || size < 16 || size > StreamOps.STREAM_MAX_RECORD || off + size > bytes.length) {
                throw new AssertionError(where + ": bad size " + size);
            }
            StructLayout layout = layoutFor(op);
            if (layout == null || op == StreamOps.OP_UPSCALE) {
                throw new AssertionError(where + ": op " + op + " must never be recorded in M1");
            }
            if (initStream && !StreamOps.isInitStreamOp(op)) {
                throw new AssertionError(where + ": only INIT_TEXTURE/NOP are allowed in the init stream");
            }
            if (op == StreamOps.OP_BEGIN_PASS) {
                if (inPass) {
                    throw new AssertionError(where + ": BEGIN_PASS inside a pass");
                }
                inPass = true;
            } else if (op == StreamOps.OP_END_PASS) {
                if (!inPass) {
                    throw new AssertionError(where + ": END_PASS outside a pass");
                }
                inPass = false;
            } else if (StreamOps.isPassOp(op) && !inPass) {
                throw new AssertionError(where + ": pass op outside a pass");
            } else if (StreamOps.isEncoderOp(op) && inPass) {
                throw new AssertionError(where + ": encoder op inside a pass");
            }
            long defined;
            int expectedSize;
            switch (op) {
                case StreamOps.OP_BEGIN_PASS -> {
                    if (arg < 0 || arg > MtlNative.MAX_COLOR_ATTACHMENTS) {
                        throw new AssertionError(where + ": colorCount " + arg);
                    }
                    defined = StreamOps.SIZE_BEGIN_PASS_BASE + (long)StreamOps.SIZE_COLOR_ATTACHMENT * arg;
                    expectedSize = StreamOps.beginPassSize(arg);
                }
                case StreamOps.OP_PUSH_DEBUG_GROUP -> {
                    if (arg < 0 || arg > MtlNative.MAX_LABEL_BYTES) {
                        throw new AssertionError(where + ": labelLength " + arg);
                    }
                    defined = StreamOps.PUSH_DEBUG_GROUP_SIZEOF + arg;
                    expectedSize = StreamOps.pushDebugGroupSize(arg);
                    for (int k = 0; k < arg; k++) {
                        if (bytes[(int)(off + StreamOps.PUSH_DEBUG_GROUP_SIZEOF + k)] == 0) {
                            throw new AssertionError(where + ": NUL inside the label");
                        }
                    }
                }
                case StreamOps.OP_PUSH_CONSTANTS -> {
                    if (arg <= 0 || arg > MtlNative.MAX_PUSH_CONSTANT_BYTES) {
                        throw new AssertionError(where + ": byteLength " + arg);
                    }
                    defined = StreamOps.PUSH_CONSTANTS_SIZEOF + arg;
                    expectedSize = StreamOps.pushConstantsSize(arg);
                }
                case StreamOps.OP_NOP -> {
                    defined = StreamOps.EMPTY_SIZEOF;
                    expectedSize = size; // padding of any size
                }
                default -> {
                    defined = layout.byteSize();
                    expectedSize = (int)((layout.byteSize() + 15) & ~15L);
                }
            }
            if (size != expectedSize) {
                throw new AssertionError(where + ": size " + size + ", expected " + expectedSize);
            }
            checkReservedZero(seg, off, layout, where);
            if (op == StreamOps.OP_BEGIN_PASS) {
                for (int k = 0; k < arg; k++) {
                    checkReservedZero(seg, off + StreamOps.BEGIN_PASS_SIZEOF + (long)k * StreamOps.COLOR_ATTACHMENT_SIZEOF, StreamOps.Layouts.COLOR_ATTACHMENT,
                        where + " attachment " + k);
                }
            }
            for (long p = off + defined; p < off + size; p++) {
                if (bytes[(int)p] != 0) {
                    throw new AssertionError(where + ": padding byte " + (p - off) + " is " + bytes[(int)p]);
                }
            }
            Rec rec = new Rec(seg, off, layout, op, size, arg);
            checkSemantics(rec, where);
            out.add(rec);
            off += size;
        }
        if (inPass) {
            throw new AssertionError("stream ends inside a render pass");
        }
        return out;
    }

    private static void checkReservedZero(final MemorySegment seg, final long base, final StructLayout layout, final String where) {
        for (MemoryLayout m : layout.memberLayouts()) {
            String name = m.name().orElse("");
            if (name.startsWith("reserved")) {
                long at = base + layout.byteOffset(MemoryLayout.PathElement.groupElement(name));
                for (long k = 0; k < m.byteSize(); k++) {
                    if (seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, at + k) != 0) {
                        throw new AssertionError(where + ": reserved field " + name + " is not zero");
                    }
                }
            }
        }
    }

    private static void checkSemantics(final Rec r, final String where) {
        switch (r.op) {
            case StreamOps.OP_SET_BUFFER -> {
                if (r.i("stages") == 0 || (r.i("stages") & ~3) != 0) {
                    throw new AssertionError(where + ": stages " + r.i("stages"));
                }
                if (r.i("index") < 0 || r.i("index") > MtlNative.MAX_UNIFORM_BUFFER_INDEX) {
                    throw new AssertionError(where + ": buffer index " + r.i("index"));
                }
            }
            case StreamOps.OP_SET_TEXTURE -> {
                if (r.i("stages") == 0 || (r.i("stages") & ~3) != 0) {
                    throw new AssertionError(where + ": stages " + r.i("stages"));
                }
                if (r.i("index") < 0 || r.i("index") >= MtlNative.MAX_TEXTURE_BINDINGS) {
                    throw new AssertionError(where + ": texture index " + r.i("index"));
                }
            }
            case StreamOps.OP_CLEAR_BINDING -> {
                if (r.i("stages") == 0) {
                    throw new AssertionError(where + ": stages 0");
                }
                int kind = r.i("kind");
                if (kind != StreamOps.BINDING_KIND_BUFFER && kind != StreamOps.BINDING_KIND_TEXTURE) {
                    throw new AssertionError(where + ": kind " + kind);
                }
            }
            case StreamOps.OP_SET_VERTEX_BUFFER -> {
                if (r.i("slot") < 0 || r.i("slot") >= MtlNative.MAX_VERTEX_BUFFERS) {
                    throw new AssertionError(where + ": slot " + r.i("slot"));
                }
            }
            case StreamOps.OP_SET_INDEX_BUFFER -> {
                int t = r.i("indexType");
                if (t != MetalFormats.INDEX_TYPE_UINT16 && t != MetalFormats.INDEX_TYPE_UINT32) {
                    throw new AssertionError(where + ": index type " + t);
                }
            }
            case StreamOps.OP_INIT_TEXTURE -> {
                if (r.i("mode") != StreamOps.INIT_TEXTURE_ZERO || r.l("texture") == 0L) {
                    throw new AssertionError(where + ": bad INIT_TEXTURE");
                }
            }
            case StreamOps.OP_DRAW_INDIRECT, StreamOps.OP_DRAW_INDEXED_INDIRECT -> {
                if ((r.l("offset") & 3L) != 0L || r.i("drawCount") <= 0) {
                    throw new AssertionError(where + ": bad indirect draw");
                }
            }
            case StreamOps.OP_COPY_BUFFER_TO_TEXTURE, StreamOps.OP_COPY_TEXTURE_TO_BUFFER -> {
                if (r.i("width") <= 0 || r.i("height") <= 0 || r.l("buffer") == 0L || r.l("texture") == 0L) {
                    throw new AssertionError(where + ": empty or unbound buffer/texture copy");
                }
            }
            default -> {
            }
        }
    }

    /** Layout of an op's fixed part (null for unknown ops). */
    static StructLayout layoutFor(final int op) {
        return switch (op) {
            case StreamOps.OP_NOP, StreamOps.OP_POP_DEBUG_GROUP, StreamOps.OP_RESET_SCISSOR -> StreamOps.Layouts.EMPTY;
            case StreamOps.OP_BEGIN_PASS -> StreamOps.Layouts.BEGIN_PASS;
            case StreamOps.OP_END_PASS -> StreamOps.Layouts.END_PASS;
            case StreamOps.OP_CLEAR_TEXTURE -> StreamOps.Layouts.CLEAR_TEXTURE;
            case StreamOps.OP_CLEAR_REGION -> StreamOps.Layouts.CLEAR_REGION;
            case StreamOps.OP_COPY_BUFFER -> StreamOps.Layouts.COPY_BUFFER;
            case StreamOps.OP_COPY_BUFFER_TO_TEXTURE, StreamOps.OP_COPY_TEXTURE_TO_BUFFER -> StreamOps.Layouts.COPY_BUFFER_TEXTURE;
            case StreamOps.OP_COPY_TEXTURE -> StreamOps.Layouts.COPY_TEXTURE;
            case StreamOps.OP_PRESENT -> StreamOps.Layouts.PRESENT;
            case StreamOps.OP_INIT_TEXTURE -> StreamOps.Layouts.INIT_TEXTURE;
            case StreamOps.OP_UPSCALE -> StreamOps.Layouts.UPSCALE;
            case StreamOps.OP_TIMESTAMP -> StreamOps.Layouts.TIMESTAMP;
            case StreamOps.OP_PUSH_DEBUG_GROUP -> StreamOps.Layouts.PUSH_DEBUG_GROUP;
            case StreamOps.OP_SET_PIPELINE -> StreamOps.Layouts.SET_PIPELINE;
            case StreamOps.OP_SET_BUFFER -> StreamOps.Layouts.SET_BUFFER;
            case StreamOps.OP_SET_TEXTURE -> StreamOps.Layouts.SET_TEXTURE;
            case StreamOps.OP_CLEAR_BINDING -> StreamOps.Layouts.CLEAR_BINDING;
            case StreamOps.OP_SET_VERTEX_BUFFER -> StreamOps.Layouts.SET_VERTEX_BUFFER;
            case StreamOps.OP_SET_INDEX_BUFFER -> StreamOps.Layouts.SET_INDEX_BUFFER;
            case StreamOps.OP_SET_SCISSOR -> StreamOps.Layouts.SET_SCISSOR;
            case StreamOps.OP_PUSH_CONSTANTS -> StreamOps.Layouts.PUSH_CONSTANTS;
            case StreamOps.OP_DRAW -> StreamOps.Layouts.DRAW;
            case StreamOps.OP_DRAW_INDEXED -> StreamOps.Layouts.DRAW_INDEXED;
            case StreamOps.OP_DRAW_INDIRECT, StreamOps.OP_DRAW_INDEXED_INDIRECT -> StreamOps.Layouts.DRAW_INDIRECT;
            default -> null;
        };
    }

    static String opName(final int op) {
        for (java.lang.reflect.Field f : StreamOps.class.getFields()) {
            if (f.getName().startsWith("OP_") && f.getType() == int.class && !f.getName().equals("OP_LIMIT")) {
                try {
                    if (f.getInt(null) == op) {
                        return f.getName().substring(3);
                    }
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return "OP#" + op;
    }

    /** Op names of a record list (compact expectations in tests). */
    static List<String> ops(final List<Rec> records) {
        List<String> out = new ArrayList<>();
        for (Rec r : records) {
            out.add(opName(r.op));
        }
        return out;
    }

    /** Reads {@code n} bytes at an absolute address (test buffers / staging memory). */
    static byte[] read(final long address, final int n) {
        byte[] out = new byte[n];
        for (int k = 0; k < n; k++) {
            out[k] = MemoryUtil.memGetByte(address + k);
        }
        return out;
    }
}
