package org.kolobok.transformer;

import org.junit.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LogContextLineNumberTest {

    @Test
    public void preservesLineNumberForThrownException() throws Exception {
        Path tempDir = Files.createTempDirectory("kolobok-log-lines");
        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir.resolve("sample"));
        Files.createDirectories(classesDir);

        int expectedLine = writeSources(srcDir);
        compileSources(srcDir, classesDir);

        Path classFile = classesDir.resolve("sample/SampleService.class");
        KolobokTransformer transformer = new KolobokTransformer();
        transformer.transformClassFile(classFile);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            Class<?> clazz = loader.loadClass("sample.SampleService");
            Object instance = clazz.getDeclaredConstructor().newInstance();
            Method explode = clazz.getMethod("explode");
            try {
                explode.invoke(instance);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                assertThat(cause).isInstanceOf(IllegalStateException.class);
                int actualLine = findLine(cause.getStackTrace(), "sample.SampleService", "explode");
                assertThat(actualLine).isEqualTo(expectedLine);
                return;
            }
        }

        throw new AssertionError("Expected exception was not thrown");
    }

    private int writeSources(Path srcDir) throws IOException {
        String[] lines = new String[]{
                "package sample;",
                "",
                "import org.kolobok.annotation.DebugLog;",
                "import org.slf4j.Logger;",
                "import org.slf4j.LoggerFactory;",
                "",
                "public class SampleService {",
                "    private static final Logger log = LoggerFactory.getLogger(SampleService.class);",
                "",
                "    @DebugLog",
                "    public void explode() {",
                "        throw new IllegalStateException(\"boom\");",
                "    }",
                "}",
                ""
        };
        Files.writeString(srcDir.resolve("sample/SampleService.java"), String.join("\n", lines));
        return findLine(lines, "        throw new IllegalStateException(\"boom\");");
    }

    private int findLine(String[] lines, String needle) {
        for (int i = 0; i < lines.length; i++) {
            if (needle.equals(lines[i])) {
                return i + 1;
            }
        }
        throw new IllegalStateException("Line not found: " + needle);
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

    private int findLine(StackTraceElement[] stack, String className, String methodName) {
        for (StackTraceElement element : stack) {
            if (className.equals(element.getClassName()) && methodName.equals(element.getMethodName())) {
                return element.getLineNumber();
            }
        }
        return -1;
    }
}
