---
title: "0.11.0 migration step: a body-bound action parameter's defaultValue is applied"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### A body-bound action parameter's `defaultValue` is applied

`@ActionParam(defaultValue = "...")` on a parameter bound from the request body is applied when the
body omits the member or sends it as `null`, as it already was for a `@QueryParam` parameter whose
key the query string omits. Before, nothing read it on the body path: a primitive member the body
omitted reached the entity method as `0` or `false`, and a reference member as `null`.

The generated action request record carries the default. A defaulted component is declared with its
boxed type, so that an omitted value is told apart from an explicit zero, and the record's compact
constructor sets the default when the component is `null`. For example,

```java
@Action(name = "issue", label = "Issue")
public void issue(
        @ActionParam(label = "Due in days", required = false, defaultValue = "30") int dueInDays) {
}
```

generates, in the entity's handler,

```java
public static record IssueRequest(Integer dueInDays) {
    public IssueRequest {
        if (dueInDays == null) {
            dueInDays = Integer.valueOf("30");
        }
    }
}
```

The default is converted from its text when the record is built, by the conversion the query path
applies. A default that does not convert to the parameter's type, such as `"soon"` for a `boolean`,
fails every request that omits the member, and the Community JSON decoder reports it as a malformed body:
`400`.

**What breaks.** A caller that omits a defaulted member, or sends `null`, now reaches the entity
method with the default instead of `0`, `false` or `null`. A caller that relied on the zero value
sends it explicitly. Code of your own that names a generated `<Action>Request` record sees the
defaulted component's accessor return the boxed type; a record without a `defaultValue` on any body
parameter is byte-identical to the previous release.
