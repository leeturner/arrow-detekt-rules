# Arrow detekt rules: proof of concept

Date: 2026-11-01
Background: the "Arrow detekt rules plugin: idea" doc (claude.ai artifact B1DUsRYvotDnRQd9o25J4s), which recommends waiting for detekt 2 final. This PoC goes ahead anyway to de-risk the build.

## Intent

Prove that one Arrow-aware detekt 2 rule can be written against the Analysis API, tested in isolation, and run inside encore-web-api's `detektMain` without a formal release. The riskiest unknown is the poorly documented `RequiresAnalysisApi` path; this PoC exists to answer it.

## Goals

1. `NoCatchAllInRaiseScope` passes its own tests (`./gradlew test`). This is the success bar.
2. encore-web-api can run the rule from this local checkout with `--include-build`, no publish step.

## Non-goals

- The other candidate rules (`UseEnsure`, `UseEnsureNotNull`, `PreferEitherCatch`, `NoFoldBackIntoEither`, `NoNestedEither`).
- Publishing to Maven Central or GitHub Packages, release automation, CI matrix.
- Quick fixes / autocorrect.
- Supporting any detekt version other than 2.0.0-alpha.6.

## Anti-goals

- Committing anything to encore-web-api that breaks its build for someone without this checkout.

## Constraints (verified in the Gradle cache)

- detekt `2.0.0-alpha.6` (`dev.detekt`). `detekt-api` and `detekt-test` depend on `kotlin-compiler` **2.4.10**, so the plugin compiles with Kotlin 2.4.10 to match the compiler detekt loads it into (encore itself is on 2.4.21; that is fine for the consumer).
- Arrow `2.2.3`, Gradle `9.8.1`, JDK 25 locally.
- Registration: `META-INF/services/dev.detekt.api.RuleSetProvider` listing the provider class (same as detekt's own rule jars).
- `RequiresAnalysisApi` is a marker interface; such rules run only in full-analysis tasks (`detektMain`, `detektTest`).
- Tests use `lintWithContext(createEnvironment(), code)` from `detekt-test`. `createEnvironment()` takes the test runtime classpath, so `arrow-core` as a test dependency makes Arrow calls resolve in snippets.
- In Arrow 2.2.3: `Raise` is `arrow.core.raise.Raise`; `bind` is a member of `Raise`; `ensure` is an extension on `Raise`; `RaiseCancellationException` is public and sealed, and is what a catch-all swallows.

## Approaches considered (local consumption in encore)

| Approach | Trade-off |
|---|---|
| **A. `--include-build` flag** (chosen) | Gradle substitutes the plugin coordinate with this project and rebuilds it on each run. No publish step, nothing path-specific in encore's settings. The `detektPlugins` line won't resolve without the flag, so it stays uncommitted (or behind a property). |
| B. `publishToMavenLocal` + `mavenLocal()` | Manual republish after every change; `mavenLocal()` can leak stale jars into other builds. |
| C. `detektPlugins(files(".../x.jar"))` | Manual rebuild, no versioning, transitive deps by hand. |

Picked A because editing a rule and re-running encore needs no extra step.

## Decided

- **Repo**: this directory, single Gradle module, git on `main`, to be pushed to `github.com/leeturner/arrow-detekt-rules`.
- **Coordinates**: `io.github.leeturner:arrow-detekt-rules:0.1.0-SNAPSHOT` (GitHub-style namespace, which Maven Central verifies through the GitHub account). Rule set id `arrow`. `--include-build` substitutes by `group:name`, so `settings.gradle.kts` must set `rootProject.name = "arrow-detekt-rules"` and the build must set `group = "io.github.leeturner"`; otherwise the flag silently does nothing.
- **Dependencies**: `detekt-api` as `compileOnly` (detekt supplies it at runtime); `detekt-test` and `arrow-core` as test dependencies.
- **Honest tests**: the test task sets `systemProperty("compile-test-snippets", "true")`. Without it `lintWithContext` lints snippets that don't compile, so unresolved Arrow calls would make the "does not flag" cases pass vacuously. Each test case below is shorthand; the real snippet must compile (imports, declared error type, stub functions).
- **Watch item**: the plugin builds with Kotlin Gradle plugin 2.4.10 while encore applies 2.4.21. The composite run may log classloader warnings; that's the version split, not a rule bug.
- **Consuming in encore**: add `detektPlugins("io.github.leeturner:arrow-detekt-rules:0.1.0-SNAPSHOT")`, enable the rule under `arrow:` in `config/detekt/detekt.yml`, run `./gradlew detektMain --include-build ../../personal/arrow-detekt-rules`. Encore has no `raise(` calls in `src/main`, so this proves loading, not detection.

## The rule: `NoCatchAllInRaiseScope`

Flags a catch-all that wraps a raising call, because the catch-all swallows Arrow's `RaiseCancellationException` and the error never reaches the Raise scope.

**A raising call** is a call that resolves to a function with `Raise` as its dispatch, extension or context receiver (covers `raise`, `bind`, `ensure`, `ensureNotNull`, ...), and whose `Raise` receiver comes from *outside* the try / `runCatching` body.

**A catch-all** is:
- `runCatching { ... }` (stdlib, plain or receiver form), or
- a `catch` clause whose type `RaiseCancellationException` is a subtype of (`Throwable`, `Exception`, `RuntimeException`, `IllegalStateException`, `CancellationException`), unless the catch block rethrows the caught exception (`throw e` or `e.nonFatalOrThrow()`).

Reported on the `runCatching` call or the `catch` clause. Severity default, active by default in the rule set (it catches a bug; the idea doc's "inactive by default" applies to the idiom rules).

### Test cases

Flags:
1. `either { runCatching { findUser(id).bind() } }`
2. `either { try { raise(E) } catch (e: Exception) { } }`
3. Same as 2 with `Throwable` and with `CancellationException`
4. `fun Raise<E>.load() { try { ensure(x) { E } } catch (e: Exception) { } }`

Does not flag:
5. `either { Either.catch { repo.find(id) }.mapLeft(::LookupFailed).bind() }`
6. `either { try { raise(E) } catch (e: IOException) { } }`
7. `try { either { raise(E) } } catch (e: Exception) { }` (Raise scope opened inside the try)
8. `either { try { raise(E) } catch (e: Exception) { throw e } }` and the `e.nonFatalOrThrow()` form
9. `either { runCatching { plainCall() } }` (no raising call)

## Left to the builder

- Exact Analysis API calls (`analyze(element) { ... }`, `resolveToCall`, subtype checks) — read stock detekt rules that implement `RequiresAnalysisApi` for the pattern.
- Test framework (JUnit 5 + whatever assertion lib is least setup).
- Gradle wrapper (copy 9.8.1 from encore-web-api).
