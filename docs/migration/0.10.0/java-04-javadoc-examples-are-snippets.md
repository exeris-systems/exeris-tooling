---
title: "0.10.0 migration step: Code examples in generated Javadoc are `{@snippet}` blocks"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### Code examples in generated Javadoc are `{@snippet}` blocks

Regeneration rewrites the code examples in the Javadoc of the generated `Application`,
`RuntimeComponents` and `<Entity>Client` from `<pre>{@code … }</pre>` to `{@snippet : … }`. The
example text inside is unchanged, and so are the API and the behaviour. Expect a two-line diff per
example in a committed generated tree. `{@snippet}` needs a `javadoc` from JDK 18 or later, below
the JDK 25 the generated code already requires.
