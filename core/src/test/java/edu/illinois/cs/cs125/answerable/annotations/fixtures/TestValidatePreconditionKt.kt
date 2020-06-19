package edu.illinois.cs.cs125.answerable.annotations.fixtures

import edu.illinois.cs.cs125.answerable.annotations.Precondition
import edu.illinois.cs.cs125.answerable.annotations.Solution

@Precondition(name = "correct0")
fun correct0(): Boolean = true

// wrong number/types of args
@Precondition(name = "broken0")
fun broken0(): Boolean = true

class TestValidatePreconditionKt {
    @Solution(name = "correct0")
    fun good0(): Boolean = true

    @Solution(name = "broken0")
    fun bad0(i: Int): Boolean = i >= 0
}
