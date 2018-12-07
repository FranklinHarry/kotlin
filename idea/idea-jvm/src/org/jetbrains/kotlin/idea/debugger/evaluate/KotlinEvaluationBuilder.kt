/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.debugger.evaluate

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.SuspendContext
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.evaluation.expression.*
import com.intellij.diagnostic.LogMessageEx
import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ExceptionUtil
import com.sun.jdi.*
import com.sun.jdi.request.EventRequest
import org.jetbrains.eval4j.*
import org.jetbrains.eval4j.Value
import org.jetbrains.eval4j.jdi.JDIEval
import org.jetbrains.eval4j.jdi.asJdiValue
import org.jetbrains.eval4j.jdi.asValue
import org.jetbrains.eval4j.jdi.makeInitialFrame
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.AsmUtil.LABELED_THIS_PARAMETER
import org.jetbrains.kotlin.codegen.AsmUtil.RECEIVER_PARAMETER_NAME
import org.jetbrains.kotlin.codegen.binding.CodegenBinding
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.descriptors.impl.SyntheticFieldDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.caches.resolve.util.getJavaClassDescriptor
import org.jetbrains.kotlin.idea.core.quoteSegmentsIfNeeded
import org.jetbrains.kotlin.idea.debugger.DebuggerUtils
import org.jetbrains.kotlin.idea.debugger.evaluate.KotlinDebuggerCaches.*
import org.jetbrains.kotlin.idea.debugger.evaluate.classLoading.ClassToLoad
import org.jetbrains.kotlin.idea.debugger.evaluate.compilingEvaluator.loadClassesSafely
import org.jetbrains.kotlin.idea.debugger.getBackingFieldName
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionResult
import org.jetbrains.kotlin.idea.runInReadActionWithWriteActionPriorityWithPCE
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.attachment.attachmentByPsiFile
import org.jetbrains.kotlin.idea.util.attachment.mergeAttachments
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.codeFragmentUtil.debugTypeInfo
import org.jetbrains.kotlin.psi.codeFragmentUtil.suppressDiagnosticsInDebugMode
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.resolve.AnalyzingUtils
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.isInlineClassType
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.Opcodes.API_VERSION
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import java.util.*

internal val THIS_NAME = "this"
internal val LOG = Logger.getInstance("#org.jetbrains.kotlin.idea.debugger.evaluate.KotlinEvaluator")
internal val GENERATED_FUNCTION_NAME = "generated_for_debugger_fun"
internal val GENERATED_CLASS_NAME = "Generated_for_debugger_class"

private val DEBUG_MODE = false

object KotlinEvaluationBuilder : EvaluatorBuilder {
    override fun build(codeFragment: PsiElement, position: SourcePosition?): ExpressionEvaluator {
        if (codeFragment !is KtCodeFragment || position == null) {
            return EvaluatorBuilderImpl.getInstance()!!.build(codeFragment, position)
        }

        if (position.line < 0) {
            throw EvaluateExceptionUtil.createEvaluateException("Couldn't evaluate kotlin expression at $position")
        }

        val file = position.file
        if (file is KtFile) {
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            if (document == null || document.lineCount < position.line) {
                throw EvaluateExceptionUtil.createEvaluateException(
                        "Couldn't evaluate kotlin expression: breakpoint is placed outside the file. " +
                        "It may happen when you've changed source file after starting a debug process.")
            }
        }

        if (codeFragment.context !is KtElement) {
            val attachments = arrayOf(attachmentByPsiFile(position.file),
                                      attachmentByPsiFile(codeFragment),
                                      Attachment("breakpoint.info", "line: ${position.line}"))

            LOG.error("Trying to evaluate ${codeFragment::class.java} with context ${codeFragment.context?.javaClass}", mergeAttachments(*attachments))
            throw EvaluateExceptionUtil.createEvaluateException("Couldn't evaluate kotlin expression in this context")
        }

        return ExpressionEvaluatorImpl(KotlinEvaluator(codeFragment, position))
    }
}

class KotlinEvaluator(val codeFragment: KtCodeFragment, val sourcePosition: SourcePosition) : Evaluator {
    override fun evaluate(context: EvaluationContextImpl): Any? {
        if (codeFragment.text.isEmpty()) {
            return context.debugProcess.virtualMachineProxy.mirrorOfVoid()
        }

        if (DumbService.getInstance(codeFragment.project).isDumb) {
            throw EvaluateExceptionUtil.createEvaluateException("Code fragment evaluation is not available in the dumb mode")
        }

        var isCompiledDataFromCache = true
        try {
            val compiledData = KotlinDebuggerCaches.getOrCreateCompiledData(codeFragment, sourcePosition, context) {
                fragment, position ->
                isCompiledDataFromCache = false
                extractAndCompile(fragment, position, context)
            }

            val classLoaderRef = loadClassesSafely(context, compiledData.classes)

            val result = if (classLoaderRef != null) {
                evaluateWithCompilation(context, compiledData, classLoaderRef) ?: runEval4j(context, compiledData, classLoaderRef)
            }
            else {
                runEval4j(context, compiledData, classLoaderRef)
            }

            // If bytecode was taken from cache and exception was thrown - recompile bytecode and run eval4j again
            if (isCompiledDataFromCache && result is ExceptionThrown && result.kind == ExceptionThrown.ExceptionKind.BROKEN_CODE) {
                return runEval4j(context, extractAndCompile(codeFragment, sourcePosition, context), classLoaderRef).toJdiValue(context)
            }

            return if (result is InterpreterResult) {
                result.toJdiValue(context)
            } else {
                result
            }
        }
        catch (e: EvaluateException) {
            throw e
        }
        catch (e: ProcessCanceledException) {
            exception(e)
        }
        catch (e: Eval4JInterpretingException) {
            exception(e.cause)
        }
        catch (e: Exception) {
            val isSpecialException = isSpecialException(e)
            if (isSpecialException) {
                exception(e)
            }

            val text = runReadAction { codeFragment.context?.text ?: "null" }
            val attachments = arrayOf(attachmentByPsiFile(sourcePosition.file),
                                      attachmentByPsiFile(codeFragment),
                                      Attachment("breakpoint.info", "line: ${runReadAction { sourcePosition.line }}"),
                                      Attachment("context.info", text))

            LOG.error(LogMessageEx.createEvent(
                    "Couldn't evaluate expression",
                    ExceptionUtil.getThrowableText(e),
                    mergeAttachments(*attachments)))

            val cause = if (e.message != null) ": ${e.message}" else ""
            exception("An exception occurs during Evaluate Expression Action $cause")
        }
    }

    private fun isSpecialException(th: Throwable): Boolean {
        return when (th) {
            is ClassNotPreparedException,
            is InternalException,
            is AbsentInformationException,
            is ClassNotLoadedException,
            is IncompatibleThreadStateException,
            is InconsistentDebugInfoException,
            is ObjectCollectedException,
            is VMDisconnectedException -> true
            else -> false
        }
    }

    override fun getModifier(): Modifier? {
        return null
    }

    companion object {
        private fun extractAndCompile(codeFragment: KtCodeFragment, sourcePosition: SourcePosition, context: EvaluationContextImpl): CompiledDataDescriptor {
            val (bindingContext) = codeFragment.checkForErrors()

            if (codeFragment.wrapToStringIfNeeded(bindingContext)) {
                // Repeat analysis with toString() added
                codeFragment.checkForErrors()
            }

            val extractionResult = getFunctionForExtractedFragment(codeFragment, sourcePosition.file, sourcePosition.line)
                                   ?: throw IllegalStateException("Code fragment cannot be extracted to function: ${codeFragment.text}")
            val (parametersDescriptor, extractedFunction) = try {
                extractionResult.getParametersForDebugger(codeFragment, context) to extractionResult.declaration as KtNamedFunction
            } finally {
                Disposer.dispose(extractionResult)
            }

            if (LOG.isDebugEnabled) {
                LOG.debug("Extracted function:\n" + runReadAction { extractedFunction.text })
            }

            val classFileFactory = createClassFileFactory(codeFragment, extractedFunction, context, parametersDescriptor)

            val outputFiles = classFileFactory.asList().filterClassFiles()

            for (file in outputFiles) {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Output file generated: ${file.relativePath}")
                }
                if (DEBUG_MODE) {
                    println(file.asText())
                }
            }

            val additionalFiles = outputFiles.map { ClassToLoad(getClassName(it.relativePath), it.relativePath, it.asByteArray()) }

            return CompiledDataDescriptor(
                    additionalFiles,
                    sourcePosition,
                    parametersDescriptor)
        }

        private fun KtCodeFragment.wrapToStringIfNeeded(bindingContext: BindingContext): Boolean {
            if (this !is KtExpressionCodeFragment) {
                return false
            }

            val contentElement = runReadAction { getContentElement() }
            val expressionType = bindingContext[BindingContext.EXPRESSION_TYPE_INFO, contentElement]?.type
            if (contentElement != null && expressionType?.isInlineClassType() == true) {
                val newExpression = runReadAction {
                    val expressionText = contentElement.text
                    KtPsiFactory(project).createExpression("($expressionText).toString()")
                }
                runInEdtAndWait {
                    project.executeWriteCommand("Wrap with 'toString()'") {
                        contentElement.replace(newExpression)
                    }
                }
                return true
            }

            return false
        }

        private fun getClassName(fileName: String): String {
            return fileName.substringBeforeLast(".class").replace("/", ".")
        }

        private val CompiledDataDescriptor.mainClass
            get() = classes.firstOrNull { it.isMainClass() } ?: error(
                    "Can't find main class for " + sourcePosition.elementAt.getParentOfType<KtDeclaration>(strict = false))

        private fun evaluateWithCompilation(
            context: EvaluationContextImpl,
            compiledData: CompiledDataDescriptor,
            classLoader: ClassLoaderReference
        ): Any? {
            val vm = context.debugProcess.virtualMachineProxy.virtualMachine
            val mainClassBytecode = compiledData.mainClass.bytes

            try {
                val mainClassAsmNode = ClassNode().apply { ClassReader(mainClassBytecode).accept(this, ClassReader.SKIP_CODE) }
                assert(mainClassAsmNode.methods.size == 1)

                val methodToInvoke = mainClassAsmNode.methods[0]
                assert(methodToInvoke.parameters == null || methodToInvoke.parameters.isEmpty())

                val thread = context.suspendContext.thread?.threadReference!!
                val invokePolicy = context.suspendContext.getInvokePolicy()
                val eval = JDIEval(vm, classLoader, thread, invokePolicy)

                val mainClassValue = (eval.loadClass(Type.getObjectType(mainClassAsmNode.name), classLoader) as? ObjectValue)
                val mainClass = (mainClassValue?.value as? ClassObjectReference)?.reflectedType() as? ClassType ?: return null

                // Preload all classes
                compiledData.classes.asSequence()
                    .filter { !it.isMainClass() }
                    .forEach { eval.loadClass(Type.getObjectType(it.className), classLoader) }

                return vm.executeWithBreakpointsDisabled {
                    // Prepare the main class

                    val argumentTypes = Type.getArgumentTypes(methodToInvoke.desc)
                    val args = context.getArgumentsForEvaluation(compiledData.parameters, argumentTypes)
                            .zip(argumentTypes)
                            .map { (value, type) ->
                                // Make argument type classes prepared for sure
                                eval.loadClassByName(type.className, classLoader)
                                boxOrUnboxArgumentIfNeeded(eval, value, type).asJdiValue(vm, type)
                            }


                    mainClass.invokeMethod(thread, mainClass.methods().single(), args, invokePolicy)
                }
            } catch (e: Throwable) {
                LOG.error("Unable to evaluate expression with compilation", e)
                return null
            }
        }

        private fun runEval4j(
            context: EvaluationContextImpl,
            compiledData: CompiledDataDescriptor,
            classLoader: ClassLoaderReference?
        ): InterpreterResult {
            val virtualMachine = context.debugProcess.virtualMachineProxy.virtualMachine
            var resultValue: InterpreterResult? = null

            // assert [0] with some context
            val mainClassBytecode = compiledData.mainClass.bytes

            ClassReader(mainClassBytecode).accept(object : ClassVisitor(API_VERSION) {
                override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    // Maybe just take the single method from the class, as it is done in 'evaluateWithCompilation'
                    if (name == GENERATED_FUNCTION_NAME || name.startsWith(GENERATED_FUNCTION_NAME + "-")) {
                        val argumentTypes = Type.getArgumentTypes(desc)
                        val args = context.getArgumentsForEvaluation(compiledData.parameters, argumentTypes)

                        return object : MethodNode(Opcodes.API_VERSION, access, name, desc, signature, exceptions) {
                            override fun visitEnd() {
                                virtualMachine.executeWithBreakpointsDisabled {
                                    val eval = JDIEval(virtualMachine,
                                                       classLoader ?: context.classLoader,
                                                       context.suspendContext.thread?.threadReference!!,
                                                       context.suspendContext.getInvokePolicy())

                                    resultValue = interpreterLoop(
                                            this,
                                            makeInitialFrame(this, args.zip(argumentTypes).map { boxOrUnboxArgumentIfNeeded(eval, it.first, it.second) }),
                                            eval
                                    )
                                }
                            }
                        }
                    }

                    return super.visitMethod(access, name, desc, signature, exceptions)
                }
            }, 0)

            return resultValue ?: throw IllegalStateException("resultValue is null: cannot find method " + GENERATED_FUNCTION_NAME)
        }

        private inline fun <T> VirtualMachine.executeWithBreakpointsDisabled(block: () -> T): T {
            val allRequests = eventRequestManager().breakpointRequests() + eventRequestManager().classPrepareRequests()

            try {
                allRequests.forEach { it.disable() }
                return block()
            } finally {
                allRequests.forEach { it.enable() }
            }
        }

        private fun boxOrUnboxArgumentIfNeeded(eval: JDIEval, argumentValue: Value, parameterType: Type): Value {
            val argumentType = argumentValue.asmType

            if (AsmUtil.isPrimitive(parameterType) && !AsmUtil.isPrimitive(argumentType)) {
                try {
                    val unboxedType = AsmUtil.unboxType(argumentType)
                    if (parameterType == unboxedType) {
                        return eval.unboxType(argumentValue, parameterType)
                    }
                }
                catch(ignored: UnsupportedOperationException) {
                }
            }

            if (!AsmUtil.isPrimitive(parameterType) && AsmUtil.isPrimitive(argumentType)) {
                if (parameterType.descriptor == "Ljava/lang/Object;" || parameterType == AsmUtil.boxType(argumentType)) {
                    return eval.boxType(argumentValue)
                }
            }

            return argumentValue
        }

        private fun InterpreterResult.toJdiValue(context: EvaluationContextImpl): com.sun.jdi.Value? {
            val jdiValue = when (this) {
                is ValueReturned -> result
                is ExceptionThrown -> {
                    when {
                        this.kind == ExceptionThrown.ExceptionKind.FROM_EVALUATED_CODE ->
                            exception(InvocationException(this.exception.value as ObjectReference))
                        this.kind == ExceptionThrown.ExceptionKind.BROKEN_CODE ->
                            throw exception.value as Throwable
                        else ->
                            exception(exception.toString())
                    }
                }
                is AbnormalTermination -> exception(message)
                else -> throw IllegalStateException("Unknown result value produced by eval4j")
            }

            val vm = context.debugProcess.virtualMachineProxy.virtualMachine

            val sharedVar = getValueIfSharedVar(jdiValue)
            return sharedVar?.value ?: jdiValue.asJdiValue(vm, jdiValue.asmType)
        }

        private fun getValueIfSharedVar(value: Value): VariableFinder.Result? {
            val obj = value.obj(value.asmType) as? ObjectReference ?: return null
            return VariableFinder.Result(VariableFinder.unwrapRefValue(obj))
        }

        private fun ExtractionResult.getParametersForDebugger(
            fragment: KtCodeFragment,
            context: EvaluationContextImpl
        ): List<Parameter> {
            return runReadAction {
                val valuesForLabels = HashMap<String, Value>()

                val contextElementFile = fragment.context?.containingFile
                if (contextElementFile is KtCodeFragment) {
                    contextElementFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitProperty(property: KtProperty) {
                            val value = property.getUserData(KotlinCodeFragmentFactory.LABEL_VARIABLE_VALUE_KEY)
                            if (value != null) {
                                valuesForLabels.put(property.name?.quoteIfNeeded()!!, value.asValue())
                            }
                        }
                    })
                }

                val parameters = mutableListOf<Parameter>()
                val receiver = config.descriptor.receiverParameter
                if (receiver != null) {
                    parameters += Parameter(THIS_NAME, receiver.getParameterType(true))
                }

                for (param in config.descriptor.parameters) {
                    val paramName = when {
                        param.argumentText.contains("@") -> param.argumentText.substringBefore("@")
                        param.argumentText.startsWith("::") -> param.argumentText.substring(2)
                        else -> param.argumentText
                    }

                    val paramDescriptor = param.originalDescriptor
                    if (paramDescriptor is SyntheticFieldDescriptor) {
                        val backingFieldName = paramDescriptor.propertyDescriptor.getBackingFieldName()
                        if (backingFieldName != null) {
                            val thisObject = context.suspendContext.frameProxy?.thisObject()
                            val field = thisObject?.referenceType()?.fieldByName(backingFieldName)

                            val parameter = if (thisObject != null && field != null) {
                                Parameter(backingFieldName, param.getParameterType(true), thisObject.getValue(field).asValue())
                            } else {
                                Parameter(
                                    backingFieldName, paramDescriptor.builtIns.unitType,
                                    error = EvaluateException("Can't find a backing field for property ${paramDescriptor.name}")
                                )
                            }

                            parameters += parameter
                            continue
                        }
                    }

                    parameters += Parameter(paramName, param.getParameterType(true), valuesForLabels[paramName])
                }

                parameters
            }
        }

        private fun EvaluationContextImpl.getArgumentsForEvaluation(
            parameters: List<Parameter>,
            parameterTypes: Array<Type>
        ): List<Value> {
            val variableFinder = VariableFinder.instance(this) ?: error("No stack frame available")
            return parameters.zip(parameterTypes).map { (parameter, type) ->
                parameter.error?.let { throw it }

                val result = parameter.value ?: variableFinder.get(parameter.callText, type).asValue()

                if (LOG.isDebugEnabled) {
                    LOG.debug("Parameter for eval4j: name = ${parameter.callText}, type = $type, value = $result")
                }
                result
            }
        }

        private fun createClassFileFactory(
                codeFragment: KtCodeFragment,
                extractedFunction: KtNamedFunction,
                context: EvaluationContextImpl,
                parameters: List<Parameter>
        ): ClassFileFactory {
            return runReadAction {
                val fileForDebugger = createFileForDebugger(codeFragment, extractedFunction)
                if (LOG.isDebugEnabled) {
                    LOG.debug("File for eval4j:\n${runReadAction { fileForDebugger.text }}")
                }

                val (bindingContext, moduleDescriptor, files) = fileForDebugger.checkForErrors(true, codeFragment.getContextContainingFile())

                val generateClassFilter = object : GenerationState.GenerateClassFilter() {
                    override fun shouldGeneratePackagePart(ktFile: KtFile) = ktFile == fileForDebugger
                    override fun shouldAnnotateClass(processingClassOrObject: KtClassOrObject) = true
                    override fun shouldGenerateClass(processingClassOrObject: KtClassOrObject) = processingClassOrObject.containingKtFile == fileForDebugger
                    override fun shouldGenerateScript(script: KtScript) = false
                }

                val state = GenerationState.Builder(
                        fileForDebugger.project,
                        if (!DEBUG_MODE) ClassBuilderFactories.BINARIES else ClassBuilderFactories.TEST,
                        moduleDescriptor,
                        bindingContext,
                        files,
                        CompilerConfiguration.EMPTY
                ).generateDeclaredClassFilter(generateClassFilter).build()

                val variableFinder = VariableFinder.instance(context) ?: error("No stack frame available")

                val extractedFunctionName = extractedFunction.name
                    ?: error("Extracted function has an empty name: ${extractedFunction.text}")

                extractedFunction.receiverTypeReference?.let {
                    val name = AsmUtil.getLabeledThisName(extractedFunctionName, LABELED_THIS_PARAMETER, RECEIVER_PARAMETER_NAME)
                    state.bindingTrace.recordAnonymousType(it, name, variableFinder)
                }

                val valueParameters = extractedFunction.valueParameters
                var paramIndex = 0
                for (param in parameters) {
                    val valueParameter = valueParameters[paramIndex++]

                    val paramRef = valueParameter.typeReference
                    if (paramRef == null) {
                        LOG.error("Each parameter for extracted function should have a type reference",
                                  Attachment("codeFragment.txt", codeFragment.text),
                                  Attachment("extractedFunction.txt", extractedFunction.text))

                        exception("An exception occurs during Evaluate Expression Action")
                    }

                    state.bindingTrace.recordAnonymousType(paramRef, param.callText, variableFinder)
                }

                KotlinCodegenFacade.compileCorrectFiles(state, CompilationErrorHandler.THROW_EXCEPTION)

                state.factory
            }
        }

        private fun BindingTrace.recordAnonymousType(
            typeReference: KtTypeReference,
            localVariableName: String,
            variableFinder: VariableFinder
        ) {
            val paramAnonymousType = typeReference.debugTypeInfo
            if (paramAnonymousType != null) {
                val declarationDescriptor = paramAnonymousType.constructor.declarationDescriptor
                if (declarationDescriptor is ClassDescriptor) {
                    val lookupResult = variableFinder.find(localVariableName, null)
                        ?: exception("Couldn't find local variable this in current frame to get classType for anonymous type $paramAnonymousType}")
                    val localVariable = lookupResult.value.asValue()

                    record(CodegenBinding.ASM_TYPE, declarationDescriptor, localVariable.asmType)
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Asm type ${localVariable.asmType.className} was recorded for ${declarationDescriptor.name}")
                    }
                }
            }
        }

        private fun exception(msg: String): Nothing = throw EvaluateExceptionUtil.createEvaluateException(msg)

        private fun exception(e: Throwable): Nothing = throw EvaluateExceptionUtil.createEvaluateException(e)

        private val IGNORED_DIAGNOSTICS: Set<DiagnosticFactory<*>> = Errors.INVISIBLE_REFERENCE_DIAGNOSTICS

        // contextFile must be NotNull when analyzeInlineFunctions = true
        private fun KtFile.checkForErrors(analyzeInlineFunctions: Boolean = false, contextFile: KtFile? = null): ExtendedAnalysisResult {
            return runInReadActionWithWriteActionPriorityWithPCE {
                try {
                    AnalyzingUtils.checkForSyntacticErrors(this)
                }
                catch (e: IllegalArgumentException) {
                    throw EvaluateExceptionUtil.createEvaluateException(e.message)
                }

                val filesToAnalyze = if (contextFile == null) listOf(this) else listOf(this, contextFile)
                val resolutionFacade = KotlinCacheService.getInstance(project).getResolutionFacade(filesToAnalyze)
                val analysisResult = resolutionFacade.analyzeWithAllCompilerChecks(filesToAnalyze)

                if (analysisResult.isError()) {
                    exception(analysisResult.error)
                }

                val bindingContext = analysisResult.bindingContext
                val filteredDiagnostics = bindingContext.diagnostics.filter { it.factory !in IGNORED_DIAGNOSTICS }
                filteredDiagnostics.firstOrNull { it.severity == Severity.ERROR }?.let {
                    if (it.psiElement.containingFile == this) {
                        exception(DefaultErrorMessages.render(it))
                    }
                }

                if (analyzeInlineFunctions) {
                    val (newBindingContext, files) = DebuggerUtils.analyzeInlinedFunctions(resolutionFacade, this, false)
                    ExtendedAnalysisResult(newBindingContext, analysisResult.moduleDescriptor, files)
                }
                else {
                    ExtendedAnalysisResult(bindingContext, analysisResult.moduleDescriptor, Collections.singletonList(this))
                }
            }
        }

        private data class ExtendedAnalysisResult(val bindingContext: BindingContext, val moduleDescriptor: ModuleDescriptor, val files: List<KtFile>)
    }
}

private val template = """
@file:kotlin.jvm.JvmName("$GENERATED_CLASS_NAME")
!PACKAGE!

!IMPORT_LIST!

!FUNCTION!
"""

private fun createFileForDebugger(codeFragment: KtCodeFragment,
                                  extractedFunction: KtNamedFunction
): KtFile {
    val containingContextFile = codeFragment.getContextContainingFile()
    val importsFromContextFile = containingContextFile?.importList?.let { it.text + "\n" } ?: ""

    var fileText = template.replace(
            "!IMPORT_LIST!",
            importsFromContextFile + codeFragment.importsToString().split(KtCodeFragment.IMPORT_SEPARATOR).joinToString("\n")
    )

    val packageFromContextFile = containingContextFile?.packageFqName?.let {
        if (!it.isRoot) "package ${it.quoteSegmentsIfNeeded()}" else ""
    } ?: ""
    fileText = fileText.replace("!PACKAGE!", packageFromContextFile)

    val extractedFunctionText = extractedFunction.text
    assert(extractedFunctionText != null) { "Text of extracted function shouldn't be null" }
    fileText = fileText.replace("!FUNCTION!", extractedFunction.text!!)

    val jetFile = codeFragment.createKtFile("debugFile.kt", fileText)
    jetFile.suppressDiagnosticsInDebugMode = true

    val list = jetFile.declarations
    val function = list[0] as KtNamedFunction

    function.receiverTypeReference?.debugTypeInfo = extractedFunction.receiverTypeReference?.debugTypeInfo

    for ((newParam, oldParam) in function.valueParameters.zip(extractedFunction.valueParameters)) {
        newParam.typeReference?.debugTypeInfo = oldParam.typeReference?.debugTypeInfo
    }

    function.typeReference?.debugTypeInfo = extractedFunction.typeReference?.debugTypeInfo

    return jetFile
}

private fun PsiElement.createKtFile(fileName: String, fileText: String): KtFile {
    // Not using KtPsiFactory because we need a virtual file attached to the KtFile
    val virtualFile = LightVirtualFile(fileName, KotlinLanguage.INSTANCE, fileText)
    virtualFile.charset = CharsetToolkit.UTF8_CHARSET
    val jetFile = (PsiFileFactory.getInstance(project) as PsiFileFactoryImpl)
            .trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile
    jetFile.analysisContext = this
    return jetFile
}

internal fun SuspendContext.getInvokePolicy(): Int {
    return if (suspendPolicy == EventRequest.SUSPEND_EVENT_THREAD) ObjectReference.INVOKE_SINGLE_THREADED else 0
}

fun Type.getClassDescriptor(scope: GlobalSearchScope): ClassDescriptor? {
    if (AsmUtil.isPrimitive(this)) return null

    val jvmName = JvmClassName.byInternalName(internalName).fqNameForClassNameWithoutDollars

    // TODO: use the correct built-ins from the module instead of DefaultBuiltIns here
    JavaToKotlinClassMap.mapJavaToKotlin(jvmName)?.let(
            DefaultBuiltIns.Instance.builtInsModule::findClassAcrossModuleDependencies
    )?.let { return it }

    return runReadAction {
        val classes = JavaPsiFacade.getInstance(scope.project).findClasses(jvmName.asString(), scope)
        if (classes.isEmpty()) null
        else {
            classes.first().getJavaClassDescriptor()
        }
    }
}
