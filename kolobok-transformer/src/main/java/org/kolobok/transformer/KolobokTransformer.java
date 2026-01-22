package org.kolobok.transformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KolobokTransformer {
    public static final String OPTIONAL_PARAMS_DESC = "Lorg/kolobok/annotation/FindWithOptionalParams;";
    public static final String DEBUG_LOG_DESC = "Lorg/kolobok/annotation/DebugLog;";
    public static final String DEBUG_LOG_IGNORE_DESC = "Lorg/kolobok/annotation/DebugLogIgnore;";
    public static final String DEBUG_LOG_MASK_DESC = "Lorg/kolobok/annotation/DebugLogMask;";
    private static final String SLF4J_LOGGER_DESC = "Lorg/slf4j/Logger;";
    private static final String[] LOGGER_FIELD_NAMES = {"log", "logger", "LOG", "LOGGER"};
    private final DebugLogDefaults defaults;

    public KolobokTransformer() {
        this(DebugLogDefaults.fromSystemEnv());
    }

    public KolobokTransformer(DebugLogDefaults defaults) {
        this.defaults = defaults == null ? new DebugLogDefaults() : defaults;
    }

    private final RepoMethodUtil repoMethodUtil = new RepoMethodUtil();

    public void transformDirectory(Path classesDirectory) throws IOException {
        if (classesDirectory == null || !Files.isDirectory(classesDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(classesDirectory)) {
            List<Path> classFiles = paths
                    .filter(path -> path.toString().endsWith(".class"))
                    .collect(Collectors.toList());
            for (Path classFile : classFiles) {
                transformClassFile(classFile);
            }
        }
    }

    public void transformClassFile(Path classFile) throws IOException {
        byte[] original = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(original);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

        boolean modified;
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
            modified = transformInterface(classNode);
        } else {
            modified = transformLogContext(classNode);
        }
        if (!modified) {
            return;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        Files.write(classFile, writer.toByteArray());
    }

    private boolean transformInterface(ClassNode classNode) {
        List<MethodNode> annotatedMethods = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (hasAnnotation(method, OPTIONAL_PARAMS_DESC)) {
                annotatedMethods.add(method);
            }
        }

        if (annotatedMethods.isEmpty()) {
            return false;
        }

        Set<String> existingSignatures = new HashSet<>();
        for (MethodNode method : classNode.methods) {
            existingSignatures.add(signature(method));
        }

        for (MethodNode method : annotatedMethods) {
            RepoMethod repoMethod = repoMethodUtil.parseMethodName(method.name);
            validateReturnType(method, repoMethod);
            List<ParamInfo> params = buildParamInfo(method.desc);
            int criteriaCount = repoMethod.getParts().length;
            if (criteriaCount > params.size()) {
                throw new IllegalStateException("Method '" + method.name + "' has fewer parameters than repo parts");
            }
            for (int i = 0; i < criteriaCount; i++) {
                if (params.get(i).type.getSort() != Type.OBJECT && params.get(i).type.getSort() != Type.ARRAY) {
                    throw new IllegalStateException("Method '" + method.name + "' has primitive parameter at index " + i + " which cannot be optional");
                }
            }

            buildDefaultMethod(classNode, method, repoMethod, params);
            addGeneratedMethods(classNode, method, repoMethod, params, existingSignatures);
        }

        return true;
    }

    private boolean transformLogContext(ClassNode classNode) {
        AnnotationNode classAnnotation = findAnnotation(classNode.visibleAnnotations, DEBUG_LOG_DESC);
        if (classAnnotation == null) {
            classAnnotation = findAnnotation(classNode.invisibleAnnotations, DEBUG_LOG_DESC);
        }

        Map<MethodNode, LogContextConfig> methodsToInstrument = new HashMap<>();
        for (MethodNode method : classNode.methods) {
            LogContextConfig config = resolveLogContextConfig(method, classAnnotation);
            if (config != null && shouldInstrumentMethod(method)) {
                methodsToInstrument.put(method, config);
            }
        }

        if (methodsToInstrument.isEmpty()) {
            return false;
        }

        FieldNode loggerField = findLoggerField(classNode);
        if (loggerField == null) {
            throw new IllegalStateException("Class '" + classNode.name
                    + "' uses @DebugLog but no static logger field named log/logger/LOG/LOGGER with type org.slf4j.Logger was found");
        }

        for (Map.Entry<MethodNode, LogContextConfig> entry : methodsToInstrument.entrySet()) {
            instrumentLogContextMethod(classNode, entry.getKey(), loggerField, entry.getValue());
        }

        return true;
    }

    private boolean shouldInstrumentMethod(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;
        }
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            return false;
        }
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        return true;
    }

    private LogContextConfig resolveLogContextConfig(MethodNode method, AnnotationNode classAnnotation) {
        AnnotationNode methodAnnotation = findAnnotation(method, DEBUG_LOG_DESC);
        if (methodAnnotation == null) {
            methodAnnotation = classAnnotation;
        }
        if (methodAnnotation == null) {
            return null;
        }
        boolean lineHeatMap = resolveBoolean(methodAnnotation, "lineHeatMap",
                DebugLogDefaults.DEFAULT_LINE_HEAT_MAP, defaults.getLineHeatMap());
        boolean lineHeatMapOnException = resolveBoolean(methodAnnotation, "lineHeatMapOnException",
                DebugLogDefaults.DEFAULT_LINE_HEAT_MAP_ON_EXCEPTION, defaults.getLineHeatMapOnException());
        boolean subHeatMap = resolveBoolean(methodAnnotation, "subHeatMap",
                DebugLogDefaults.DEFAULT_SUB_HEAT_MAP, defaults.getSubHeatMap());
        boolean logDuration = resolveBoolean(methodAnnotation, "logDuration",
                DebugLogDefaults.DEFAULT_LOG_DURATION, defaults.getLogDuration());
        boolean aggregateChildren = resolveBoolean(methodAnnotation, "aggregateChildren",
                DebugLogDefaults.DEFAULT_AGGREGATE_CHILDREN, defaults.getAggregateChildren());
        boolean logArgs = resolveBoolean(methodAnnotation, "logArgs",
                DebugLogDefaults.DEFAULT_LOG_ARGS, defaults.getLogArgs());
        String mask = resolveString(methodAnnotation, "mask",
                DebugLogDefaults.DEFAULT_MASK, defaults.getMask());
        int maxArgLength = resolveInt(methodAnnotation, "maxArgLength",
                DebugLogDefaults.DEFAULT_MAX_ARG_LENGTH, defaults.getMaxArgLength());
        String logLevelName = resolveEnum(methodAnnotation, "logLevel",
                DebugLogDefaults.DEFAULT_LOG_LEVEL.name(), defaults.getLogLevel());
        String logFormatName = resolveEnum(methodAnnotation, "logFormat",
                DebugLogDefaults.DEFAULT_LOG_FORMAT.name(), defaults.getLogFormat());
        boolean logThreadId = resolveBoolean(methodAnnotation, "logThreadId",
                DebugLogDefaults.DEFAULT_LOG_THREAD_ID, defaults.getLogThreadId());
        boolean logThreadName = resolveBoolean(methodAnnotation, "logThreadName",
                DebugLogDefaults.DEFAULT_LOG_THREAD_NAME, defaults.getLogThreadName());
        boolean logLocals = resolveBoolean(methodAnnotation, "logLocals",
                DebugLogDefaults.DEFAULT_LOG_LOCALS, defaults.getLogLocals());
        boolean logLocalsOnException = resolveBoolean(methodAnnotation, "logLocalsOnException",
                DebugLogDefaults.DEFAULT_LOG_LOCALS_ON_EXCEPTION, defaults.getLogLocalsOnException());
        if (lineHeatMapOnException) {
            lineHeatMap = true;
        }
        return new LogContextConfig(lineHeatMap, lineHeatMapOnException, subHeatMap, logDuration, aggregateChildren,
                logArgs, mask, maxArgLength, LogLevelConfig.fromName(logLevelName),
                LogFormatConfig.fromName(logFormatName), logThreadId, logThreadName, logLocals, logLocalsOnException);
    }

    private boolean resolveBoolean(AnnotationNode annotation, String name, boolean builtinDefault, Boolean override) {
        boolean value = getBooleanValue(annotation, name, builtinDefault);
        if (value == builtinDefault && override != null) {
            return override;
        }
        return value;
    }

    private int resolveInt(AnnotationNode annotation, String name, int builtinDefault, Integer override) {
        int value = getIntValue(annotation, name, builtinDefault);
        if (value == builtinDefault && override != null) {
            return override;
        }
        return value;
    }

    private String resolveString(AnnotationNode annotation, String name, String builtinDefault, String override) {
        String value = getStringValue(annotation, name, builtinDefault);
        if (builtinDefault.equals(value) && override != null) {
            return override;
        }
        return value;
    }

    private String resolveEnum(AnnotationNode annotation, String name, String builtinDefault, Enum<?> override) {
        String value = getEnumValue(annotation, name, builtinDefault);
        if (builtinDefault.equals(value) && override != null) {
            return override.name();
        }
        return value;
    }

    private FieldNode findLoggerField(ClassNode classNode) {
        for (FieldNode field : classNode.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            if (!SLF4J_LOGGER_DESC.equals(field.desc)) {
                continue;
            }
            for (String name : LOGGER_FIELD_NAMES) {
                if (name.equals(field.name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private void instrumentLogContextMethod(ClassNode classNode, MethodNode method, FieldNode loggerField, LogContextConfig config) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] argTypes = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);
        int[] argIndexes = computeArgIndexes(isStatic, argTypes);

        List<Integer> lineNumbers = config.lineHeatMap ? collectLineNumbers(method.instructions) : Collections.emptyList();
        boolean hasHeatMap = config.lineHeatMap && !lineNumbers.isEmpty();
        int originalMaxLocals = method.maxLocals;
        ParamLogConfig[] paramConfigs = buildParamConfigs(method, argTypes.length);
        LocalLogConfig localLogConfig = buildLocalLogConfig(method, originalMaxLocals, config.logLocals || config.logLocalsOnException);
        boolean enableLocalLogs = (config.logLocals || config.logLocalsOnException) && localLogConfig.hasAnnotations;

        int nextLocal = method.maxLocals;
        int linesVar = -1;
        int countsVar = -1;
        int traceVar = -1;
        if (hasHeatMap) {
            linesVar = nextLocal++;
            countsVar = nextLocal++;
            traceVar = nextLocal++;
        }

        int startTimeVar = nextLocal;
        nextLocal += 2;

        int returnVar = -1;
        if (returnType.getSort() != Type.VOID) {
            returnVar = nextLocal;
            nextLocal += returnType.getSize();
        }

        int localsSnapshotVar = -1;
        int localsNamesVar = -1;
        int localsIgnoreVar = -1;
        int localsMaskFirstVar = -1;
        int localsMaskLastVar = -1;
        if (enableLocalLogs) {
            localsSnapshotVar = nextLocal++;
            localsNamesVar = nextLocal++;
            localsIgnoreVar = nextLocal++;
            localsMaskFirstVar = nextLocal++;
            localsMaskLastVar = nextLocal++;
        }

        int durationVar = startTimeVar;

        int exceptionVar = nextLocal;
        nextLocal += 1;

        method.maxLocals = nextLocal;

        LabelNode startLabel = new LabelNode();
        LabelNode endLabel = new LabelNode();
        LabelNode handlerLabel = new LabelNode();

        InsnList entry = new InsnList();
        if (hasHeatMap) {
            append(entry, buildLineArrayInit(lineNumbers, linesVar, countsVar));
            insertLineCounters(method, lineNumbers, countsVar);
            append(entry, buildTraceEnter(classNode, method, traceVar, config.subHeatMap, config.aggregateChildren,
                    config.logArgs, config.mask, config.maxArgLength, config.logFormat, argTypes, argIndexes, paramConfigs));
        }
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        entry.add(new VarInsnNode(Opcodes.LSTORE, startTimeVar));
        if (enableLocalLogs) {
            append(entry, buildLocalLogInit(localLogConfig, originalMaxLocals, localsSnapshotVar, localsNamesVar,
                    localsIgnoreVar, localsMaskFirstVar, localsMaskLastVar));
        }
        append(entry, buildEntryLog(classNode, method, loggerField, config, argTypes, argIndexes, paramConfigs));
        entry.add(startLabel);
        method.instructions.insert(entry);

        if (enableLocalLogs) {
            insertLocalSnapshotUpdates(method, localLogConfig, localsSnapshotVar);
        }

        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                returns.add(insn);
            }
        }

        for (AbstractInsnNode ret : returns) {
            InsnList exit = new InsnList();
            int opcode = ret.getOpcode();
            if (returnType.getSort() == Type.VOID) {
                append(exit, buildExitLog(classNode, method, loggerField, config, startTimeVar, durationVar, null, null,
                        localsSnapshotVar, localsNamesVar, localsIgnoreVar, localsMaskFirstVar, localsMaskLastVar, enableLocalLogs));
                if (hasHeatMap) {
                    append(exit, buildHeatMapLog(classNode, method, loggerField, linesVar, countsVar, traceVar, config, durationVar, false));
                }
                exit.add(new InsnNode(Opcodes.RETURN));
            } else {
                exit.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), returnVar));
                append(exit, buildExitLog(classNode, method, loggerField, config, startTimeVar, durationVar, returnType, returnVar,
                        localsSnapshotVar, localsNamesVar, localsIgnoreVar, localsMaskFirstVar, localsMaskLastVar, enableLocalLogs));
                if (hasHeatMap) {
                    append(exit, buildHeatMapLog(classNode, method, loggerField, linesVar, countsVar, traceVar, config, durationVar, false));
                }
                exit.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnVar));
                exit.add(new InsnNode(opcode));
            }
            method.instructions.insertBefore(ret, exit);
            method.instructions.remove(ret);
        }

        InsnList handler = new InsnList();
        handler.add(endLabel);
        handler.add(handlerLabel);
        handler.add(new VarInsnNode(Opcodes.ASTORE, exceptionVar));
        append(handler, buildErrorLog(classNode, method, loggerField, config, startTimeVar, durationVar, exceptionVar,
                argTypes, argIndexes, paramConfigs, localsSnapshotVar, localsNamesVar, localsIgnoreVar,
                localsMaskFirstVar, localsMaskLastVar, enableLocalLogs));
        if (hasHeatMap) {
            append(handler, buildHeatMapLog(classNode, method, loggerField, linesVar, countsVar, traceVar, config, durationVar, true));
        }
        handler.add(new VarInsnNode(Opcodes.ALOAD, exceptionVar));
        handler.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(handler);

        method.tryCatchBlocks.add(new TryCatchBlockNode(startLabel, endLabel, handlerLabel, "java/lang/Throwable"));
    }

    private InsnList buildEntryLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                   LogContextConfig config, Type[] argTypes, int[] argIndexes, ParamLogConfig[] paramConfigs) {
        InsnList insns = new InsnList();
        LabelNode skipLabel = new LabelNode();

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", config.logLevel.isEnabledMethod,
                "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));

        String methodDisplay = buildMethodDisplayName(classNode, method, config.logFormat);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        if (config.logFormat.jsonFormat) {
            insns.add(new LdcInsnNode("{\"type\":\"enter\",\"method\":\"" + escapeJson(methodDisplay) + "\""));
        } else {
            insns.add(new LdcInsnNode("[KLB] ENTER " + methodDisplay));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false));

        if (config.logFormat.jsonFormat) {
            append(insns, buildTraceIdAppendJson());
            if (config.logThreadId) {
                append(insns, buildThreadIdAppendJson());
            }
            if (config.logThreadName) {
                append(insns, buildThreadNameAppendJson());
            }
            if (config.logArgs) {
                insns.add(new LdcInsnNode(",\"args\":"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                append(insns, buildAnnotatedArgsArray(argTypes, argIndexes, paramConfigs, false, config.maxArgLength));
                insns.add(new LdcInsnNode(config.mask));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatArgsJson",
                        "([Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            insns.add(new LdcInsnNode("}"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        } else {
            append(insns, buildTraceIdAppendHuman());
            if (config.logThreadId) {
                append(insns, buildThreadIdAppendHuman());
            }
            if (config.logThreadName) {
                append(insns, buildThreadNameAppendHuman());
            }
            if (config.logArgs) {
                insns.add(new LdcInsnNode(" args="));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                append(insns, buildAnnotatedArgsArray(argTypes, argIndexes, paramConfigs, false, config.maxArgLength));
                insns.add(new LdcInsnNode(config.mask));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatArgs",
                        "([Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
        }

        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", config.logLevel.logMethod,
                "(Ljava/lang/String;)V", true));

        insns.add(skipLabel);
        return insns;
    }

    private InsnList buildExitLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                  LogContextConfig config, int startTimeVar, int durationVar,
                                  Type returnType, Integer returnVar, int localsSnapshotVar, int localsNamesVar,
                                  int localsIgnoreVar, int localsMaskFirstVar, int localsMaskLastVar,
                                  boolean hasLocalAnnotations) {
        InsnList insns = new InsnList();
        LabelNode skipLabel = new LabelNode();

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", config.logLevel.isEnabledMethod,
                "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));

        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, startTimeVar));
        insns.add(new InsnNode(Opcodes.LSUB));
        insns.add(new VarInsnNode(Opcodes.LSTORE, durationVar));

        String methodDisplay = buildMethodDisplayName(classNode, method, config.logFormat);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        if (config.logFormat.jsonFormat) {
            insns.add(new LdcInsnNode("{\"type\":\"exit\",\"method\":\"" + escapeJson(methodDisplay) + "\""));
        } else {
            insns.add(new LdcInsnNode("[KLB] EXIT " + methodDisplay));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false));

        if (config.logFormat.jsonFormat) {
            append(insns, buildTraceIdAppendJson());
            if (config.logThreadId) {
                append(insns, buildThreadIdAppendJson());
            }
            if (config.logThreadName) {
                append(insns, buildThreadNameAppendJson());
            }
            insns.add(new LdcInsnNode(",\"durationNs\":"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(J)Ljava/lang/StringBuilder;", false));
            if (config.logLocals && hasLocalAnnotations) {
                insns.add(new LdcInsnNode(",\"locals\":"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsSnapshotVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsNamesVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsIgnoreVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskFirstVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskLastVar));
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatLocalsJson",
                        "([Ljava/lang/Object;[Ljava/lang/String;[I[I[IZI)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            insns.add(new LdcInsnNode(",\"result\":\""));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            if (returnType == null) {
                insns.add(new LdcInsnNode("void"));
            } else {
                insns.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnVar));
                boxValue(insns, returnType);
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "escapeJson",
                    "(Ljava/lang/String;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new LdcInsnNode("\"}"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        } else {
            append(insns, buildTraceIdAppendHuman());
            if (config.logThreadId) {
                append(insns, buildThreadIdAppendHuman());
            }
            if (config.logThreadName) {
                append(insns, buildThreadNameAppendHuman());
            }
            insns.add(new LdcInsnNode(" dur="));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(J)Ljava/lang/StringBuilder;", false));
            insns.add(new LdcInsnNode("ns result="));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            if (returnType == null) {
                insns.add(new LdcInsnNode("void"));
            } else {
                insns.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnVar));
                boxValue(insns, returnType);
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            if (config.logLocals && hasLocalAnnotations) {
                insns.add(new LdcInsnNode(" locals={"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsSnapshotVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsNamesVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsIgnoreVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskFirstVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskLastVar));
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatLocalsHuman",
                        "([Ljava/lang/Object;[Ljava/lang/String;[I[I[IZI)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                insns.add(new LdcInsnNode("}"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
        }

        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", config.logLevel.logMethod,
                "(Ljava/lang/String;)V", true));

        insns.add(skipLabel);
        return insns;
    }

    private InsnList buildErrorLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                   LogContextConfig config, int startTimeVar, int durationVar, int exceptionVar,
                                   Type[] argTypes, int[] argIndexes, ParamLogConfig[] paramConfigs,
                                   int localsSnapshotVar, int localsNamesVar, int localsIgnoreVar,
                                   int localsMaskFirstVar, int localsMaskLastVar, boolean hasLocalAnnotations) {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, startTimeVar));
        insns.add(new InsnNode(Opcodes.LSUB));
        insns.add(new VarInsnNode(Opcodes.LSTORE, durationVar));

        String methodDisplay = buildMethodDisplayName(classNode, method, config.logFormat);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        if (config.logFormat.jsonFormat) {
            insns.add(new LdcInsnNode("{\"type\":\"error\",\"method\":\"" + escapeJson(methodDisplay) + "\""));
        } else {
            insns.add(new LdcInsnNode("[KLB] ERROR " + methodDisplay));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false));

        if (config.logFormat.jsonFormat) {
            append(insns, buildTraceIdAppendJson());
            if (config.logThreadId) {
                append(insns, buildThreadIdAppendJson());
            }
            if (config.logThreadName) {
                append(insns, buildThreadNameAppendJson());
            }
            insns.add(new LdcInsnNode(",\"durationNs\":"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(J)Ljava/lang/StringBuilder;", false));
            if (config.logArgs) {
                insns.add(new LdcInsnNode(",\"args\":"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                append(insns, buildAnnotatedArgsArray(argTypes, argIndexes, paramConfigs, true, config.maxArgLength));
                insns.add(new LdcInsnNode(config.mask));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatArgsJson",
                        "([Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            if (hasLocalAnnotations && (config.logLocalsOnException || config.logLocals)) {
                insns.add(new LdcInsnNode(",\"locals\":"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsSnapshotVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsNamesVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsIgnoreVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskFirstVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskLastVar));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatLocalsJson",
                        "([Ljava/lang/Object;[Ljava/lang/String;[I[I[IZI)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            insns.add(new LdcInsnNode(",\"error\":\""));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new VarInsnNode(Opcodes.ALOAD, exceptionVar));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "escapeJson",
                    "(Ljava/lang/String;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new LdcInsnNode("\"}"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        } else {
            append(insns, buildTraceIdAppendHuman());
            if (config.logThreadId) {
                append(insns, buildThreadIdAppendHuman());
            }
            if (config.logThreadName) {
                append(insns, buildThreadNameAppendHuman());
            }
            insns.add(new LdcInsnNode(" dur="));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(J)Ljava/lang/StringBuilder;", false));
            insns.add(new LdcInsnNode("ns"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            if (config.logArgs) {
                insns.add(new LdcInsnNode(" args="));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                append(insns, buildAnnotatedArgsArray(argTypes, argIndexes, paramConfigs, true, config.maxArgLength));
                insns.add(new LdcInsnNode(config.mask));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatArgs",
                        "([Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            if (hasLocalAnnotations && (config.logLocalsOnException || config.logLocals)) {
                insns.add(new LdcInsnNode(" locals={"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsSnapshotVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsNamesVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsIgnoreVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskFirstVar));
                insns.add(new VarInsnNode(Opcodes.ALOAD, localsMaskLastVar));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new LdcInsnNode(config.maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatLocalsHuman",
                        "([Ljava/lang/Object;[Ljava/lang/String;[I[I[IZI)Ljava/lang/String;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                insns.add(new LdcInsnNode("}"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            insns.add(new LdcInsnNode(" err="));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            insns.add(new VarInsnNode(Opcodes.ALOAD, exceptionVar));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        }

        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, exceptionVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "error",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", true));
        return insns;
    }

    private InsnList buildHeatMapLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                     int linesVar, int countsVar, int traceVar, LogContextConfig config,
                                     int durationVar, boolean isException) {
        InsnList insns = new InsnList();
        insns.add(new VarInsnNode(Opcodes.ALOAD, traceVar));
        insns.add(new VarInsnNode(Opcodes.ALOAD, linesVar));
        insns.add(new VarInsnNode(Opcodes.ALOAD, countsVar));

        if (config.logDuration) {
            insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
        } else {
            insns.add(new LdcInsnNode(-1L));
        }
        insns.add(new LdcInsnNode(config.lineHeatMapOnException));
        insns.add(new LdcInsnNode(isException));
        insns.add(new LdcInsnNode(config.logFormat.jsonFormat));
        insns.add(new LdcInsnNode(config.logThreadId));
        insns.add(new LdcInsnNode(config.logThreadName));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "exitFormatted",
                "(Ljava/lang/Object;[I[IJZZZZZ)Ljava/lang/String;", false));

        LabelNode skipNull = new LabelNode();
        LabelNode skipLog = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, skipNull));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", config.logLevel.isEnabledMethod,
                "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, skipLog));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", config.logLevel.logMethod,
                "(Ljava/lang/String;)V", true));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(skipLog);
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(skipNull);
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(done);
        return insns;
    }

    private InsnList buildLineArrayInit(List<Integer> lineNumbers, int linesVar, int countsVar) {
        InsnList insns = new InsnList();
        pushInt(insns, lineNumbers.size());
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        for (int i = 0; i < lineNumbers.size(); i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            insns.add(new LdcInsnNode(lineNumbers.get(i)));
            insns.add(new InsnNode(Opcodes.IASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, linesVar));

        pushInt(insns, lineNumbers.size());
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, countsVar));
        return insns;
    }

    private void insertLineCounters(MethodNode method, List<Integer> lineNumbers, int countsVar) {
        Map<Integer, Integer> indexByLine = new HashMap<>();
        for (int i = 0; i < lineNumbers.size(); i++) {
            indexByLine.put(lineNumbers.get(i), i);
        }

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LineNumberNode)) {
                continue;
            }
            LineNumberNode lineNode = (LineNumberNode) insn;
            Integer idx = indexByLine.get(lineNode.line);
            if (idx == null) {
                continue;
            }
            InsnList inc = new InsnList();
            inc.add(new VarInsnNode(Opcodes.ALOAD, countsVar));
            pushInt(inc, idx);
            inc.add(new InsnNode(Opcodes.DUP2));
            inc.add(new InsnNode(Opcodes.IALOAD));
            inc.add(new InsnNode(Opcodes.ICONST_1));
            inc.add(new InsnNode(Opcodes.IADD));
            inc.add(new InsnNode(Opcodes.IASTORE));
            method.instructions.insert(lineNode, inc);
        }
    }

    private InsnList buildTraceEnter(ClassNode classNode, MethodNode method, int traceVar, boolean subHeatMap,
                                     boolean aggregateChildren, boolean logArgs, String mask, int maxArgLength,
                                     LogFormatConfig logFormat, Type[] argTypes, int[] argIndexes,
                                     ParamLogConfig[] paramConfigs) {
        InsnList insns = new InsnList();
        String methodDescriptor = logFormat.jsonFormat
                ? buildMethodDescriptor(classNode, method)
                : buildShortMethodDescriptor(classNode, method);
        insns.add(new LdcInsnNode(methodDescriptor));
        insns.add(new LdcInsnNode(subHeatMap));
        insns.add(new LdcInsnNode(aggregateChildren));
        insns.add(new LdcInsnNode(logArgs));
        insns.add(new LdcInsnNode(mask));
        insns.add(new LdcInsnNode(maxArgLength));
        if (logArgs) {
            append(insns, buildAnnotatedArgsArray(argTypes, argIndexes, paramConfigs, false, maxArgLength));
        } else {
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "enter",
                "(Ljava/lang/String;ZZZLjava/lang/String;I[Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, traceVar));
        return insns;
    }

    private List<Integer> collectLineNumbers(InsnList instructions) {
        List<Integer> lines = new ArrayList<>();
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode) {
                lines.add(((LineNumberNode) insn).line);
            }
        }
        Collections.sort(lines);
        List<Integer> unique = new ArrayList<>();
        Integer last = null;
        for (Integer line : lines) {
            if (last == null || !last.equals(line)) {
                unique.add(line);
                last = line;
            }
        }
        return unique;
    }


    private InsnList buildArgsArray(Type[] argTypes, int[] argIndexes) {
        InsnList insns = new InsnList();
        pushInt(insns, argTypes.length);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < argTypes.length; i++) {
            Type type = argTypes[i];
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            insns.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), argIndexes[i]));
            boxValue(insns, type);
            insns.add(new InsnNode(Opcodes.AASTORE));
        }
        return insns;
    }

    private InsnList buildAnnotatedArgsArray(Type[] argTypes, int[] argIndexes, ParamLogConfig[] paramConfigs,
                                             boolean forException, int maxArgLength) {
        if (paramConfigs == null || paramConfigs.length == 0) {
            return buildArgsArray(argTypes, argIndexes);
        }
        InsnList insns = new InsnList();
        pushInt(insns, argTypes.length);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < argTypes.length; i++) {
            ParamLogConfig config = paramConfigs[i];
            insns.add(new InsnNode(Opcodes.DUP));
            pushInt(insns, i);
            if (config != null && config.shouldIgnore(forException)) {
                insns.add(new LdcInsnNode("***"));
                insns.add(new InsnNode(Opcodes.AASTORE));
                continue;
            }
            if (config != null && config.hasMask()) {
                insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argIndexes[i]));
                boxValue(insns, argTypes[i]);
                insns.add(new LdcInsnNode(config.maskFirst));
                insns.add(new LdcInsnNode(config.maskLast));
                insns.add(new LdcInsnNode(maxArgLength));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "maskValue",
                        "(Ljava/lang/Object;III)Ljava/lang/String;", false));
                insns.add(new InsnNode(Opcodes.AASTORE));
                continue;
            }
            insns.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ILOAD), argIndexes[i]));
            boxValue(insns, argTypes[i]);
            insns.add(new InsnNode(Opcodes.AASTORE));
        }
        return insns;
    }

    private void boxValue(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false));
                break;
            case Type.BYTE:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                        "(B)Ljava/lang/Byte;", false));
                break;
            case Type.CHAR:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf",
                        "(C)Ljava/lang/Character;", false));
                break;
            case Type.SHORT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                        "(S)Ljava/lang/Short;", false));
                break;
            case Type.INT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false));
                break;
            case Type.FLOAT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false));
                break;
            case Type.LONG:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false));
                break;
            case Type.DOUBLE:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false));
                break;
            default:
                break;
        }
    }

    private void pushInt(InsnList insns, int value) {
        if (value == -1) {
            insns.add(new InsnNode(Opcodes.ICONST_M1));
        } else if (value >= 0 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    private int[] computeArgIndexes(boolean isStatic, Type[] argTypes) {
        int[] indexes = new int[argTypes.length];
        int idx = isStatic ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            indexes[i] = idx;
            idx += argTypes[i].getSize();
        }
        return indexes;
    }

    private ParamLogConfig[] buildParamConfigs(MethodNode method, int paramCount) {
        ParamLogConfig[] configs = new ParamLogConfig[paramCount];
        List<AnnotationNode>[] visible = method.visibleParameterAnnotations;
        List<AnnotationNode>[] invisible = method.invisibleParameterAnnotations;
        for (int i = 0; i < paramCount; i++) {
            ParamLogConfig config = new ParamLogConfig();
            boolean configured = false;
            if (visible != null && i < visible.length) {
                configured |= applyParamAnnotations(config, visible[i]);
            }
            if (invisible != null && i < invisible.length) {
                configured |= applyParamAnnotations(config, invisible[i]);
            }
            configs[i] = configured ? config : null;
        }
        return configs;
    }

    private boolean applyParamAnnotations(ParamLogConfig config, List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        boolean configured = false;
        for (AnnotationNode annotation : annotations) {
            if (DEBUG_LOG_IGNORE_DESC.equals(annotation.desc)) {
                String mode = getEnumValue(annotation, "mode", "ALWAYS");
                config.ignoreMode = "SUCCESS".equalsIgnoreCase(mode) ? IgnoreMode.SUCCESS : IgnoreMode.ALWAYS;
                configured = true;
            } else if (DEBUG_LOG_MASK_DESC.equals(annotation.desc)) {
                config.maskFirst = getIntValue(annotation, "first", 0);
                config.maskLast = getIntValue(annotation, "last", 0);
                configured = true;
            }
        }
        return configured;
    }

    private LocalLogConfig buildLocalLogConfig(MethodNode method, int localsSize, boolean includeAllLocals) {
        LocalLogConfig config = new LocalLogConfig(localsSize);
        config.includeAllLocals = includeAllLocals;
        if (method.visibleLocalVariableAnnotations != null) {
            applyLocalVariableAnnotations(config, method.visibleLocalVariableAnnotations);
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            applyLocalVariableAnnotations(config, method.invisibleLocalVariableAnnotations);
        }
        if (!config.hasAnnotations && !includeAllLocals) {
            return config;
        }
        if (includeAllLocals) {
            config.hasAnnotations = true;
        }
        config.names = new String[localsSize];
        config.ignoreModes = new int[localsSize];
        config.maskFirst = new int[localsSize];
        config.maskLast = new int[localsSize];
        if (includeAllLocals) {
            for (int i = 0; i < localsSize; i++) {
                config.names[i] = "var" + i;
            }
        }
        if (method.localVariables != null) {
            for (LocalVariableNode var : method.localVariables) {
                if (var.index >= 0 && var.index < localsSize && config.hasIndex(var.index)) {
                    if (config.names[var.index] == null) {
                        config.names[var.index] = var.name;
                    }
                }
            }
        }
        for (LocalVarConfig entry : config.entries) {
            int idx = entry.index;
            if (idx >= 0 && idx < localsSize) {
                if (config.names[idx] == null) {
                    config.names[idx] = "var" + idx;
                }
                config.ignoreModes[idx] = entry.ignoreMode == IgnoreMode.ALWAYS ? 1
                        : entry.ignoreMode == IgnoreMode.SUCCESS ? 2 : 0;
                config.maskFirst[idx] = entry.maskFirst;
                config.maskLast[idx] = entry.maskLast;
            }
        }
        return config;
    }

    private void applyLocalVariableAnnotations(LocalLogConfig config, List<LocalVariableAnnotationNode> annotations) {
        for (LocalVariableAnnotationNode annotation : annotations) {
            if (!DEBUG_LOG_IGNORE_DESC.equals(annotation.desc) && !DEBUG_LOG_MASK_DESC.equals(annotation.desc)) {
                continue;
            }
            for (int index : annotation.index) {
                LocalVarConfig entry = config.findOrCreate(index);
                if (DEBUG_LOG_IGNORE_DESC.equals(annotation.desc)) {
                    String mode = getEnumValue(annotation, "mode", "ALWAYS");
                    entry.ignoreMode = "SUCCESS".equalsIgnoreCase(mode) ? IgnoreMode.SUCCESS : IgnoreMode.ALWAYS;
                } else if (DEBUG_LOG_MASK_DESC.equals(annotation.desc)) {
                    entry.maskFirst = getIntValue(annotation, "first", 0);
                    entry.maskLast = getIntValue(annotation, "last", 0);
                }
                config.hasAnnotations = true;
            }
        }
    }

    private InsnList buildLocalLogInit(LocalLogConfig config, int localsSize, int snapshotVar, int namesVar,
                                       int ignoreVar, int maskFirstVar, int maskLastVar) {
        InsnList insns = new InsnList();
        pushInt(insns, localsSize);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, snapshotVar));
        pushInt(insns, localsSize);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, namesVar));
        pushInt(insns, localsSize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, ignoreVar));
        pushInt(insns, localsSize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, maskFirstVar));
        pushInt(insns, localsSize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, maskLastVar));

        for (int i = 0; i < localsSize; i++) {
            if (config.names == null || config.names.length <= i) {
                continue;
            }
            String name = config.names[i];
            if (name != null) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, namesVar));
                pushInt(insns, i);
                insns.add(new LdcInsnNode(name));
                insns.add(new InsnNode(Opcodes.AASTORE));
            }
            int ignore = config.ignoreModes != null && config.ignoreModes.length > i ? config.ignoreModes[i] : 0;
            int first = config.maskFirst != null && config.maskFirst.length > i ? config.maskFirst[i] : 0;
            int last = config.maskLast != null && config.maskLast.length > i ? config.maskLast[i] : 0;
            if (ignore != 0) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, ignoreVar));
                pushInt(insns, i);
                pushInt(insns, ignore);
                insns.add(new InsnNode(Opcodes.IASTORE));
            }
            if (first != 0) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, maskFirstVar));
                pushInt(insns, i);
                pushInt(insns, first);
                insns.add(new InsnNode(Opcodes.IASTORE));
            }
            if (last != 0) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, maskLastVar));
                pushInt(insns, i);
                pushInt(insns, last);
                insns.add(new InsnNode(Opcodes.IASTORE));
            }
        }
        return insns;
    }

    private void insertLocalSnapshotUpdates(MethodNode method, LocalLogConfig config, int snapshotVar) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode varInsn = (VarInsnNode) insn;
                if (!config.hasIndex(varInsn.var)) {
                    continue;
                }
                int opcode = varInsn.getOpcode();
                if (opcode == Opcodes.ISTORE || opcode == Opcodes.ASTORE) {
                    InsnList update = new InsnList();
                    update.add(new VarInsnNode(Opcodes.ALOAD, snapshotVar));
                    pushInt(update, varInsn.var);
                    update.add(new VarInsnNode(opcodeToLoad(opcode), varInsn.var));
                    boxValue(update, opcodeToType(opcode));
                    update.add(new InsnNode(Opcodes.AASTORE));
                    method.instructions.insert(varInsn, update);
                }
            } else if (insn.getOpcode() == Opcodes.IINC) {
                IincInsnNode iinc = (IincInsnNode) insn;
                if (!config.hasIndex(iinc.var)) {
                    continue;
                }
                InsnList update = new InsnList();
                update.add(new VarInsnNode(Opcodes.ALOAD, snapshotVar));
                pushInt(update, iinc.var);
                update.add(new VarInsnNode(Opcodes.ILOAD, iinc.var));
                boxValue(update, Type.INT_TYPE);
                update.add(new InsnNode(Opcodes.AASTORE));
                method.instructions.insert(iinc, update);
            }
        }
    }

    private int opcodeToLoad(int storeOpcode) {
        switch (storeOpcode) {
            case Opcodes.ISTORE:
                return Opcodes.ILOAD;
            case Opcodes.ASTORE:
                return Opcodes.ALOAD;
            default:
                return Opcodes.ALOAD;
        }
    }

    private Type opcodeToType(int storeOpcode) {
        switch (storeOpcode) {
            case Opcodes.ISTORE:
                return Type.INT_TYPE;
            case Opcodes.ASTORE:
                return Type.getType(Object.class);
            default:
                return Type.getType(Object.class);
        }
    }

    private String toHumanName(String internalName) {
        return internalName.replace('/', '.');
    }

    private String buildMethodDescriptor(ClassNode classNode, MethodNode method) {
        return toHumanName(classNode.name) + "#" + method.name + method.desc;
    }

    private String buildShortMethodDescriptor(ClassNode classNode, MethodNode method) {
        StringBuilder sb = new StringBuilder();
        sb.append(toHumanName(classNode.name)).append('#').append(method.name).append('(');
        Type[] argTypes = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < argTypes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shortTypeName(argTypes[i]));
        }
        sb.append("):").append(shortTypeName(Type.getReturnType(method.desc)));
        return sb.toString();
    }

    private String shortTypeName(Type type) {
        switch (type.getSort()) {
            case Type.VOID:
                return "void";
            case Type.BOOLEAN:
                return "boolean";
            case Type.BYTE:
                return "byte";
            case Type.CHAR:
                return "char";
            case Type.SHORT:
                return "short";
            case Type.INT:
                return "int";
            case Type.FLOAT:
                return "float";
            case Type.LONG:
                return "long";
            case Type.DOUBLE:
                return "double";
            default:
                break;
        }
        String name = type.getClassName();
        int arrayDim = 0;
        while (name.endsWith("[]")) {
            arrayDim++;
            name = name.substring(0, name.length() - 2);
        }
        int lastDot = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        if (lastDot >= 0) {
            name = name.substring(lastDot + 1);
        }
        StringBuilder sb = new StringBuilder(name);
        for (int i = 0; i < arrayDim; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private String buildMethodDisplayName(ClassNode classNode, MethodNode method, LogFormatConfig logFormat) {
        return logFormat.jsonFormat ? buildMethodDescriptor(classNode, method) : buildShortMethodDescriptor(classNode, method);
    }

    private String escapeJson(String value) {
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

    private InsnList buildTraceIdAppendHuman() {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatTraceIdHuman",
                "()Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        return insns;
    }

    private InsnList buildTraceIdAppendJson() {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/kolobok/runtime/LogContextTrace", "formatTraceIdJson",
                "()Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        return insns;
    }

    private InsnList buildThreadIdAppendHuman() {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(" t="));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getId", "()J", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(J)Ljava/lang/StringBuilder;", false));
        return insns;
    }

    private InsnList buildThreadNameAppendHuman() {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(" tn="));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName", "()Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        return insns;
    }

    private InsnList buildThreadIdAppendJson() {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(",\"threadId\":"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getId", "()J", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(J)Ljava/lang/StringBuilder;", false));
        return insns;
    }

    private InsnList buildThreadNameAppendJson() {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(",\"threadName\":\""));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName", "()Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new LdcInsnNode("\""));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        return insns;
    }

    private void append(InsnList target, InsnList source) {
        for (AbstractInsnNode node : source.toArray()) {
            target.add(node);
        }
    }

    private void validateReturnType(MethodNode method, RepoMethod repoMethod) {
        Type returnType = Type.getReturnType(method.desc);
        if (repoMethod.getType() == RepoMethod.Type.FIND) {
            if (!isIterablePageOrList(returnType)) {
                throw new IllegalStateException("Find method '" + method.name
                        + "' should return java.lang.Iterable, java.util.List, or org.springframework.data.domain.Page");
            }
        } else if (repoMethod.getType() == RepoMethod.Type.COUNT) {
            if (!(returnType.getSort() == Type.LONG || returnType.equals(Type.getType(Long.class)))) {
                throw new IllegalStateException("Count method '" + method.name
                        + "' should return long or java.lang.Long");
            }
        }
    }

    private boolean isIterablePageOrList(Type returnType) {
        if (returnType.getSort() != Type.OBJECT) {
            return false;
        }
        String internalName = returnType.getInternalName();
        return "java/lang/Iterable".equals(internalName)
                || "java/util/List".equals(internalName)
                || "org/springframework/data/domain/Page".equals(internalName);
    }

    private void buildDefaultMethod(ClassNode classNode, MethodNode method, RepoMethod repoMethod, List<ParamInfo> params) {
        method.access = (method.access & ~Opcodes.ACC_ABSTRACT) | Opcodes.ACC_PUBLIC;
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;

        GeneratorAdapter ga = new GeneratorAdapter(method, method.access, method.name, method.desc);
        Type returnType = Type.getReturnType(method.desc);
        emitIfChain(ga, classNode.name, repoMethod, params, returnType, 0,
                repoMethod.getType().getGeneratedMethodPrefix(), new ArrayList<>());
        ga.endMethod();
    }

    private void emitIfChain(
            GeneratorAdapter ga,
            String ownerInternalName,
            RepoMethod repoMethod,
            List<ParamInfo> params,
            Type returnType,
            int idx,
            String methodPrefix,
            List<ParamInfo> paramsToCall
    ) {
        String normalizedPrefix = repoMethodUtil.firstLetterToLowerCase(methodPrefix);
        int criteriaCount = repoMethod.getParts().length;
        RepoMethod.Part part = repoMethod.getParts()[idx];

        if (idx >= criteriaCount - 1) {
            String findAll = repoMethod.getType() == RepoMethod.Type.FIND ? "findAll" : "count";
            String nullMethodName = paramsToCall.isEmpty() ? findAll : normalizedPrefix;

            List<ParamInfo> nullParams = new ArrayList<>(paramsToCall);
            List<ParamInfo> nonNullParams = new ArrayList<>(paramsToCall);
            nonNullParams.add(params.get(idx));

            String nonNullMethodName = paramsToCall.isEmpty()
                    ? repoMethodUtil.firstLetterToLowerCase(normalizedPrefix + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression()))
                    : normalizedPrefix + part.getPreOperation() + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression());

            if (repoMethod.getType() == RepoMethod.Type.COUNT && nonNullParams.size() == params.size()) {
                nonNullParams.add(params.get(idx));
                RepoMethod.Operation op = part.getPreOperation() == null ? RepoMethod.Operation.And : part.getPreOperation();
                nonNullMethodName = nonNullMethodName + op + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression());
            }

            for (int j = criteriaCount; j < params.size(); j++) {
                nullParams.add(params.get(j));
                nonNullParams.add(params.get(j));
            }

            String finalNullMethodName = nullMethodName;
            String finalNonNullMethodName = nonNullMethodName;
            List<ParamInfo> finalNullParams = new ArrayList<>(nullParams);
            List<ParamInfo> finalNonNullParams = new ArrayList<>(nonNullParams);

            emitIfElse(ga, params.get(idx),
                    () -> emitCall(ga, ownerInternalName, finalNullMethodName, returnType, finalNullParams),
                    () -> emitCall(ga, ownerInternalName, finalNonNullMethodName, returnType, finalNonNullParams));
            return;
        }

        List<ParamInfo> nextParamsToCall = new ArrayList<>(paramsToCall);
        nextParamsToCall.add(params.get(idx));

        String nextMethodName = paramsToCall.isEmpty()
                ? normalizedPrefix + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression())
                : normalizedPrefix + part.getPreOperation() + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression());
        String finalNextMethodName = repoMethodUtil.firstLetterToLowerCase(nextMethodName);
        List<ParamInfo> finalParamsToCall = new ArrayList<>(paramsToCall);
        List<ParamInfo> finalNextParamsToCall = new ArrayList<>(nextParamsToCall);

        emitIfElse(ga, params.get(idx),
                () -> emitIfChain(ga, ownerInternalName, repoMethod, params, returnType, idx + 1, normalizedPrefix, finalParamsToCall),
                () -> emitIfChain(ga, ownerInternalName, repoMethod, params, returnType, idx + 1, finalNextMethodName, finalNextParamsToCall));
    }

    private void emitIfElse(GeneratorAdapter ga, ParamInfo param, Runnable nullBranch, Runnable nonNullBranch) {
        if (param.type.getSort() != Type.OBJECT && param.type.getSort() != Type.ARRAY) {
            nonNullBranch.run();
            return;
        }
        org.objectweb.asm.Label nonNullLabel = ga.newLabel();
        ga.loadArg(param.argIndex);
        ga.ifNonNull(nonNullLabel);
        nullBranch.run();
        ga.mark(nonNullLabel);
        nonNullBranch.run();
    }

    private void emitCall(GeneratorAdapter ga, String ownerInternalName, String methodName, Type returnType, List<ParamInfo> callParams) {
        Type[] argTypes = callParams.stream().map(p -> p.type).toArray(Type[]::new);
        String methodDesc = Type.getMethodDescriptor(returnType, argTypes);
        ga.loadThis();
        for (ParamInfo param : callParams) {
            ga.loadArg(param.argIndex);
        }
        ga.invokeInterface(Type.getObjectType(ownerInternalName), new Method(methodName, methodDesc));
        ga.returnValue();
    }

    private void addGeneratedMethods(
            ClassNode classNode,
            MethodNode sourceMethod,
            RepoMethod repoMethod,
            List<ParamInfo> params,
            Set<String> existingSignatures
    ) {
        int criteriaCount = repoMethod.getParts().length;
        int pow2 = 1 << criteriaCount;
        Type returnType = Type.getReturnType(sourceMethod.desc);

        for (int i = 1; i < pow2; i++) {
            int roll = i;
            int idx = 0;
            List<RepoMethod.Part> newParts = new ArrayList<>();
            List<ParamInfo> newParams = new ArrayList<>();

            while (roll != 0) {
                if ((roll & 1) != 0) {
                    newParts.add(repoMethod.getParts()[idx]);
                    newParams.add(params.get(idx));
                }
                roll >>= 1;
                idx++;
            }

            if (i == pow2 - 1 && repoMethod.getType() == RepoMethod.Type.COUNT) {
                ParamInfo lastParam = newParams.get(newParams.size() - 1);
                newParams.add(lastParam);
                newParts.add(newParts.get(newParts.size() - 1));
            }

            for (int j = criteriaCount; j < params.size(); j++) {
                newParams.add(params.get(j));
            }

            String newMethodName = repoMethodUtil.generateMethodName(newParts);
            if (repoMethod.getType() == RepoMethod.Type.COUNT) {
                newMethodName = repoMethod.getType().getDefaultPrefix()
                        + repoMethodUtil.firstLetterToUpperCase(newMethodName);
            }

            Type[] argTypes = newParams.stream().map(p -> p.type).toArray(Type[]::new);
            String methodDesc = Type.getMethodDescriptor(returnType, argTypes);
            String signature = newMethodName + methodDesc;
            if (existingSignatures.contains(signature)) {
                continue;
            }

            MethodNode generated = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    newMethodName,
                    methodDesc,
                    null,
                    sourceMethod.exceptions == null ? null : sourceMethod.exceptions.toArray(new String[0])
            );
            classNode.methods.add(generated);
            existingSignatures.add(signature);
        }
    }

    private List<ParamInfo> buildParamInfo(String methodDesc) {
        Type[] argTypes = Type.getArgumentTypes(methodDesc);
        List<ParamInfo> params = new ArrayList<>();
        for (int i = 0; i < argTypes.length; i++) {
            params.add(new ParamInfo(i, argTypes[i]));
        }
        return params;
    }

    private boolean hasAnnotation(MethodNode method, String desc) {
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (desc.equals(annotation.desc)) {
                    return true;
                }
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode annotation : method.invisibleAnnotations) {
                if (desc.equals(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private AnnotationNode findAnnotation(MethodNode method, String desc) {
        AnnotationNode annotation = findAnnotation(method.visibleAnnotations, desc);
        if (annotation != null) {
            return annotation;
        }
        return findAnnotation(method.invisibleAnnotations, desc);
    }

    private boolean getBooleanValue(AnnotationNode annotation, String name, boolean defaultValue) {
        if (annotation == null || annotation.values == null) {
            return defaultValue;
        }
        List<Object> values = annotation.values;
        for (int i = 0; i < values.size() - 1; i += 2) {
            if (name.equals(values.get(i))) {
                Object value = values.get(i + 1);
                if (value instanceof Boolean) {
                    return (Boolean) value;
                }
            }
        }
        return defaultValue;
    }

    private String getStringValue(AnnotationNode annotation, String name, String defaultValue) {
        if (annotation == null || annotation.values == null) {
            return defaultValue;
        }
        List<Object> values = annotation.values;
        for (int i = 0; i < values.size() - 1; i += 2) {
            if (name.equals(values.get(i))) {
                Object value = values.get(i + 1);
                if (value instanceof String) {
                    return (String) value;
                }
            }
        }
        return defaultValue;
    }

    private int getIntValue(AnnotationNode annotation, String name, int defaultValue) {
        if (annotation == null || annotation.values == null) {
            return defaultValue;
        }
        List<Object> values = annotation.values;
        for (int i = 0; i < values.size() - 1; i += 2) {
            if (name.equals(values.get(i))) {
                Object value = values.get(i + 1);
                if (value instanceof Integer) {
                    return (Integer) value;
                }
            }
        }
        return defaultValue;
    }

    private String getEnumValue(AnnotationNode annotation, String name, String defaultValue) {
        if (annotation == null || annotation.values == null) {
            return defaultValue;
        }
        List<Object> values = annotation.values;
        for (int i = 0; i < values.size() - 1; i += 2) {
            if (name.equals(values.get(i))) {
                Object value = values.get(i + 1);
                if (value instanceof String[]) {
                    String[] enumValue = (String[]) value;
                    if (enumValue.length == 2) {
                        return enumValue[1];
                    }
                }
            }
        }
        return defaultValue;
    }

    private boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    private AnnotationNode findAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private String signature(MethodNode method) {
        return method.name + method.desc;
    }

    private static class ParamInfo {
        private final int argIndex;
        private final Type type;

        private ParamInfo(int argIndex, Type type) {
            this.argIndex = argIndex;
            this.type = type;
        }
    }

    private enum IgnoreMode {
        NONE,
        ALWAYS,
        SUCCESS
    }

    private static final class ParamLogConfig {
        private IgnoreMode ignoreMode = IgnoreMode.NONE;
        private int maskFirst;
        private int maskLast;

        private boolean hasMask() {
            return maskFirst > 0 || maskLast > 0;
        }

        private boolean shouldIgnore(boolean forException) {
            if (ignoreMode == IgnoreMode.ALWAYS) {
                return true;
            }
            return ignoreMode == IgnoreMode.SUCCESS && !forException;
        }
    }

    private static final class LocalVarConfig {
        private final int index;
        private IgnoreMode ignoreMode = IgnoreMode.NONE;
        private int maskFirst;
        private int maskLast;

        private LocalVarConfig(int index) {
            this.index = index;
        }
    }

    private static final class LocalLogConfig {
        private final java.util.List<LocalVarConfig> entries = new java.util.ArrayList<>();
        private final int localsSize;
        private boolean hasAnnotations;
        private boolean includeAllLocals;
        private String[] names;
        private int[] ignoreModes;
        private int[] maskFirst;
        private int[] maskLast;

        private LocalLogConfig(int localsSize) {
            this.localsSize = localsSize;
        }

        private LocalVarConfig findOrCreate(int index) {
            for (LocalVarConfig entry : entries) {
                if (entry.index == index) {
                    return entry;
                }
            }
            LocalVarConfig entry = new LocalVarConfig(index);
            entries.add(entry);
            return entry;
        }

        private boolean hasIndex(int index) {
            if (index < 0 || index >= localsSize) {
                return false;
            }
            if (includeAllLocals) {
                return true;
            }
            for (LocalVarConfig entry : entries) {
                if (entry.index == index) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class LogContextConfig {
        private final boolean lineHeatMap;
        private final boolean lineHeatMapOnException;
        private final boolean subHeatMap;
        private final boolean logDuration;
        private final boolean aggregateChildren;
        private final boolean logArgs;
        private final String mask;
        private final int maxArgLength;
        private final LogLevelConfig logLevel;
        private final LogFormatConfig logFormat;
        private final boolean logThreadId;
        private final boolean logThreadName;
        private final boolean logLocals;
        private final boolean logLocalsOnException;

        private LogContextConfig(boolean lineHeatMap, boolean lineHeatMapOnException, boolean subHeatMap, boolean logDuration,
                                 boolean aggregateChildren, boolean logArgs, String mask, int maxArgLength,
                                 LogLevelConfig logLevel, LogFormatConfig logFormat,
                                 boolean logThreadId, boolean logThreadName, boolean logLocals, boolean logLocalsOnException) {
            this.lineHeatMap = lineHeatMap;
            this.lineHeatMapOnException = lineHeatMapOnException;
            this.subHeatMap = subHeatMap;
            this.logDuration = logDuration;
            this.aggregateChildren = aggregateChildren;
            this.logArgs = logArgs;
            this.mask = mask;
            this.maxArgLength = maxArgLength;
            this.logLevel = logLevel;
            this.logFormat = logFormat;
            this.logThreadId = logThreadId;
            this.logThreadName = logThreadName;
            this.logLocals = logLocals;
            this.logLocalsOnException = logLocalsOnException;
        }
    }

    private static final class LogLevelConfig {
        private final String name;
        private final String isEnabledMethod;
        private final String logMethod;

        private LogLevelConfig(String name, String isEnabledMethod, String logMethod) {
            this.name = name;
            this.isEnabledMethod = isEnabledMethod;
            this.logMethod = logMethod;
        }

        private static LogLevelConfig fromName(String name) {
            if ("TRACE".equalsIgnoreCase(name)) {
                return new LogLevelConfig("TRACE", "isTraceEnabled", "trace");
            }
            if ("INFO".equalsIgnoreCase(name)) {
                return new LogLevelConfig("INFO", "isInfoEnabled", "info");
            }
            if ("WARN".equalsIgnoreCase(name)) {
                return new LogLevelConfig("WARN", "isWarnEnabled", "warn");
            }
            if ("ERROR".equalsIgnoreCase(name)) {
                return new LogLevelConfig("ERROR", "isErrorEnabled", "error");
            }
            return new LogLevelConfig("DEBUG", "isDebugEnabled", "debug");
        }
    }

    private static final class LogFormatConfig {
        private final boolean jsonFormat;

        private LogFormatConfig(boolean jsonFormat) {
            this.jsonFormat = jsonFormat;
        }

        private static LogFormatConfig fromName(String name) {
            return new LogFormatConfig("JSON".equalsIgnoreCase(name));
        }
    }
}
