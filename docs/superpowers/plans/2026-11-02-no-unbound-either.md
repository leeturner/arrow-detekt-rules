# NoUnboundEither Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new rule, `NoUnboundEither`, flags an Either, Option, Ior or Effect whose value is dropped as a statement while a Raise is in scope.

**Architecture:** One rule file. It overrides `visitExpression` and only looks at statement-shaped expressions (whole statements and `if`/`when` branches), so a chain is reported once. Each check runs in an `analyze` block, cheapest first: the value is unused (`isUsedAsExpression`), the type is bindable, the expression isn't an `onLeft`-style side-effect call, and a Raise is among the implicit values in scope (`scopeContext`).

**Tech Stack:** Kotlin, detekt 2 alpha (`detekt-api`, `detekt-test`), Kotlin Analysis API, Arrow core (tests).

**Spec:** `docs/superpowers/specs/2026-11-02-no-unbound-either-design.md`

## Global Constraints

- Package `io.github.leeturner.arrowdetekt`. Tests use the shared `PRELUDE` in `src/test/kotlin/io/github/leeturner/arrowdetekt/Prelude.kt`, and lint with `rule.lintWithContext(createEnvironment(), PRELUDE + body)` as `NoNestedEitherTest` does.
- Test snippets must compile (`compile-test-snippets` is on). Use fully qualified names (`arrow.core.Some(1)`) rather than editing `PRELUDE`.
- Bindable types: `arrow/core/Either`, `arrow/core/Option`, `arrow/core/Ior`, and any function type whose receiver is a subtype of `RAISE` (which covers Effect and EagerEffect). `kotlin.Result` is not bindable.
- The message is exactly `The <Type> from <expression text> is never bound, so its error is dropped. Use <expression text>.bind() instead.`, where `<Type>` is `Either`, `Option`, `Ior` or `Effect`. EagerEffect reports as `Effect`.
- Reuse `RAISE` from `Raise.kt`.
- Commits use `git commit --no-gpg-sign`.

## Review Focus

1. `raise(E)` and `TODO()` statements have type `Nothing`, which is a subtype of Either. They must not be flagged, and they appear in almost every Raise scope. Test `ignoresNothingStatements` in Task 1.
2. `findUser(1).onLeft { println(it) }` returns an Either but is a deliberate side effect, so the spec says it isn't flagged. Test `ignoresOnLeftSideEffect` in Task 1.
3. A local function inside `either { }` still sees the Raise, so the spec counts it. Test `flagsInsideLocalFunction` in Task 1.
4. A `when` branch used as a statement is flagged the same way as an `if` branch. Covered in `flagsIfAndWhenBranches` in Task 1.
5. `if (c) findUser(1) else findUser(2)` as a statement gives two findings, not three: the `if` itself is unused and has type Either too. Test `reportsBranchesNotTheIfAroundThem` in Task 1.
6. A lambda stored in a `val` (`val load = { findUser(1) }`) returns its last line, so that line is used and isn't flagged. Test `ignoresLambdaResult` in Task 1.

---

### Task 1: NoUnboundEither rule, registration and docs

**Files:**
- Create: `src/main/kotlin/io/github/leeturner/arrowdetekt/NoUnboundEither.kt`
- Create: `src/test/kotlin/io/github/leeturner/arrowdetekt/NoUnboundEitherTest.kt`
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/ArrowRuleSetProvider.kt` (add `::NoUnboundEither` after `::NoNestedEither`)
- Modify: `src/main/resources/config/config.yml` (add `NoUnboundEither: active: true` after `NoNestedEither`)
- Modify: `src/test/kotlin/io/github/leeturner/arrowdetekt/ArrowRuleSetProviderTest.kt`
- Modify: `README.md` (a `### NoUnboundEither` section after `### NoNestedEither`)
- Modify: `arrow-detekt-rules.taskpaper` (archive the item)

**Interfaces:**
- Consumes: `RAISE` from `Raise.kt`
- Produces:
  ```kotlin
  class NoUnboundEither(config: Config) :
      Rule(config, "An Either, Option, Ior or Effect left unbound inside a Raise scope drops its error."),
      RequiresAnalysisApi
  ```

- [ ] **Step 1: Write the failing tests**

Create `NoUnboundEitherTest` with the same `env`/`rule`/`lint` setup as `NoNestedEitherTest`, and these tests:

```kotlin
@Test
fun flagsEitherStatement() {
    val findings = lint("fun f() = either<E, Int> { findUser(1); 1 }")
    assertEquals("The Either from findUser(1) is never bound, so its error is dropped. Use findUser(1).bind() instead.", findings.single().message)
}

@Test
fun namesOptionIorAndEffect() {
    val findings = lint(
        """
        fun f() = either<E, Int> {
            arrow.core.Some(1)
            arrow.core.Ior.Right(1)
            arrow.core.raise.effect<E, Int> { 1 }
            arrow.core.raise.eagerEffect<E, Int> { 1 }
            1
        }
        """.trimIndent(),
    )
    assertEquals(listOf("The Option", "The Ior", "The Effect", "The Effect"), findings.map { it.message.substringBefore(" from") })
}

@Test
fun flagsRaiseExtensionFunction() {
    assertEquals(1, lint("fun Raise<E>.g() { findUser(1) }").size)
}

@Test
fun flagsContextParameterFunction() {
    assertEquals(1, lint("context(r: Raise<E>) fun g() { findUser(1) }").size)
}

@Test
fun flagsInsideNestedLambda() {
    assertEquals(1, lint("fun f() = either<E, Unit> { listOf(1).forEach { findUser(it) } }").size)
}

@Test
fun flagsInsideLocalFunction() {
    assertEquals(1, lint("fun f() = either<E, Int> { fun load() { findUser(1) }; load(); 1 }").size)
}

@Test
fun flagsLastLineOfUnitLambda() {
    assertEquals(1, lint("fun f() = either<E, Unit> { findUser(1) }").size)
}

@Test
fun flagsIfAndWhenBranches() {
    val findings = lint(
        """
        fun Raise<E>.g(c: Boolean, n: Int) {
            if (c) findUser(1)
            if (c) { findUser(2) }
            when (n) {
                1 -> findUser(3)
                else -> {}
            }
        }
        """.trimIndent(),
    )
    assertEquals(3, findings.size)
}

// The if itself has type Either and is unused too; only its branches are reported
@Test
fun reportsBranchesNotTheIfAroundThem() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) findUser(1) else findUser(2) }")
    assertEquals(listOf("findUser(1)", "findUser(2)"), findings.map { it.message.substringAfter("from ").substringBefore(" is never") })
}

@Test
fun reportsChainOnce() {
    val findings = lint("fun f() = either<E, Int> { findUser(1).map { it.length }; 1 }")
    assertEquals("Use findUser(1).map { it.length }.bind() instead.", findings.single().message.substringAfter("dropped. "))
}
```

Not flagged (each asserts `0` findings):

```kotlin
ignoresBound:                "fun f() = either<E, Int> { findUser(1).bind(); 1 }"
ignoresStored:               "fun f() = either<E, Int> { val u = findUser(1); u.bind().length }"
ignoresFolded:               "fun f() = either<E, Int> { findUser(1).fold({ 0 }, { it.length }); 1 }"
ignoresOnLeftSideEffect:     "fun f() = either<E, Int> { findUser(1).onLeft { println(it) }; 1 }"
ignoresReturnedAsValue:      "fun f() = either<E, _> { findUser(1) }"
ignoresLambdaResult:         "fun f() = either<E, Int> { val load = { findUser(1) }; load().bind().length }"
ignoresOutsideRaiseScope:    "fun g() { findUser(1) }"
ignoresResult:               "fun f() = either<E, Int> { runCatching { plainCall() }; 1 }"
ignoresNothingStatements:    "fun Raise<E>.g(c: Boolean) { if (c) raise(E); if (!c) TODO() }"
```

In `ArrowRuleSetProviderTest`, add `"NoUnboundEither"` after `"NoNestedEither"` in the rule list, and `  NoUnboundEither:\n    active: true\n` after `NoNestedEither`'s entry in the expected YAML.

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew test --tests '*NoUnboundEitherTest' --tests '*ArrowRuleSetProviderTest'`
Expected: compilation fails with an unresolved reference to `NoUnboundEither`.

- [ ] **Step 3: Implement `NoUnboundEither` in `NoUnboundEither.kt`**

`override fun visitExpression(expression: KtExpression)`: call `super`, then only consider `expression` when its parent is a `KtBlockExpression`, `KtContainerNodeForControlStructureBody` (`if` branch without braces) or `KtWhenEntry`. Skip `KtBlockExpression`, `KtIfExpression`, `KtWhenExpression` and `KtTryExpression` themselves: their branches are visited anyway and give a better message. Then, in one `analyze(expression)` block, return early unless all of these hold, checked in this order:

1. `bindableName(expression.expressionType)` is not null. This runs first because declarations (`KtProperty`, `KtNamedFunction`) also reach `visitExpression` with a block parent, and their null `expressionType` stops them here.
2. `!expression.isUsedAsExpression` This is a private `KaSession.bindableName(type: KaType?): String?` that returns null for `Nothing` (`isNothingType`), `"Either"`/`"Option"`/`"Ior"` when `type.isSubtypeOf(classId)`, `"Effect"` when the type is a `KaFunctionType` whose `receiverType` is a subtype of `RAISE`, and otherwise null.
3. The resolved call of the expression (the selector for a qualified expression) isn't a function in package `arrow.core` named `onLeft`, `onRight`, `onBoth`, `onSome` or `onNone`.
4. `expression.containingKtFile.scopeContext(expression).implicitValues.any { it.type.isSubtypeOf(RAISE) }`

Report `Finding(Entity.from(expression), message)` with the message from Global Constraints.

If `flagsContextParameterFunction` fails because `implicitValues` leaves out context parameters, also accept an enclosing `KtFunction` whose symbol's `contextParameters` include a Raise type (`raiseReceivers` in `Raise.kt` shows the API).

If `flagsLastLineOfUnitLambda` or `flagsInsideNestedLambda` fail because `isUsedAsExpression` is conservative about a lambda's last line, treat that line as unused when the enclosing lambda's symbol (`KaAnonymousFunctionSymbol`) has a Unit `returnType`.

If `namesOptionIorAndEffect` misses the Effects because the typealias doesn't expand to a `KaFunctionType`, check `type.abbreviation?.classId` against `arrow/core/raise/Effect` and `arrow/core/raise/EagerEffect` instead.

- [ ] **Step 4: Register the rule**

Add `::NoUnboundEither` to `ArrowRuleSetProvider.instance()` and `NoUnboundEither:\n    active: true` to `config.yml`, both after `NoNestedEither`.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, every test passing.

- [ ] **Step 6: Add the README entry**

Add a `### NoUnboundEither` section after `### NoNestedEither`. Follow the other rules' format: one sentence on what it flags, a flagged and a fine `either { }` example with the real message in the comment, then a "Not flagged" paragraph. That paragraph lists outside a Raise scope (and Kotlin 2.3's `-Xreturn-value-checker` for that case), bound, stored, returned or handled values, `onLeft`/`onRight`/`onSome`/`onNone` side effects, and `kotlin.Result`.

- [ ] **Step 7: Archive the backlog item**

Run from the repo root:

```bash
export XDG_DATA_HOME=<scratchpad>/na-data
na find "Flag an Either left unbound" --no-pager   # exactly one match
na archive "Flag an Either left unbound"
git diff arrow-detekt-rules.taskpaper              # only that item moved to Archive
```

- [ ] **Step 8: Commit**

```bash
git add src README.md arrow-detekt-rules.taskpaper
git commit --no-gpg-sign -m "Add NoUnboundEither rule

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
