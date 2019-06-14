package edu.illinois.cs.cs125.answerable

import edu.illinois.cs.cs125.answerable.typeManagement.*
import java.lang.IllegalStateException
import java.lang.reflect.*

internal fun getSolutionClass(name: String): Class<*> = findClass(
    name,
    "Couldn't find reference solution."
)

internal fun getAttemptClass(name: String): Class<*> = findClass(
    name,
    "Couldn't find student attempt class named $name."
)

private fun findClass(name: String, failMsg: String): Class<*> {
    val solutionClass: Class<*>
    try {
        solutionClass = Class.forName(name)
    } catch (unused: Exception) {
        throw IllegalStateException(failMsg)
    }
    return solutionClass
}

internal fun Class<*>.getReferenceSolutionMethod(name: String = ""): Method? {
    val methods =
            this.declaredMethods
                .filter { it.getAnnotation(Solution::class.java)?.name?.equals(name) ?: false }

    if (methods.size > 1) throw AnswerableMisuseException("Can't find singular solution method with tag `$name'.")
    if (methods.isEmpty()) return null
    val solution = methods[0]
    return solution.also { it.isAccessible = true }
}

internal fun Method.isPrinter(): Boolean = this.getAnnotation(Solution::class.java)?.prints ?: false

internal fun Class<*>.findSolutionAttemptMethod(matchTo: Method?): Method? {
    if (matchTo == null) return null
    val matchName: String = matchTo.name
    val matchRType: String = matchTo.genericReturnType.sourceName
    val matchPTypes: List<String> = matchTo.genericParameterTypes.map { it.sourceName }

    var methods =
            this.declaredMethods
                .filter { it.name == matchName }
    if (methods.isEmpty()) {
        throw SubmissionMismatchException("Expected a method named `$matchName'.")
    }

    methods = methods.filter { it.genericReturnType.sourceName == matchRType }
    if (methods.isEmpty()) {
        throw SubmissionMismatchException("Expected a method with return type `$matchRType'.")
    }

    methods = methods.filter { it.genericParameterTypes?.map { it.sourceName }?.equals(matchPTypes) ?: false }
    if (methods.isEmpty()) {
        throw SubmissionMismatchException(
            "Expected a method with parameter types `${matchPTypes.joinToString(prefix = "[", postfix = "]")}'."
        )
    }
    // If the student code compiled, there can only be one method
    return methods[0].also { it.isAccessible = true }
}

internal fun Class<*>.getPublicFields(): List<Field> =
    this.declaredFields.filter { Modifier.isPublic(it.modifiers) }

internal fun Class<*>.getPublicMethods(): List<Method> =
    declaredMethods.filter { Modifier.isPublic(it.modifiers) }

internal fun Class<*>.getAllGenerators(): List<Method> =
        this.declaredMethods
            .filter { method -> method.isAnnotationPresent(Generator::class.java) }
            .map { it.isAccessible = true; it }

internal fun Class<*>.getEnabledGenerators(enabledNames: Array<String>): List<Method> =
        this.declaredMethods
            .filter { it.isAnnotationPresent(Generator::class.java) }
            .groupBy { it.genericReturnType }
            .flatMap { entry -> when (entry.value.size) {
                1 -> entry.value
                else -> {
                    entry.value
                        .filter { it.getAnnotation(Generator::class.java).name in enabledNames }
                        .let { enabledGenerators ->
                            when (enabledGenerators.size) {
                                1 -> enabledGenerators
                                else -> {
                                    val name = entry.key.sourceName
                                    throw AnswerableMisuseException(
                                        "Failed to resolve @Generator conflict:\n" +
                                                "Multiple enabled generators found for type `$name'."
                                    )
                                }
                            }
                        }
                }
            }}
            .map { it.isAccessible = true; it }

internal fun Class<*>.getDefaultAtNext(): Method? =
        this.declaredMethods
            .filter { it.getAnnotation(Next::class.java)?.name?.equals("") ?: false }
            .let { unnamedNexts -> when (unnamedNexts.size) {
                0 -> null
                1 -> unnamedNexts[0].also {it.isAccessible = true }
                else -> throw AnswerableMisuseException("Failed to resolve @Next conflict:\nFound multiple unnamed @Next annotations.")
            }}

internal fun Class<*>.getAtNext(enabledNames: Array<String>): Method? =
        this.declaredMethods
            .filter { it.getAnnotation(Next::class.java)?.name?.let { name -> enabledNames.contains(name) } ?: false }
            .let { atNexts -> when (atNexts.size) {
                0 -> null
                1 -> atNexts[0].also { it.isAccessible = true }
                else -> throw AnswerableMisuseException("Failed to resolve @Next conflict:\nFound multiple @Next annotations with enabled names.")
            }}
            ?: this.getDefaultAtNext()

internal fun Class<*>.getCustomVerifier(name: String): Method? =
        this.declaredMethods
            .filter { it.getAnnotation(Verify::class.java)?.name?.equals(name) ?: false }
            .let { verifiers -> when(verifiers.size) {
                0 -> null
                1 -> verifiers[0].also { it.isAccessible = true }
                else -> throw AnswerableMisuseException("Found multiple @Verify annotations with name `$name'.")
            }}

// We use this class so that we can manipulate Fields and Methods at the same time,
// as it is an erroneous conflict to provide both a field and a method for the same type's edge or corner cases.
private class FieldOrMethod(val member: Member) {
    init {
        when (member) {
            is Field -> member.isAccessible = true
            is Method -> member.isAccessible = true
        }
    }
    val genericType: Type = when(member) {
        is Field -> member.genericType
        is Method -> member.genericReturnType
        else -> throw IllegalStateException()
    }

    fun <T : Annotation> isAnnotationPresent(annotationClass: Class<T>): Boolean = when (member) {
        is Field -> member.isAnnotationPresent(annotationClass)
        is Method -> member.isAnnotationPresent(annotationClass)
        else -> throw IllegalStateException()
    }
    fun <T : Annotation> getAnnotation(annotationClass: Class<T>): T? = when (member) {
        is Field -> member.getAnnotation(annotationClass)
        is Method -> member.getAnnotation(annotationClass)
        else -> throw IllegalStateException()
    }

    fun get(): ArrayWrapper {
        val value = when (member) {
            is Field -> member[null]
            is Method -> member.invoke(null)
            else -> throw IllegalStateException()
        } ?: throw IllegalStateException()

        return wrapArray(value)
    }
}

private fun Class<*>.getEnabledCases(edgeIfElseSimple: Boolean, enabledNames: Array<String>): Map<Type, ArrayWrapper> {
    val annotationClass = if (edgeIfElseSimple) EdgeCase::class.java else SimpleCase::class.java

    val caseName = if (edgeIfElseSimple) "edge" else "simple"

    val declaredMembers: List<Member> = this.declaredFields.toList() + this.declaredMethods.toList()
    val declaredFMs: List<FieldOrMethod> = declaredMembers.map { FieldOrMethod(it) }

    val casesInUseList = declaredFMs
        .filter { it.isAnnotationPresent(annotationClass) }
        .map {
            if (it.genericType.let { type -> (type is Class<*> && type.isArray) || type is GenericArrayType}) {
                it
            } else {
                throw AnswerableMisuseException("${caseName.capitalize()} case providers must provide an array.")
            }}
        .groupBy { it.genericType.let { type ->
            when (type) {
                is Class<*> -> type.componentType
                is GenericArrayType -> type.genericComponentType
                else -> throw IllegalStateException()
            }
        } as Type }
        .mapValues { entry ->
            when (entry.value.size) {
                0 -> throw IllegalStateException("An error occurred after a `groupBy` call. Please report a bug.")
                1 -> entry.value[0]
                else -> entry.value
                    .filter { when (val annotation = it.getAnnotation(annotationClass)) {
                            is EdgeCase? -> annotation?.name
                            is SimpleCase? -> annotation?.name
                            else -> null
                        } in enabledNames
                    }
                    .let { enabled ->
                        when (enabled.size) {
                            0 -> entry.value.find { when (val annotation = it.getAnnotation(annotationClass)) {
                                    is EdgeCase? -> annotation?.name
                                    is SimpleCase? -> annotation?.name
                                    else -> null
                                }?.equals("") ?: false
                            }
                            1 -> enabled[0]
                            else ->
                                throw AnswerableMisuseException(
                                    "Multiple enabled $caseName case providers for type `${entry.key.sourceName}'.")
                        }
                    }
            }
        }
        .mapNotNull { if (it.value == null) null else Pair(it.key, it.value as FieldOrMethod) }

    val casesInUseMap = mapOf(*casesInUseList.toTypedArray())

    return casesInUseMap.mapValues {
        try {
            return@mapValues it.value.get()
        } catch (e: IllegalStateException) {
            throw AnswerableMisuseException("Provided $caseName cases array for type `${it.key.sourceName}' was null.")
        }
    }
}

internal fun Class<*>.getEnabledEdgeCases(enabledNames: Array<String>): Map<Type, ArrayWrapper> =
    getEnabledCases(true, enabledNames)

internal fun Class<*>.getEnabledSimpleCases(enabledNames: Array<String>): Map<Type, ArrayWrapper> =
    getEnabledCases(false, enabledNames)

internal fun Class<*>.getPrecondition(name: String): Method? =
        this.declaredMethods
            .filter { it.getAnnotation(Precondition::class.java)?.name?.equals(name) ?: false }
            .let {
                when (it.size) {
                    0 -> null
                    1 -> it[0]
                    else -> throw AnswerableMisuseException("Multiple Preconditions found with name `$name'.")
                }
            }