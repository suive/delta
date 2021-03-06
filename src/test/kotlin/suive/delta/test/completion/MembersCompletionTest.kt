package suive.delta.test.completion

import org.junit.jupiter.api.Test
import java.nio.file.Files

class MembersCompletionTest : CompletionTest() {

    @Test
    fun `should suggest class members of variables`() {
        val workspaceRoot = createWorkspace("/test-projects/maven")
        val testClass = Files.createFile(
            workspaceRoot.resolve("src/main/kotlin/suive/delta/testproject/TestClass.kt")
        )
        Files.writeString(
            testClass, """
            package suive.delta.testproject
            
            class A {
                val prop: String = "123"
                fun method(): String = ""
                fun methodWithParams(p: Int): String = p.toString()
            }
            
            class TestClass {
                fun testMethod() {
                    val a = A()
                    a.
                }
            }
        """.trimIndent()
        )

        testEditor.initialize(workspaceRoot)

        // Completion request sent by the editor right after typing ".".
        val response = sendCompletionRequest(testClass, 11, 10)

        assertJson(response) {
            node("result.items").isNotNull.isArray.hasSizeGreaterThan(1)
                .anySatisfy {
                    assertJson(it) {
                        node("label").asString().contains("method()")
                        node("insertText").isEqualTo("method()")
                    }
                }
                .anySatisfy {
                    assertJson(it) {
                        node("label").asString().contains("methodWithParams(p: Int)")
                        node("insertText").isEqualTo("methodWithParams()")
                    }
                }
                .anySatisfy {
                    assertJson(it) {
                        node("label").asString().contains("prop: String")
                        node("insertText").isEqualTo("prop")
                    }
                }
                .anySatisfy {
                    assertJson(it) {
                        node("label").asString().contains("toString(): String")
                        node("insertText").isEqualTo("toString()")
                    }
                }
        }
    }

    @Test
    fun `should take into account visibility modifiers`() {
        val workspaceRoot = createWorkspace("/test-projects/maven")
        val testClass = Files.createFile(
            workspaceRoot.resolve("src/main/kotlin/suive/delta/testproject/TestClass.kt")
        )
        Files.writeString(
            testClass, """
            package suive.delta.testproject
            
            private class A {
                private val prop: String = "123"
                private fun method() {}
            }
            
            class TestClass {
                fun testMethod() {
                    val a = A()
                    a.
                }
            }
        """.trimIndent()
        )

        testEditor.initialize(workspaceRoot)

        val response = sendCompletionRequest(testClass, 10, 10)
        assertJson(response) {
            node("result.items").isNotNull.isArray.hasSizeGreaterThan(1)
                .noneSatisfy {
                    assertJson(it) {
                        node("label").asString().contains("method")
                    }
                }
                .noneSatisfy {
                    assertJson(it) {
                        node("label").asString().contains("prop")
                    }
                }
        }
    }

    @Test
    fun `should suggest members inside call chains`() {
        val workspaceRoot = createWorkspace("/test-projects/maven")
        val testClass = Files.createFile(
            workspaceRoot.resolve("src/main/kotlin/suive/delta/testproject/TestClass.kt")
        )
        Files.writeString(
            testClass, """
            package suive.delta.testproject
            
            class A {
                fun method(): String = ""
            }
            
            class TestClass {
                fun instantiatedClass() {
                    A().
                }
                fun literal() {
                    "".
                }
                fun callChain() { 
                    A().method().
                }
            }
        """.trimIndent()
        )

        testEditor.initialize(workspaceRoot)

        testClass.assertCompletion(8, 12, listOf("method()"))
        testClass.assertCompletion(11, 11, listOf("length"))
        testClass.assertCompletion(14, 21, listOf("length"))
    }

    @Test
    fun `should suggest static members`() {
        val workspaceRoot = createWorkspace("/test-projects/maven")
        val testClass = Files.createFile(
            workspaceRoot.resolve("src/main/kotlin/suive/delta/testproject/TestClass.kt")
        )
        Files.writeString(
            testClass, """
            package suive.delta.testproject
            import java.util.Optional
            class A {
                companion object {
                    fun method(): String {}
                    val prop = ""
                }
            }
            
            class TestClass {
                fun testMethod() {
                    A.
                }
                fun javaClass() {
                    val a = Optional.
                }
            }
        """.trimIndent()
        )

        testEditor.initialize(workspaceRoot)

        // Companion object members of a local class.
        testClass.assertCompletion(11, 10, listOf("method()", "prop"))

        // Static methods of an imported Java class.
        testClass.assertCompletion(14, 25, listOf("of"))
    }
}
