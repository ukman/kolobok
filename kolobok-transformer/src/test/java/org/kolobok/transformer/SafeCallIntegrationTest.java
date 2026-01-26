package org.kolobok.transformer;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SafeCallIntegrationTest {

    @Test
    public void transformsSafeCallForLocalParamAndField() throws Exception {
        Path tempDir = Files.createTempDirectory("kolobok-safe-it");
        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir.resolve("sample"));
        Files.createDirectories(classesDir);

        writeSources(srcDir);
        compileSources(srcDir, classesDir);

        Path classFile = classesDir.resolve("sample/SampleSafeCall.class");
        KolobokTransformer transformer = new KolobokTransformer();
        transformer.transformClassFile(classFile);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()},
                SafeCallIntegrationTest.class.getClassLoader())) {
            Class<?> clazz = loader.loadClass("sample.SampleSafeCall");
            Object instance = clazz.getDeclaredConstructor().newInstance();

            Method safeLocal = clazz.getMethod("safeLocal");
            Method safeParam = clazz.getMethod("safeParam", String.class);
            Method safeField = clazz.getMethod("safeField");

            assertThat(safeLocal.invoke(instance)).isEqualTo(0);
            assertThat(safeParam.invoke(instance, new Object[]{null})).isEqualTo(0);
            assertThat(safeField.invoke(instance)).isEqualTo(0);
        }
    }

    private void writeSources(Path srcDir) throws IOException {
        String service = String.join("\n",
                "package sample;",
                "",
                "import java.util.List;",
                "import org.kolobok.annotation.SafeCall;",
                "",
                "public class SampleSafeCall {",
                "    @SafeCall",
                "    private int fieldInit = getBox().intValue();",
                "",
                "    private static Integer getBox() {",
                "        return null;",
                "    }",
                "",
                "    public int safeLocal() {",
                "        @SafeCall List<String> names = null;",
                "        @SafeCall int count = names.size();",
                "        return count;",
                "    }",
                "",
                "    public int safeParam(@SafeCall String name) {",
                "        return name.length();",
                "    }",
                "",
                "    public int safeField() {",
                "        return fieldInit;",
                "    }",
                "}",
                ""
        );
        Files.writeString(srcDir.resolve("sample/SampleSafeCall.java"), service);
    }

    private void compileSources(Path srcDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("System Java compiler is available").isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                Arrays.asList(srcDir.resolve("sample/SampleSafeCall.java").toFile())
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

    @SuppressWarnings("unused")
    private ClassNode readClassNode(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}
