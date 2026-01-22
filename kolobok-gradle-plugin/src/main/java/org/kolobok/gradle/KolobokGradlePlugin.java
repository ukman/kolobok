package org.kolobok.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

public class KolobokGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().create("kolobok", KolobokExtension.class);
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            TaskProvider<KolobokTransformTask> transformTask = project.getTasks().register(
                    "kolobokTransform",
                    KolobokTransformTask.class,
                    task -> task.getClassesDirs().from(main.getOutput().getClassesDirs())
            );

            project.getTasks().named("classes").configure(task -> task.finalizedBy(transformTask));
        });
    }
}
