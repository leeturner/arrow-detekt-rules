# arrow-detekt-rules

A [detekt](https://detekt.dev) 2 rule set for [Arrow](https://arrow-kt.io) code.

## Usage

Add it to the project's `build.gradle.kts`. It's on Maven Central, so `mavenCentral()` in `repositories` is enough:

```kotlin
dependencies {
    detektPlugins("io.github.leeturner:arrow-detekt-rules:0.1.0")
}
```

The rules need type resolution, so they run in `detektMain` / `detektTest`, not plain `detekt`.

Every rule is active by default (the jar ships `config/config.yml`). To turn one off, add it to the project's detekt config:

```yaml
arrow:
  UseEnsure:
    active: false
```

### Compatibility

Each release is built for one detekt version, since a rule set built against one detekt API may not load in another. Use the release that matches your detekt version.

| arrow-detekt-rules | detekt |
|--------------------|--------|
| 0.1.0 | 2.0.0-alpha.6 |

0.1.0 is built with Kotlin 2.4.21 for Java 17 and later, and tested against Arrow 2.2 and 2.3.

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

### `NoFoldBackIntoEither`

Flags `fold` on an Either when one lambda ends by building a `Left` and the other a `Right`, and suggests `mapLeft` and `map`.

```kotlin
// Flagged: Use .mapLeft { LookupFailed(it) }.map { it.name } instead of .fold({ Left(LookupFailed(it)) }, { Right(it.name) }).
findUser(id).fold({ Left(LookupFailed(it)) }, { Right(it.name) })

// Fine
findUser(id).mapLeft { LookupFailed(it) }.map { it.name }
```

`Either.Left(...)` and `.left()` count too. A side that wraps its parameter unchanged (`{ Left(it) }`) is left out of the suggestion. Not flagged: a lambda that doesn't end in a `Left`/`Right` (a fold to a value, or a `flatMap`-style one returning another Either), both sides wrapped the same way (`{ Right(default) }, { Right(it) }` is a recover), named arguments, and a `fold` that isn't Either's.

### `NoNestedEither`

Flags a property or function whose type, declared or inferred, is an Either inside an Either (`Either<E, Either<E, A>>`), which usually means a `map` that should be a `flatMap` or a missing `bind()`.

```kotlin
// Flagged: user has type Either<E, Either<E, String>>, an Either inside an Either. Use flatMap instead of map, or bind() the inner Either inside either { }.
val user = findId(name).map { findUser(it) }

// Fine
val user = findId(name).flatMap { findUser(it) }
```

`Either.Right<Either<...>>` and type aliases of Either count too. Not flagged: an Either on the left (`Either<Either<E, A>, B>`), an Either inside another type (`Either<E, List<Either<E, A>>>`), and nested Eithers that are never stored in a property or returned from a function, such as a lambda's result passed straight on.

### `PreferEitherCatch`

Flags Arrow's `catch({ ... }) { ... }` when the handler only raises, and suggests `Either.catch` with `mapLeft`, which turns the exception into an error at the edge.

```kotlin
// Flagged: Use Either.catch { ... }.mapLeft { LookupFailed(it) }.bind() instead of catch({ ... }) { raise(LookupFailed(it)) }.
either {
    catch({ repository.find(id) }) { raise(LookupFailed(it)) }
}

// Fine
either {
    Either.catch { repository.find(id) }.mapLeft { LookupFailed(it) }.bind()
}
```

Not flagged: a handler that does more than raise or returns a value, a `catch` that isn't Arrow's, and the reified `catch<IOException, _>` (or a handler typed `{ e: IOException -> ... }`), whose equivalent is `Either.catchOrThrow` rather than `Either.catch`.

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

## Development

```shell
./gradlew test
```

To try a change in another project, keep the `detektPlugins` line from [Usage](#usage) there and point Gradle at this checkout, which builds it and swaps it in for the published jar:

```shell
./gradlew detektMain --include-build ../path/to/arrow-detekt-rules
```

After changing a rule, run `./gradlew --stop` in that project before re-running detekt. Its Gradle daemon keeps the plugin classes it loaded first, so it otherwise runs the old rule (and the old default config) even though the jar was rebuilt.

### Adding a rule

1. Write the rule in `src/main/kotlin/io/github/leeturner/arrowdetekt/`, extending `Rule` and implementing `RequiresAnalysisApi`. Arrow rules need type resolution, so they run in `detektMain` / `detektTest` only.
2. Register it in `ArrowRuleSetProvider.instance()`.
3. Add it to `src/main/resources/config/config.yml` with `active: true`; every rule ships on by default. detekt merges this file into its defaults.
4. Update `ArrowRuleSetProviderTest`, which pins the exact rule list and the default config.
5. Test it the way `NoCatchAllInRaiseScopeTest` does: `lintWithContext(createEnvironment(), PRELUDE + snippet)`, with the shared `PRELUDE` from `Prelude.kt`. The test task sets `compile-test-snippets=true`, so every snippet must compile; otherwise unresolved Arrow calls make "not flagged" tests pass for the wrong reason. Arrow and kotlinx-coroutines are on the test classpath. Cover at least one flagged and one unflagged case, plus any false positives found in real code.
6. Make the message say what to write instead, as stock detekt style rules do.

To tell whether a call raises into a Raise scope, check the callee's declared receiver type, not the type of the value passed: inside `either { }`, the generic `T.runCatching` receives the Raise but doesn't raise. `raiseReceivers` in `Raise.kt` does this, and every rule uses it. For an `if (...) raise(e)` rule, start from `ifRaise` in the same file, as `UseEnsure` and `UseEnsureNotNull` do; for any other statement that only raises, `raisedError`, as `PreferEitherCatch` does.

The detekt 2 docs have no complete type-resolution rule example, so `NoCatchAllInRaiseScope` is the working reference. See also detekt's [type resolution docs](https://detekt.dev/docs/gettingstarted/type-resolution) and [`RequiresAnalysisApi`](https://detekt.dev/kdoc/detekt-api/dev.detekt.api/-requires-analysis-api/index.html).
