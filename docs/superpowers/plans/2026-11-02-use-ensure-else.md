# UseEnsure else-branch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `UseEnsure` and `UseEnsureNotNull` also flag an `if` with an `else` where one branch only raises, when the `if` is a statement, a `val`/`var` initializer or a `return` value.

**Architecture:** `ifRaise` in `Raise.kt` accepts an `else`, records which branch raises and builds the shared message pieces. Both rules keep their structure and build messages from those pieces. A new `ensuredNotNull` helper decides which rule owns a null check.

**Tech Stack:** Kotlin, detekt 2 alpha (`detekt-api`, `detekt-test`), Kotlin Analysis API, Arrow core (tests).

**Spec:** `docs/superpowers/specs/2026-11-02-use-ensure-else-design.md`

## Global Constraints

- Package `io.github.leeturner.arrowdetekt`; tests use the shared `PRELUDE` in `src/test/kotlin/io/github/leeturner/arrowdetekt/Prelude.kt`.
- Test snippets must compile (`compile-test-snippets` is on).
- An `if` with no `else` keeps today's behaviour, with no position check.
- With an `else`, flag only when the `if`'s parent is a `KtBlockExpression`, or it is the `initializer` of a `KtProperty`, or the `returnedExpression` of a `KtReturnExpression`.
- Exactly one branch may be a raise. If both branches raise, the `if` isn't flagged.
- Message copy: `", then the else branch,"` when the then branch raises, `", then the if branch,"` when the else branch raises. The quoted `if` uses `…` for the other branch.
- Commits use `git commit --no-gpg-sign`.

## Review Focus

1. An `if` in an else-if chain (`if (a) plainCall() else if (c) raise(E) else plainCall()`) is not flagged: the inner `if` is in an else position and the outer doesn't raise. Test in Task 1.
2. A no-else `if` nested as another `if`'s branch (`if (a) if (c) raise(E)`) is still flagged, so the position check must not apply to no-else ifs. Test in Task 1.
3. When both branches raise, nothing is flagged. Test in Task 1.
4. An `if` that is the last line of `either { }` and whose value is the result gets flagged. Test in Task 1.
5. `null != x` with the raise in the else goes to `UseEnsureNotNull`, not `UseEnsure`. Test in Task 2.

---

### Task 1: Flag if-raise with an else in both rules

**Files:**
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/Raise.kt` (`IfRaise`, `ifRaise`)
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/UseEnsure.kt`
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/UseEnsureNotNull.kt`
- Test: `src/test/kotlin/io/github/leeturner/arrowdetekt/UseEnsureTest.kt`, `UseEnsureNotNullTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal class IfRaise(
      val condition: KtExpression,
      val raise: KtExpression,
      val receiver: String,      // "r." or ""
      val error: KtExpression,
      val raisesInElse: Boolean,
      val quoted: String,        // "if (c) raise(E)" | "if (c) raise(E) else …" | "if (c) … else raise(E)"
      val followedBy: String,    // "" | ", then the else branch," | ", then the if branch,"
  )
  internal fun ifRaise(expression: KtIfExpression): IfRaise?
  ```

- [ ] **Step 1: Write the failing tests**

In `UseEnsureTest`, delete `ignoresIfWithElse` and add:

```kotlin
@Test
fun flagsRaiseInThenWithElse() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E) else plainCall() }")
    assertEquals("Use ensure(!c) { E }, then the else branch, instead of if (c) raise(E) else ….", findings.single().message)
}

@Test
fun flagsRaiseInElse() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) plainCall() else raise(E) }")
    assertEquals("Use ensure(c) { E }, then the if branch, instead of if (c) … else raise(E).", findings.single().message)
}

@Test
fun flagsRaiseInElseBlock() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) { plainCall() } else { raise(E) } }")
    assertEquals(1, findings.size)
}

@Test
fun flagsExplicitReceiverInElse() {
    val findings = lint("fun g(r: Raise<E>, c: Boolean) { if (c) plainCall() else r.raise(E) }")
    assertEquals("Use r.ensure(c) { E }, then the if branch, instead of if (c) … else r.raise(E).", findings.single().message)
}

@Test
fun flagsValAssignedFromIf() {
    val findings = lint("fun Raise<E>.g(n: Int) { val x = if (n < 0) raise(E) else n; println(x) }")
    assertEquals("Use ensure(!(n < 0)) { E }, then the else branch, instead of if (n < 0) raise(E) else ….", findings.single().message)
}

@Test
fun flagsReturnedIf() {
    val findings = lint("fun Raise<E>.g(n: Int): Int { return if (n < 0) raise(E) else n }")
    assertEquals(1, findings.size)
}

@Test
fun flagsIfAsLastLineOfEither() {
    val findings = lint("fun f(n: Int) = either<E, Int> { if (n < 0) raise(E) else n }")
    assertEquals(1, findings.size)
}

@Test
fun flagsNoElseIfInsideAnotherIf() {
    val findings = lint("fun Raise<E>.g(a: Boolean, c: Boolean) { if (a) if (c) raise(E) }")
    assertEquals(1, findings.size)
}

@Test
fun ignoresIfAfterElvis() {
    val findings = lint("fun Raise<E>.g(x: Int?, c: Boolean): Int { val y = x ?: if (c) raise(E) else 1; return y }")
    assertEquals(0, findings.size)
}

@Test
fun ignoresIfAsArgument() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { println(if (c) raise(E) else 1) }")
    assertEquals(0, findings.size)
}

@Test
fun ignoresExpressionBody() {
    val findings = lint("fun Raise<E>.g(c: Boolean): Int = if (c) raise(E) else 1")
    assertEquals(0, findings.size)
}

@Test
fun ignoresElseIfChain() {
    val findings = lint("fun Raise<E>.g(a: Boolean, c: Boolean) { if (a) plainCall() else if (c) raise(E) else plainCall() }")
    assertEquals(0, findings.size)
}

@Test
fun ignoresBothBranchesRaising() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) raise(E) else raise(E) }")
    assertEquals(0, findings.size)
}

@Test
fun ignoresElseBlockWithMoreThanTheRaise() {
    val findings = lint("fun Raise<E>.g(c: Boolean) { if (c) plainCall() else { plainCall(); raise(E) } }")
    assertEquals(0, findings.size)
}
```

In `UseEnsureNotNullTest`, delete `ignoresIfWithElse` and add:

```kotlin
@Test
fun flagsNullCheckWithElse() {
    val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) raise(E) else println(x) }")
    assertEquals(
        "Use ensureNotNull(x) { E }, then the else branch, instead of if (x == null) raise(E) else …. It also smart-casts x to non-null.",
        findings.single().message,
    )
}
```

- [ ] **Step 2: Run the tests and check the new ones fail**

Run: `./gradlew test --tests 'io.github.leeturner.arrowdetekt.UseEnsure*'`
Expected: FAIL. The `flags…` tests added above find 0 issues, except `flagsNoElseIfInsideAnotherIf`. The `ignores…` tests pass already.

- [ ] **Step 3: Extend `IfRaise` and `ifRaise` in `Raise.kt`**

`ifRaise` takes `raisedError` of the single statement of each branch. If there's no else, the then branch must raise, as now. With an else, exactly one branch must raise and the position check from Global Constraints must pass. Fill `raisesInElse`, `quoted` and `followedBy` as the Interfaces block shows. `raise` is the raising statement and `receiver` comes from it, as today. Update the KDoc on `IfRaise`.

- [ ] **Step 4: Build both rules' messages from the shared pieces**

`UseEnsure`: the suggested condition is `match.condition.text` when `raisesInElse`, else `negate(match.condition)`. Message: `"Use ${receiver}ensure($condition) { ${error.text} }$followedBy instead of $quoted."`.
`UseEnsureNotNull`: `"Use ${receiver}ensureNotNull($value) { ${error.text} }$followedBy instead of $quoted. It also smart-casts $value to non-null."`. In this task it still uses `nullChecked(match.condition)`, plus `if (match.raisesInElse) return`. Task 2 replaces both.
Update both class KDocs to mention the else case.

- [ ] **Step 5: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/leeturner/arrowdetekt/{Raise,UseEnsure,UseEnsureNotNull}.kt src/test/kotlin/io/github/leeturner/arrowdetekt/UseEnsure*Test.kt
git commit --no-gpg-sign -m "Flag if-raise with an else in UseEnsure and UseEnsureNotNull"
```

### Task 2: Route null checks with the raise in the else, docs and backlog

**Files:**
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/Raise.kt` (`nullChecked`)
- Modify: `src/main/kotlin/io/github/leeturner/arrowdetekt/UseEnsure.kt`, `UseEnsureNotNull.kt`
- Modify: `README.md` (`UseEnsure` and `UseEnsureNotNull` sections)
- Modify: `arrow-detekt-rules.taskpaper` (via `na`)
- Test: `UseEnsureTest.kt`, `UseEnsureNotNullTest.kt`

**Interfaces:**
- Consumes: `IfRaise`, `ifRaise` from Task 1.
- Produces: `internal fun ensuredNotNull(match: IfRaise): KtExpression?`. It returns `x` when the raise happens exactly when `x` is null. That means a then-branch raise under `x == null` or `null == x`, or an else-branch raise under `x != null` or `null != x`. Otherwise it returns null. `nullChecked` becomes private and takes the operator token.

- [ ] **Step 1: Write the failing tests**

`UseEnsureNotNullTest`:

```kotlin
@Test
fun flagsNotNullCheckWithRaiseInElse() {
    val findings = lint("fun Raise<E>.g(x: String?) { if (x != null) println(x) else raise(E) }")
    assertEquals(
        "Use ensureNotNull(x) { E }, then the if branch, instead of if (x != null) … else raise(E). It also smart-casts x to non-null.",
        findings.single().message,
    )
}

@Test
fun flagsNullOnTheLeftWithRaiseInElse() {
    val findings = lint("fun Raise<E>.g(x: String?) { if (null != x) println(x) else raise(E) }")
    assertEquals(1, findings.size)
}

@Test
fun ignoresEqualsNullWithRaiseInElse() {
    val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) plainCall() else raise(E) }")
    assertEquals(0, findings.size)
}
```

`UseEnsureTest`:

```kotlin
@Test
fun ignoresNotNullCheckWithRaiseInElse() {
    val findings = lint("fun Raise<E>.g(x: String?) { if (x != null) println(x) else raise(E); if (null != x) println(x) else raise(E) }")
    assertEquals(0, findings.size)
}

@Test
fun flagsEqualsNullWithRaiseInElse() {
    val findings = lint("fun Raise<E>.g(x: String?) { if (x == null) plainCall() else raise(E) }")
    assertEquals("Use ensure(x == null) { E }, then the if branch, instead of if (x == null) … else raise(E).", findings.single().message)
}
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./gradlew test --tests 'io.github.leeturner.arrowdetekt.UseEnsure*'`
Expected: FAIL in `flagsNotNullCheckWithRaiseInElse`, `flagsNullOnTheLeftWithRaiseInElse` and `ignoresNotNullCheckWithRaiseInElse`.

- [ ] **Step 3: Add `ensuredNotNull` and use it in both rules**

`UseEnsure` skips when `ensuredNotNull(match) != null`. `UseEnsureNotNull` reports only when it's non-null, using its text as `$value`, and drops the `raisesInElse` guard from Task 1.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Update the README**

`UseEnsure`: say it also flags an `if` with an `else` when one branch only raises. List the three places it applies and add one flagged `val user = if (id < 0) raise(BadId) else load(id)` example, with the message the rule gives. Change the "Not flagged" line: drop "an `if` with an `else`" and add the `if` in other places (arguments, after `?:`, expression bodies), both branches raising, and null checks in either form. `UseEnsureNotNull`: mention `if (x != null) … else raise(e)`.

- [ ] **Step 6: Archive the backlog item**

From the repo root, with `XDG_DATA_HOME=<scratchpad>/na-data`:
`na find --exact "Flag if-raise with an else branch" --no-pager` shows one match. Then run `na archive --exact "Flag if-raise with an else branch"`. Run `git diff arrow-detekt-rules.taskpaper` and confirm only that item moved, because archive can match more than find shows.

- [ ] **Step 7: Commit**

```bash
git add src README.md arrow-detekt-rules.taskpaper
git commit --no-gpg-sign -m "Route null checks with the raise in the else to UseEnsureNotNull"
```
