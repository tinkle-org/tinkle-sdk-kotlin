package com.tinkle.build

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import javax.tools.ToolProvider
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

import static org.junit.Assert.*

class GenerateInstrumentationKeepsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder()

    File compile(Map<String, String> sources) {
        File root = temp.newFolder()
        File output = new File(root, 'classes').tap { mkdirs() }
        List<String> arguments = ['-d', output.path]
        sources.each { name, code ->
            File file = new File(root, "${name}.java")
            file.parentFile.mkdirs()
            file.text = code
            arguments.add(file.path)
        }
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null, arguments as String[]))
        output
    }

    GenerateInstrumentationKeeps fixture(String packageName = '') {
        String pathPrefix = packageName ? packageName.replace('.', '/') + '/' : ''
        String declaration = packageName ? "package ${packageName}; " : ''
        File compiled = compile([
            Base: 'public class Base { public Base(int n) {} public int inherited() { return 1; } public void unused() {} }',
            Child: 'public class Child extends Base { public Child() { super(1); } public int direct() { return 2; } }',
            Unused: 'public class Unused { public void notTested() {} }',
            TestCode: 'public class TestCode extends Base { public TestCode() { super(7); } public int test() { Child c = new Child(); return c.direct() + c.inherited(); } }'
        ].collectEntries { name, code -> [(pathPrefix + name): declaration + code] })
        File source = temp.newFolder()
        File testClass = new File(source, pathPrefix + 'TestCode.class')
        testClass.parentFile.mkdirs()
        Files.move(new File(compiled, pathPrefix + 'TestCode.class').toPath(), testClass.toPath())
        def project = ProjectBuilder.builder().withProjectDir(temp.newFolder()).build()
        def task = project.tasks.create('trace', GenerateInstrumentationKeeps)
        task.targetJars.set([])
        task.targetDirectories.set([project.layout.projectDirectory.dir(compiled.path)])
        task.sourceJars.set([])
        task.sourceDirectories.set([project.layout.projectDirectory.dir(source.path)])
        File platform = temp.newFolder()
        File object = new File(platform, 'java/lang/Object.class')
        object.parentFile.mkdirs()
        object.bytes = Object.getResourceAsStream('Object.class').withCloseable { it.bytes }
        task.libraries.from(platform)
        task.rulesFile.set(new File(project.projectDir, 'build/keeps.pro'))
        task
    }

    @Test void tracesExactMembersIncludingInheritedMethodAndSuperclassConstructor() {
        def task = fixture()
        task.generate()
        String rules = task.rulesFile.get().asFile.text
        assertTrue(rules, rules.contains('-keep class Child'))
        assertTrue(rules, rules.contains('public int direct();'))
        assertTrue(rules, rules.contains('-keep class Base'))
        assertTrue(rules, rules.contains('public <init>(int);'))
        assertTrue(rules, rules.contains('public int inherited();'))
        assertFalse(rules, rules.contains('unused'))
        assertFalse(rules, rules.contains('Unused'))
        assertFalse(rules, rules.contains('TestCode'))
        assertFalse(rules, rules.contains('*'))
    }

    @Test void overlappingRuntimeStaysTargetAndDoesNotTraceAppInternals() {
        def task = fixture()
        // Model Kotlin/JNA/app classes also appearing on test's ALL classpath.
        task.sourceDirectories.addAll(task.targetDirectories)
        task.generate()
        String rules = task.rulesFile.get().asFile.text
        assertTrue(rules, rules.contains('public int direct();'))
        assertTrue(rules, rules.contains('public <init>(int);'))
        assertFalse(rules, rules.contains('notTested'))
        assertFalse(rules, rules.contains('unused'))
    }

    @Test void jarInputsProduceSameDeterministicRulesAsDirectories() {
        def task = fixture()
        task.generate()
        String expected = task.rulesFile.get().asFile.text
        File jar = temp.newFile('test.jar')
        new JarOutputStream(new FileOutputStream(jar)).withCloseable { output ->
            output.putNextEntry(new JarEntry('TestCode.class'))
            output.write(new File(task.sourceDirectories.get()[0].asFile, 'TestCode.class').bytes)
            output.closeEntry()
        }
        task.sourceDirectories.set([])
        task.sourceJars.set([task.project.layout.projectDirectory.file(jar.path)])
        task.generate()
        assertEquals(expected, task.rulesFile.get().asFile.text)
    }

    @Test void packagedClassesHaveEquivalentDirectoryAndJarOwnership() {
        def task = fixture('fixture.runtime')
        File appDirectory = task.targetDirectories.get()[0].asFile
        File testDirectory = task.sourceDirectories.get()[0].asFile
        task.generate()
        String expected = task.rulesFile.get().asFile.text
        assertTrue(expected, expected.contains('-keep class fixture.runtime.Child'))
        assertTrue(expected, expected.contains('public int direct();'))
        assertTrue(expected, expected.contains('public <init>(int);'))
        assertFalse(expected, expected.contains('Unused'))
        File appJar = classJar(appDirectory, 'packaged-app.jar')
        File testJar = classJar(testDirectory, 'packaged-test.jar')

        // App directory targets overlap with app JAR classes on the test input.
        task.sourceDirectories.set([])
        task.sourceJars.set([appJar, testJar].collect { task.project.layout.projectDirectory.file(it.path) })
        task.generate()
        assertEquals(expected, task.rulesFile.get().asFile.text)
        assertOnlyPackagedTestSource(task)

        // Reverse the representations: app JAR targets, overlapping directories.
        task.targetDirectories.set([])
        task.targetJars.set([task.project.layout.projectDirectory.file(appJar.path)])
        task.sourceJars.set([])
        task.sourceDirectories.set([appDirectory, testDirectory].collect { task.project.layout.projectDirectory.dir(it.path) })
        task.generate()
        assertEquals(expected, task.rulesFile.get().asFile.text)
        assertOnlyPackagedTestSource(task)
    }

    @Test void windowsDirectoryNamesNormalizeToJvmArchiveNames() {
        assertEquals('fixture/runtime/Child.class',
            GenerateInstrumentationKeeps.classEntryName('fixture\\runtime\\Child.class'))
        assertEquals('fixture/runtime/Child.class',
            GenerateInstrumentationKeeps.classEntryName('fixture/runtime/Child.class'))
    }

    private File classJar(File directory, String name) {
        File jar = temp.newFile(name)
        new JarOutputStream(new FileOutputStream(jar)).withCloseable { output ->
            directory.eachFileRecurse { File file ->
                if (file.file && file.name.endsWith('.class')) {
                    String entry = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)
                    output.putNextEntry(new JarEntry(entry))
                    output.write(file.bytes)
                    output.closeEntry()
                }
            }
        }
        jar
    }

    private static void assertOnlyPackagedTestSource(GenerateInstrumentationKeeps task) {
        new ZipFile(new File(task.temporaryDir, 'test.jar')).withCloseable { zip ->
            assertEquals(['fixture/runtime/TestCode.class'], zip.entries().collect { it.name })
        }
    }

    @Test void missingClassFailsWithDiagnosticAndRemovesStaleRules() {
        def task = fixture()
        task.generate()
        new File(task.targetDirectories.get()[0].asFile, 'Child.class').delete()
        assertTraceFailure(task, 'Child')
    }

    @Test void missingMemberFailsRatherThanSilentlyEmittingPartialKeeps() {
        def task = fixture()
        File replacement = compile([
            Base: 'public class Base { public Base(int n) {} public int inherited() { return 1; } }',
            Child: 'public class Child extends Base { public Child() { super(1); } }'
        ])
        new File(task.targetDirectories.get()[0].asFile, 'Child.class').bytes = new File(replacement, 'Child.class').bytes
        assertTraceFailure(task, 'direct')
    }

    @Test void conflictingProgramDefinitionsFail() {
        def task = fixture()
        task.generate()
        File duplicate = compile([Child: 'public class Child {}'])
        task.targetDirectories.add(task.project.layout.projectDirectory.dir(duplicate.path))
        try {
            task.generate()
            fail('Conflicting program definitions must fail')
        } catch (GradleException failure) {
            assertTrue(failure.message, failure.message.contains('Conflicting definitions of Child.class'))
        }
        assertFalse(task.rulesFile.get().asFile.exists())
    }

    @Test void compileLibrariesDoNotShadowAppOwnedTargets() {
        def task = fixture()
        task.libraries.from(task.targetDirectories)
        task.generate()
        assertTrue(task.rulesFile.get().asFile.text.contains('public int direct();'))
    }

    @Test void platformStubExceptionMatchesOnlyAnExactMethodSignature() {
        def task = fixture()
        File replacement = compile([
            Base: 'public class Base { public Base(int n) {} public int inherited() { return 1; } }',
            Child: 'public class Child extends Base { public Child() { super(1); } }'
        ])
        new File(task.targetDirectories.get()[0].asFile, 'Child.class').bytes = new File(replacement, 'Child.class').bytes
        task.platformStubMethods.set(['int Child.direct(int)'])
        assertTraceFailure(task, 'direct')
        task.platformStubMethods.set(['int Child.direct()'])
        task.generate()
        assertTrue(task.rulesFile.get().asFile.text.contains('public int inherited();'))
    }

    private static void assertTraceFailure(GenerateInstrumentationKeeps task, String missing) {
        try {
            task.generate()
            fail('Unresolved references must fail')
        } catch (GradleException failure) {
            assertTrue(failure.message, failure.message.contains(missing))
        }
        assertFalse(task.rulesFile.get().asFile.exists())
    }
}
