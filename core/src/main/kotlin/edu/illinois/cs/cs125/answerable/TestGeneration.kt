package edu.illinois.cs.cs125.answerable

import edu.illinois.cs.cs125.answerable.TestGenerator.ReceiverGenStrategy.*
import edu.illinois.cs.cs125.answerable.api.*
import edu.illinois.cs.cs125.answerable.typeManagement.*
import org.junit.jupiter.api.Assertions.*
import org.opentest4j.AssertionFailedError
import java.util.*
import kotlin.math.min
import java.lang.IllegalStateException
import java.lang.reflect.*
import java.lang.reflect.Array as ReflectArray

/**
 * A generator for testing runs.
 *
 * Each [TestGenerator] is bound to a particular [solutionName] on a particular [referenceClass]. Whenever a
 * class is 'submitted' to the [TestGenerator], the [TestGenerator] will produce a [TestRunner] which can execute
 * a random test suite given a seed. Given the same seed, the [TestRunner] will always run the same test cases.
 *
 * You can also provide [TestRunnerArgs] which will be used as defaults for [TestRunner]s produced by this
 * [TestGenerator]. [TestRunnerArgs] can also be supplied when testing is initiated. If none are provided,
 * Answerable will use a set of [defaultArgs].
 *
 * @constructor Creates a [TestGenerator] for the [referenceClass] @[Solution] method named [solutionName],
 * which creates [TestRunner]s which default to using [testRunnerArgs]. If [referenceClass] was loaded dynamically,
 * a [BytecodeProvider] must be specified that can determine its bytecode.
 */
class TestGenerator(
    val referenceClass: Class<*>,
    val solutionName: String = "",
    testRunnerArgs: TestRunnerArgs = defaultArgs,
    internal val bytecodeProvider: BytecodeProvider? = null
) {
    /**
     * A secondary constructor which uses Answerable's [defaultArgs] and no custom bytecode provider.
     */
    constructor(referenceClass: Class<*>, solutionName: String) : this(referenceClass, solutionName, defaultArgs)
    init {
        validateStaticSignatures(referenceClass)
    }

    // "Usable" members are from the opened (un-final-ified) mirror of the original reference class.
    // The original members are used for certain checks so a nice class name can be displayed.

    private val languageMode = getLanguageMode(referenceClass)
    internal val typePool = TypePool(bytecodeProvider,
            if (referenceClass.classLoader == javaClass.classLoader) javaClass.classLoader else referenceClass.classLoader?.parent ?: javaClass.classLoader)
    private val controlClass: Class<*> = languageMode.findControlClass(referenceClass, typePool) ?: referenceClass
    internal val usableReferenceClass: Class<*> = mkOpenMirrorClass(referenceClass, typePool, "openref_")
    internal val usableControlClass: Class<*> =
            if (controlClass == referenceClass) usableReferenceClass
            else mkOpenMirrorClass(controlClass, mapOf(referenceClass to usableReferenceClass), typePool, "controlmirror_")
    internal val usableReferenceMethod: Method? = usableReferenceClass.getReferenceSolutionMethod(solutionName)

    private val referenceMethod: Method? = referenceClass.getReferenceSolutionMethod(solutionName)
    internal val enabledNames: Array<String> =
        referenceMethod?.getAnnotation(Solution::class.java)?.enabled ?: arrayOf()

    internal val usablePrecondition: Method? = usableControlClass.getPrecondition(solutionName)
    private val customVerifier: Method? = controlClass.getCustomVerifier(solutionName)
    internal val usableCustomVerifier: Method? = usableControlClass.getCustomVerifier(solutionName)
    internal val mergedArgs: TestRunnerArgs

    init {
        if (referenceMethod == null) {
            if (customVerifier == null) {
                throw AnswerableMisuseException("No @Solution annotation or @Verify annotation with name `$solutionName' was found.")
            } else if (!customVerifier.getAnnotation(Verify::class.java)!!.standalone) {
                throw AnswerableMisuseException("No @Solution annotation with name `$solutionName' was found.\nPerhaps you meant" +
                        "to make verifier `${MethodData(customVerifier)}' standalone?")
            }
        }
        val solutionArgsAnnotation = usableReferenceMethod?.getAnnotation(DefaultTestRunArguments::class.java)
        val verifyArgsAnnotation = usableCustomVerifier?.getAnnotation(DefaultTestRunArguments::class.java)
        if (solutionArgsAnnotation != null && verifyArgsAnnotation != null) {
            throw AnswerableMisuseException("The @Solution and @Verify methods cannot both specify a @DefaultTestRunArguments.\n" +
                "While loading question `$solutionName'.")
        }
        val argsAnnotation = solutionArgsAnnotation ?: verifyArgsAnnotation
        mergedArgs = testRunnerArgs.applyOver(argsAnnotation?.asTestRunnerArgs() ?: defaultArgs)
    }
    internal val atNextMethod: Method? = usableControlClass.getAtNext(enabledNames)
    internal val defaultConstructor: Constructor<*>? = usableReferenceClass.constructors.firstOrNull { it.parameterCount == 0 }

    internal val isStatic = referenceMethod?.let { Modifier.isStatic(it.modifiers) } ?: false
    /** Pair<return type, useGeneratorName> */
    internal val params: Array<Pair<Type, String?>> = usableReferenceMethod?.getAnswerableParams() ?: arrayOf()
    internal val paramsWithReceiver: Array<Pair<Type, String?>> = arrayOf(Pair(usableReferenceClass, null), *params)

    internal val random: Random = Random(0)
    internal val generators: Map<Pair<Type, String?>, GenWrapper<*>> = buildGeneratorMap(random)
    internal val edgeCases: Map<Type, ArrayWrapper?> =
        getEdgeCases(usableControlClass, paramsWithReceiver.map { it.first }.toTypedArray())
    internal val simpleCases: Map<Type, ArrayWrapper?> =
        getSimpleCases(usableControlClass, paramsWithReceiver.map { it.first }.toTypedArray())

    internal val timeout = referenceMethod?.getAnnotation(Timeout::class.java)?.timeout
        ?: (customVerifier?.getAnnotation(Timeout::class.java)?.timeout ?: 0)

    // Default constructor case is for when there is no @Generator and no @Next, but
    // we can still construct receiver objects via a default constructor.
    internal enum class ReceiverGenStrategy { GENERATOR, NEXT, DEFAULTCONSTRUCTOR, NONE }
    internal val receiverGenStrategy: ReceiverGenStrategy = when {
        isStatic -> NONE
        atNextMethod != null -> NEXT
        usableReferenceClass in generators.keys.map { it.first } -> GENERATOR
        defaultConstructor != null -> DEFAULTCONSTRUCTOR
        else -> throw AnswerableMisuseException("The reference solution must provide either an @Generator or an @Next method if @Solution is not static and no zero-argument constructor is accessible.")
    }

    init {
        verifySafety()
    }

    internal fun buildGeneratorMap(random: Random, submittedClassGenerator: Method? = null): Map<Pair<Type, String?>, GenWrapper<*>> {
        val generatorMapBuilder = GeneratorMapBuilder(params.toSet(), random, typePool, if (isStatic) null else usableReferenceClass, languageMode)

        val enabledGens: List<Pair<Pair<Type, String?>, CustomGen>> = usableControlClass.getEnabledGenerators(enabledNames).map {
            return@map if (it.returnType == usableReferenceClass && submittedClassGenerator != null) {
                Pair(Pair(it.genericReturnType, null), CustomGen(submittedClassGenerator))
            } else {
                Pair(Pair(it.genericReturnType, null), CustomGen(it))
            }
        }

        enabledGens.groupBy { it.first }.forEach { gensForType ->
            if (gensForType.value.size > 1) throw AnswerableMisuseException(
                "Found multiple enabled generators for type `${gensForType.key.first.sourceName}'."
            )
        }

        enabledGens.forEach(generatorMapBuilder::accept)

        // The map builder needs to be aware of all named generators for parameter-specific generator choices
        val otherGens: List<Pair<Pair<Type, String?>, CustomGen>> = usableControlClass.getAllGenerators().mapNotNull {
            // Skip unnamed generators
            val name = it.getAnnotation(Generator::class.java)?.name ?: return@mapNotNull null
            return@mapNotNull if (it.returnType == usableReferenceClass && submittedClassGenerator != null) {
                Pair(Pair(it.genericReturnType, name), CustomGen(submittedClassGenerator))
            } else {
                Pair(Pair(it.genericReturnType, name), CustomGen(it))
            }
        }

        otherGens.forEach(generatorMapBuilder::accept)

        return generatorMapBuilder.build()
    }

    private fun getEdgeCases(clazz: Class<*>, types: Array<Type>): Map<Type, ArrayWrapper?> {
        val all = languageMode.defaultEdgeCases + clazz.getEnabledEdgeCases(enabledNames)
        return mapOf(*types.map { it to all[it] }.toTypedArray())
    }
    private fun getSimpleCases(clazz: Class<*>, types: Array<Type>): Map<Type, ArrayWrapper?> {
        val all = languageMode.defaultSimpleCases + clazz.getEnabledSimpleCases(enabledNames)
        return mapOf(*types.map { it to all[it] }.toTypedArray())
    }

    private fun verifySafety() {
        verifyMemberAccess(referenceClass, typePool)

        val dryRunOutput = PassedClassDesignRunner(this,
                mkOpenMirrorClass(referenceClass, typePool, "dryrunopenref_"),
                listOf(), mergedArgs, typePool.getLoader(),
                timeoutOverride = 10000).runTestsUnsecured(0x0403)

        if (dryRunOutput.timedOut) throw AnswerableVerificationException("Testing reference against itself timed out (10s).")

        synchronized(dryRunOutput.testSteps) {
            dryRunOutput.testSteps.filterIsInstance(ExecutedTestStep::class.java).forEach {
                if (!it.succeeded) {
                    throw AnswerableVerificationException(
                            "Testing reference against itself failed on inputs: ${Arrays.deepToString(
                                    it.refOutput.args
                            )}"
                    ).initCause(it.assertErr)
                }
            }
        }
    }

    /**
     * Load a submission class to the problem represented by this [TestGenerator].
     *
     * The submission class will be run through Class Design Analysis against the reference solution.
     * The results of class design analysis will be included in the output of every test run by the [TestRunner] returned.
     * If class design analysis fails, the returned [TestRunner] will never execute any tests, as doing so
     * would be unsafe and cause nasty errors.
     *
     * @param submissionClass the class to be tested against the reference
     * @param testRunnerArgs the arguments that the [TestRunner] returned should default to.
     * @param bytecodeProvider provider of bytecode for the submission class(es), or null if not loaded dynamically
     */
    fun loadSubmission(
        submissionClass: Class<*>,
        testRunnerArgs: TestRunnerArgs = defaultArgs,
        bytecodeProvider: BytecodeProvider? = null
    ): TestRunner {
        val cda = ClassDesignAnalysis(solutionName, referenceClass, submissionClass).runSuite()
        val cdaPassed = cda.all { ao -> ao.result is Matched }

        return if (cdaPassed) {
            PassedClassDesignRunner(this, submissionClass, cda, testRunnerArgs.applyOver(this.mergedArgs), bytecodeProvider)
        } else {
            FailedClassDesignTestRunner(referenceClass, solutionName, submissionClass, cda)
        }
    }

}

/**
 * Represents a class that can execute tests.
 */
interface TestRunner {
    fun runTests(seed: Long, environment: TestEnvironment, testRunnerArgs: TestRunnerArgs): TestRunOutput
    fun runTests(seed: Long, environment: TestEnvironment): TestRunOutput
}

fun TestRunner.runTestsUnsecured(seed: Long, testRunnerArgs: TestRunnerArgs = defaultArgs)
        = this.runTests(seed, defaultEnvironment, testRunnerArgs)

/**
 * The primary [TestRunner] subclass which tests classes that have passed Class Design Analysis.
 *
 * The only publicly-exposed way to create a [PassedClassDesignRunner] is to invoke
 * [TestGenerator.loadSubmission] on an existing [TestGenerator].
 */
class PassedClassDesignRunner internal constructor(
        private val testGenerator: TestGenerator,
        private val submissionClass: Class<*>,
        private val cachedClassDesignAnalysisResult: List<AnalysisOutput> = listOf(),
        private val testRunnerArgs: TestRunnerArgs, // Already merged by TestGenerator#loadSubmission
        private val bytecodeProvider: BytecodeProvider?,
        private val timeoutOverride: Long? = null
) : TestRunner {

    internal constructor(
            testGenerator: TestGenerator, submissionClass: Class<*>, cdaResult: List<AnalysisOutput> = listOf(), testRunnerArgs: TestRunnerArgs = defaultArgs
    ) : this(testGenerator, submissionClass, cdaResult, testRunnerArgs, null)

    internal constructor(
            referenceClass: Class<*>, submissionClass: Class<*>, cdaResult: List<AnalysisOutput> = listOf(), testRunnerArgs: TestRunnerArgs = defaultArgs
    ) : this(TestGenerator(referenceClass), submissionClass, cdaResult, testRunnerArgs)

    /**
     * [TestRunner.runTests] override which accepts [TestRunnerArgs]. Executes a test suite.
     *
     * If the method under test has a timeout, [runTests] will run as many tests as it can before the timeout
     * is reached, and record the results.
     *
     * When called with the same [seed], [runTests] will always produce the same result.
     */
    override fun runTests(seed: Long, environment: TestEnvironment, testRunnerArgs: TestRunnerArgs): TestRunOutput {
        val submissionTypePool = TypePool(bytecodeProvider, submissionClass.classLoader)
        val untrustedSubMirror = mkOpenMirrorClass(submissionClass, submissionTypePool, "opensub_")
        val loader = environment.sandbox.transformLoader(submissionTypePool.getLoader())
        val sandboxedSubMirror = Class.forName(untrustedSubMirror.name, false, loader.getLoader())
        val worker = TestRunWorker(testGenerator, sandboxedSubMirror, environment, loader)
        val timeLimit = timeoutOverride ?: testGenerator.timeout

        // Store reference class static field values so that the next run against this solution doesn't break
        val refStaticFieldValues = testGenerator.usableReferenceClass.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }.map {
            it.isAccessible = true
            it to it.get(null)
        }

        val testSteps = mutableListOf<TestStep>()
        val testingBlockCounts = TestingBlockCounts()
        val startTime = System.currentTimeMillis()
        val timedOut = !environment.sandbox.run(if (timeLimit == 0L) null else timeLimit, Runnable {
            worker.runTests(seed, testRunnerArgs.applyOver(this.testRunnerArgs), testSteps, testingBlockCounts)
        })
        val endTime = System.currentTimeMillis()

        // Restore reference class static field values
        refStaticFieldValues.forEach { (field, value) -> field.set(null, value) }

        return TestRunOutput(
                seed = seed,
                referenceClass = testGenerator.referenceClass,
                testedClass = submissionClass,
                solutionName = testGenerator.solutionName,
                startTime = startTime,
                endTime = endTime,
                timedOut = timedOut,
                numDiscardedTests = testingBlockCounts.discardedTests,
                numTests = testingBlockCounts.numTests,
                numEdgeCaseTests = testingBlockCounts.edgeTests,
                numSimpleCaseTests = testingBlockCounts.simpleTests,
                numSimpleAndEdgeCaseTests = testingBlockCounts.simpleEdgeMixedTests,
                numMixedTests = testingBlockCounts.generatedMixedTests,
                numAllGeneratedTests = testingBlockCounts.allGeneratedTests,
                classDesignAnalysisResult = cachedClassDesignAnalysisResult,
                testSteps = synchronized(testSteps) { testSteps.toList() }
        )
    }

    /**
     * [TestRunner.runTests] overload which uses the [TestRunnerArgs] that this [PassedClassDesignRunner] was constructed with.
     */
    override fun runTests(seed: Long, environment: TestEnvironment) = runTests(seed, environment, this.testRunnerArgs) // to expose the overload to Java
}

class TestRunWorker internal constructor(
    private val testGenerator: TestGenerator,
    private val usableSubmissionClass: Class<*>,
    private val environment: TestEnvironment,
    private val bytecodeProvider: BytecodeProvider?
) {
    private val usableReferenceClass = testGenerator.usableReferenceClass
    private val usableControlClass = testGenerator.usableControlClass
    private val usableReferenceMethod = testGenerator.usableReferenceMethod
    private val usableCustomVerifier = testGenerator.usableCustomVerifier
    private val passRandomToVerify = usableCustomVerifier?.parameters?.size == 3

    private val submissionTypePool = TypePool(bytecodeProvider, usableSubmissionClass.classLoader)
    private val adapterTypePool = TypePool(testGenerator.typePool, submissionTypePool)
    private val usableSubmissionMethod = usableSubmissionClass.findSolutionAttemptMethod(usableReferenceMethod, usableReferenceClass)

    private val params = testGenerator.params
    private val paramsWithReceiver = testGenerator.paramsWithReceiver

    private val precondition = testGenerator.usablePrecondition

    private val testRunnerRandom = Random(0)
    private val randomForReference = testGenerator.random
    private val randomForSubmission = Random(0)

    private val mirrorToStudentClass = if (testGenerator.usableControlClass == testGenerator.usableReferenceClass) {
        mkGeneratorMirrorClass(usableReferenceClass, usableSubmissionClass, adapterTypePool, "genmirror_")
    } else {
        mkOpenMirrorClass(usableControlClass, mapOf(usableReferenceClass to usableSubmissionClass), adapterTypePool, "controlgenmirror_")
    }

    private val referenceAtNext = testGenerator.atNextMethod
    private val submissionAtNext = mirrorToStudentClass.getAtNext(testGenerator.enabledNames)
    private val referenceDefaultCtor = testGenerator.defaultConstructor
    private val submissionDefaultCtor = usableSubmissionClass.constructors.firstOrNull { it.parameterCount == 0 }

    private val referenceEdgeCases = testGenerator.edgeCases
    private val referenceSimpleCases = testGenerator.simpleCases
    private val submissionEdgeCases: Map<Type, ArrayWrapper?> = referenceEdgeCases
        // replace reference class cases with mirrored cases
        // the idea is that each map takes `params` to the correct generator/cases
        .toMutableMap().apply {
            replace(
                usableReferenceClass,
                mirrorToStudentClass.getEnabledEdgeCases(testGenerator.enabledNames)[usableSubmissionClass]
            )
        }
    private val submissionSimpleCases: Map<Type, ArrayWrapper?> = referenceSimpleCases
        // replace reference class cases with mirrored cases
        .toMutableMap().apply {
            replace(
                usableReferenceClass,
                mirrorToStudentClass.getEnabledSimpleCases(testGenerator.enabledNames)[usableSubmissionClass]
            )
        }

    private val referenceGens = testGenerator.generators
    private val submissionGens = mirrorToStudentClass
        .getEnabledGenerators(testGenerator.enabledNames)
        .find { it.returnType == usableSubmissionClass }
        .let { testGenerator.buildGeneratorMap(randomForSubmission, it) }

    private val receiverGenStrategy = testGenerator.receiverGenStrategy
    private val capturePrint = usableReferenceMethod?.getAnnotation(Solution::class.java)?.prints ?: false
    private val isStatic = testGenerator.isStatic

    private fun calculateNumCases(cases: Map<Type, ArrayWrapper?>): Int =
        paramsWithReceiver.foldIndexed(1) { idx, acc, param ->
            cases[param.first]?.let { cases: ArrayWrapper ->
                (if (idx == 0) ((cases.array as? Array<*>)?.filterNotNull()?.size ?: 1) else cases.size).let {
                    return@foldIndexed acc * it
                }
            } ?: acc
        }

    private fun calculateCase(
        index: Int,
        total: Int,
        cases: Map<Type, ArrayWrapper?>,
        backups: Map<Pair<Type, String?>, GenWrapper<*>>
    ): Array<Any?> {
        var segmentSize = total
        var segmentIndex = index

        val case = Array<Any?>(paramsWithReceiver.size) { null }
        for (i in paramsWithReceiver.indices) {
            val param = paramsWithReceiver[i]
            val typeCases = cases[param.first]

            if (i == 0) { // receiver
                if (typeCases == null) {
                    case[0] = null
                    continue
                }
                val typeCasesArr = typeCases.array as? Array<*> ?: throw IllegalStateException("Answerable thinks a receiver type is primitive. Please report a bug.")
                val typeCasesLst = typeCasesArr.filterNotNull() // receivers can't be null

                if (typeCasesLst.isEmpty()) {
                    case[0] = null
                    continue
                }

                val typeNumCases = typeCasesLst.size
                segmentSize /= typeNumCases
                case[i] = typeCases[segmentIndex / segmentSize]
                segmentIndex %= segmentSize

            } else { // non-receiver

                if (typeCases == null || typeCases.size == 0) {
                    case[i] = backups[param]?.generate(0)
                    continue
                }

                val typeNumCases = typeCases.size
                segmentSize /= typeNumCases
                case[i] = typeCases[segmentIndex / segmentSize]
                segmentIndex %= segmentSize
            }
        }
        return case
    }

    private fun mkSimpleEdgeMixedCase(
        edges: Map<Type, ArrayWrapper?>,
        simples: Map<Type, ArrayWrapper?>,
        backups: Map<Pair<Type, String?>, GenWrapper<*>>,
        random: Random
    ): Array<Any?> {
        val case = Array<Any?>(params.size) { null }
        for (i in params.indices) {
            val edge = random.nextInt(2) == 0
            var simple = !edge
            val param = params[i]
            val type = param.first

            if (edge) {
                if (edges[type] != null && edges.getValue(type)!!.size != 0) {
                    case[i] = edges.getValue(type)!!.random(random)
                } else {
                    simple = true
                }
            }
            if (simple) {
                if (simples[type] != null && simples.getValue(type)!!.size != 0) {
                    case[i] = simples.getValue(type)!!.random(random)
                } else {
                    case[i] = backups[param]?.generate(if (edge) 0 else 2)
                }
            }
        }

        return case
    }

    private fun mkGeneratedMixedCase(
        edges: Map<Type, ArrayWrapper?>,
        simples: Map<Type, ArrayWrapper?>,
        gens: Map<Pair<Type, String?>, GenWrapper<*>>,
        complexity: Int,
        random: Random
    ): Array<Any?> {
        val case = Array<Any?>(params.size) { null }

        for (i in params.indices) {
            val param = params[i]
            val type = param.first
            var choice = random.nextInt(3)
            if (choice == 0) {
                if (edges[type] != null && edges.getValue(type)!!.size != 0) {
                    case[i] = edges.getValue(type)!!.random(random)
                } else {
                    choice = 2
                }
            }
            if (choice == 1) {
                if (simples[type] != null && edges.getValue(type)!!.size != 0) {
                    case[i] = simples.getValue(type)!!.random(random)
                } else {
                    choice = 2
                }
            }
            if (choice == 2) {
                case[i] = (gens[param] ?: error("Missing generator for ${param.first.sourceName}")).generate(complexity)
            }
        }

        return case
    }

    private fun testWith(
        iteration: Int,
        refReceiver: Any?,
        subReceiver: Any?,
        refMethodArgs: Array<Any?>,
        subMethodArgs: Array<Any?>
    ): TestStep {

        var subProxy: Any? = null

        if (!isStatic) {
            subProxy = mkProxy(usableReferenceClass, usableSubmissionClass, subReceiver!!, testGenerator.typePool)
        }

        return test(iteration, refReceiver, subReceiver, subProxy, refMethodArgs, subMethodArgs)
    }

    private fun mkRefReceiver(iteration: Int, complexity: Int, prevRefReceiver: Any?): Any? =
        when (receiverGenStrategy) {
            NONE -> null
            DEFAULTCONSTRUCTOR -> referenceDefaultCtor?.newInstance()
            GENERATOR -> referenceGens[Pair(usableReferenceClass, null)]?.generate(complexity)
            NEXT -> referenceAtNext?.invoke(null, prevRefReceiver, iteration, randomForReference)
        }

    private fun mkSubReceiver(iteration: Int, complexity: Int, prevSubReceiver: Any?): Any? =
        when (receiverGenStrategy) {
            NONE -> null
            DEFAULTCONSTRUCTOR -> submissionDefaultCtor?.newInstance()
            GENERATOR -> submissionGens[Pair(usableReferenceClass, null)]?.generate(complexity)
            NEXT -> submissionAtNext?.invoke(null, prevSubReceiver, iteration, randomForSubmission)
        }

    private fun test(
        iteration: Int,
        refReceiver: Any?,
        subReceiver: Any?,
        subProxy: Any?,
        refArgs: Array<Any?>,
        subArgs: Array<Any?>
    ): TestStep {
        fun runOne(receiver: Any?, refCompatibleReceiver: Any?, method: Method?, args: Array<Any?>): TestOutput<Any?> {
            var behavior: Behavior? = null

            var threw: Throwable? = null
            var outText: String? = null
            var errText: String? = null
            var output: Any? = null
            if (method != null) {
                val toRun = Runnable {
                    try {
                        output = method(receiver, *args)
                        behavior = Behavior.RETURNED
                    } catch (e: InvocationTargetException) {
                        if (e.cause is ThreadDeath) throw ThreadDeath()
                        threw = e.cause ?: e
                        behavior = Behavior.THREW
                    }
                }
                if (capturePrint) {
                    environment.outputCapturer.runCapturingOutput(toRun)
                    outText = environment.outputCapturer.getStandardOut()
                    errText = environment.outputCapturer.getStandardErr()
                } else {
                    toRun.run()
                }
            } else {
                behavior = Behavior.VERIFY_ONLY
            }
            return TestOutput(
                typeOfBehavior = behavior!!,
                receiver = refCompatibleReceiver,
                args = args,
                output = output,
                threw = threw,
                stdOut = outText,
                stdErr = errText
            )
        }

        val refBehavior = runOne(refReceiver, refReceiver, usableReferenceMethod, refArgs)
        val subBehavior = runOne(subReceiver, subProxy, usableSubmissionMethod, subArgs)

        var assertErr: Throwable? = null
        try {
            if (usableCustomVerifier == null) {
                assertEquals(refBehavior.threw?.javaClass, subBehavior.threw?.javaClass)
                assertEquals(refBehavior.output, subBehavior.output)
                assertEquals(refBehavior.stdOut, subBehavior.stdOut)
                assertEquals(refBehavior.stdErr, subBehavior.stdErr)
            } else {
                if (subProxy != null) {
                    usableSubmissionClass.getPublicFields().forEach {
                        usableReferenceClass.getField(it.name).set(subProxy, it.get(subReceiver))
                    }
                }
                if (passRandomToVerify) {
                    usableCustomVerifier.invoke(null, refBehavior, subBehavior, testRunnerRandom)
                } else {
                    usableCustomVerifier.invoke(null, refBehavior, subBehavior)
                }
            }
        } catch (ite: InvocationTargetException) {
            assertErr = ite.cause
        } catch (e: AssertionFailedError) {
            assertErr = e
        }

        return ExecutedTestStep(
            iteration = iteration,
            refReceiver = refReceiver,
            subReceiver = subReceiver,
            succeeded = assertErr == null,
            refOutput = refBehavior,
            subOutput = subBehavior,
            assertErr = assertErr
        )
    }

    fun runTests(seed: Long, testRunnerArgs: TestRunnerArgs, testStepList: MutableList<TestStep>, testingBlockCounts: TestingBlockCounts) {
        val resolvedArgs = testRunnerArgs.resolve() // All properties are non-null
        val numTests = resolvedArgs.numTests!!
        val numEdgeCombinations = calculateNumCases(referenceEdgeCases)
        val numSimpleCombinations = calculateNumCases(referenceSimpleCases)

        val numEdgeCaseTests = if (referenceEdgeCases.values.all { it.isNullOrEmpty() }) 0 else min(
            resolvedArgs.maxOnlyEdgeCaseTests!!,
            numEdgeCombinations
        )
        val edgeExhaustive = numEdgeCombinations <= resolvedArgs.maxOnlyEdgeCaseTests!!
        val numSimpleCaseTests = if (referenceSimpleCases.values.all { it.isNullOrEmpty() }) 0 else min(
            resolvedArgs.maxOnlySimpleCaseTests!!,
            numSimpleCombinations
        )
        val simpleExhaustive = numSimpleCombinations <= resolvedArgs.maxOnlySimpleCaseTests!!
        val numSimpleEdgeMixedTests = resolvedArgs.numSimpleEdgeMixedTests!!
        val numAllGeneratedTests = resolvedArgs.numAllGeneratedTests!!

        val simpleCaseUpperBound = numEdgeCaseTests + numSimpleCaseTests
        val simpleEdgeMixedUpperBound = simpleCaseUpperBound + numSimpleEdgeMixedTests

        val numGeneratedMixedTests: Int
                by lazy { numTests -
                        numAllGeneratedTests -
                        testingBlockCounts.let { it.edgeTests + it.simpleTests + it.simpleEdgeMixedTests }
                }

        setOf(randomForReference, randomForSubmission, testRunnerRandom).forEach { it.setSeed(seed) }

        var refReceiver: Any? = null
        var subReceiver: Any? = null

        var block: Int
        var generatedMixedIdx = 0
        var allGeneratedIdx = 0

        var i = 0
        while (testingBlockCounts.numTests < numTests) {
            val refMethodArgs: Array<Any?>
            val subMethodArgs: Array<Any?>
            when {
                i in 1 .. numEdgeCaseTests -> {
                    block = 0

                    // if we can't exhaust the cases, duplicates are less impactful
                    val idx = if (edgeExhaustive) (i - 1) else testRunnerRandom.nextInt(numEdgeCombinations)

                    val refCase = calculateCase(idx, numEdgeCombinations, referenceEdgeCases, referenceGens)
                    val subCase = calculateCase(idx, numEdgeCombinations, submissionEdgeCases, submissionGens)

                    refMethodArgs = refCase.slice(1..refCase.indices.last).toTypedArray()
                    subMethodArgs = subCase.slice(1..subCase.indices.last).toTypedArray()

                    refReceiver = if (refCase[0] != null) refCase[0] else mkRefReceiver(i, 0, refReceiver)
                    subReceiver = if (subCase[0] != null) subCase[0] else mkSubReceiver(i, 0, subReceiver)

                }
                i in (numEdgeCaseTests + 1) .. simpleCaseUpperBound -> {
                    block = 1

                    val idxInSegment = i - numEdgeCaseTests - 1
                    val idx = if (simpleExhaustive) idxInSegment else testRunnerRandom.nextInt(numSimpleCombinations)

                    val refCase = calculateCase(idx, numSimpleCombinations, referenceSimpleCases, referenceGens)
                    val subCase = calculateCase(idx, numSimpleCombinations, submissionSimpleCases, submissionGens)

                    refMethodArgs = refCase.slice(1..refCase.indices.last).toTypedArray()
                    subMethodArgs = subCase.slice(1..subCase.indices.last).toTypedArray()

                    refReceiver = if (refCase[0] != null) refCase[0] else mkRefReceiver(i, 0, refReceiver)
                    subReceiver = if (subCase[0] != null) subCase[0] else mkSubReceiver(i, 0, subReceiver)

                }
                i in (simpleCaseUpperBound + 1) .. simpleEdgeMixedUpperBound -> {
                    block = 2

                    refReceiver = mkRefReceiver(i, 2, refReceiver)
                    subReceiver = mkSubReceiver(i, 2, subReceiver)

                    refMethodArgs = mkSimpleEdgeMixedCase(referenceEdgeCases, referenceSimpleCases, referenceGens, randomForReference)
                    subMethodArgs = mkSimpleEdgeMixedCase(submissionEdgeCases, submissionSimpleCases, submissionGens, randomForSubmission)
                }
                testingBlockCounts.allGeneratedTests < numAllGeneratedTests -> {
                    block = 3

                    val comp = min((resolvedArgs.maxComplexity!! * allGeneratedIdx) / numAllGeneratedTests, resolvedArgs.maxComplexity)

                    refReceiver = mkRefReceiver(i, comp, refReceiver)
                    subReceiver = mkSubReceiver(i, comp, subReceiver)

                    refMethodArgs = params.map { referenceGens[it]?.generate(comp) }.toTypedArray()
                    subMethodArgs = params.map { submissionGens[it]?.generate(comp) }.toTypedArray()

                    allGeneratedIdx++
                }
                testingBlockCounts.numTests < numTests -> {
                    block = 4

                    val comp = min((resolvedArgs.maxComplexity!! * generatedMixedIdx) / numGeneratedMixedTests, resolvedArgs.maxComplexity)

                    refReceiver = mkRefReceiver(i, comp, refReceiver)
                    subReceiver = mkSubReceiver(i, comp, subReceiver)

                    refMethodArgs = mkGeneratedMixedCase(referenceEdgeCases, referenceSimpleCases, referenceGens, comp, randomForReference)
                    subMethodArgs = mkGeneratedMixedCase(submissionEdgeCases, submissionSimpleCases, submissionGens, comp, randomForSubmission)

                    generatedMixedIdx++
                }

                else ->
                    throw IllegalStateException(
                            "Answerable somehow lost proper track of test block counts. Please report a bug."
                    )
            }

            val preconditionMet: Boolean = (precondition?.invoke(refReceiver, *refMethodArgs) ?: true) as Boolean

            val result: TestStep
            if (preconditionMet) {
                result = testWith(i, refReceiver, subReceiver, refMethodArgs, subMethodArgs)
                when (block) {
                    0 -> testingBlockCounts.edgeTests++
                    1 -> testingBlockCounts.simpleTests++
                    2 -> testingBlockCounts.simpleEdgeMixedTests++
                    3 -> testingBlockCounts.allGeneratedTests++
                    4 -> testingBlockCounts.generatedMixedTests++
                }
            } else {
                result = DiscardedTestStep(i, refReceiver, refMethodArgs)
                testingBlockCounts.discardedTests++
            }
            synchronized(testStepList) {
                testStepList.add(result)
            }

            if (testingBlockCounts.discardedTests >= resolvedArgs.maxDiscards!!) break
            i++
        }
    }

}

/**
 * The secondary [TestRunner] subclass representing a [submissionClass] which failed Class Design Analysis
 * against the [referenceClass].
 *
 * [runTests] will always execute 0 tests and produce an empty [TestRunOutput.testSteps].
 * The class design analysis results will be contained in the output.
 */
class FailedClassDesignTestRunner(
    private val referenceClass: Class<*>,
    private val solutionName: String,
    private val submissionClass: Class<*>,
    private val failedCDAResult: List<AnalysisOutput>
) : TestRunner {
    override fun runTests(seed: Long, environment: TestEnvironment): TestRunOutput =
            TestRunOutput(
                seed = seed,
                referenceClass = referenceClass,
                testedClass = submissionClass,
                solutionName = solutionName,
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                timedOut = false,
                numDiscardedTests = 0,
                numTests = 0,
                numEdgeCaseTests = 0,
                numSimpleCaseTests = 0,
                numSimpleAndEdgeCaseTests = 0,
                numMixedTests = 0,
                numAllGeneratedTests = 0,
                classDesignAnalysisResult = failedCDAResult,
                testSteps = listOf()
            )

    override fun runTests(seed: Long, environment: TestEnvironment, testRunnerArgs: TestRunnerArgs): TestRunOutput = runTests(seed, environment)
}

operator fun <T> MutableMap<Pair<Type, String?>, T>.get(type: Type): T? = this[Pair(type, null)]
operator fun <T> MutableMap<Pair<Type, String?>, T>.set(type: Type, newVal: T) {
    this[Pair(type, null)] = newVal
}

// NOTE: [Generator Keys]
// goalTypes holds types that we need generators for. @UseGenerator annotations allow specifying a specific generator.
// The string in the Pair is non-null iff a specific generator is requested.
private class GeneratorMapBuilder(goalTypes: Collection<Pair<Type, String?>>, private val random: Random, private val pool: TypePool,
                                  private val receiverType: Class<*>?, languageMode: LanguageMode) {
    private var knownGenerators: MutableMap<Pair<Type, String?>, Lazy<Gen<*>>> = mutableMapOf()
    private val defaultGenerators: Map<Pair<Class<*>, String?>, Gen<*>> = languageMode.defaultGenerators.mapKeys { (k, _) -> Pair(k, null) }
    init {
        defaultGenerators.forEach { (k, v) -> accept(k, v) }
        knownGenerators[String::class.java] = lazy { DefaultStringGen(knownGenerators[Char::class.java]!!.value) }
    }

    private val requiredGenerators: Set<Pair<Type, String?>> = goalTypes.toSet().also { it.forEach(this::request) }

    private fun lazyGenError(type: Type) = AnswerableMisuseException(
        "A generator for type `${pool.getOriginalClass(type).sourceName}' was requested, but no generator for that type was found."
    )

    private fun lazyArrayError(type: Type) = AnswerableMisuseException(
        "A generator for an array with component type `${pool.getOriginalClass(type).sourceName}' was requested, but no generator for that type was found."
    )

    fun accept(pair: Pair<Pair<Type, String?>, Gen<*>?>) = accept(pair.first, pair.second)

    fun accept(type: Pair<Type, String?>, gen: Gen<*>?) {
        if (gen != null) {
            // kotlin fails to smart cast here even though it says the cast isn't needed
            @Suppress("USELESS_CAST")
            knownGenerators[type] = lazy { gen as Gen<*> }
        }
    }

    private fun request(pair: Pair<Type, String?>) {
        if (pair.second == null) {
            request(pair.first)
        }
    }

    private fun request(type: Type) {
        when (type) {
            is Class<*> -> if (type.isArray) {
                request(type.componentType)
                knownGenerators[type] =
                    lazy {
                        DefaultArrayGen(
                            knownGenerators[type.componentType]?.value ?: throw lazyArrayError(type.componentType),
                            type.componentType
                        )
                    }
            }
        }
    }

    private fun generatorCompatible(requested: Type, known: Type): Boolean {
        // TODO: There are probably more cases we'd like to handle, but we should be careful to not be too liberal in matching
        if (requested == known) return true
        return when (requested) {
            is ParameterizedType -> when (known) {
                is ParameterizedType -> requested.rawType == known.rawType
                        && requested.actualTypeArguments.indices
                            .all { generatorCompatible(requested.actualTypeArguments[it], known.actualTypeArguments[it]) }
                else -> false
            }
            is WildcardType -> when (known) {
                is Class<*> -> requested.lowerBounds.elementAtOrNull(0) == known
                        || requested.upperBounds.elementAtOrNull(0) == known
                is ParameterizedType -> {
                    val hasLower = requested.lowerBounds.size == 1
                    val matchesLower = hasLower && generatorCompatible(requested.lowerBounds[0], known)
                    val hasUpper = requested.upperBounds.size == 1
                    val matchesUpper = hasUpper && generatorCompatible(requested.upperBounds[0], known)
                    (!hasLower || matchesLower) && (!hasUpper || matchesUpper) && (hasLower || hasUpper)
                }
                else -> false
            }
            else -> false
        }
    }

    fun build(): Map<Pair<Type, String?>, GenWrapper<*>> {
        fun selectGenerator(goal: Pair<Type, String?>): Gen<*>? {
            // Selects a variant-compatible generator if an exact match isn't found
            // e.g. Kotlin Function1<? super Whatever, SomethingElse> (required) is compatible with Function1<Whatever, SomethingElse> (known)
            knownGenerators[goal]?.value?.let { return it }
            return knownGenerators.filter { (known, _) ->
                known.second == goal.second && generatorCompatible(goal.first, known.first)
            }.toList().firstOrNull()?.second?.value
        }
        val discovered = mutableMapOf(*requiredGenerators
                .map { it to (GenWrapper(selectGenerator(it) ?: throw lazyGenError(it.first), random)) }
                .toTypedArray())
        if (receiverType != null) {
            // Add a receiver generator if possible - don't fail here if not found because there might be a default constructor
            val receiverTarget = Pair(receiverType, null)
            if (!discovered.containsKey(receiverTarget)) knownGenerators[receiverType]?.value?.let {
                discovered[receiverTarget] = GenWrapper(it, random)
            }
        }
        return discovered
    }

}

internal class GenWrapper<T>(val gen: Gen<T>, private val random: Random) {
    operator fun invoke(complexity: Int) = gen.generate(complexity, random)

    fun generate(complexity: Int): T = gen.generate(complexity, random)
}

// So named as to avoid conflict with the @Generator annotation, as that class name is part of the public API and this one is not.
internal interface Gen<out T> {
    fun generate(complexity: Int, random: Random): T
}
@Suppress("NOTHING_TO_INLINE")
internal inline operator fun <T> Gen<T>.invoke(complexity: Int, random: Random): T = generate(complexity, random)

internal class CustomGen(private val gen: Method) : Gen<Any?> {
    override fun generate(complexity: Int, random: Random): Any? = gen(null, complexity, random)
}

internal class DefaultStringGen(private val cGen: Gen<*>) : Gen<String> {
    override fun generate(complexity: Int, random: Random): String {
        val len = random.nextInt(complexity + 1)

        return String((1..len).map { cGen(complexity, random) as Char }.toTypedArray().toCharArray())
    }
}

internal class DefaultArrayGen<T>(private val tGen: Gen<T>, private val tClass: Class<*>) : Gen<Any> {
    override fun generate(complexity: Int, random: Random): Any {
        return ReflectArray.newInstance(tClass, random.nextInt(complexity + 1)).also {
            val wrapper = ArrayWrapper(it)
            (0 until wrapper.size).forEach { idx -> wrapper[idx] = tGen(random.nextInt(complexity + 1), random) }
        }
    }
}

internal class DefaultListGen<T>(private val tGen: Gen<T>) : Gen<List<T>> {
    override fun generate(complexity: Int, random: Random): List<T> {
        fun genList(complexity: Int, length: Int): List<T> =
                if (length <= 0) {
                    listOf()
                } else {
                    listOf(tGen(random.nextInt(complexity + 1), random)) + genList(complexity, length - 1)
                }
        return genList(complexity, random.nextInt(complexity + 1))
    }
}

internal class ArrayWrapper(val array: Any) {
    val size = ReflectArray.getLength(array)
    operator fun get(index: Int): Any? {
        return ReflectArray.get(array, index)
    }
    operator fun set(index: Int, value: Any?) {
        ReflectArray.set(array, index, value)
    }
    fun random(random: Random): Any? {
        return get(random.nextInt(size))
    }
}
internal fun ArrayWrapper?.isNullOrEmpty() = this == null || this.size == 0

/**
 * The types of behaviors that methods under test can have.
 */
enum class Behavior { RETURNED, THREW, VERIFY_ONLY }

/**
 * Represents a single iteration of the main testing loop.
 */
abstract class TestStep(
    /** The number of the test represented by this [TestStep]. */
    val testNumber: Int,
    /** Whether or not this test case was discarded. */
    val wasDiscarded: Boolean
) : DefaultSerializable

/**
 * Represents a test case that was executed.
 */
class ExecutedTestStep(
    iteration: Int,
    /** The receiver object passed to the reference. */
    val refReceiver: Any?,
    /** The receiver object passed to the submission. */
    val subReceiver: Any?,
    /** Whether or not the test case succeeded. */
    val succeeded: Boolean,
    /** The return value of the reference solution. */
    val refOutput: TestOutput<Any?>,
    /** The return value of the submission. */
    val subOutput: TestOutput<Any?>,
    /** The assertion error thrown, if any, by the verifier. */
    val assertErr: Throwable?
) : TestStep(iteration, false) {
    override fun toJson() = defaultToJson()
}

/**
 * Represents a discarded test case.
 */
class DiscardedTestStep(
    iteration: Int,
    /** The receiver object that was passed to the precondition. */
    val receiver: Any?,
    /** The other arguments that were passed to the precondition. */
    val args: Array<Any?>
) : TestStep(iteration, true) {
    override fun toJson() = defaultToJson()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiscardedTestStep

        if (receiver != other.receiver) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = receiver?.hashCode() ?: 0
        result = 31 * result + args.contentHashCode()
        return result
    }
}

/**
 * Represents the output of an entire testing run.
 */
data class TestRunOutput(
    /** The seed that this testing run used. */
    val seed: Long,
    /** The reference class for this testing run. */
    val referenceClass: Class<*>,
    /** The submission class for this testing run. */
    val testedClass: Class<*>,
    /** The [Solution.name] of the @[Solution] annotation that this test used. */
    val solutionName: String,
    /** The time (in ms since epoch) that this test run started. Only the main testing loop is considered. */
    val startTime: Long,
    /** The time (in ms since epoch) that this test run ended. Only the main testing loop is considered. */
    val endTime: Long,
    /** Whether or not this test run ended in a time-out. */
    val timedOut: Boolean,
    /** The number of discarded test cases. */
    val numDiscardedTests: Int,
    /** The number of non-discarded tests which were executed. */
    val numTests: Int,
    /** The number of tests which contained only edge cases. */
    val numEdgeCaseTests: Int,
    /** The number of tests which contained only simple cases. */
    val numSimpleCaseTests: Int,
    /** The number of tests which contained a mix of simple and edge cases. */
    val numSimpleAndEdgeCaseTests: Int,
    /** The number of tests which contained a mix of edge, simple, and generated cases. */
    val numMixedTests: Int,
    /** The number of tests which contained purely generated inputs. */
    val numAllGeneratedTests: Int,
    /** The results of class design analysis between the [referenceClass] and [testedClass]. */
    val classDesignAnalysisResult: List<AnalysisOutput>,
    /** The list of [TestStep]s that were performed during this test run. */
    val testSteps: List<TestStep>
) : DefaultSerializable {
    override fun toJson() = defaultToJson()
}

data class TestingBlockCounts(
    var discardedTests: Int = 0,
    var edgeTests: Int = 0,
    var simpleTests: Int = 0,
    var simpleEdgeMixedTests: Int = 0,
    var generatedMixedTests: Int = 0,
    var allGeneratedTests: Int = 0) {

    val numTests: Int
        get() = edgeTests + simpleTests + simpleEdgeMixedTests + generatedMixedTests + allGeneratedTests
}
