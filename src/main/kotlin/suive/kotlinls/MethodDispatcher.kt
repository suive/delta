package suive.kotlinls

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import suive.kotlinls.method.CompletionMethod
import suive.kotlinls.method.InitializeMethod
import suive.kotlinls.method.Method
import suive.kotlinls.method.Request
import suive.kotlinls.model.CompletionParams
import suive.kotlinls.model.InitializeParams
import suive.kotlinls.model.Output
import suive.kotlinls.model.Params
import suive.kotlinls.service.CompilerService
import suive.kotlinls.service.CompletionService
import suive.kotlinls.service.DiagnosticService
import suive.kotlinls.service.MavenClasspathCollector
import suive.kotlinls.service.SymbolSearchIndexingService
import suive.kotlinls.task.DiagnosticsTask
import suive.kotlinls.task.IndexingTask
import suive.kotlinls.task.NotificationTask
import suive.kotlinls.task.UpdateClasspathTask
import suive.kotlinls.util.NamedThreadFactory
import java.util.concurrent.Executors
import kotlin.reflect.KClass

object MethodDispatcher {
    private val indexingService = SymbolSearchIndexingService()
    private val mavenClasspathCollector = MavenClasspathCollector()
    private val compilerService = CompilerService()
    private val diagnosticService = DiagnosticService(compilerService)
    private val completionService = CompletionService(compilerService)
    private val paramsConverter = ObjectMapper().registerModule(KotlinModule())

    private val actionUnits = listOf<ActionUnit<*, out Method<*, *>, out KClass<out Params>>>(
        ActionUnit(
            methodName = "initialize",
            paramsClass = InitializeParams::class,
            method = { InitializeMethod() },
            tasks = { params ->
                listOf(
                    UpdateClasspathTask(mavenClasspathCollector, compilerService),
                    DiagnosticsTask(compilerService, requireNotNull(params.rootUri)),
                    IndexingTask(indexingService)
                )
            }
        ),
        ActionUnit(
            methodName = "textDocument/completion",
            paramsClass = CompletionParams::class,
            method = { CompletionMethod(completionService) },
            tasks = { emptyList() }
        )
    )

    private val dispatchTable = actionUnits.associateBy { it.methodName }

    private val workerThreadPool = Executors.newCachedThreadPool(NamedThreadFactory("Worker"))

    fun dispatch(
        request: Request,
        methodName: String,
        paramsRaw: Map<*, *>,
        yield: (Output) -> Unit
    ) {
        val actionUnit = requireNotNull(dispatchTable[methodName]) { "No such method" }

        @Suppress("UNCHECKED_CAST")
        val method = actionUnit.method() as Method<Params, *>
        val params = paramsConverter.convertValue(paramsRaw, actionUnit.paramsClass.java) ?: error { "Params are null" }
        workerThreadPool.execute {
            val result = method.doProcess(request, params)
            yield(result)

            actionUnit.getTasks(params).forEach { task ->
                if (task is NotificationTask<*>) {
                    task.execute().map { Output.Notification(task.method(), it) }.forEach {
                        yield(it)
                    }
                } else {
                    task.execute()
                }
            }
        }
    }
}
