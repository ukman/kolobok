package org.kolobok.transformer;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class LogContextResultMaskIntegrationTest {

    @Test
    public void instrumentsResultFormatting() throws Exception {
        Path tempDir = Files.createTempDirectory("kolobok-log-result");
        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir.resolve("sample"));
        Files.createDirectories(classesDir);

        writeSources(srcDir);
        compileSources(srcDir, classesDir);

        Path classFile = classesDir.resolve("sample/SampleService.class");
        KolobokTransformer transformer = new KolobokTransformer();
        transformer.transformClassFile(classFile);

        ClassNode classNode = readClassNode(classFile);
        MethodNode method = findMethod(classNode, "work", "(Ljava/lang/String;)Ljava/lang/String;");
        assertThat(method).isNotNull();

        boolean hasFormatResult = Arrays.stream(method.instructions.toArray())
                .filter(node -> node instanceof MethodInsnNode)
                .map(node -> (MethodInsnNode) node)
                .anyMatch(node -> "org/kolobok/runtime/LogContextTrace".equals(node.owner)
                        && "formatResult".equals(node.name));
        assertThat(hasFormatResult).isTrue();
    }

    private void writeSources(Path srcDir) throws IOException {
        String service = String.join("\n",
                "package sample;",
                "",
                "import org.kolobok.annotation.DebugLog;",
                "import org.slf4j.Logger;",
                "import org.slf4j.LoggerFactory;",
                "",
                "public class SampleService {",
                "    private static final Logger log = LoggerFactory.getLogger(SampleService.class);",
                "",
                "    @DebugLog(resultMask = \"2,4\", maxResultLength = 20)",
                "    public String work(String value) {",
                "        return \"value-\" + value;",
                "    }",
                "}",
                ""
        );
        Files.writeString(srcDir.resolve("sample/SampleService.java"), service);
    }

    private void compileSources(Path srcDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("System Java compiler is available").isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                Arrays.asList(srcDir.resolve("sample/SampleService.java").toFile())
        );

        List<String> options = Arrays.asList(
                "-d", classesDir.toString(),
                "-classpath", System.getProperty("java.class.path")
        );

        Boolean result = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
        fileManager.close();

        if (result == null || !result) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                sb.append(diagnostic.getKind()).append(": ")
                        .append(diagnostic.getMessage(null)).append("\n");
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    private ClassNode readClassNode(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private MethodNode findMethod(ClassNode classNode, String name, String desc) {
        Optional<MethodNode> method = classNode.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst();
        return method.orElse(null);
    }
}
