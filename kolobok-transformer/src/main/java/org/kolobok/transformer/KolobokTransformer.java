package org.kolobok.transformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

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

        if ((classNode.access & Opcodes.ACC_INTERFACE) == 0) {
            return;
        }

        boolean modified = transformInterface(classNode);
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
