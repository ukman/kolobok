package org.kolobok.transformer;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
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

public class FindWithOptionalParamsIntegrationTest {

    @Test
    public void transformsFindWithOptionalParamsMethod() throws Exception {
        Path tempDir = Files.createTempDirectory("kolobok-it");
        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir.resolve("sample"));
        Files.createDirectories(classesDir);

        writeSources(srcDir);
        compileSources(srcDir, classesDir);

        Path classFile = classesDir.resolve("sample/PersonRepository.class");
        KolobokTransformer transformer = new KolobokTransformer();
        transformer.transformClassFile(classFile);

        ClassNode classNode = readClassNode(classFile);
        MethodNode original = findMethod(classNode, "findByIdAndName", "(Ljava/lang/Long;Ljava/lang/String;)Ljava/lang/Iterable;");
        assertThat(original).isNotNull();
        assertThat((original.access & Opcodes.ACC_ABSTRACT) == 0).isTrue();

        MethodNode idMethod = findMethod(classNode, "id", "(Ljava/lang/Long;)Ljava/lang/Iterable;");
        MethodNode nameMethod = findMethod(classNode, "name", "(Ljava/lang/String;)Ljava/lang/Iterable;");
        MethodNode idAndName = findMethod(classNode, "idAndName", "(Ljava/lang/Long;Ljava/lang/String;)Ljava/lang/Iterable;");

        assertThat(idMethod).isNotNull();
        assertThat(nameMethod).isNotNull();
        assertThat(idAndName).isNotNull();
        assertThat((idMethod.access & Opcodes.ACC_ABSTRACT) != 0).isTrue();
    }

    private void writeSources(Path srcDir) throws IOException {
        String person = """
                package sample;

                public class Person {
                    private Long id;
                    private String name;
                }
                """;
        String repository = """
                package sample;

                import org.kolobok.annotation.FindWithOptionalParams;
                import java.lang.Iterable;

                public interface PersonRepository {
                    @FindWithOptionalParams
                    Iterable<Person> findByIdAndName(Long id, String name);
                }
                """;

        Files.writeString(srcDir.resolve("sample/Person.java"), person);
        Files.writeString(srcDir.resolve("sample/PersonRepository.java"), repository);
    }

    private void compileSources(Path srcDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("System Java compiler is available").isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                Arrays.asList(
                        srcDir.resolve("sample/Person.java").toFile(),
                        srcDir.resolve("sample/PersonRepository.java").toFile()
                )
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
