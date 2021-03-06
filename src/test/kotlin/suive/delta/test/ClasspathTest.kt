package suive.delta.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ClasspathTest : LanguageServerTest() {
    @Test
    // TODO this test is incorrect
    fun `should resolve classpath from Maven pom`() {
        val workspaceRoot = createWorkspace("/test-projects/maven")
        val testClass = Files.createFile(
            workspaceRoot.resolve("src/main/kotlin/suive/delta/testproject/TestClass.kt")
        )

        val pom = workspaceRoot.resolve("pom.xml")
        Files.writeString(
            pom, Files.readString(pom).replace(
                "</dependencies>", """
            <dependency>
                <groupId>io.vavr</groupId>
                <artifactId>vavr</artifactId>
                <version>0.10.3</version>
            </dependency>
        </dependencies>
        """
            )
        )
        Files.writeString(
            testClass, """
            package suive.delta.testproject
            
            import io.vavr.control.Option
            
            class TestClass {
                fun testMethod() {
                    Option.of(1)
                }
            }
            """
        )

        testEditor.initialize(workspaceRoot)

        assertThat(testEditor.getNotification("textDocument/publishDiagnostics")).isNull()
    }

    @Test
    fun `should rebuild workspace after changing classpath`() {
        val workspaceRoot = createWorkspace("/test-projects/maven")
        val testClass = Files.createFile(
            workspaceRoot.resolve("src/main/kotlin/suive/delta/testproject/TestClass.kt")
        )
        Files.writeString(
            testClass,
            """
            package suive.delta.testproject
            
            import io.vavr.control.Option
            
            class TestClass {
                fun testMethod() {
                    Option.of(1)
                }
            }
            """.trimIndent()
        )

        testEditor.initialize(workspaceRoot)

        // Vavr is not in the classpath, should receive build errors.
        val diagnosticNotification = testEditor.getNotification("textDocument/publishDiagnostics")
        assertJson(diagnosticNotification) {
            node("params.uri").isEqualTo(testClass.toUri().toString())
            node("params.diagnostics").isArray.hasSizeGreaterThan(1)
            node("params.diagnostics[0].message").asString().contains("Unresolved reference")
        }

        // Server should send dynamic registration request to receive pom.xml change notifications.
        val registrationRequest = testEditor.getRequest("client/registerCapability")
        assertJson(registrationRequest) {
            node("params.registrations[0].method").isEqualTo("workspace/didChangeWatchedFiles")
            node("params.registrations[0].registerOptions.watchers[0].globPattern").asString().contains("pom.xml")
        }

        // Add missing dependency.
        val pom = workspaceRoot.resolve("pom.xml")
        Files.writeString(
            pom, Files.readString(pom).replace(
                "</dependencies>", """
            <dependency>
                <groupId>io.vavr</groupId>
                <artifactId>vavr</artifactId>
                <version>0.10.3</version>
            </dependency>
        </dependencies>
        """.trimIndent()
            )
        )

        // Send didChangeWatchedFiles.
        testEditor.sendNotification(
            "workspace/didChangeWatchedFiles",
            """
                {
                  "changes": [{
                    "uri": "${pom.toUri()}",
                    "type": 2
                  }]
                }
            """.trimIndent()
        )

        // Build should succeed.
        val secondNotification = testEditor.getNotification("textDocument/publishDiagnostics")
        assertJson(secondNotification) {
            node("params.uri").isEqualTo(testClass.toUri().toString())
            node("params.diagnostics").isArray.hasSize(0)
        }

        // Remove dependency again.
        Files.writeString(
            pom, Files.readString(pom).replace(
                """
            <dependency>
                <groupId>io.vavr</groupId>
                <artifactId>vavr</artifactId>
                <version>0.10.3</version>
            </dependency>
        </dependencies>
        """.trimIndent() ,"</dependencies>"
            )
        )

        // Send didChangeWatchedFiles.
        testEditor.sendNotification(
            "workspace/didChangeWatchedFiles",
            """
                {
                  "changes": [{
                    "uri": "${pom.toUri()}",
                    "type": 2
                  }]
                }
            """.trimIndent()
        )

        // Should receive build error again.
        val newDiagnosticNotification = testEditor.getNotification("textDocument/publishDiagnostics")
        assertJson(newDiagnosticNotification) {
            node("params.uri").isEqualTo(testClass.toUri().toString())
            node("params.diagnostics").isArray.hasSizeGreaterThan(1)
            node("params.diagnostics[0].message").asString().contains("Unresolved reference")
        }
    }
}
