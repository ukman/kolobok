package org.kolobok.annotation.processor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation processor that makes find methods in Spring repository to find entities with optional params.
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
@SupportedAnnotationTypes(value = {FindWithOptionalParamsAnnotationProcessor.ANNOTATION_TYPE})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class FindWithOptionalParamsAnnotationProcessor extends AbstractProcessor {
    public static final String ANNOTATION_TYPE = "org.kolobok.annotation.FindWithOptionalParams";
    public static final String LAST_DOUBLE_PARAM_NAME = "__last___param___";

    private JavacProcessingEnvironment javacProcessingEnv;
    private TreeMaker maker;
    private Context context;
    private RepoMethodUtil repoMethodUtil;

    @Override
    public void init(ProcessingEnvironment procEnv) {
        super.init(procEnv);
        this.javacProcessingEnv = (JavacProcessingEnvironment) procEnv;
        this.maker = TreeMaker.instance(javacProcessingEnv.getContext());
        this.context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.repoMethodUtil = new RepoMethodUtil();
    }

    /**
     * Process spring data repository interfaces with methods annotated with @FindWithOptionalParams
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        if ( annotations == null || annotations.isEmpty()) {
            return false;
        }

        JavacElements utils = javacProcessingEnv.getElementUtils();

        List<Element> methodsToProcess = new ArrayList();

        boolean hasErrors = false;

        // Check methods marked with annotation
        for (TypeElement annotation : annotations) {

            if (ANNOTATION_TYPE.equals(annotation.asType().toString())){
                // Find all annotated methods
                final Set<? extends Element> methods = roundEnv.getElementsAnnotatedWith(annotation);
                for (Element method : methods) {
                    if(method.getEnclosingElement().getKind() != ElementKind.INTERFACE) {
                        // ERROR Annotated method is in CLASS (not INTERFACE)
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Method '%s' in '%s' cannot be annotated with @%s  because '%s' is not interface ", method, method.getEnclosingElement(), ANNOTATION_TYPE, method.getEnclosingElement()));
                        hasErrors = true;
                    }
                    if(method.getModifiers().contains(Modifier.DEFAULT)) {
                        // ERROR Annotated method should not have default implementation
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Method '%s' in '%' cannot be annotated with @%s because it has default implementation.", method, method.getEnclosingElement(), ANNOTATION_TYPE));
                        hasErrors = true;
                    }
                    JCTree methodTree = utils.getTree(method);
                    JCTree.JCMethodDecl methodDecl = (JCTree.JCMethodDecl) methodTree;

                    String returnTypeName = String.valueOf(methodDecl.getReturnType().type);

                    RepoMethod repoMethod = repoMethodUtil.parseMethodName(methodDecl.name.toString());

                    if(repoMethod.getType() == RepoMethod.Type.FIND && !returnTypeName.startsWith("java.lang.Iterable") && !returnTypeName.startsWith("org.springframework.data.domain.Page")) {
                        // ERROR Annotated method should return java.lang.Iterable or org.springframework.data.domain.Page
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Find method '%s' in '%s' should return either 'org.springframework.data.domain.Page' or 'java.lang.Iterable<?>' but it returns '%s'.", method, method.getEnclosingElement(), returnTypeName));
                        hasErrors = true;
                    } else if(repoMethod.getType() == RepoMethod.Type.COUNT && !returnTypeName.startsWith("java.lang.Long") && !returnTypeName.startsWith("long")) {
                        // ERROR Annotated count method should return java.lang.Long or long
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Count method '%s' in '%s' should return either 'java.lang.Long' or 'long' but it returns '%s'.", method, method.getEnclosingElement(), returnTypeName));
                        hasErrors = true;
                    }
                    methodsToProcess.add(method);
                }
            }
        }

        if(!hasErrors) {
            JCTree.JCModifiers modifiers = maker.Modifiers(Flags.PUBLIC);
            Set<String> existingSignatures = new HashSet<>();
            for (Element method : methodsToProcess) {
                Element cs = method.getEnclosingElement();
                JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) utils.getTree(cs);

                // Scan and add other existing method to signatures list
                for (JCTree member : classDecl.getMembers()) {
                    if(member instanceof JCTree.JCMethodDecl) {
                        JCTree.JCMethodDecl methodDecl = (JCTree.JCMethodDecl) member;
                        String signature = generateSignature(methodDecl);
                        existingSignatures.add(signature);
                    }
                }

                JCTree methodTree = utils.getTree(method);
                JCTree.JCMethodDecl methodDecl = (JCTree.JCMethodDecl) methodTree;
                com.sun.tools.javac.util.List<JCTree.JCVariableDecl> params = methodDecl.params;

                RepoMethod repoMethod = repoMethodUtil.parseMethodName(methodDecl.name.toString());
                JCTree.JCIf ifSt = generateIf(repoMethod, params, 0, repoMethod.getType().getGeneratedMethodPrefix(), new ArrayList<>());

                com.sun.tools.javac.util.List<JCTree.JCStatement> statements = com.sun.tools.javac.util.List.from(new JCTree.JCStatement[]{ifSt});

                methodDecl.body = maker.Block(0, statements);
                methodDecl.mods.flags |= Flags.DEFAULT;

                int pow2 = 1 << repoMethod.getParts().length;
                for(int i = 1; i < pow2; i++) {
                    int roll = i;
                    int idx = 0;
                    List<RepoMethod.Part> newParts = new ArrayList<>();
                    List<JCTree.JCVariableDecl> newParams = new ArrayList();
                    while(roll != 0) {
                        if((roll & 1) != 0) {
                            newParts.add(repoMethod.getParts()[idx]);
                            JCTree.JCVariableDecl param = params.get(idx);
                            newParams.add(param);
                        }
                        roll >>= 1;
                        idx++;
                    }

                    if(i == pow2 - 1 && repoMethod.getType() == RepoMethod.Type.COUNT) {
                        // Duplicate last param for COUNT method
                        JCTree.JCVariableDecl lastParam = newParams.get(newParams.size() - 1);

                        Name name = getName(LAST_DOUBLE_PARAM_NAME);
                        JCTree.JCVariableDecl lp = maker.Param(name, lastParam.getType().type, lastParam.sym);
                        newParams.add(lp);

                        // Duplicate last part
                        newParts.add(newParts.get(newParts.size() - 1));
                    }

                    // Additional params (Paging/Sorting)
                    for(int j = repoMethod.getParts().length; j < params.length(); j++) {
                        newParams.add(params.get(j));
                    }

                    com.sun.tools.javac.util.List<JCTree.JCVariableDecl> newParameters = com.sun.tools.javac.util.List.from(newParams);
                    String newMethodName = repoMethodUtil.generateMethodName(newParts);
                    if(repoMethod.getType() == RepoMethod.Type.COUNT) {
                        newMethodName = repoMethod.getType().getDefaultPrefix() + repoMethodUtil.firstLetterToUpperCase(newMethodName);
                    }

                    JCTree.JCMethodDecl newMethodDecl =
                            maker.MethodDef(modifiers, getName(newMethodName), methodDecl.restype, methodDecl.typarams, newParameters, methodDecl.thrown,
                                    null, null);
                    String signature = generateSignature(newMethodDecl);
                    if(!existingSignatures.contains(signature)) {
                        existingSignatures.add(signature);
                        classDecl.defs = classDecl.defs.append(newMethodDecl);
                    }
                }
            }
        }

        return false;
    }

    /**
     * Generates signature of a method to be checked if such method already has been generated
     * @param method method for which signature should be generated
     * @return signature string
     */
    private String generateSignature(JCTree.JCMethodDecl method) {
        return method.name + "(" +
                method.params.stream().map(p -> p.getType().toString())
                .collect(Collectors.joining(",")) +
                ")";
    }

    /**
     * Recursively generates if statement for optional params
     * @param repoMethod repository method descriptor
     * @param params all params of original method
     * @param idx idx starting from which if-statement should be generated
     * @param methodPrefix methodPrefix for previously generated find method
     * @param paramsToCall params for current if-statement
     * @return generated if-statement
     */
    private JCTree.JCIf generateIf(
            // String[] parts,
            RepoMethod repoMethod,
            com.sun.tools.javac.util.List<JCTree.JCVariableDecl> params,
                                   final int idx,
                                   String methodPrefix, List<JCTree.JCVariableDecl> paramsToCall) {
        methodPrefix = repoMethodUtil.firstLetterToLowerCase(methodPrefix);
        JCTree.JCLiteral nil = maker.Literal(TypeTag.BOT, null);
        JCTree.JCStatement nullSt, nonNullSt;

        ArrayList<JCTree.JCVariableDecl> newParamsToCall = new ArrayList<>(paramsToCall);
        newParamsToCall.add(params.get(idx));
        RepoMethod.Part part = repoMethod.getParts()[idx];

        if(idx >= repoMethod.getParts().length - 1) {
            // Last param in the list - return;
            String findAll = repoMethod.getType() == RepoMethod.Type.FIND ? "findAll" : "count";
            JCTree.JCIdent funcNullName = maker.Ident(getName(paramsToCall.isEmpty() ? findAll : methodPrefix));
            String newMethodName = paramsToCall.isEmpty() ?
                    repoMethodUtil.firstLetterToLowerCase(methodPrefix + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression())) :
                    methodPrefix + part.getPreOperation() + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression());
            if(repoMethod.getType() == RepoMethod.Type.COUNT && newParamsToCall.size() == params.size()) {
                // Duplicate last param
                newParamsToCall.add(params.get(idx));
                newMethodName = newMethodName + (part.getPreOperation() == null ? RepoMethod.Operation.And : part.getPreOperation()) + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression());
            }

            JCTree.JCIdent funcNonNullName = maker.Ident(getName(newMethodName));

            // Add aditional (pageable/sort params)
            List<JCTree.JCVariableDecl> nullParamsToCall = new ArrayList<>(paramsToCall);
            for(int j = repoMethod.getParts().length; j < params.size(); j++) {
                nullParamsToCall.add(params.get(j));
                newParamsToCall.add(params.get(j));
            }

            nullSt = maker.Return(maker.Apply(com.sun.tools.javac.util.List.<JCTree.JCExpression>nil(), funcNullName, com.sun.tools.javac.util.List.<JCTree.JCExpression>from(nullParamsToCall.stream().map(decl -> maker.Ident(getName("" + decl.getName()))).toArray(n -> new JCTree.JCExpression[n]))));
            List<JCTree.JCIdent> lst = newParamsToCall.stream()
                    .map(decl -> {
                        return maker.Ident(getName("" + decl.getName()));
                    })
                    .collect(Collectors.toList());
            com.sun.tools.javac.util.List<JCTree.JCExpression> newP =
                    com.sun.tools.javac.util.List.<JCTree.JCExpression>from(lst);
            nonNullSt = maker.Return(maker.Apply(com.sun.tools.javac.util.List.<JCTree.JCExpression>nil(), funcNonNullName, newP));
        } else {
            // Add new if
            nullSt = generateIf(repoMethod, params, idx + 1, methodPrefix, paramsToCall);
            String newMethodName = paramsToCall.isEmpty() ? methodPrefix + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression()) : methodPrefix + part.getPreOperation() + repoMethodUtil.firstLetterToUpperCase(part.getFullExpression());
            newMethodName = repoMethodUtil.firstLetterToLowerCase(newMethodName);
            nonNullSt = generateIf(repoMethod, params, idx + 1, newMethodName, newParamsToCall);
        }
        JCTree.JCIf res = maker.If(maker.Binary(
                JCTree.Tag.EQ,
                maker.Ident(params.get(idx).name),
                nil

        ), nullSt, nonNullSt);
        return res;
    }

    /**
     * Creates Name AST object by string
     * @param s name to be converted to AST Name
     * @return Name for AST
     */
    private Name getName(String s) {
        return Names.instance(context).fromString(s);
    }
}
