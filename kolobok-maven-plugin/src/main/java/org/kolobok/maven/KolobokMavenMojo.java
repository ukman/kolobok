package org.kolobok.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kolobok.annotation.DebugLog;
import org.kolobok.transformer.DebugLogDefaults;
import org.kolobok.transformer.KolobokTransformer;

import java.io.IOException;
import java.nio.file.Path;

@Mojo(name = "transform", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class KolobokMavenMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private String classesDirectory;

    @Parameter(property = "kolobok.skip", defaultValue = "false")
    private boolean skip;

    @Parameter
    private DebugLogDefaultsConfig debugLogDefaults;

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
            DebugLogDefaults defaults = DebugLogDefaults.fromSystemEnv();
            if (debugLogDefaults != null) {
                defaults = defaults.merge(debugLogDefaults.toDefaults());
            }
            KolobokTransformer transformer = new KolobokTransformer(defaults);
            transformer.transformDirectory(Path.of(classesDirectory));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to transform classes in " + classesDirectory, e);
        }
    }

    public static class DebugLogDefaultsConfig {
        @Parameter
        private Boolean lineHeatMap;
        @Parameter
        private Boolean lineHeatMapOnException;
        @Parameter
        private Boolean subHeatMap;
        @Parameter
        private Boolean logDuration;
        @Parameter
        private Boolean aggregateChildren;
        @Parameter
        private Boolean logArgs;
        @Parameter
        private String mask;
        @Parameter
        private Integer maxArgLength;
        @Parameter
        private String logLevel;
        @Parameter
        private String logFormat;
        @Parameter
        private Boolean logThreadId;
        @Parameter
        private Boolean logThreadName;
        @Parameter
        private Boolean logLocals;
        @Parameter
        private Boolean logLocalsOnException;

        private DebugLogDefaults toDefaults() {
            DebugLogDefaults defaults = new DebugLogDefaults();
            defaults.setLineHeatMap(lineHeatMap);
            defaults.setLineHeatMapOnException(lineHeatMapOnException);
            defaults.setSubHeatMap(subHeatMap);
            defaults.setLogDuration(logDuration);
            defaults.setAggregateChildren(aggregateChildren);
            defaults.setLogArgs(logArgs);
            defaults.setMask(mask);
            defaults.setMaxArgLength(maxArgLength);
            if (logLevel != null) {
                defaults.setLogLevel(parseEnum(DebugLog.LogLevel.class, logLevel));
            }
            if (logFormat != null) {
                defaults.setLogFormat(parseEnum(DebugLog.LogFormat.class, logFormat));
            }
            defaults.setLogThreadId(logThreadId);
            defaults.setLogThreadName(logThreadName);
            defaults.setLogLocals(logLocals);
            defaults.setLogLocalsOnException(logLocalsOnException);
            return defaults;
        }

        private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }
}
