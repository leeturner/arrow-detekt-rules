# arrow-detekt-rules

Proof of concept: a [detekt](https://detekt.dev) 2 rule set for [Arrow](https://arrow-kt.io) code. Built against detekt `2.0.0-alpha.6`; not released.

## Rules

### `NoCatchAllInRaiseScope`

Flags `runCatching`, or a `catch` of `Exception`, `Throwable`, `CancellationException` or similar, wrapped around a call that raises into an outer Raise scope (`raise`, `bind`, `ensure`, ...). The catch-all swallows Arrow's `RaiseCancellationException`, so the error never reaches the scope.

```kotlin
// Flagged: runCatching catches the raise, so it never reaches either { }
either {
    val user = runCatching { findUser(id).bind() }.getOrNull()
    ensureNotNull(user) { UserMissing }
}

// Fine: turn exceptions into errors at the edge, then bind
either {
    val user = Either.catch { repository.find(id) }.mapLeft(::LookupFailed).bind()
    ensureNotNull(user) { UserMissing }
}
```

Not flagged: narrow catches (`IOException`), catches that always rethrow the raise (`throw e`, `e.nonFatalOrThrow()`, `if (e is CancellationException) throw e`), `runCatching { ... }.getOrThrow()`, Raise scopes opened inside the catch-all, and raises in code that runs later rather than inside the `try`. When catch-alls nest, only the innermost one is reported.

What counts as running later is a guess:

- A lambda passed as an argument is assumed to run during the call (`forEach`, `withContext`, `coroutineScope`, `transaction`), unless the call returns something that holds on to it: a `Sequence`, `Flow`, `Lazy` or function. So `asSequence().map { it.bind() }` and `lazy { raise(e) }` are not flagged.
- Local functions and lambdas stored in a `val` are treated as running later, even when they are called straight away inside the `try` (`val t = { raise(e) }; t()`).
- A non-inline helper that stores its lambda and returns something else is treated as running it now, so a raise inside it is flagged.

### `UseEnsure`

Flags an `if` with no `else` whose only statement is Arrow's `raise`, and says what to write instead.

```kotlin
// Flagged: Use ensure(!(amount > limit)) { OverLimit } instead of if (amount > limit) raise(OverLimit).
either {
    if (amount > limit) raise(OverLimit)
}

// Fine
either {
    ensure(amount <= limit) { OverLimit }
}
```

Not flagged: an `if` with an `else`, a block that does more than raise, a `raise` that isn't Arrow's, and null checks (`if (x == null) raise(e)`), which `UseEnsureNotNull` covers.

### `UseEnsureNotNull`

Flags `if (x == null) raise(e)` (or `null == x`) with no `else`, and suggests `ensureNotNull(x) { e }`, which also smart-casts `x`. `x ?: raise(e)` is idiomatic Kotlin and isn't flagged.

```kotlin
// Flagged: Use ensureNotNull(user) { UserMissing } instead of if (user == null) raise(UserMissing). It also smart-casts user to non-null.
either {
    if (user == null) raise(UserMissing)
}

// Fine
either {
    ensureNotNull(user) { UserMissing }
}
```

The rules need type resolution, so they run in `detektMain` / `detektTest`, not plain `detekt`.

## Using it from a local checkout

In the consuming project's `build.gradle.kts`:

```kotlin
dependencies {
    detektPlugins("io.github.leeturner:arrow-detekt-rules:0.1.0-SNAPSHOT")
}
```

Every rule is active by default (the jar ships `config/config.yml`). To turn one off, add it to the project's detekt config:

```yaml
arrow:
  UseEnsure:
    active: false
```

Then point Gradle at this checkout, which builds it and swaps it in for the dependency:

```shell
./gradlew detektMain --include-build ../path/to/arrow-detekt-rules
```

Without `--include-build` the dependency won't resolve, so keep that line out of the consumer's committed build until there is a release.

After changing a rule, run `./gradlew --stop` in the consuming project before re-running detekt. Its Gradle daemon keeps the plugin classes it loaded first, so it otherwise runs the old rule (and the old default config) even though the jar was rebuilt.

## Development

```shell
./gradlew test
```

### Adding a rule

1. Write the rule in `src/main/kotlin/io/github/leeturner/arrowdetekt/`, extending `Rule` and implementing `RequiresAnalysisApi`. Arrow rules need type resolution, so they run in `detektMain` / `detektTest` only.
2. Register it in `ArrowRuleSetProvider.instance()`.
3. Add it to `src/main/resources/config/config.yml` with `active: true`; every rule ships on by default. detekt merges this file into its defaults.
4. Update `ArrowRuleSetProviderTest`, which pins the exact rule list and the default config.
5. Test it the way `NoCatchAllInRaiseScopeTest` does: `lintWithContext(createEnvironment(), PRELUDE + snippet)`, with the shared `PRELUDE` from `Prelude.kt`. The test task sets `compile-test-snippets=true`, so every snippet must compile; otherwise unresolved Arrow calls make "not flagged" tests pass for the wrong reason. Arrow and kotlinx-coroutines are on the test classpath. Cover at least one flagged and one unflagged case, plus any false positives found in real code.
6. Make the message say what to write instead, as stock detekt style rules do.

To tell whether a call raises into a Raise scope, check the callee's declared receiver type, not the type of the value passed: inside `either { }`, the generic `T.runCatching` receives the Raise but doesn't raise. `raiseReceivers` in `Raise.kt` does this, and every rule uses it. For an `if (...) raise(e)` rule, start from `ifRaise` in the same file, as `UseEnsure` and `UseEnsureNotNull` do.

The detekt 2 docs have no complete type-resolution rule example, so `NoCatchAllInRaiseScope` is the working reference. See also detekt's [type resolution docs](https://detekt.dev/docs/gettingstarted/type-resolution) and [`RequiresAnalysisApi`](https://detekt.dev/kdoc/detekt-api/dev.detekt.api/-requires-analysis-api/index.html).
