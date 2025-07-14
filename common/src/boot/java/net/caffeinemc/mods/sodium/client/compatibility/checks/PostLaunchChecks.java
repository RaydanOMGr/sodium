package net.caffeinemc.mods.sodium.client.compatibility.checks;

import net.caffeinemc.mods.sodium.client.compatibility.environment.GlContextInfo;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.nvidia.NvidiaWorkarounds;
import net.caffeinemc.mods.sodium.client.platform.NativeWindowHandle;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Performs OpenGL driver validation after the game creates an OpenGL context. This runs immediately after OpenGL
 * context creation, and uses the implementation details of the OpenGL context to perform validation.
 */
public class PostLaunchChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-PostlaunchChecks");

    public static void onContextInitialized(NativeWindowHandle window, GlContextInfo context) {
        GraphicsDriverChecks.postContextInit(window, context);
        NvidiaWorkarounds.applyContextChanges(context);

        // FIXME: This can be determined earlier, but we can't access the GUI classes in pre-launch
        if (isUsingPojavLauncher()) {
            throw new RuntimeException("It appears that you are using PojavLauncher, which is not supported when " +
                    "using Sodium. Please check your mods list.");
        }
    }

    // https://github.com/CaffeineMC/sodium/issues/1916
    private static boolean isUsingPojavLauncher() {
        if (System.getenv("POJAV_RENDERER") != null) {
            LOGGER.warn("Detected presence of environment variable POJAV_LAUNCHER, which seems to indicate we are running on Android");

            return true;
        }

        var librarySearchPaths = System.getProperty("java.library.path", null);

        if (librarySearchPaths != null) {
            for (var path : librarySearchPaths.split(Pattern.quote(File.pathSeparator))) {
                if (isKnownAndroidPathFragment(path)) {
                    LOGGER.warn("Found a library search path which seems to be hosted in an Android filesystem: {}", path);

                    return true;
                }
                Path searchPath = Path.of(path);
                try(var walker = Files.walk(searchPath, 1)) {
                    Optional<Path> pojavExec = walker.filter(p -> p.getFileName().toString().toLowerCase().contains("pojavexec")).findFirst();
                    if(pojavExec.isPresent()) {
                        LOGGER.warn("Found a pojav library in the library search path: {}", pojavExec.get());
                        return true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to walk through {}", path);
                }
            }
        }

        try {
            ClassReader reader = new ClassReader(GLFW.class.getName());
            List<String> literals = new ArrayList<>();
            reader.accept(new SearchForStringVisitor(literals), 0);

            for (String string : literals) {
                if(string.toLowerCase().contains("pojav")) {
                    LOGGER.warn("Found a mention of pojav in GLFW's literal strings: {}", string);
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}!", GLFW.class.getName(), e);
        }

        var workingDirectory = System.getProperty("user.home", null);

        if (workingDirectory != null) {
            if (isKnownAndroidPathFragment(workingDirectory)) {
                LOGGER.warn("Working directory seems to be hosted in an Android filesystem: {}", workingDirectory);
            }
        }

        String glVer = GL11.glGetString(GL11.GL_VERSION);
        if(glVer != null && glVer.toLowerCase().contains("openltw")) {
            LOGGER.warn("Detected LTW renderer in OpenGL version string: {}", glVer);
            return true;
        }

        return false;
    }

    private static boolean isKnownAndroidPathFragment(String path) {
        return path.matches("/data/user/[0-9]+/net\\.kdt\\.pojavlaunch");
    }

    static class SearchForStringVisitor extends ClassVisitor {
        private final List<String> literals;
        public SearchForStringVisitor(List<String> literals) {
            super(Opcodes.ASM9);
            this.literals = literals;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (value instanceof String s) {
                literals.add(s);
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitLdcInsn(Object value) {
                    if(value instanceof String s) {
                        literals.add(s);
                    }
                }
            };
        }
    }
}
