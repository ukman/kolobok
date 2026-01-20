package org.kolobok.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kolobok.transformer.KolobokTransformer;

import java.io.IOException;
import java.nio.file.Path;

@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class KolobokMavenMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private String classesDirectory;

    @Parameter(property = "kolobok.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Kolobok transform skipped");
            return;
        }
        if (classesDirectory == null || classesDirectory.isEmpty()) {
            getLog().info("Kolobok classes directory not set, skipping");
            return;
        }
        try {
            KolobokTransformer transformer = new KolobokTransformer();
            transformer.transformDirectory(Path.of(classesDirectory));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to transform classes in " + classesDirectory, e);
        }
    }
}
