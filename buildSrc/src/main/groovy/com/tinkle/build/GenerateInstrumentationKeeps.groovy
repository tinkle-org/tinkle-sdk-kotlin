package com.tinkle.build

import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.StringConsumer
import com.android.tools.r8.tracereferences.TraceReferences
import com.android.tools.r8.tracereferences.TraceReferencesCommand
import com.android.tools.r8.tracereferences.TraceReferencesCheckConsumer
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules
import com.android.tools.r8.tracereferences.TraceReferencesConsumer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/** App-owned classes are targets; only test-owned bytecode is traced as source. */
@CacheableTask
abstract class GenerateInstrumentationKeeps extends DefaultTask {
    @Classpath abstract ListProperty<RegularFile> getTargetJars()
    @Classpath abstract ListProperty<Directory> getTargetDirectories()
    @Classpath abstract ListProperty<RegularFile> getSourceJars()
    @Classpath abstract ListProperty<Directory> getSourceDirectories()
    @Classpath abstract ConfigurableFileCollection getLibraries()
    /** Exact source-form method signatures for documented platform stub gaps. */
    @Input abstract SetProperty<String> getPlatformStubMethods()
    @OutputFile abstract RegularFileProperty getRulesFile()

    GenerateInstrumentationKeeps() {
        platformStubMethods.convention([])
    }

    @TaskAction
    void generate() {
        File output = rulesFile.get().asFile
        // Do not leave a successful-looking stale output after any input failure.
        output.delete()
        Map<String, byte[]> targets = classes(targetJars.get()*.asFile + targetDirectories.get()*.asFile)
        Map<String, byte[]> sources = classes(sourceJars.get()*.asFile + sourceDirectories.get()*.asFile)
        // The app owns overlapping runtime classes (notably Kotlin). Leaving them
        // in source makes TraceReferences remove them from its target set entirely.
        sources.keySet().removeAll(targets.keySet())
        // Boot libraries precede compile libraries, just as on the compiler's
        // classpath (android.jar and core-lambda-stubs contain overlapping types).
        Map<String, byte[]> libraryClasses = classes(libraries.files, false)
        libraryClasses.keySet().removeAll(targets.keySet())
        libraryClasses.keySet().removeAll(sources.keySet())
        if (targets.empty || sources.empty) {
            throw new GradleException('Instrumentation tracing needs nonempty app and test classes')
        }
        File target = archive(new File(temporaryDir, 'app.jar'), targets)
        File source = archive(new File(temporaryDir, 'test.jar'), sources)
        File library = archive(new File(temporaryDir, 'libraries.jar'), libraryClasses)
        List<String> diagnostics = []
        DiagnosticsHandler handler = new DiagnosticsHandler() {
            @Override void error(Diagnostic diagnostic) { diagnostics.add(diagnostic.diagnosticMessage) }
            @Override void warning(Diagnostic diagnostic) { diagnostics.add(diagnostic.diagnosticMessage) }
        }
        StringBuilder rules = new StringBuilder()
        def consumer = TraceReferencesKeepRules.builder()
            .setOutputConsumer(new StringConsumer() {
                @Override void accept(String value, DiagnosticsHandler ignored) { rules.append(value) }
                @Override void finished(DiagnosticsHandler ignored) { }
            }).build()
        Set<String> platformMethods = platformStubMethods.get()
        def checked = new TraceReferencesCheckConsumer(consumer)
        def filtered = new TraceReferencesConsumer.ForwardingConsumer(checked) {
            @Override void acceptMethod(TraceReferencesConsumer.TracedMethod method, DiagnosticsHandler diagnosticsHandler) {
                String signature = method.reference.toSourceString()
                if (method.missingDefinition && platformMethods.contains(signature)) {
                    logger.info("Known platform SDK stub gap: ${signature}")
                } else {
                    super.acceptMethod(method, diagnosticsHandler)
                }
            }
        }
        try {
            TraceReferences.run(TraceReferencesCommand.builder(handler)
                .addLibraryFiles(library.toPath())
                .addTargetFiles(target.toPath())
                .addSourceFiles(source.toPath())
                // KeepRules alone omits unresolved references; CheckConsumer
                // diagnoses them while forwarding resolved members to KeepRules.
                .setConsumer(filtered).build())
        } catch (Exception failure) {
            throw new GradleException("Instrumentation reference tracing failed:\n${diagnostics.join('\n')}", failure)
        }
        if (!diagnostics.empty) {
            throw new GradleException("Instrumentation reference tracing reported diagnostics:\n${diagnostics.join('\n')}")
        }
        output.parentFile.mkdirs()
        output.setText('# Generated by R8 TraceReferences; app-owned test references only.\n' + rules, 'UTF-8')
    }

    private static Map<String, byte[]> classes(Collection<File> inputs, boolean rejectConflicts = true) {
        Map<String, byte[]> result = new TreeMap<>()
        inputs.each { File input ->
            if (input.directory) {
                input.eachFileRecurse { File file ->
                    if (file.file && file.name.endsWith('.class')) {
                        add(result, classEntryName(input.toPath().relativize(file.toPath()).toString()), file.bytes, rejectConflicts)
                    }
                }
            } else {
                new ZipFile(input).withCloseable { zip ->
                    zip.entries().each { entry ->
                        if (!entry.directory && entry.name.endsWith('.class') && !entry.name.startsWith('META-INF/')) {
                            add(result, entry.name, zip.getInputStream(entry).bytes, rejectConflicts)
                        }
                    }
                }
            }
        }
        result
    }

    static String classEntryName(String relativePath) {
        // JVM archive names use '/', including for directory inputs on Windows.
        relativePath.replace('\\', '/')
    }

    private static void add(Map<String, byte[]> classes, String name, byte[] bytes, boolean rejectConflicts) {
        byte[] previous = classes.putIfAbsent(name, bytes)
        if (rejectConflicts && previous != null && !Arrays.equals(previous, bytes)) {
            throw new GradleException("Conflicting definitions of ${name} on tracing classpath")
        }
    }

    private static File archive(File file, Map<String, byte[]> classes) {
        new JarOutputStream(new FileOutputStream(file)).withCloseable { jar ->
            classes.each { name, bytes ->
                def entry = new JarEntry(name)
                entry.time = 0
                jar.putNextEntry(entry)
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        file
    }
}
