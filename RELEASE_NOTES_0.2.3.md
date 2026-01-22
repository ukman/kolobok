# Kolobok 0.2.3 Release Notes

## Highlights
- Verified Java 11+ compatibility (tested on 11, 17, 21, 25) and adjusted build targets.
- Added `@DebugLog` instrumentation that logs method entry/exit/exception timing using existing SLF4J logger fields.
- Added `@DebugLog` line heat map output with nested JSON, traceId, and optional duration logging.
- Added `@DebugLog` options for `logArgs`, `mask`, `maxArgLength`, `logLevel`, `logFormat`, `logThreadId`, and `logThreadName`.
- Expanded HUMAN heat map logs to include full nested details with indentation.
- Added Gradle support for `-Pkolobok.skip=true` to disable transformer.
- Added `@DebugLogIgnore` and `@DebugLogMask` for per-parameter and local-variable masking in error logs.
- Local-variable capture currently supports `int` and reference types.
- Added `logLocals` and `logLocalsOnException` options for `@DebugLog`.
- `logLocals`/`logLocalsOnException` now log all locals by default, unless ignored/masked.
- Updated samples and example dependencies to 0.2.3.

## Compatibility
- Java: 11+
