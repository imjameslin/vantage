// Night Shift helper for benchmarking (Night Shift's display color transform forces WindowServer to
// composite fullscreen games, which caps/paces presentation).
//
//   nightshift status
//   nightshift off <statefile>      save the current state to <statefile>, then turn Night Shift off
//   nightshift restore <statefile>  restore exactly the saved mode, schedule and enabled flag
//
// Build: clang -fobjc-arc -framework Foundation tools/nightshift.m -o build/nightshift
#import <Foundation/Foundation.h>
#import <dlfcn.h>
#import <objc/message.h>

typedef struct { int hour; int minute; } NSTime;
typedef struct { NSTime from; NSTime to; } NSSchedule;
typedef struct {
    BOOL active;
    BOOL enabled;
    BOOL sunSchedulePermitted;
    int mode;               // 0 off, 1 sunset->sunrise, 2 custom schedule
    NSSchedule schedule;
    unsigned long long disableFlags;
    BOOL available;
} NSStatus;

static id client(void) {
    dlopen("/System/Library/PrivateFrameworks/CoreBrightness.framework/CoreBrightness", RTLD_NOW);
    return [NSClassFromString(@"CBBlueLightClient") new];
}

static NSStatus status(id c) {
    NSStatus s = {0};
    ((BOOL (*)(id, SEL, NSStatus *))objc_msgSend)(c, @selector(getBlueLightStatus:), &s);
    return s;
}

static void print(NSStatus s) {
    printf("active=%d enabled=%d mode=%d schedule=%02d:%02d-%02d:%02d\n", s.active, s.enabled, s.mode,
           s.schedule.from.hour, s.schedule.from.minute, s.schedule.to.hour, s.schedule.to.minute);
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        id c = client();
        if (!c) { fprintf(stderr, "CBBlueLightClient unavailable\n"); return 2; }
        NSString *cmd = argc > 1 ? @(argv[1]) : @"status";
        NSStatus s = status(c);
        if ([cmd isEqualToString:@"status"]) { print(s); return 0; }
        if ([cmd isEqualToString:@"deactivate"]) {   /* setActive:NO only; saves nothing */
            ((BOOL (*)(id, SEL, BOOL))objc_msgSend)(c, @selector(setActive:), NO);
            [NSThread sleepForTimeInterval:1.0];
            printf("now: "); print(status(c));
            return 0;
        }
        if (argc < 3) { fprintf(stderr, "missing state file\n"); return 2; }
        NSString *file = @(argv[2]);
        if ([cmd isEqualToString:@"off"]) {
            NSDictionary *saved = @{ @"active": @(s.active), @"enabled": @(s.enabled), @"mode": @(s.mode),
                                     @"fromH": @(s.schedule.from.hour), @"fromM": @(s.schedule.from.minute),
                                     @"toH": @(s.schedule.to.hour), @"toM": @(s.schedule.to.minute) };
            if (![saved writeToFile:file atomically:YES]) { fprintf(stderr, "cannot write %s\n", argv[2]); return 1; }
            printf("saved: "); print(s);
            ((BOOL (*)(id, SEL, BOOL))objc_msgSend)(c, @selector(setEnabled:), NO);
            ((BOOL (*)(id, SEL, int))objc_msgSend)(c, @selector(setMode:), 0);
            ((BOOL (*)(id, SEL, BOOL))objc_msgSend)(c, @selector(setActive:), NO);
            [NSThread sleepForTimeInterval:1.0];
            printf("now:   "); print(status(c));
            return 0;
        }
        if ([cmd isEqualToString:@"restore"]) {
            NSDictionary *saved = [NSDictionary dictionaryWithContentsOfFile:file];
            if (!saved) { fprintf(stderr, "cannot read %s\n", argv[2]); return 1; }
            NSSchedule sch = { { [saved[@"fromH"] intValue], [saved[@"fromM"] intValue] },
                               { [saved[@"toH"] intValue], [saved[@"toM"] intValue] } };
            int mode = [saved[@"mode"] intValue];
            if (mode == 2) ((BOOL (*)(id, SEL, NSSchedule *))objc_msgSend)(c, @selector(setSchedule:), &sch);
            ((BOOL (*)(id, SEL, int))objc_msgSend)(c, @selector(setMode:), mode);
            ((BOOL (*)(id, SEL, BOOL))objc_msgSend)(c, @selector(setEnabled:), [saved[@"enabled"] boolValue]);
            if ([saved[@"active"] boolValue]) ((BOOL (*)(id, SEL, BOOL))objc_msgSend)(c, @selector(setActive:), YES);
            [NSThread sleepForTimeInterval:1.0];
            printf("restored: "); print(status(c));
            return 0;
        }
        fprintf(stderr, "unknown command\n");
        return 2;
    }
}
