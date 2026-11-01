# Arrow detekt rules PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A detekt 2 rule set `arrow` with one rule, `NoCatchAllInRaiseScope`, passing its own tests and loadable by encore-web-api via `--include-build`.

**Architecture:** Single-module Gradle project. A `RuleSetProvider` registered via `META-INF/services` exposes one rule that implements `RequiresAnalysisApi` and uses the Kotlin Analysis API to find catch-alls (`runCatching`, broad `catch`) wrapping a call whose `Raise` receiver comes from outside the catch-all.

**Tech Stack:** Kotlin 2.4.10 (JVM), Gradle 9.8.1 wrapper, detekt `2.0.0-alpha.6` (`dev.detekt:detekt-api`, `dev.detekt:detekt-test`), Arrow `2.2.3` (`io.arrow-kt:arrow-core`, tests only), JUnit 5 via `kotlin("test")`.

**Spec:** `docs/superpowers/specs/2026-11-01-arrow-detekt-rules-poc-design.md`

## Global Constraints

- Coordinates `io.github.leeturner:arrow-detekt-rules:0.1.0-SNAPSHOT`; `settings.gradle.kts` sets `rootProject.name = "arrow-detekt-rules"`, build sets `group = "io.github.leeturner"`.
- Kotlin Gradle plugin `2.4.10` (matches the `kotlin-compiler` detekt alpha.6 depends on).
- `detekt-api` is `compileOnly`; `detekt-test` and `arrow-core` are `testImplementation`.
- Test task sets `systemProperty("compile-test-snippets", "true")`; every test snippet must compile.
- Bytecode target JVM 17 (`kotlin.compilerOptions.jvmTarget = JVM_17`, `java.targetCompatibility = 17`); local JDK is 25, no toolchain provisioning.
- Rule set id `arrow`; rule name `NoCatchAllInRaiseScope`; active by default.
- Package `io.github.leeturner.arrowdetekt`.
- Commits use `git commit --no-gpg-sign`. Nothing is committed in encore-web-api.

## Review Focus

1. A raising call inside an ordinary inline lambda in the try body (`try { ids.forEach { find(it).bind() } } catch (e: Exception) {}`) must still be flagged — the Raise receiver is outside. Test in Task 2.
2. A Raise scope opened with something other than `either` inside the catch-all (`runCatching { recover({ raise(E) }) { 0 } }`) must not be flagged. Test in Task 3.
3. Several catch clauses: only the broad one is reported (`catch (e: IOException)` then `catch (e: Exception)` → one finding, on the second clause). Test in Task 2.
4. A broad catch that uses the exception without rethrowing (logs it, wraps it) is still flagged. Test in Task 2.
5. Receiver-form `runCatching` (`id.runCatching { findUser(this).bind() }`) is flagged like the plain form. Test in Task 1.

---

## Test harness (shared by Tasks 1–3)

`src/test/kotlin/io/github/leeturner/arrowdetekt/NoCatchAllInRaiseScopeTest.kt`:

```kotlin
class NoCatchAllInRaiseScopeTest {
    private val env = createEnvironment()
    private val rule = NoCatchAllInRaiseScope(Config.empty)

    private fun lint(body: String): List<Finding> = rule.lintWithContext(env, PRELUDE + body)

    private companion object {
        val PRELUDE = """
            import arrow.core.Either
            import arrow.core.raise.Raise
            import arrow.core.raise.either
            import arrow.core.raise.ensure
            import arrow.core.raise.recover
            import arrow.core.nonFatalOrThrow
            import java.io.IOException
            import kotlin.coroutines.cancellation.CancellationException

            object E
            fun findUser(id: Int): Either<E, String> = Either.Right("u")
            fun plainCall(): Int = 1
        """.trimIndent() + "\n"
    }
    // tests below
}
```

Each test body is top-level Kotlin appended to `PRELUDE`, typically `fun f() = either<E, Unit> { ... }`.

---

### Task 1: Project, provider and `runCatching` detection

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `.gitignore` (`build/`, `.gradle/`, `.idea/`, `.kotlin/`)
- Copy from `~/dev/encore/encore-web-api`: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `src/main/kotlin/io/github/leeturner/arrowdetekt/ArrowRuleSetProvider.kt`
- Create: `src/main/kotlin/io/github/leeturner/arrowdetekt/NoCatchAllInRaiseScope.kt`
- Create: `src/main/resources/META-INF/services/dev.detekt.api.RuleSetProvider` (one line: `io.github.leeturner.arrowdetekt.ArrowRuleSetProvider`)
- Test: `src/test/kotlin/io/github/leeturner/arrowdetekt/NoCatchAllInRaiseScopeTest.kt`, `src/test/kotlin/io/github/leeturner/arrowdetekt/ArrowRuleSetProviderTest.kt`

**Interfaces:**
- Produces: `class ArrowRuleSetProvider : RuleSetProvider` with `ruleSetId = RuleSetId("arrow")`, `instance() = RuleSet(ruleSetId, listOf(::NoCatchAllInRaiseScope))`.
- Produces: `class NoCatchAllInRaiseScope(config: Config) : Rule(config, "<description>"), RequiresAnalysisApi`, overriding `visitCallExpression(expression: KtCallExpression)`.
- Produces (private, same file, reused by Tasks 2–3): `fun containsEscapingRaiseCall(body: KtElement): Boolean`.

- [ ] **Step 1: Build files and wrapper.** Plugins `kotlin("jvm") version "2.4.10"`; repositories `mavenCentral()`; deps per Global Constraints plus `testImplementation(kotlin("test"))` and `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`; `tasks.test { useJUnitPlatform(); systemProperty("compile-test-snippets", "true") }`.

- [ ] **Step 2: Write failing tests**

`ArrowRuleSetProviderTest.providerIsRegistered`: `ServiceLoader.load(RuleSetProvider::class.java).single { it.ruleSetId.value == "arrow" }` and its `instance().rules.keys.map { it.value } == listOf("NoCatchAllInRaiseScope")`.

In `NoCatchAllInRaiseScopeTest`:

| Test | Body | Expect |
|---|---|---|
| `flagsRunCatchingAroundBind` | `fun f() = either<E, String?> { runCatching { findUser(1).bind() }.getOrNull() }` | 1 finding |
| `flagsReceiverRunCatching` | `fun f() = either<E, String?> { 1.runCatching { findUser(this).bind() }.getOrNull() }` | 1 finding |
| `ignoresEitherCatch` | `fun f() = either<E, String> { Either.catch { plainCall() }.mapLeft { E }.bind(); "x" }` | 0 |
| `ignoresRunCatchingWithoutRaise` | `fun f() = either<E, Int> { runCatching { plainCall() }.getOrDefault(0) }` | 0 |

Assert with `assertEquals(n, findings.size)`.

- [ ] **Step 3: Run** `./gradlew test` — expect compile failure (classes missing).

- [ ] **Step 4: Implement provider, service file and rule.** `visitCallExpression` reports `Finding(Entity.from(expression), message)` when the call resolves to callable id `kotlin.runCatching` (both overloads) and `containsEscapingRaiseCall(lambda body)` is true. Message: `"runCatching catches Arrow's RaiseCancellationException, so the raise never reaches its Raise scope. Use Either.catch, or rethrow with nonFatalOrThrow()."`

`containsEscapingRaiseCall` — the algorithm the tests don't determine:

```
for call in body.collectDescendantsOfType<KtCallExpression>():
  analyze(call):
    sym = call.resolveToCall()?.successfulFunctionCallOrNull()?.partiallyAppliedSymbol ?: continue
    for r in [sym.dispatchReceiver, sym.extensionReceiver] + sym.contextArguments (non-null):
      if r.type is a subtype of arrow.core.raise.Raise
         and (r is not an implicit receiver            // explicit receiver: treat as escaping
              or r.symbol.psi is not inside body):     // receiver provided by a lambda/function outside
        return true
return false
```

Read a stock detekt rule implementing `RequiresAnalysisApi` (e.g. `IgnoredReturnValue` in `dev.detekt:detekt-rules-potential-bugs:2.0.0-alpha.6` sources) for exact Analysis API calls.

- [ ] **Step 5: Run** `./gradlew test` — expect all 5 tests PASS.

- [ ] **Step 6: Commit** `git add -A && git commit --no-gpg-sign -m "Add arrow rule set with runCatching detection"`

### Task 2: Broad `catch` detection

**Files:**
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/NoCatchAllInRaiseScope.kt`
- Test: `src/test/kotlin/io/github/leeturner/arrowdetekt/NoCatchAllInRaiseScopeTest.kt`

**Interfaces:**
- Consumes: `containsEscapingRaiseCall(body: KtElement)` from Task 1.
- Produces: override `visitTryExpression(expression: KtTryExpression)`.

- [ ] **Step 1: Write failing tests**

| Test | Body | Expect |
|---|---|---|
| `flagsCatchException` | `fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { } }` | 1 |
| `flagsCatchThrowable` | same with `Throwable` | 1 |
| `flagsCatchCancellationException` | same with `CancellationException` | 1 |
| `flagsInRaiseExtensionFunction` | `fun Raise<E>.load(x: Boolean) { try { ensure(x) { E } } catch (e: Exception) { } }` | 1 |
| `flagsBindInsideInlineLambda` | `fun f() = either<E, Unit> { try { listOf(1, 2).forEach { findUser(it).bind() } } catch (e: Exception) { } }` | 1 |
| `flagsCatchThatLogsButDoesNotRethrow` | `fun f() = either<E, Unit> { try { raise(E) } catch (e: Exception) { println(e) } }` | 1 |
| `flagsOnlyTheBroadClause` | `fun f() = either<E, Unit> { try { raise(E) } catch (e: IOException) { } catch (e: Exception) { } }` | 1, and `findings.single().entity` is on the line of `catch (e: Exception)` |
| `ignoresNarrowCatch` | `... catch (e: IOException) { } }` only | 0 |
| `ignoresRethrow` | `... catch (e: Exception) { throw e } }` | 0 |
| `ignoresNonFatalOrThrow` | `... catch (e: Exception) { e.nonFatalOrThrow() } }` | 0 |

- [ ] **Step 2: Run** `./gradlew test` — expect the 7 "flags" tests FAIL, the rest PASS.

- [ ] **Step 3: Implement `visitTryExpression`.** Skip unless `containsEscapingRaiseCall(expression.tryBlock)`. For each `catchClause`: flag when `arrow.core.raise.RaiseCancellationException` is a subtype of the resolved catch parameter type, and the catch block has neither `throw <param>` nor `<param>.nonFatalOrThrow()`. Report on the catch clause. Message: `"Catching <type> here swallows Arrow's RaiseCancellationException, so the raise never reaches its Raise scope. Catch a narrower type, use Either.catch, or rethrow with nonFatalOrThrow()."`

- [ ] **Step 4: Run** `./gradlew test` — expect all PASS.

- [ ] **Step 5: Commit** `git commit --no-gpg-sign -am "Detect broad catch clauses around raising calls"`

### Task 3: Raise scopes opened inside the catch-all

**Files:**
- Modify: `NoCatchAllInRaiseScope.kt` only if a test fails
- Test: `NoCatchAllInRaiseScopeTest.kt`

- [ ] **Step 1: Write tests**

| Test | Body | Expect |
|---|---|---|
| `ignoresEitherOpenedInsideTry` | `fun f() = try { either<E, Unit> { raise(E) } } catch (e: Exception) { null }` | 0 |
| `ignoresRecoverOpenedInsideRunCatching` | `fun f() = runCatching { recover<E, Int>({ raise(E) }) { 0 } }` | 0 |
| `flagsOuterScopeRaiseNextToInnerScope` | `fun f() = either<E, Unit> { try { either<E, Unit> { raise(E) }; raise(E) } catch (e: Exception) { } }` | 1 |

- [ ] **Step 2: Run** `./gradlew test`. If Task 1's receiver-origin check is right these pass already; if any fail, fix `containsEscapingRaiseCall` (not the tests) and re-run until all PASS.

- [ ] **Step 3: Commit** `git commit --no-gpg-sign -am "Pin Raise scopes opened inside catch-alls"`

### Task 4: Run inside encore-web-api, README

**Files:**
- Create: `README.md` — what the rule flags (one example from the spec), the `detektPlugins` line, the yml snippet, the `--include-build` command.
- Temporarily modify in `~/dev/encore/encore-web-api` (revert after, never commit): `build.gradle.kts` dependencies, `config/detekt/detekt.yml`.

- [ ] **Step 0: Record encore's state.** Run `git -C ~/dev/encore/encore-web-api status --short` and keep the output. If `build.gradle.kts` or `config/detekt/detekt.yml` already have uncommitted changes, stop and ask before touching them.

- [ ] **Step 1: Wire encore.** Add `detektPlugins("io.github.leeturner:arrow-detekt-rules:0.1.0-SNAPSHOT")`; append to `detekt.yml`:

```yaml
arrow:
  NoCatchAllInRaiseScope:
    active: true
```

- [ ] **Step 2: Prove loading.** From encore: `./gradlew detektMain --include-build ../../personal/arrow-detekt-rules`. Expect the included build to compile and the task to succeed with no `arrow` config-validation error. KGP version warnings are expected (spec watch item).

- [ ] **Step 3: Prove detection.** Add a throwaway `src/main/kotlin/PocProbe.kt` in encore containing the `flagsCatchException` body (with imports), re-run Step 2's command, expect a `NoCatchAllInRaiseScope` finding on `PocProbe.kt`. Delete the file.

- [ ] **Step 4: Revert encore.** Remove the lines added in Step 1 (by hand, not `git checkout`). `git -C ~/dev/encore/encore-web-api status --short` matches the output from Step 0.

- [ ] **Step 5: Commit README here** `git add README.md && git commit --no-gpg-sign -m "Add README with local usage"`
