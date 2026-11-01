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

Not flagged: narrow catches (`IOException`), catches that always rethrow the raise (`throw e`, `e.nonFatalOrThrow()`, `if (e is CancellationException) throw e`), and Raise scopes opened inside the catch-all.

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

## Development

```shell
./gradlew test
```
