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
 * Annotation processor that inits long/Long fields inside a class with compilation time (in ms).
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
@SupportedAnnotationTypes(value = {CompileTimeAnnotationProcessor.ANNOTATION_TYPE})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class CompileTimeAnnotationProcessor extends AbstractProcessor {
    public static final String ANNOTATION_TYPE = "org.kolobok.annotation.CompileTime";

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
     * Process fields with @CompileTime
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        if ( annotations == null || annotations.isEmpty()) {
            return false;
        }

        JavacElements utils = javacProcessingEnv.getElementUtils();

        // Check fields marked with annotation
        for (TypeElement annotation : annotations) {

            if (ANNOTATION_TYPE.equals(annotation.asType().toString())){
                // Find all annotated fields
                final Set<? extends Element> fields = roundEnv.getElementsAnnotatedWith(annotation);
                for (Element field : fields) {
                    JCTree fieldTree = utils.getTree(field);
                    if(fieldTree instanceof JCTree.JCVariableDecl) {
                        JCTree.JCVariableDecl variableDecl = (JCTree.JCVariableDecl) fieldTree;
                        variableDecl.init = maker.Literal(System.currentTimeMillis());
                    } else {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Unknown '%s' is marked with @CompileTime annotation.", fields, ANNOTATION_TYPE));
                    }
                }
            }
        }
        return false;
    }
}
