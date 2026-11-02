# NoUnboundEither: flag an Either left unbound as a statement

## Why

Inside a Raise scope, `either { findUser(id); ... }` drops `findUser`'s error, because the `bind()` is missing. `NoNestedEither` only catches a missing bind when the result is stored or returned. woltapp/arrow-detekt-rules has `NoEffectScopeBindableValueAsStatement` for this, but only for detekt 1.x. This is the "Flag an Either left unbound as a statement" item in `arrow-detekt-rules.taskpaper`.

## What gets flagged

An expression whose value is never used, whose type is a bindable Arrow type, and where a Raise is in scope.

- **Bindable types:** `arrow.core.Either`, `Option`, `Ior`, and `Effect`/`EagerEffect`, which in Arrow 2 are function types with a `Raise` receiver. Subtypes count too (`Either.Right`, `Some`).
- **Value never used:** a statement in a block, the last line of a lambda that returns Unit, or the branch of an `if`/`when` used as a statement. In practice, whatever the Analysis API's `isUsedAsExpression` says is unused.
- **Raise in scope:** some enclosing lambda or function, at any depth, has a Raise extension receiver, a Raise dispatch receiver or a Raise context parameter (`either { }`, `option { }`, `effect { }`, `fun Raise<E>.f()`, `context(_: Raise<E>) fun f()`). That makes nested lambdas (`forEach { save(it) }`, `with(x) { … }`) and local functions count. The test is the same as "would `bind()` compile here".
- **A chain is reported once,** on the whole expression: `findUser(id).map { it.name }` is one finding, not two.

Not flagged:

- A bare bindable outside any Raise scope. That case is left to Kotlin 2.3's unused return value checker (`-Xreturn-value-checker`).
- A value that is bound, stored, returned, passed on or handled (`.bind()`, `val x = …`, `.getOrElse { }`). `.onLeft { }` and `.onSome { }` still pass the Either on, so a statement ending in them is flagged: the fix is `.onLeft { … }.bind()`.
- `kotlin.Result`. Wolt flags it, but `runCatching { cleanup() }` on its own line is a common deliberate discard, and `bind()` on a Result only works inside `result { }`.

## Message

Says what to write instead, quoting the expression:

`The Either from findUser(id) is never bound, so its error is dropped. Use findUser(id).bind() instead.`

The type name in the message (Either, Option, Ior, Effect) matches what was found.

## Approaches

- **A. Start from each unused expression and look outward for a Raise (picked).** The type check and the unused check are cheap and run first. The outward walk only happens for the rare hits. Nested scopes, local functions and context parameters need no extra code.
- B. Wolt's way: find Raise-scope lambdas and functions, then run a second visitor over their bodies. Nested scopes get walked twice and need de-duplicating, and context parameters need a third entry point.
- C. Fold it into `NoNestedEither`. Dropped: the trigger is different (statements, not declarations), and the two rules would have to be switched on and off together.

## Non-goals

- Flagging outside Raise scopes. The rule can be widened later by dropping the scope check.
- Suggesting `withError` when the error types differ. The message still says `bind()`, and the compile error then points the way.
- An auto-fix.

## My calls

- The rule name, `NoUnboundEither`, matches `NoNestedEither`, even though Option, Ior and Effect count too.
- Leaving out `kotlin.Result`.
- A dropped Either inside a nested `launch { }` is flagged like any other nested lambda. It's still a dropped error, even if `bind()` isn't the right fix there.

## Done means

- The rule is registered in `ArrowRuleSetProvider`, set `active: true` in `config.yml`, and `ArrowRuleSetProviderTest` is updated.
- Tests, flagged:
  - Either, Option, Ior and Effect as statements in `either { }`
  - a `Raise<E>` extension function
  - a context-parameter function
  - a nested `forEach` lambda
  - the last line of a Unit lambda
  - an `if` branch
  - a chain, reported once
- Tests, not flagged:
  - `.bind()`
  - stored in a `val`
  - handled with `getOrElse`
  - returned as the lambda's value
  - outside a Raise scope
  - a `Result` statement
- A README entry in the same style as the others.
- The taskpaper item archived.

## Left to the builder

- How the outward Raise walk is written: parent walk with type checks, or the Analysis API's scope context.
- How a chain's outermost expression is picked.
