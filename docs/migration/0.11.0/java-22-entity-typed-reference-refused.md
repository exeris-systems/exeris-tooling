---
title: "0.11.0 migration step: a field typed as another entity is refused"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### A field typed as another entity is refused

The generated repository stores a reference to another entity as that entity's `UUID` key: an
explicit field such as `private UUID customerId;`, which `@Relationship(targetEntity =
Customer.class)` makes a foreign key (`customer_id`, constrained in the foreign-key migration). A
field typed as the referenced entity itself (`private Customer customer;`) has no column that holds
it. The repository emitted for such a field bound it with `toString()` and read it back with
`Customer.valueOf(String)`, which does not compile.

Code generation now refuses such a field with `EXT-GEN-3003` before it writes anything, the same
refusal an unstorable field type gets. The message names each field, the entity it references, and
the key field to declare instead:

```
com.shop.domain.Invoice.customer : com.shop.domain.Customer (a reference to the entity Customer,
which no column stores; hold its key instead: UUID customerId, with
@Relationship(targetEntity = Customer.class) for a foreign key)
```

A field is a reference when its type is an `@ExerisDomain` entity of the same build: by its
qualified name, or by its simple name when it is in the referencing entity's package. This applies
whether the field carries `@Relationship`, `@GraphEdge`, or neither. A `List` of entities is not
affected. It is stored as a JSON column, as before.

**Where it applies.** Only to entities that declare a field typed as another entity of the build,
or as themselves. Output for every other entity is byte-identical to the previous release.

**What breaks.** Nothing that compiled before. A build with such a field produced a generated tree
that `javac` rejected, and it now stops at code generation with the message above. To fix, replace
the field with the referenced entity's key:

```java
// before
@Relationship(targetEntity = Customer.class, displayField = "name",
        relationshipType = Relationship.RelationshipType.MANY_TO_ONE)
private Customer customer;

// after
@Relationship(targetEntity = Customer.class, displayField = "name",
        relationshipType = Relationship.RelationshipType.MANY_TO_ONE)
private UUID customerId;
```

The generated schema already named the column `customer_id` for both forms, so moving to the key
form does not rename it.
