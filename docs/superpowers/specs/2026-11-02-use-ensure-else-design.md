# UseEnsure and UseEnsureNotNull: flag if-raise with an else

## Why

`UseEnsure` and `UseEnsureNotNull` only flag an `if` with no `else`. arrow-intellij's `EnsureInspection` also flags `if (c) raise(e) else x` and `if (c) x else raise(e)`. Following it means detekt and the IDE give the same advice. This is the "Flag if-raise with an else branch" item in `arrow-detekt-rules.taskpaper`.

## What gets flagged

An `if` with an `else` where exactly one branch is just Arrow's `raise(e)`. That branch can be a single expression or a block holding only the raise. The `if` must also sit in one of these places:

- a statement in a block, or a block's or lambda's last line
- the whole initializer of a `val`/`var`
- the whole value of a `return`

Anywhere else (function arguments, the right side of `?:`, expression bodies, inside other expressions) is skipped. In those places, moving the `ensure` above the line is not a plain rewrite. In `cached ?: if (c) raise(E) else load()` it changes what the code does.

Not flagged: both branches raise; the raising branch does more than raise; the raise isn't Arrow's (same check as today).

An `if` with no `else` keeps today's behaviour with no position check. It's a Unit statement wherever it appears.

## Which rule and what it says

- Raise in the then branch: the suggested condition is the negated `if` condition, as today.
- Raise in the else branch: the suggested condition is the `if` condition as written.
- If the suggested condition is a null check, `UseEnsureNotNull` reports and `UseEnsure` skips. `nullChecked` learns `!=` so that `if (x != null) use(x) else raise(e)` counts.

Messages describe the other branch rather than quoting it, since it may be a long block:

- `Use ensure(id >= 0) { BadId }, then the else branch, instead of if (id < 0) raise(BadId) else ….`
- `Use ensure(ok) { E }, then the if branch, instead of if (ok) … else raise(E).`
- `UseEnsureNotNull` keeps its smart-cast sentence and uses the same "then the … branch" wording.

The receiver prefix (`r.ensure`) works as today.

## Approaches

- **A. Extend `ifRaise` in `Raise.kt` (picked).** It accepts an `else`, returns which branch raises, the condition to suggest and the other branch, and checks the position. Both rules pick up the new cases with small changes. It keeps one place deciding what counts as an "if that raises".
- B. A second `ifElseRaise` helper. Today's path is untouched, but the condition, receiver and position logic is duplicated.
- C. A new rule. Separately switchable, but it overlaps the two existing rules and adds config and docs.

## Non-goals

- The smart-cast guard from arrow-intellij (skip when the error uses a variable the condition narrows). It applies equally to today's no-else case, so it's a separate question.
- An auto-fix.

## Done means

- Tests in `UseEnsureTest` and `UseEnsureNotNullTest` for: raise in then, raise in else, block branches, each of the three positions, a skipped position (`?:` and argument), both branches raising, `!=` null check routed to `UseEnsureNotNull`. `ignoresIfWithElse` is replaced.
- README entries for both rules updated.
- The taskpaper item archived.

## Left to the builder

The exact shape of the value `ifRaise` returns, and how the position check is written.
