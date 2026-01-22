package org.kolobok.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.kolobok.transformer.DebugLogDefaults;
import org.kolobok.transformer.KolobokTransformer;

import java.io.File;
import java.io.IOException;

public abstract class KolobokTransformTask extends DefaultTask {

    private final ConfigurableFileCollection classesDirs = getProject().files();

    @InputFiles
    public ConfigurableFileCollection getClassesDirs() {
        return classesDirs;
    }

    @TaskAction
    public void transform() throws IOException {
        Object skipProp = getProject().findProperty("kolobok.skip");
        if (skipProp != null && Boolean.parseBoolean(skipProp.toString())) {
            getLogger().lifecycle("Kolobok transform skipped");
            return;
        }
        DebugLogDefaults defaults = DebugLogDefaults.fromSystemEnv();
        KolobokExtension extension = getProject().getExtensions().findByType(KolobokExtension.class);
        if (extension != null) {
            defaults = defaults.merge(extension.getDebugLogDefaults().toDefaults());
        }
        KolobokTransformer transformer = new KolobokTransformer(defaults);
        for (File dir : classesDirs) {
            transformer.transformDirectory(dir.toPath());
        }
    }
}
