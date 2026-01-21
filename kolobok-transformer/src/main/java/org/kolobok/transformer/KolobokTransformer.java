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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KolobokTransformer {
    public static final String OPTIONAL_PARAMS_DESC = "Lorg/kolobok/annotation/FindWithOptionalParams;";
    public static final String LOG_CONTEXT_DESC = "Lorg/kolobok/annotation/LogContext;";
    private static final String SLF4J_LOGGER_DESC = "Lorg/slf4j/Logger;";
    private static final String[] LOGGER_FIELD_NAMES = {"log", "logger", "LOG", "LOGGER"};

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
        boolean classAnnotated = hasAnnotation(classNode.visibleAnnotations, LOG_CONTEXT_DESC)
                || hasAnnotation(classNode.invisibleAnnotations, LOG_CONTEXT_DESC);

        List<MethodNode> methodsToInstrument = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (shouldInstrumentMethod(method, classAnnotated)) {
                methodsToInstrument.add(method);
            }
        }

        if (methodsToInstrument.isEmpty()) {
            return false;
        }

        FieldNode loggerField = findLoggerField(classNode);
        if (loggerField == null) {
            throw new IllegalStateException("Class '" + classNode.name
                    + "' uses @LogContext but no static logger field named log/logger/LOG/LOGGER with type org.slf4j.Logger was found");
        }

        for (MethodNode method : methodsToInstrument) {
            instrumentLogContextMethod(classNode, method, loggerField);
        }

        return true;
    }

    private boolean shouldInstrumentMethod(MethodNode method, boolean classAnnotated) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return false;
        }
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            return false;
        }
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        return classAnnotated || hasAnnotation(method, LOG_CONTEXT_DESC);
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

    private void instrumentLogContextMethod(ClassNode classNode, MethodNode method, FieldNode loggerField) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] argTypes = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);
        int[] argIndexes = computeArgIndexes(isStatic, argTypes);

        int startTimeVar = method.maxLocals;
        method.maxLocals += 2;

        int returnVar = -1;
        if (returnType.getSort() != Type.VOID) {
            returnVar = method.maxLocals;
            method.maxLocals += returnType.getSize();
        }

        int durationVar = method.maxLocals;
        method.maxLocals += 2;

        int exceptionVar = method.maxLocals;
        method.maxLocals += 1;

        LabelNode startLabel = new LabelNode();
        LabelNode endLabel = new LabelNode();
        LabelNode handlerLabel = new LabelNode();

        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        entry.add(new VarInsnNode(Opcodes.LSTORE, startTimeVar));
        append(entry, buildEntryLog(classNode, method, loggerField, argTypes, argIndexes));
        entry.add(startLabel);
        method.instructions.insert(entry);

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
                append(exit, buildExitLog(classNode, method, loggerField, startTimeVar, durationVar, null, null));
                exit.add(new InsnNode(Opcodes.RETURN));
            } else {
                exit.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), returnVar));
                append(exit, buildExitLog(classNode, method, loggerField, startTimeVar, durationVar, returnType, returnVar));
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
        append(handler, buildErrorLog(classNode, method, loggerField, startTimeVar, durationVar, exceptionVar));
        handler.add(new VarInsnNode(Opcodes.ALOAD, exceptionVar));
        handler.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(handler);

        method.tryCatchBlocks.add(new TryCatchBlockNode(startLabel, endLabel, handlerLabel, "java/lang/Throwable"));
    }

    private InsnList buildEntryLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                   Type[] argTypes, int[] argIndexes) {
        InsnList insns = new InsnList();
        LabelNode skipLabel = new LabelNode();

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "isDebugEnabled", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));

        append(insns, buildArgsArray(argTypes, argIndexes));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "deepToString",
                "([Ljava/lang/Object;)Ljava/lang/String;", false));

        String prefix = "Enter " + toHumanName(classNode.name) + "#" + method.name + " args=";
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode(prefix));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "debug",
                "(Ljava/lang/String;)V", true));

        insns.add(skipLabel);
        return insns;
    }

    private InsnList buildExitLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                  int startTimeVar, int durationVar, Type returnType, Integer returnVar) {
        InsnList insns = new InsnList();
        LabelNode skipLabel = new LabelNode();

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "isDebugEnabled", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));

        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, startTimeVar));
        insns.add(new InsnNode(Opcodes.LSUB));
        insns.add(new VarInsnNode(Opcodes.LSTORE, durationVar));

        String prefix = "Exit " + toHumanName(classNode.name) + "#" + method.name + " result=";
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode(prefix));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false));

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

        insns.add(new LdcInsnNode(" took="));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(J)Ljava/lang/StringBuilder;", false));
        insns.add(new LdcInsnNode("ns"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));

        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "debug",
                "(Ljava/lang/String;)V", true));

        insns.add(skipLabel);
        return insns;
    }

    private InsnList buildErrorLog(ClassNode classNode, MethodNode method, FieldNode loggerField,
                                   int startTimeVar, int durationVar, int exceptionVar) {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, startTimeVar));
        insns.add(new InsnNode(Opcodes.LSUB));
        insns.add(new VarInsnNode(Opcodes.LSTORE, durationVar));

        String prefix = "Error " + toHumanName(classNode.name) + "#" + method.name + " took=";
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode(prefix));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false));
        insns.add(new VarInsnNode(Opcodes.LLOAD, durationVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(J)Ljava/lang/StringBuilder;", false));
        insns.add(new LdcInsnNode("ns"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, loggerField.name, loggerField.desc));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, exceptionVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "error",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", true));
        return insns;
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

    private String toHumanName(String internalName) {
        return internalName.replace('/', '.');
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
}
