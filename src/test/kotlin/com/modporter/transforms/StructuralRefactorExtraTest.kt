package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Additional tests for StructuralRefactorPass edge cases and error handling.
 */
class StructuralRefactorExtraTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve(filename).writeText(content)
        return tempDir
    }

    @Test
    fun `handles unparseable file gracefully`() {
        val projectDir = createFile("Broken.java", "this is not valid java {{{")
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        // Should not crash, may have 0 changes
        assertTrue(result.errors.isEmpty() || result.changeCount == 0)
    }

    @Test
    fun `handles file with no capability patterns`() {
        val projectDir = createFile("Clean.java", """
            package com.example;
            public class Clean {
                int x = 5;
                void doStuff() { System.out.println("hello"); }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertEquals(0, result.changeCount)
    }

    @Test
    fun `detects ifPresent and orElse on capability-related expressions`() {
        val projectDir = createFile("CapUsage.java", """
            package com.example;
            public class CapUsage {
                void use() {
                    Object handler = getCapability(cap, side);
                    handler.ifPresent(h -> use(h));
                    Object fallback = handler.orElse(null);
                    Object resolved = cap.resolve().orElseThrow();
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertTrue(result.changes.any { it.ruleId == "struct-lazy-optional-method" },
            "Should detect ifPresent/orElse/orElseThrow on cap-related code")
    }

    @Test
    fun `detects EventNetworkChannel`() {
        val projectDir = createFile("EventNet.java", """
            package com.example;
            public class EventNet {
                private EventNetworkChannel channel;
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertTrue(result.changes.any { it.ruleId == "struct-networking-channel" })
    }

    @Test
    fun `apply mode writes changes to file`() {
        val projectDir = createFile("ApplyTest.java", """
            package com.example;
            import net.minecraftforge.common.capabilities.ICapabilityProvider;
            public class ApplyTest implements ICapabilityProvider {
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    return null;
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        assertTrue(result.changeCount > 0)
    }

    @Test
    fun `detects newSimpleChannel call`() {
        val projectDir = createFile("NetReg.java", """
            package com.example;
            public class NetReg {
                void init() {
                    Object ch = NetworkRegistry.newSimpleChannel(id, ver, c, s);
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertTrue(result.changes.any { it.ruleId == "struct-networking-register" })
    }

    @Test
    fun `empty project returns empty results`() {
        val projectDir = tempDir.resolve("empty-project")
        projectDir.createDirectories()
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertEquals(0, result.changeCount)
        assertTrue(result.errors.isEmpty())
    }
}
