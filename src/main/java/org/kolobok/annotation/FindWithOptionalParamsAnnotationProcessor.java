package org.kolobok.annotation;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
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
    private static final String FIND_BY = "findBy";
    private static final String AND = "And";

    private JavacProcessingEnvironment javacProcessingEnv;
    private TreeMaker maker;
    private Context context;

    @Override
    public void init(ProcessingEnvironment procEnv) {
        super.init(procEnv);
        this.javacProcessingEnv = (JavacProcessingEnvironment) procEnv;
        this.maker = TreeMaker.instance(javacProcessingEnv.getContext());
        this.context = ((JavacProcessingEnvironment) processingEnv).getContext();
    }

    /**
     * Process spring data repository interfaces with methods annotated with @FindWithOptionalParams
     * @param annotations
     * @param roundEnv
     * @return
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

                JCTree methodTree = utils.getTree(method);
                JCTree.JCMethodDecl methodDecl = (JCTree.JCMethodDecl) methodTree;
                com.sun.tools.javac.util.List<JCTree.JCVariableDecl> params = methodDecl.params;

                String[] parts = parseFindMethodName(methodDecl.name.toString());
                JCTree.JCIf ifSt = generateIf(parts, params, 0, "", new ArrayList<>());

                com.sun.tools.javac.util.List<JCTree.JCStatement> statements = com.sun.tools.javac.util.List.from(new JCTree.JCStatement[]{ifSt});

                methodDecl.body = maker.Block(0, statements);
                methodDecl.mods.flags |= Flags.DEFAULT;

                int pow2 = 1 << parts.length;
                for(int i = 1; i < pow2; i++) {
                    int roll = i;
                    int idx = 0;
                    List<String> newParts = new ArrayList<>();
                    List<JCTree.JCVariableDecl> newParams = new ArrayList();
                    while(roll != 0) {
                        if((roll & 1) != 0) {
                            newParts.add(parts[idx]);
                            JCTree.JCVariableDecl param = params.get(idx);
                            newParams.add(param);
                        }
                        roll >>= 1;
                        idx++;
                    }
                    // Adsitional params (Paging/Sorting)
                    for(int j = parts.length; j < params.length(); j++) {
                        newParams.add(params.get(j));
                    }

                    com.sun.tools.javac.util.List<JCTree.JCVariableDecl> newParameters = com.sun.tools.javac.util.List.from(newParams);
                    String newMethodName = newParts.stream().collect(Collectors.joining(AND));
                    newMethodName = firstLetterToLowerCase(newMethodName);
                    String paramStr = newParameters.stream().map(p -> p.toString()).collect(Collectors.joining(", "));

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
        return method.name + "(" + method.params.stream().map(p -> p.getType().toString()).collect(Collectors.joining(",")) + ")";
    }

    /**
     * Recursively generates if statement for optional params
     * @param parts parts of method
     * @param params all params of original method
     * @param idx idx starting from which if-statement should be generated
     * @param methodPrefix methodPrefix for previously generated find method
     * @param paramsToCall params for current if-statement
     * @return generated if-statement
     */
    private JCTree.JCIf generateIf(String[] parts,
            com.sun.tools.javac.util.List<JCTree.JCVariableDecl> params,
                                   int idx,
                                   String methodPrefix, List<JCTree.JCVariableDecl> paramsToCall) {
        methodPrefix = firstLetterToLowerCase(methodPrefix);
        JCTree.JCLiteral nil = maker.Literal(TypeTag.BOT, null);
        JCTree.JCStatement nullSt, nonNullSt;

        ArrayList<JCTree.JCVariableDecl> newParamsToCall = new ArrayList<>(paramsToCall);
        newParamsToCall.add(params.get(idx));

        if(idx >= params.length() - 1) {
            // Last param in the list - return;
//            nullSt = maker.Return(maker.Ident(getName(methodPrefix)));
//            nonNullSt = maker.Return(maker.Ident(getName(methodPrefix + "_And_" + params.get(idx).name)));
            JCTree.JCIdent funcNullName = maker.Ident(getName(methodPrefix.isEmpty() ? "findAll" : methodPrefix));
            String newMethodName = methodPrefix.isEmpty() ? methodPrefix + parts[idx] : methodPrefix + AND + parts[idx];
            newMethodName = firstLetterToLowerCase(newMethodName);
            JCTree.JCIdent funcNonNullName = maker.Ident(getName(newMethodName));

            nullSt = maker.Return(maker.Apply(com.sun.tools.javac.util.List.<JCTree.JCExpression>nil(), funcNullName, com.sun.tools.javac.util.List.<JCTree.JCExpression>from(paramsToCall.stream().map(decl -> maker.Ident(getName("" + decl.getName()))).toArray(n -> new JCTree.JCExpression[n]))));
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
            nullSt = generateIf(parts, params, idx + 1, methodPrefix, paramsToCall);
            String newMethodName = methodPrefix.isEmpty() ? methodPrefix + parts[idx] : methodPrefix + AND + parts[idx];
            newMethodName = firstLetterToLowerCase(newMethodName);
            nonNullSt = generateIf(parts, params, idx + 1, newMethodName, newParamsToCall);
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

    private String[] parseFindMethodName(String findMethodName) {
        if(!findMethodName.startsWith(FIND_BY)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Method '%s' is not started with '%s'", findMethodName, FIND_BY));
        }
        String postFix = findMethodName.substring(FIND_BY.length());
        String[] parts = postFix.split(AND);
        return parts;
    }

    /**
     * Converts first symbol of a string to lower case
     * @param s string to be converted
     * @return converted string
     */
    private String firstLetterToLowerCase(String s) {
        return s.length() == 0 ? s : s.substring(0, 1).toLowerCase() + s.substring(1);
    }
}
