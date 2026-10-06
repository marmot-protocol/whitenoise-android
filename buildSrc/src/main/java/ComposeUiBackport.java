import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.ExecOperations;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Builds the four upstream source files against the exact released AAR, preserving other bytes. */
@CacheableTransform
public abstract class ComposeUiBackport implements TransformAction<ComposeUiBackport.Parameters> {
    private static final String AAR_SHA =
            "3f1ae17e237d9dc0a2a0f42cb04b85b8de6e12f67adc5f5c12173fe31e501cb2";
    private static final String SOURCES_SHA =
            "8ddde80690da97cd840c6f8f59c608701f9e42c245f12f0eabc1434db57e0273";
    private static final String COMMIT = "fd550bed793b66378c83091532e29c18fdef44cc";
    private static final String PACKAGE = "androidx/compose/ui/node/";
    private static final Map<String, String> PATCHED_SOURCES = Map.of(
            "LayoutNodeAlignmentLines.kt", "43317a0e99b5fde36f2f9bda4033ed1940fda75042f73aabee490ca0f32c0dee",
            "LayoutNodeLayoutDelegate.kt", "fd01bf142d2d877e35913b8a603e48869809c9bc1f1242f66136b6327e849078",
            "LookaheadPassDelegate.kt", "35ba4d7eb1e9e51d808b20760b57192d6a497e7258e746c7ef033890836cb512",
            "MeasurePassDelegate.kt", "17eb1756c29038076c96a1f09a095fc1afcd1ce47038811c3dd12d6dbcbfec50");

    /** Immutable build inputs; the compiler's classpath deliberately resolves unpatched artifacts. */
    public interface Parameters extends TransformParameters {
        @Classpath ConfigurableFileCollection getCompilerClasspath();
        @Classpath ConfigurableFileCollection getComposeCompiler();
        @Classpath ConfigurableFileCollection getOriginalClasspath();
        @InputFile @PathSensitive(PathSensitivity.NONE) RegularFileProperty getSources();
        @InputFile @PathSensitive(PathSensitivity.NONE) RegularFileProperty getPatch();
        @Classpath ConfigurableFileCollection getAndroidJar();
    }

    @InputArtifact
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Inject
    protected abstract ExecOperations getExecOperations();

    /** Passes unrelated libraries through; a new Compose UI artifact must remove this backport. */
    @Override
    public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        if (!input.getName().startsWith("ui-android-")) {
            outputs.file(input);
            return;
        }
        try {
            verifyHash(input.toPath(), AAR_SHA);
            Path sourceJar = getParameters().getSources().get().getAsFile().toPath();
            verifyHash(sourceJar, SOURCES_SHA);
            File output = outputs.file("ui-android-1.12.1-whitenoise-rectlist.aar");
            Path work = output.toPath().getParent().resolve("compile");
            Files.createDirectories(work);
            Path sourceRoot = work.resolve("compose/ui/ui/src/commonMain/kotlin/" + PACKAGE);
            Files.createDirectories(sourceRoot);
            Map<String, byte[]> sources = readZip(Files.readAllBytes(sourceJar));
            List<String> sourcePaths = new ArrayList<>();
            for (String name : PATCHED_SOURCES.keySet().stream().sorted().toList()) {
                Path source = sourceRoot.resolve(name);
                Files.write(source, required(sources, "commonMain/" + PACKAGE + name));
                sourcePaths.add(source.toString());
            }
            applyPatch(work, true);
            applyPatch(work, false);
            for (var expected : PATCHED_SOURCES.entrySet()) {
                verifyHash(sourceRoot.resolve(expected.getKey()), expected.getValue());
            }

            Map<String, byte[]> aar = readZip(Files.readAllBytes(input.toPath()));
            Path original = work.resolve("ui-original.jar");
            Files.write(original, required(aar, "classes.jar"));
            List<String> classpath = new ArrayList<>(List.of(original.toString()));
            int index = 0;
            for (File dependency : getParameters().getOriginalClasspath().getFiles().stream().sorted().toList()) {
                if (dependency.getName().endsWith(".aar")) {
                    Map<String, byte[]> entries = readZip(Files.readAllBytes(dependency.toPath()));
                    for (var entry : entries.entrySet()) {
                        if (entry.getKey().equals("classes.jar") ||
                                (entry.getKey().startsWith("libs/") && entry.getKey().endsWith(".jar"))) {
                            Path jar = work.resolve("classpath-" + index++ + ".jar");
                            Files.write(jar, entry.getValue());
                            classpath.add(jar.toString());
                        }
                    }
                } else {
                    classpath.add(dependency.toString());
                }
            }
            getParameters().getAndroidJar().forEach(file -> classpath.add(file.toString()));
            Path classes = work.resolve("classes");
            List<String> args = new ArrayList<>(List.of(
                    "-no-stdlib", "-no-reflect", "-jvm-target", "11",
                    "-language-version", "2.1", "-api-version", "2.1", "-module-name", "ui",
                    "-jvm-default=no-compatibility", "-Xlambdas=class",
                    "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions",
                    "-Xfriend-paths=" + original,
                    "-Xplugin=" + getParameters().getComposeCompiler().getSingleFile(),
                    "-classpath", String.join(File.pathSeparator, classpath), "-d", classes.toString()));
            args.addAll(sourcePaths);
            getExecOperations().javaexec(spec -> {
                spec.setClasspath(getParameters().getCompilerClasspath());
                spec.getMainClass().set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
                spec.setArgs(args);
                spec.setMaxHeapSize("2g");
            }).assertNormalExitValue();

            Map<String, byte[]> originalClasses = readZip(required(aar, "classes.jar"));
            Map<String, byte[]> replacements = new TreeMap<>();
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
                    byte[] bytes = Files.readAllBytes(file);
                    if (!isPatchedClass(name, bytes)) throw new GradleException("Unexpected compiled class: " + name);
                    replacements.put(name, bytes);
                }
            }
            for (var entry : originalClasses.entrySet()) {
                if (isPatchedClass(entry.getKey(), entry.getValue())) {
                    verifyAbi(entry.getKey(), entry.getValue(), required(replacements, entry.getKey()));
                }
            }
            if (replacements.isEmpty()) throw new GradleException("Compose backport emitted no classes");
            originalClasses.putAll(replacements);
            // Retain original kotlin_module mappings: no top-level declaration is added by this patch.
            originalClasses.put("META-INF/whitenoise-compose-rectlist-backport.properties",
                    ("upstream=" + COMMIT + "\nbase=1.12.1\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            aar.put("classes.jar", writeZip(originalClasses));
            Files.write(output.toPath(), writeZip(aar));
        } catch (Exception exception) {
            throw new GradleException("Compose 1.12.1 source backport failed; do not bypass the guard", exception);
        }
    }

    /** Prevents an enclosing checkout or inherited Git variables from changing patch semantics. */
    private void applyPatch(Path work, boolean checkOnly) {
        getExecOperations().exec(spec -> {
            Map<String, Object> environment = new HashMap<>(spec.getEnvironment());
            environment.keySet().removeIf(name -> name.startsWith("GIT_"));
            environment.put("GIT_CEILING_DIRECTORIES", work.getParent().toString());
            spec.setEnvironment(environment);
            spec.setWorkingDir(work);
            List<String> command = new ArrayList<>(List.of("git", "apply"));
            if (checkOnly) command.add("--check");
            command.add(getParameters().getPatch().get().getAsFile().toString());
            spec.commandLine(command);
        }).assertNormalExitValue();
    }

    /** Rejects changed Google artifacts rather than silently patching an unreviewed version. */
    private static void verifyHash(Path file, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!expected.equals(actual)) {
            throw new GradleException("Unexpected Compose artifact " + file.getFileName() + ": " + actual +
                    ". Verify the official fix, then remove the backport when upgrading Compose.");
        }
    }

    /** Reads archive members in deterministic order and refuses ambiguous duplicate entries. */
    private static Map<String, byte[]> readZip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entries.put(entry.getName(), zip.readAllBytes()) != null) {
                    throw new GradleException("Duplicate archive entry: " + entry.getName());
                }
            }
        }
        return entries;
    }

    /** Writes stable archive bytes without host timestamps or filesystem ordering. */
    private static byte[] writeZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                ZipEntry member = new ZipEntry(entry.getKey());
                member.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(member);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Requires expected source/archive members before changing any packaged content. */
    private static byte[] required(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name);
        if (bytes == null) throw new GradleException("Missing backport input/output: " + name);
        return bytes;
    }

    /** Limits replacement to classes produced by the four files in Google's patch. */
    private static boolean isPatchedClass(String name, byte[] bytes) {
        if (!name.startsWith(PACKAGE) || !name.endsWith(".class")) return false;
        String source = classNode(bytes).sourceFile;
        return source != null && PATCHED_SOURCES.containsKey(source);
    }

    /** Parses declarations without executing dependency bytecode. */
    private static ClassNode classNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return node;
    }

    /** Prevents recompilation from breaking unchanged Compose classes' JVM linkage. */
    private static void verifyAbi(String name, byte[] before, byte[] after) {
        ClassNode oldNode = classNode(before);
        ClassNode newNode = classNode(after);
        if (!java.util.Objects.equals(oldNode.superName, newNode.superName) ||
                !newNode.interfaces.containsAll(oldNode.interfaces) || oldNode.version != newNode.version ||
                !members(newNode).containsAll(members(oldNode))) {
            throw new GradleException("Backport changes existing JVM linkage: " + name);
        }
    }

    /** Captures externally accessible member descriptors, access levels and static/final contracts. */
    private static Set<String> members(ClassNode node) {
        Set<String> members = new HashSet<>();
        node.fields.stream().filter(field -> (field.access & Opcodes.ACC_PRIVATE) == 0)
                .forEach(field -> members.add("field:" + field.name + field.desc + ":" + field.access));
        node.methods.stream().filter(method -> (method.access & Opcodes.ACC_PRIVATE) == 0)
                .forEach(method -> members.add("method:" + method.name + method.desc + ":" + method.access));
        return members;
    }
}
