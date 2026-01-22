package org.kolobok.runtime;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

public final class LogContextTrace {
    private static final ThreadLocal<TraceState> TRACE = new ThreadLocal<>();

    private LogContextTrace() {
    }

    public static Object enter(String method, boolean subHeatMap, boolean aggregateChildren, boolean logArgs,
                               String mask, int maxArgLength, Object[] args) {
        TraceState state = TRACE.get();
        if (state == null) {
            state = new TraceState(resolveTraceId(), subHeatMap, aggregateChildren);
            TRACE.set(state);
        }
        TraceNode node = new TraceNode(method);
        node.arguments = sanitizeArgs(args, logArgs, mask, maxArgLength);
        if (!state.stack.isEmpty()) {
            state.stack.peek().children.add(node);
        }
        state.stack.push(node);
        return node;
    }

    public static Object enter(String method, boolean subHeatMap, boolean aggregateChildren, Object[] args) {
        return enter(method, subHeatMap, aggregateChildren, true, "", 200, args);
    }

    public static String exit(Object token, int[] lines, int[] counts, long durationNs, boolean logOnException, boolean isException) {
        TraceResult result = exitInternal(token, lines, counts, durationNs, logOnException, isException);
        if (result == null) {
            return null;
        }
        if (result.aggregateChildren) {
            aggregateNode(result.node);
        }
        return buildJson(result.traceId, result.node);
    }

    public static String exitFormatted(Object token, int[] lines, int[] counts, long durationNs,
                                       boolean logOnException, boolean isException, boolean jsonFormat,
                                       boolean logThreadId, boolean logThreadName) {
        TraceResult result = exitInternal(token, lines, counts, durationNs, logOnException, isException);
        if (result == null) {
            return null;
        }
        if (result.aggregateChildren) {
            aggregateNode(result.node);
        }
        if (jsonFormat) {
            return buildJson(result.traceId, result.node);
        }
        return buildHumanHeatMap(result.traceId, result.node, logThreadId, logThreadName);
    }

    private static String buildJson(String traceId, TraceNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"traceId\":\"").append(escapeJson(traceId)).append("\",");
        appendNodeJson(sb, node);
        sb.append('}');
        return sb.toString();
    }

    private static void appendNodeJson(StringBuilder sb, TraceNode node) {
        sb.append("\"method\":\"").append(escapeJson(node.method)).append("\",");
        sb.append("\"count\":").append(node.count).append(',');
        if (node.arguments != null) {
            sb.append("\"arguments\":[");
            for (int i = 0; i < node.arguments.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(toJsonValue(node.arguments[i]));
            }
            sb.append("],");
        }
        sb.append("\"lineHeatMap\":{");
        if (node.lineCounts != null && !node.lineCounts.isEmpty()) {
            sb.append(compressLineCounts(node.lineCounts));
        }
        sb.append("},");
        if (node.durationNs >= 0) {
            sb.append("\"durationNs\":").append(node.durationNs).append(',');
        }
        sb.append("\"children\":[");
        for (int i = 0; i < node.children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            appendNodeJson(sb, node.children.get(i));
            sb.append('}');
        }
        sb.append(']');
    }

    public static String currentTraceId() {
        TraceState state = TRACE.get();
        if (state != null) {
            return state.traceId;
        }
        return resolveFromMdc();
    }

    public static String formatTraceIdHuman() {
        String traceId = currentTraceId();
        if (traceId == null || traceId.isEmpty()) {
            return "";
        }
        return " trace=" + traceId;
    }

    public static String formatTraceIdJson() {
        String traceId = currentTraceId();
        if (traceId == null || traceId.isEmpty()) {
            return "";
        }
        return ",\"traceId\":\"" + escapeJson(traceId) + "\"";
    }

    private static String resolveTraceId() {
        String traceId = resolveFromMdc();
        return (traceId == null || traceId.isEmpty()) ? UUID.randomUUID().toString() : traceId;
    }

    private static String resolveFromMdc() {
        try {
            Class<?> mdcClass = Class.forName("org.slf4j.MDC");
            Method get = mdcClass.getMethod("get", String.class);
            Object value = get.invoke(null, "traceId");
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    public static String formatArgs(Object[] args, String mask, int maxArgLength) {
        Object[] sanitized = sanitizeArgs(args, true, mask, maxArgLength);
        if (sanitized == null) {
            return "[]";
        }
        return java.util.Arrays.deepToString(sanitized);
    }

    public static String formatArgsJson(Object[] args, String mask, int maxArgLength) {
        Object[] sanitized = sanitizeArgs(args, true, mask, maxArgLength);
        if (sanitized == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < sanitized.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toJsonValue(sanitized[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    public static String maskValue(Object value, int first, int last, int maxArgLength) {
        if (value == null) {
            return "null";
        }
        String raw = String.valueOf(value);
        String truncated = truncate(raw, maxArgLength > 0 ? maxArgLength : 200);
        if (first <= 0 && last <= 0) {
            return "***";
        }
        int length = truncated.length();
        int prefix = Math.max(0, first);
        int suffix = Math.max(0, last);
        if (prefix + suffix >= length) {
            return truncated;
        }
        String start = truncated.substring(0, prefix);
        String end = truncated.substring(length - suffix);
        return start + "***" + end;
    }

    public static String formatLocalsHuman(Object[] locals, String[] names, int[] ignoreModes,
                                           int[] maskFirst, int[] maskLast, boolean isException, int maxArgLength) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (names == null || locals == null) {
            return "";
        }
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name == null) {
                continue;
            }
            int mode = ignoreModes != null && i < ignoreModes.length ? ignoreModes[i] : 0;
            if (mode == 1) {
                continue;
            }
            if (mode == 2 && !isException) {
                continue;
            }
            Object value = i < locals.length ? locals[i] : null;
            String out;
            int firstCount = maskFirst != null && i < maskFirst.length ? maskFirst[i] : 0;
            int lastCount = maskLast != null && i < maskLast.length ? maskLast[i] : 0;
            if (firstCount > 0 || lastCount > 0) {
                out = maskValue(value, firstCount, lastCount, maxArgLength);
            } else {
                out = truncate(String.valueOf(value), maxArgLength > 0 ? maxArgLength : 200);
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(name).append('=').append(out);
        }
        return sb.toString();
    }

    public static String formatLocalsJson(Object[] locals, String[] names, int[] ignoreModes,
                                          int[] maskFirst, int[] maskLast, boolean isException, int maxArgLength) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (names == null || locals == null) {
            return "{}";
        }
        sb.append('{');
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (name == null) {
                continue;
            }
            int mode = ignoreModes != null && i < ignoreModes.length ? ignoreModes[i] : 0;
            if (mode == 1) {
                continue;
            }
            if (mode == 2 && !isException) {
                continue;
            }
            Object value = i < locals.length ? locals[i] : null;
            String out;
            int firstCount = maskFirst != null && i < maskFirst.length ? maskFirst[i] : 0;
            int lastCount = maskLast != null && i < maskLast.length ? maskLast[i] : 0;
            if (firstCount > 0 || lastCount > 0) {
                out = maskValue(value, firstCount, lastCount, maxArgLength);
            } else {
                out = truncate(String.valueOf(value), maxArgLength > 0 ? maxArgLength : 200);
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(name)).append("\":\"").append(escapeJson(out)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static final class TraceState {
        private final String traceId;
        private final boolean suppressedRoot;
        private final boolean aggregateChildren;
        private final Deque<TraceNode> stack = new ArrayDeque<>();

        private TraceState(String traceId, boolean suppressedRoot, boolean aggregateChildren) {
            this.traceId = traceId;
            this.suppressedRoot = suppressedRoot;
            this.aggregateChildren = aggregateChildren;
        }
    }

    private static final class TraceNode {
        private final String method;
        private final List<TraceNode> children = new ArrayList<>();
        private int count;
        private java.util.Map<Integer, Integer> lineCounts;
        private long durationNs = -1;
        private Object[] arguments;

        private TraceNode(String method) {
            this.method = method;
        }
    }

    private static java.util.Map<Integer, Integer> buildLineCounts(int[] lines, int[] counts) {
        if (lines == null || counts == null || lines.length != counts.length) {
            return java.util.Collections.emptyMap();
        }
        java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            int count = counts[i];
            if (count != 0) {
                map.put(lines[i], count);
            }
        }
        return map;
    }

    private static String compressLineCounts(java.util.Map<Integer, Integer> counts) {
        java.util.List<Integer> lines = new java.util.ArrayList<>(counts.keySet());
        java.util.Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < lines.size()) {
            int start = lines.get(i);
            int end = start;
            int value = counts.get(start);
            int j = i + 1;
            while (j < lines.size()) {
                int next = lines.get(j);
                if (next != end + 1 || counts.get(next) != value) {
                    break;
                }
                end = next;
                j++;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(start);
            if (end != start) {
                sb.append('-').append(end);
            }
            sb.append("\":").append(value);
            i = j;
        }
        return sb.toString();
    }

    private static String buildHumanHeatMap(String traceId, TraceNode node, boolean logThreadId, boolean logThreadName) {
        StringBuilder sb = new StringBuilder();
        appendHumanNode(sb, node, 0, traceId, logThreadId, logThreadName);
        return sb.toString();
    }

    private static void appendHumanNode(StringBuilder sb, TraceNode node, int depth, String traceId,
                                        boolean logThreadId, boolean logThreadName) {
        if (depth == 0) {
            sb.append("[KLB] HEATMAP ");
        } else {
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append("- ");
        }
        sb.append(node.method);
        if (depth == 0 && traceId != null && !traceId.isEmpty()) {
            sb.append(" trace=").append(traceId);
        }
        if (depth == 0 && logThreadId) {
            sb.append(" t=").append(Thread.currentThread().getId());
        }
        if (depth == 0 && logThreadName) {
            sb.append(" tn=").append(Thread.currentThread().getName());
        }
        sb.append(" count=").append(node.count);
        if (node.durationNs >= 0) {
            sb.append(" dur=").append(node.durationNs).append("ns");
        }
        if (node.arguments != null) {
            sb.append(" args=").append(java.util.Arrays.deepToString(node.arguments));
        }
        sb.append(" heatmap={");
        if (node.lineCounts != null && !node.lineCounts.isEmpty()) {
            sb.append(compressLineCounts(node.lineCounts));
        }
        sb.append('}');
        if (!node.children.isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < node.children.size(); i++) {
                if (i > 0) {
                    // previous child already added newline
                }
                appendHumanNode(sb, node.children.get(i), depth + 1, traceId, logThreadId, logThreadName);
                if (i < node.children.size() - 1) {
                    sb.append('\n');
                }
            }
        }
    }

    private static Object[] sanitizeArgs(Object[] args, boolean logArgs, String mask, int maxArgLength) {
        if (!logArgs) {
            return null;
        }
        Object[] safeArgs = args == null ? new Object[0] : args;
        if (safeArgs.length == 0) {
            return safeArgs;
        }
        MaskSpec maskSpec = parseMask(mask);
        int limit = maxArgLength > 0 ? maxArgLength : 200;
        Object[] sanitized = new Object[safeArgs.length];
        for (int i = 0; i < safeArgs.length; i++) {
            if (maskSpec.maskAll || maskSpec.indexes.contains(i)) {
                sanitized[i] = "***";
                continue;
            }
            Object arg = safeArgs[i];
            if (arg == null || arg instanceof Number || arg instanceof Boolean) {
                sanitized[i] = arg;
                continue;
            }
            String value = String.valueOf(arg);
            sanitized[i] = truncate(value, limit);
        }
        return sanitized;
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private static MaskSpec parseMask(String mask) {
        MaskSpec spec = new MaskSpec();
        if (mask == null) {
            return spec;
        }
        String trimmed = mask.trim();
        if (trimmed.isEmpty()) {
            return spec;
        }
        if ("*".equals(trimmed)) {
            spec.maskAll = true;
            return spec;
        }
        String[] parts = trimmed.split(",");
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            if (dash > 0) {
                try {
                    int start = Integer.parseInt(token.substring(0, dash).trim());
                    int end = Integer.parseInt(token.substring(dash + 1).trim());
                    if (start > end) {
                        int tmp = start;
                        start = end;
                        end = tmp;
                    }
                    for (int i = start; i <= end; i++) {
                        spec.indexes.add(i);
                    }
                } catch (NumberFormatException ignored) {
                    continue;
                }
            } else {
                try {
                    spec.indexes.add(Integer.parseInt(token));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
        }
        return spec;
    }

    private static void aggregateNode(TraceNode node) {
        java.util.Map<String, TraceNode> aggregated = new java.util.LinkedHashMap<>();
        for (TraceNode child : node.children) {
            TraceNode existing = aggregated.get(child.method);
            if (existing == null) {
                aggregated.put(child.method, child);
            } else {
                existing.count += child.count;
                existing.durationNs += child.durationNs;
                mergeLineCounts(existing.lineCounts, child.lineCounts);
                existing.children.addAll(child.children);
                if (existing.count > 1) {
                    existing.arguments = null;
                }
            }
        }
        node.children.clear();
        for (TraceNode child : aggregated.values()) {
            if (!child.children.isEmpty()) {
                aggregateNode(child);
            }
            node.children.add(child);
        }
    }

    private static void mergeLineCounts(java.util.Map<Integer, Integer> target, java.util.Map<Integer, Integer> source) {
        if (target == null || source == null) {
            return;
        }
        for (java.util.Map.Entry<Integer, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static TraceResult exitInternal(Object token, int[] lines, int[] counts, long durationNs,
                                            boolean logOnException, boolean isException) {
        TraceState state = TRACE.get();
        if (state == null) {
            return null;
        }
        TraceNode node = (TraceNode) token;
        node.lineCounts = buildLineCounts(lines, counts);
        node.durationNs = durationNs;
        node.count = 1;
        state.stack.pop();

        if (!state.stack.isEmpty()) {
            return null;
        }

        TRACE.remove();

        if (state.suppressedRoot) {
            return null;
        }
        if (logOnException && !isException) {
            return null;
        }
        return new TraceResult(state.traceId, node, state.aggregateChildren);
    }

    private static final class TraceResult {
        private final String traceId;
        private final TraceNode node;
        private final boolean aggregateChildren;

        private TraceResult(String traceId, TraceNode node, boolean aggregateChildren) {
            this.traceId = traceId;
            this.node = node;
            this.aggregateChildren = aggregateChildren;
        }
    }

    private static final class MaskSpec {
        private final java.util.Set<Integer> indexes = new java.util.HashSet<>();
        private boolean maskAll;
    }
}
