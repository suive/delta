package suive.delta.service

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.codeInsight.ReferenceVariantsHelper
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import suive.delta.Request
import suive.delta.Workspace
import suive.delta.executeTimed
import suive.delta.model.CompletionItem
import suive.delta.model.CompletionResult
import suive.delta.model.ProgressParams
import suive.delta.util.getOffset
import java.nio.file.Files

class CompletionService(
    private val workspace: Workspace,
    private val sender: Sender,
    private val symbolRepository: SymbolRepository
) {
    private val excludedFromCompletion: List<String> = listOf(
        "kotlin.jvm.internal",
        "kotlin.coroutines.experimental.intrinsics",
        "kotlin.coroutines.intrinsics",
        "kotlin.coroutines.experimental.jvm.internal",
        "kotlin.coroutines.jvm.internal",
        "kotlin.reflect.jvm.internal"
    )

    // TODO refactor this long method.
    fun sendCompletions(request: Request, fileUri: String, row: Int, col: Int, partialResultToken: String? = null) {
        val trace = CliBindingTrace()
        val rootDisposable = Disposer.newDisposable()
        val configuration = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, DiagnosticMessageCollector(workspace))
            val langFeatures = mutableMapOf<LanguageFeature, LanguageFeature.State>()
            for (langFeature in LanguageFeature.values()) {
                // TODO not all features should be enabled
                langFeatures[langFeature] = LanguageFeature.State.ENABLED
            }
            val languageVersionSettings = LanguageVersionSettingsImpl(
                LanguageVersion.LATEST_STABLE, // TODO should be taken from Project config.
                ApiVersion.createByLanguageVersion(LanguageVersion.LATEST_STABLE),
                emptyMap(),
                langFeatures
            )

            put(CommonConfigurationKeys.MODULE_NAME, JvmProtoBufUtil.DEFAULT_MODULE_NAME)
            put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, languageVersionSettings)
        }
        configuration.addJvmClasspathRoots(workspace.classpath)
        val environment = KotlinCoreEnvironment.createForProduction(
            rootDisposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        val psiFileFactory = PsiFileFactory.getInstance(environment.project) as PsiFileFactoryImpl

        val file = workspace.toInternalPath(fileUri)
        val text = Files.readString(file)
        val offset = getOffset(text, row, col)
        val completionSubstitute = "COMPLETION_SUBSTITUTE"
        val modifiedText = text.substring(0, offset) + completionSubstitute + text.substring(offset)

        val ktFile = executeTimed("parse ${file.fileName}") {
            psiFileFactory.createFileFromText(
                file.fileName.toString(),
                KotlinFileType.INSTANCE,
                modifiedText
            ) as KtFile
        }

        val element = ktFile.findElementAt(offset)?.let { el ->
            el.parentsWithSelf.find { it is KtExpression }
        } ?: error("No element at point")

        if (element.parent is KtUserType) {
            val term = element.text.replace(completionSubstitute, "")
            val searchResults = symbolRepository.search(term)

            val completionItems = searchResults.map {
                CompletionItem(it)
            }
            sender.sendResponse(
                request.requestId,
                CompletionResult(items = completionItems, isIncomplete = true)
            )
            return
        }

        // TODO this should be saved and updated after each rebuild.
        val container = TopDownAnalyzerFacadeForJVM.createContainer(
            project = environment.project,
            files = listOf(ktFile),
            trace = trace,
            configuration = environment.configuration,
            packagePartProvider = environment::createPackagePartProvider,
            declarationProviderFactory = ::FileBasedDeclarationProviderFactory
        )
        val analyzer = container.get<LazyTopDownAnalyzer>()
        val moduleDescriptor = container.get<ModuleDescriptor>()

        executeTimed("analysis of ${file.fileName}") {
            analyzer.analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, listOf(ktFile))
        }
        val bc = trace.bindingContext

        fun basicCompletions(): Collection<DeclarationDescriptor> = executeTimed("basicCompletions") {
            when (val parent = element.parent) {
                is KtQualifiedExpression -> {
                    descriptorsFromType(bc, parent.receiverExpression)
                }
                else -> bc.get(BindingContext.LEXICAL_SCOPE, element as KtExpression)
                    ?.getContributedDescriptors(DescriptorKindFilter.ALL, MemberScope.ALL_NAME_FILTER)
                    ?.toList() ?: emptyList()
            }
        }

        fun referenceVariants(): List<DeclarationDescriptor>? = executeTimed("referenceVariants") {
            (referenceVariants(container, bc, moduleDescriptor, element, environment)
                ?: referenceVariants(container, bc, moduleDescriptor, element.parent, environment))
        }

        if (partialResultToken != null) {
            // Client requested partial results.
            // Send basic completions right away.
            sender.sendNotification(
                "$/progress",
                ProgressParams(
                    partialResultToken,
                    CompletionResult(true, basicCompletions().mapNotNull(::descriptorToItem))
                )
            )

            // Compute and send more costly reference variants.
            val referenceVariants = referenceVariants()
            if (referenceVariants != null) {
                sender.sendNotification(
                    "$/progress", ProgressParams(
                        partialResultToken, CompletionResult(
                            true, referenceVariants.mapNotNull(::descriptorToItem)
                        )
                    )
                )
            }

            sender.sendResponse(request.requestId, CompletionResult(items = emptyList()))
        } else {
            // Send everything in the request response.
            val descriptors = referenceVariants() ?: basicCompletions()
            sender.sendResponse(
                request.requestId,
                CompletionResult(items = descriptors.mapNotNull(this::descriptorToItem))
            )
        }
    }

    private fun descriptorToItem(descriptor: DeclarationDescriptor): CompletionItem? =
        when (descriptor) {
            is FunctionDescriptor -> {
                val name = descriptor.name
                val parameters = descriptor.valueParameters.joinToString(", ") { "${it.name}: ${it.type}" }
                val returnType = descriptor.returnType
                val label = "$name($parameters): $returnType"
                val insertText = "$name()" // TODO insert { } after function if lambda is the only param
                CompletionItem(label, insertText)
            }
            is PropertyDescriptor, is LocalVariableDescriptor -> {
                descriptor as CallableDescriptor
                val name = descriptor.name
                val returnType = descriptor.returnType
                val label = "$name: $returnType"
                CompletionItem(label, name.toString())
            }
            else -> null
        }

    private fun descriptorsFromType(
        bc: BindingContext,
        receiverExpression: KtExpression
    ): Collection<DeclarationDescriptor> {
        val type = bc[BindingContext.EXPRESSION_TYPE_INFO, receiverExpression]?.type
        return type?.memberScope?.getContributedDescriptors(
            DescriptorKindFilter.ALL,
            MemberScope.ALL_NAME_FILTER
        ) ?: emptyList()
    }

    private fun referenceVariants(
        componentProvider: ComponentProvider,
        bindingContext: BindingContext,
        moduleDescriptor: ModuleDescriptor,
        element: PsiElement,
        coreEnvironment: KotlinCoreEnvironment
    ): List<DeclarationDescriptor>? {
        val elementKt = element as? KtElement ?: return emptyList()
        val resolutionFacade = KotlinResolutionFacade(
            project = coreEnvironment.project,
            componentProvider = componentProvider,
            moduleDescriptor = moduleDescriptor
        )
        val inDescriptor: DeclarationDescriptor =
            elementKt.getResolutionScope(bindingContext, resolutionFacade).ownerDescriptor
        return when (element) {
            is KtSimpleNameExpression -> ReferenceVariantsHelper(
                bindingContext = bindingContext,
                resolutionFacade = resolutionFacade,
                moduleDescriptor = moduleDescriptor,
                visibilityFilter = VisibilityFilter(inDescriptor, bindingContext, element, resolutionFacade)
            ).getReferenceVariants(
                expression = element,
                kindFilter = DescriptorKindFilter.ALL,
                nameFilter = { !it.isSpecial },
                filterOutJavaGettersAndSetters = true,
                filterOutShadowed = true,
                excludeNonInitializedVariable = true,
                useReceiverType = null
            ).toList()
            else -> null
        }
    }

    private inner class VisibilityFilter(
        private val inDescriptor: DeclarationDescriptor,
        private val bindingContext: BindingContext,
        private val element: KtElement,
        private val resolutionFacade: KotlinResolutionFacade
    ) : (DeclarationDescriptor) -> Boolean {
        override fun invoke(descriptor: DeclarationDescriptor): Boolean {
            if (descriptor is TypeParameterDescriptor && !isTypeParameterVisible(descriptor)) return false

            if (descriptor is DeclarationDescriptorWithVisibility) {
                return descriptor.isVisible(element, null, bindingContext, resolutionFacade)
            }

            if (descriptor.isInternalImplementationDetail()) return false

            return true
        }

        private fun isTypeParameterVisible(typeParameter: TypeParameterDescriptor): Boolean {
            val owner = typeParameter.containingDeclaration
            var parent: DeclarationDescriptor? = inDescriptor
            while (parent != null) {
                if (parent == owner) return true
                if (parent is ClassDescriptor && !parent.isInner) return false
                parent = parent.containingDeclaration
            }
            return true
        }

        private fun DeclarationDescriptor.isInternalImplementationDetail(): Boolean =
            importableFqName?.asString() in excludedFromCompletion
    }
}
