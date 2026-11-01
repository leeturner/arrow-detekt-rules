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

The rule needs type resolution, so it runs in `detektMain` / `detektTest`, not plain `detekt`.

## Using it from a local checkout

In the consuming project's `build.gradle.kts`:

```kotlin
dependencies {
    detektPlugins("io.github.leeturner:arrow-detekt-rules:0.1.0-SNAPSHOT")
}
```

The rule is active by default (the jar ships `config/config.yml`). To turn it off, add this to the project's detekt config:

```yaml
arrow:
  NoCatchAllInRaiseScope:
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
