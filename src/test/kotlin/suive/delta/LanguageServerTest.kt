package suive.delta

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.contracts.contract

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LanguageServerTest {

    protected lateinit var languageServer: TcpServer
    protected lateinit var testEditor: TestEditor

    companion object {
        const val LANGUAGE_SERVER_PORT = 8500 // TODO Should be selected randomly.
    }

    @BeforeAll
    fun setup() {
        languageServer = TcpServer(LANGUAGE_SERVER_PORT)
        thread(start = true) { languageServer.start() }
        Thread.sleep(50)

        testEditor = TestEditor(LANGUAGE_SERVER_PORT)
    }

    @AfterAll
    fun shutdown() {
        testEditor.stopSession()
        languageServer.stop()
    }

    protected fun createWorkspace(resource: String? = null): Path {
        val root = Files.createTempDirectory("delta-test-workspace")

        if (resource != null) {
            copyDirectory(Paths.get(LanguageServerTest::class.java.getResource(resource).toURI()), root)
        }
        return root
    }

    private fun copyDirectory(dirFrom: Path, dirTo: Path) {
        Files.walk(dirFrom).forEach {
            val p = dirTo.resolve(dirFrom.relativize(it))
            if (Files.isDirectory(it)) {
                Files.createDirectories(p)
            } else {
                Files.copy(it, p)
            }
        }
    }

    protected fun <T : Any?> assertNotNull(a: T?): T? {
        contract {
            returns() implies (a != null)
        }
        Assertions.assertNotNull(a)
        return a
    }
}
