package org.kolobok.annotation.processor;

import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import org.kolobok.annotation.BuildNumber;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Annotation processor that inits String/long/Long/int/Integer fields inside a class with build number.
 * @author Sergey Grigorchuk sergey.grigorchuk@gmail.com
 */
@SupportedAnnotationTypes(value = {BuildNumberAnnotationProcessor.ANNOTATION_TYPE})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class BuildNumberAnnotationProcessor extends AbstractProcessor {
    public static final String ANNOTATION_TYPE = "org.kolobok.annotation.BuildNumber";

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
     * Process fields with @BuildNumber
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        if ( annotations == null || annotations.isEmpty()) {
            return false;
        }

        JavacElements utils = javacProcessingEnv.getElementUtils();

        boolean hasError = false;
        // Check fields marked with annotation
        for (TypeElement annotation : annotations) {

            if (ANNOTATION_TYPE.equals(annotation.asType().toString())){

                // Find all annotated fields
                final Set<? extends Element> fields = roundEnv.getElementsAnnotatedWith(annotation);
                for (Element field : fields) {
                    BuildNumber ba = field.getAnnotation(BuildNumber.class);
                    JCTree fieldTree = utils.getTree(field);
                    if(fieldTree instanceof JCTree.JCVariableDecl) {
                        JCTree.JCVariableDecl variableDecl = (JCTree.JCVariableDecl) fieldTree;

                        String value = null;
                        try {
                            URL url = new URL(ba.url());
                            HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
                            httpCon.setDoOutput(true);
                            httpCon.setConnectTimeout(ba.timeout());
                            httpCon.setReadTimeout(ba.timeout());
                            httpCon.setRequestMethod(ba.method());
                            try(OutputStreamWriter out = new OutputStreamWriter(
                                    httpCon.getOutputStream())) {
                                out.write("");
                            }
                            try (InputStream is = httpCon.getInputStream()) {
                                byte[] buf = new byte[1000]; // Usually it's enough to get build number
                                int count = is.read(buf);
                                value = new String(buf, 0, count);
                            }

                        } catch (IOException e) {
                            if(ba.defaultValue() == null || ba.defaultValue().isEmpty()) {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Error getting build number from URL '%s'\n%s", ba.url(), e.getLocalizedMessage()));
                                hasError = true;
                            } else {
                                value = ba.defaultValue();
                            }
                        }
                        if(!hasError) {
                            // Value is read. Check variable type
                            String varTypeName = String.valueOf(variableDecl.getType().type);
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "varTypeName = " + varTypeName + " value = " + value);
                            if (varTypeName.equals(Long.class.getName()) || varTypeName.equals(long.class.getName())) {
                                variableDecl.init = maker.Literal(Long.valueOf(value));
                            } else if (varTypeName.equals(Integer.class.getName()) || varTypeName.equals(int.class.getName())) {
                                variableDecl.init = maker.Literal(Integer.valueOf(value));
                            } else {
                                variableDecl.init = maker.Literal(value);
                            }
                        }

                    } else {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("Unknown '%s' is marked with @BuildNumber annotation.", fields, ANNOTATION_TYPE));
                    }
                }
            }
        }
        return hasError;
    }
}
