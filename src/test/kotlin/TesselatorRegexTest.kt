import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TesselatorRegexTest {
    @Test
    fun `pipeline simulation - multiline rule then standalone`() {
        // Multiline rule (runs first)
        val rule1Pattern = Regex("""(\w+)\s*=\s*tesselator\.getBuilder\(\);[\r\n\s]*\1\.begin\(([^)]+)\);""")
        val rule1Replacement = "$1 = tesselator.begin($2);"
        
        // Standalone rule (runs second)  
        val rule2Pattern = "tesselator.getBuilder()"
        val rule2Replacement = "tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX) // TODO: [forge2neo] verify begin() args"
        
        var content = "        BufferBuilder buffer = tesselator.getBuilder();\r\n\r\n        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);\r\n        buffer.vertex(matrix, 0,0, 0);\r\n"
        
        // Rule 1: apply multiline regex on full content
        println("Before rule 1:")
        println(content)
        println("Rule 1 matches: ${rule1Pattern.containsMatchIn(content)}")
        content = rule1Pattern.replace(content, rule1Replacement)
        println("After rule 1:")
        println(content)
        
        // Rule 2: apply literal replacement on full content
        content = content.replace(rule2Pattern, rule2Replacement)
        println("After rule 2:")
        println(content)
        
        assertTrue(!content.contains("getBuilder"), "getBuilder should not be present: $content")
    }
}
