/*
 * abi_test.m -- headless test of the frozen ABI and the architect-owned common infrastructure.
 * OWNER: architect. Built and run by tools/build-native-tests.sh (linked with every native/src .m file).
 *
 * Checks: ABI version, every struct size reported by mcmtl_abi_struct_size, mcmtl_probe, last-error
 * plumbing, the device-context basics, completion tracking (in-order, out-of-order beyond any window, CPU
 * completion that must not raise the event past outstanding values), mcmtl_wait_completed (timeout /
 * invalid / success), the mcmtl_submit guard (every error path consumes the signal value), the
 * debug-message ring and the depth-stencil cache.
 * Independent of I1/I2 progress: stubs are only checked to return a valid status code.
 */
#import "mcmetal_internal.h"

#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int failures = 0;

#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            failures++;                                                    \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);                    \
            printf(__VA_ARGS__);                                           \
            printf("\n");                                                  \
        }                                                                  \
    } while (0)

static void check_struct_sizes(void) {
    struct { uint32_t id; size_t size; const char *name; } table[] = {
        { MCMTL_STRUCT_PROBE_INFO, sizeof(mcmtl_probe_info), "probe_info" },
        { MCMTL_STRUCT_DEVICE_DESC, sizeof(mcmtl_device_desc), "device_desc" },
        { MCMTL_STRUCT_DEVICE_INFO, sizeof(mcmtl_device_info), "device_info" },
        { MCMTL_STRUCT_STATUS_PAGE, sizeof(mcmtl_status_page), "status_page" },
        { MCMTL_STRUCT_BUFFER_DESC, sizeof(mcmtl_buffer_desc), "buffer_desc" },
        { MCMTL_STRUCT_BUFFER_RESULT, sizeof(mcmtl_buffer_result), "buffer_result" },
        { MCMTL_STRUCT_TEXTURE_DESC, sizeof(mcmtl_texture_desc), "texture_desc" },
        { MCMTL_STRUCT_TEXTURE_VIEW_DESC, sizeof(mcmtl_texture_view_desc), "texture_view_desc" },
        { MCMTL_STRUCT_TEXEL_VIEW_DESC, sizeof(mcmtl_texel_view_desc), "texel_view_desc" },
        { MCMTL_STRUCT_SAMPLER_DESC, sizeof(mcmtl_sampler_desc), "sampler_desc" },
        { MCMTL_STRUCT_OBJECT_RESULT, sizeof(mcmtl_object_result), "object_result" },
        { MCMTL_STRUCT_SHADER_DESC, sizeof(mcmtl_shader_desc), "shader_desc" },
        { MCMTL_STRUCT_VERTEX_ATTRIBUTE, sizeof(mcmtl_vertex_attribute), "vertex_attribute" },
        { MCMTL_STRUCT_VERTEX_LAYOUT, sizeof(mcmtl_vertex_layout), "vertex_layout" },
        { MCMTL_STRUCT_COLOR_TARGET, sizeof(mcmtl_color_target), "color_target" },
        { MCMTL_STRUCT_PIPELINE_DESC, sizeof(mcmtl_pipeline_desc), "pipeline_desc" },
        { MCMTL_STRUCT_SURFACE_CONFIG, sizeof(mcmtl_surface_config), "surface_config" },
        { MCMTL_STRUCT_SUBMIT_INFO, sizeof(mcmtl_submit_info), "submit_info" },
        { MCMTL_STRUCT_SUBMIT_RESULT, sizeof(mcmtl_submit_result), "submit_result" },
        { MCMTL_STRUCT_CMD_HEADER, sizeof(mcmtl_cmd_header), "cmd_header" },
        { MCMTL_STRUCT_CMD_EMPTY, sizeof(mcmtl_cmd_empty), "cmd_empty" },
        { MCMTL_STRUCT_CMD_BEGIN_PASS, sizeof(mcmtl_cmd_begin_pass), "cmd_begin_pass" },
        { MCMTL_STRUCT_CMD_COLOR_ATTACHMENT, sizeof(mcmtl_cmd_color_attachment), "cmd_color_attachment" },
        { MCMTL_STRUCT_CMD_END_PASS, sizeof(mcmtl_cmd_end_pass), "cmd_end_pass" },
        { MCMTL_STRUCT_CMD_CLEAR_TEXTURE, sizeof(mcmtl_cmd_clear_texture), "cmd_clear_texture" },
        { MCMTL_STRUCT_CMD_CLEAR_REGION, sizeof(mcmtl_cmd_clear_region), "cmd_clear_region" },
        { MCMTL_STRUCT_CMD_COPY_BUFFER, sizeof(mcmtl_cmd_copy_buffer), "cmd_copy_buffer" },
        { MCMTL_STRUCT_CMD_COPY_BUFFER_TEXTURE, sizeof(mcmtl_cmd_copy_buffer_texture), "cmd_copy_buffer_texture" },
        { MCMTL_STRUCT_CMD_COPY_TEXTURE, sizeof(mcmtl_cmd_copy_texture), "cmd_copy_texture" },
        { MCMTL_STRUCT_CMD_PRESENT, sizeof(mcmtl_cmd_present), "cmd_present" },
        { MCMTL_STRUCT_CMD_INIT_TEXTURE, sizeof(mcmtl_cmd_init_texture), "cmd_init_texture" },
        { MCMTL_STRUCT_CMD_TIMESTAMP, sizeof(mcmtl_cmd_timestamp), "cmd_timestamp" },
        { MCMTL_STRUCT_CMD_PUSH_DEBUG_GROUP, sizeof(mcmtl_cmd_push_debug_group), "cmd_push_debug_group" },
        { MCMTL_STRUCT_CMD_SET_PIPELINE, sizeof(mcmtl_cmd_set_pipeline), "cmd_set_pipeline" },
        { MCMTL_STRUCT_CMD_SET_BUFFER, sizeof(mcmtl_cmd_set_buffer), "cmd_set_buffer" },
        { MCMTL_STRUCT_CMD_SET_TEXTURE, sizeof(mcmtl_cmd_set_texture), "cmd_set_texture" },
        { MCMTL_STRUCT_CMD_CLEAR_BINDING, sizeof(mcmtl_cmd_clear_binding), "cmd_clear_binding" },
        { MCMTL_STRUCT_CMD_SET_VERTEX_BUFFER, sizeof(mcmtl_cmd_set_vertex_buffer), "cmd_set_vertex_buffer" },
        { MCMTL_STRUCT_CMD_SET_INDEX_BUFFER, sizeof(mcmtl_cmd_set_index_buffer), "cmd_set_index_buffer" },
        { MCMTL_STRUCT_CMD_SET_SCISSOR, sizeof(mcmtl_cmd_set_scissor), "cmd_set_scissor" },
        { MCMTL_STRUCT_CMD_PUSH_CONSTANTS, sizeof(mcmtl_cmd_push_constants), "cmd_push_constants" },
        { MCMTL_STRUCT_CMD_DRAW, sizeof(mcmtl_cmd_draw), "cmd_draw" },
        { MCMTL_STRUCT_CMD_DRAW_INDEXED, sizeof(mcmtl_cmd_draw_indexed), "cmd_draw_indexed" },
        { MCMTL_STRUCT_CMD_DRAW_INDIRECT, sizeof(mcmtl_cmd_draw_indirect), "cmd_draw_indirect" },
        { MCMTL_STRUCT_CMD_UPSCALE, sizeof(mcmtl_cmd_upscale), "cmd_upscale" },
    };
    for (size_t i = 0; i < sizeof table / sizeof table[0]; i++) {
        uint32_t reported = mcmtl_abi_struct_size(table[i].id);
        CHECK(reported == table[i].size, "struct %s: abi_struct_size %u != sizeof %zu", table[i].name, reported, table[i].size);
    }
    CHECK(mcmtl_abi_struct_size(9999) == 0, "unknown struct id must report 0");
    printf("struct sizes: %zu checked\n", sizeof table / sizeof table[0]);
}

static void check_probe(void) {
    mcmtl_probe_info info;
    memset(&info, 0, sizeof info);
    info.structSize = 1;
    CHECK(mcmtl_probe(&info) == MCMTL_E_ABI_MISMATCH, "probe must reject a bad structSize");
    CHECK(strlen(mcmtl_last_error()) > 0, "last error must be set after a failure");
    info.structSize = sizeof info;
    int32_t rc = mcmtl_probe(&info);
    CHECK(rc == MCMTL_OK, "probe failed: %d %s", rc, mcmtl_last_error());
    CHECK(info.familyMask & MCMTL_FAMILY_APPLE7, "expected an Apple7+ GPU, mask 0x%x", info.familyMask);
    printf("probe: '%s' families=0x%x macOS %u.%u.%u unified=%u maxBuffer=%llu\n", info.deviceName, info.familyMask,
           info.osMajor, info.osMinor, info.osPatch, info.hasUnifiedMemory, (unsigned long long)info.maxBufferLength);
}

/* Commits an empty command buffer that signals `value` (what the decoder does on COMMIT). */
static void commit_signal(MCMTLDevice *dev, uint64_t value) {
    @autoreleasepool {
        id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
        [cb encodeSignalEvent:dev->event value:value];
        mcmtl_track_command_buffer(dev, cb, value);
        [cb commit];
        mcmtl_status_raise_u64(&dev->status->submittedValue, value);
    }
}

/* mcmtl_wait_completed may return as soon as the GPU executed the signal; the completion handlers that
 * advance the contiguous prefix run shortly after. Polls the prefix (bounded) for checks that need it. */
static BOOL wait_prefix(MCMTLDevice *dev, uint64_t value) {
    for (int i = 0; i < 20000; i++) {
        if (mcmtl_status_load_u64(&dev->status->completedValue) >= value) return YES;
        usleep(100);
    }
    return NO;
}

static void check_timeline(void) {
    MCMTLDevice *dev = mcmtl_device_context_create(MTLCreateSystemDefaultDevice(), @"abi_test");
    CHECK(dev != nil, "device context: %s", mcmtl_last_error());
    if (!dev) return;
    mcmtl_handle handle = (mcmtl_handle)(uintptr_t)(__bridge void *)dev;   /* borrowed: no +1 */

    CHECK(mcmtl_timeline_completed(handle) == 0, "fresh timeline must be 0");
    CHECK(mcmtl_wait_completed(dev, 1, 1000000) == MCMTL_E_INVALID_ARGUMENT, "waiting for an uncommitted value must fail fast");

    commit_signal(dev, 1);
    commit_signal(dev, 2);
    int32_t rc = mcmtl_wait_completed(dev, 2, 2000000000ull);
    CHECK(rc == MCMTL_OK, "wait(2) -> %d %s", rc, mcmtl_last_error());
    CHECK(wait_prefix(dev, 2), "completion handlers must advance the prefix to 2");
    CHECK(mcmtl_timeline_completed(handle) >= 2, "completed %llu", (unsigned long long)mcmtl_timeline_completed(handle));
    CHECK(mcmtl_timeline_wait(handle, 2, 0) == MCMTL_OK, "timeline_wait(2, 0) after completion");

    /* Out-of-order completion bookkeeping: 4 completes before 3 -> prefix stays at 2, then jumps to 4. */
    mcmtl_status_raise_u64(&dev->status->submittedValue, 4);
    mcmtl_complete_value_on_cpu(dev, 4);
    CHECK(mcmtl_status_load_u64(&dev->status->completedValue) == 2, "value 4 alone must not advance the prefix (got %llu)",
          (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue));
    mcmtl_complete_value_on_cpu(dev, 3);
    CHECK(mcmtl_status_load_u64(&dev->status->completedValue) == 4, "prefix must reach 4 (got %llu)",
          (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue));

    /* Timeout path: value 5 committed but never signaled. */
    mcmtl_status_raise_u64(&dev->status->submittedValue, 5);
    rc = mcmtl_wait_completed(dev, 5, 20000000ull);
    CHECK(rc == MCMTL_E_TIMEOUT, "wait(5, 20 ms) -> %d", rc);
    mcmtl_complete_value_on_cpu(dev, 5);
    CHECK(mcmtl_wait_completed(dev, 5, MCMTL_WAIT_FOREVER) == MCMTL_OK, "wait(5) after CPU completion");

    /* Real GPU commit after CPU-completed values keeps the dense sequence. */
    commit_signal(dev, 6);
    CHECK(mcmtl_wait_completed(dev, 6, 2000000000ull) == MCMTL_OK, "wait(6): %s", mcmtl_last_error());
    CHECK(wait_prefix(dev, 6), "prefix must reach 6");

    /* CPU completion of 8 while 7 is outstanding must not make 7 look signaled (event <= prefix). */
    mcmtl_status_raise_u64(&dev->status->submittedValue, 8);
    mcmtl_complete_value_on_cpu(dev, 8);
    CHECK(dev->event.signaledValue < 7, "event raised to %llu past outstanding value 7",
          (unsigned long long)dev->event.signaledValue);
    rc = mcmtl_wait_completed(dev, 7, 20000000ull);
    CHECK(rc == MCMTL_E_TIMEOUT, "wait(7) must time out while 7 is outstanding (got %d)", rc);
    mcmtl_complete_value_on_cpu(dev, 7);
    CHECK(mcmtl_status_load_u64(&dev->status->completedValue) == 8 && dev->event.signaledValue >= 8,
          "prefix/event must reach 8 (completed %llu, event %llu)",
          (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue), (unsigned long long)dev->event.signaledValue);

    /* No window limit: 300 values completing in reverse order never advance the prefix early. */
    const uint64_t base = 8, span = 300;
    mcmtl_status_raise_u64(&dev->status->submittedValue, base + span);
    for (uint64_t v = base + span; v >= base + 2; v--) mcmtl_complete_value_on_cpu(dev, v);
    CHECK(mcmtl_status_load_u64(&dev->status->completedValue) == base, "prefix moved to %llu with %llu outstanding",
          (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue), (unsigned long long)(base + 1));
    CHECK(dev->event.signaledValue <= base, "event %llu ran ahead of the prefix", (unsigned long long)dev->event.signaledValue);
    mcmtl_complete_value_on_cpu(dev, base + 1);
    CHECK(mcmtl_status_load_u64(&dev->status->completedValue) == base + span, "prefix must reach %llu (got %llu)",
          (unsigned long long)(base + span), (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue));
    commit_signal(dev, base + span + 1);
    CHECK(mcmtl_wait_completed(dev, base + span + 1, 2000000000ull) == MCMTL_OK, "GPU commit after the reverse run");

    /* Message ring. */
    for (int i = 0; i < (int)MCMTL_MAX_DEBUG_MESSAGES + 3; i++) mcmtl_device_message(dev, "message %d\nline", i);
    char buffer[8192];
    uint32_t length = 0;
    CHECK(mcmtl_device_copy_messages(handle, buffer, sizeof buffer, &length) == MCMTL_OK, "copy_messages");
    CHECK(length > 0 && strstr(buffer, "message 66 line") != NULL && strstr(buffer, "message 2 line") == NULL,
          "ring must keep the newest %u messages", MCMTL_MAX_DEBUG_MESSAGES);
    CHECK(dev->status->messageCount == MCMTL_MAX_DEBUG_MESSAGES + 3, "messageCount %u", dev->status->messageCount);
    char tiny[8];
    CHECK(mcmtl_device_copy_messages(handle, tiny, sizeof tiny, &length) == MCMTL_OK && length == 7 && tiny[7] == '\0',
          "truncation to capacity");

    /* Depth-stencil cache. */
    id<MTLDepthStencilState> a = mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionGreaterEqual, YES);
    id<MTLDepthStencilState> b = mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionGreaterEqual, YES);
    id<MTLDepthStencilState> c = mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionAlways, NO);
    CHECK(a != nil && a == b && a != c, "depth-stencil cache");
    printf("timeline/messages/cache: completed=%llu messages=%u\n",
           (unsigned long long)mcmtl_timeline_completed(handle), dev->status->messageCount);
}

/* mcmtl_submit guard: every COMMIT path consumes signalValue exactly once (CONTRACT.md 4.2). Works with
 * the I2 stub (MCMTL_E_UNIMPLEMENTED -> CPU completion) and with the real decoder (empty streams commit). */
static void check_submit_guard(void) {
    MCMTLDevice *dev = mcmtl_device_context_create(MTLCreateSystemDefaultDevice(), @"abi_test guard");
    CHECK(dev != nil, "device context: %s", mcmtl_last_error());
    if (!dev) return;
    CHECK(mcmtl_decoder_device_init(dev), "decoder init: %s", mcmtl_last_error());
    mcmtl_handle handle = (mcmtl_handle)(uintptr_t)(__bridge void *)dev;
    const uint64_t second = 2000000000ull;

    mcmtl_submit_info info = { .structSize = sizeof info, .flags = MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, .signalValue = 1 };
    mcmtl_submit_result result = { .structSize = sizeof result };
    int32_t rc = mcmtl_submit(handle, &info, &result);
    BOOL committed = (result.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED) != 0;
    CHECK((rc == MCMTL_OK && committed) || (rc != MCMTL_OK && !committed), "empty COMMIT: rc %d, COMMITTED %d", rc, committed);
    CHECK(mcmtl_wait_completed(dev, 1, second) == MCMTL_OK, "value 1 consumed (rc %d: %s)", rc, mcmtl_last_error());
    CHECK(dev->currentCommandBuffer == nil, "no open command buffer after COMMIT");
    printf("submit guard: empty COMMIT -> %d (%s)\n", rc, rc == MCMTL_OK ? "decoder committed" : "stub, CPU-completed");

    struct { const char *what; int32_t expected; } cases[] = {
        { "result structSize 1", MCMTL_E_ABI_MISMATCH },
        { "NULL result", MCMTL_E_INVALID_ARGUMENT },
        { "info structSize 8", MCMTL_E_ABI_MISMATCH },
        { "ASYNC flag", MCMTL_E_UNSUPPORTED },
        { "unknown flag", MCMTL_E_INVALID_ARGUMENT },
        { "stream length 8", MCMTL_E_BAD_STREAM },
    };
    static const uint8_t junk[16] = {0};
    for (uint32_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        uint64_t value = 2 + i;
        mcmtl_submit_info bad = info;
        mcmtl_submit_result res = { .structSize = sizeof res };
        mcmtl_submit_result *resPtr = &res;
        bad.signalValue = value;
        switch (i) {
            case 0: res.structSize = 1; break;
            case 1: resPtr = NULL; break;
            case 2: bad.structSize = 8; break;
            case 3: bad.flags |= MCMTL_SUBMIT_FLAG_ASYNC; break;
            case 4: bad.flags |= 1u << 7; break;
            case 5: bad.stream = junk; bad.streamLength = 8; break;
        }
        rc = mcmtl_submit(handle, &bad, resPtr);
        CHECK(rc == cases[i].expected, "%s: rc %d, expected %d", cases[i].what, rc, cases[i].expected);
        CHECK(mcmtl_status_load_u64(&dev->status->submittedValue) >= value, "%s: value %llu not consumed", cases[i].what,
              (unsigned long long)value);
        CHECK(mcmtl_wait_completed(dev, value, second) == MCMTL_OK, "%s: wait(%llu)", cases[i].what, (unsigned long long)value);
        CHECK(!(res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "%s: COMMITTED must not be set", cases[i].what);
    }
    const uint64_t last = 1 + sizeof cases / sizeof cases[0];

    /* Divergence: a reused value is rejected without touching the timeline. */
    mcmtl_submit_info reuse = info;
    reuse.signalValue = last;
    CHECK(mcmtl_submit(handle, &reuse, &result) == MCMTL_E_INVALID_ARGUMENT, "reused signal value must be rejected");
    CHECK(mcmtl_status_load_u64(&dev->status->submittedValue) == last, "reuse changed submittedValue");

    /* NULL device / info: nothing can be consumed. */
    CHECK(mcmtl_submit(0, &info, &result) == MCMTL_E_INVALID_ARGUMENT, "NULL device");
    CHECK(mcmtl_submit(handle, NULL, &result) == MCMTL_E_INVALID_ARGUMENT, "NULL info");

    /* Non-COMMIT submit leaves the timeline alone. */
    mcmtl_submit_info open = { .structSize = sizeof open, .flags = 0, .signalValue = 0 };
    rc = mcmtl_submit(handle, &open, &result);
    CHECK(mcmtl_status_load_u64(&dev->status->submittedValue) == last, "non-COMMIT submit changed submittedValue (rc %d)", rc);

    /* Skipped value: rejected and CPU-completed, but the gap keeps the prefix where it was. */
    mcmtl_submit_info skip = info;
    skip.signalValue = last + 3;
    CHECK(mcmtl_submit(handle, &skip, &result) == MCMTL_E_INVALID_ARGUMENT, "skipped signal value must be rejected");
    CHECK(mcmtl_status_load_u64(&dev->status->completedValue) == last, "gap must hold the prefix at %llu (got %llu)",
          (unsigned long long)last, (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue));
    CHECK(dev->currentCommandBuffer == nil, "no open command buffer after errors");
    printf("submit guard: %zu error paths consumed their values, completed=%llu\n", sizeof cases / sizeof cases[0],
           (unsigned long long)mcmtl_status_load_u64(&dev->status->completedValue));
    mcmtl_decoder_device_shutdown(dev);
}

static void check_stubs_return_status(void) {
    mcmtl_device_desc desc = { .structSize = sizeof desc, .framesInFlight = 2 };
    mcmtl_handle device = 0;
    int32_t rc = mcmtl_device_create(&desc, &device);
    CHECK(rc == MCMTL_OK || rc == MCMTL_E_UNIMPLEMENTED, "device_create returned %d", rc);
    printf("mcmtl_device_create -> %d (%s)\n", rc, rc == MCMTL_OK ? "implemented" : mcmtl_last_error());
    if (rc == MCMTL_OK && device) {
        CHECK(mcmtl_device_destroy(device) == MCMTL_OK, "device_destroy: %s", mcmtl_last_error());
    }
}

int main(void) {
    @autoreleasepool {
        CHECK(mcmtl_abi_version() == MCMTL_ABI_VERSION, "abi version %u", mcmtl_abi_version());
        CHECK(mcmtl_last_error() != NULL, "last_error never NULL");
        check_struct_sizes();
        check_probe();
        check_timeline();
        check_submit_guard();
        check_stubs_return_status();
    }
    printf("%s (%d failure%s)\n", failures ? "abi_test FAILED" : "abi_test PASSED", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
