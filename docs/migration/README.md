# Migration fragments

The open release train of [`MIGRATION-0.x-to-1.0.md`](../MIGRATION-0.x-to-1.0.md) is written here,
one file per change, and assembled into MIGRATION when that version is cut. A pull request that
changes what a consumer sees after regenerating adds its step as a new file under
`docs/migration/<version>/` and does not edit MIGRATION, so two open pull requests never touch the
same lines.

## Writing a step

Create `docs/migration/<version>/<area>-<NN>-<slug>.md`, where `<version>` is the release the
change ships in (the reactor version without `-SNAPSHOT`):

- `<area>` is `java` for the Maven reactor (the processor, the Java generators, the plugin, the
  published coordinates) and `ts` for `exeris-codegen-ts`. A change that needs a step on each side
  writes two files.
- `<NN>` is two digits: the next number after the highest one already in that area. Two pull
  requests that pick the same number do not conflict, because their slugs differ; the slug then
  decides which comes first.
- `<slug>` is lowercase kebab-case and names the change.

The release assembles the train in byte order of the filenames: every `java` step, then every `ts`
step, each area in `NN` order. A Maven consumer reads the first half and an npm consumer the second.

The file holds a front matter block, one blank line, and exactly one `###` section, the same text
the step would have in MIGRATION:

```markdown
---
title: "0.10.0 migration step: <the heading text>"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: YYYY-MM-DD
---

### <What changed, as the consumer sees it>

<What happens now, and what to do.>
```

- The heading of a `ts` step starts with `` `exeris-codegen-ts`: ``, and a `java` step's heading
  does not.
- Use `####` for a sub-heading; a second `###`, or any `#` or `##` outside fenced code, is refused.
- Write a relative link relative to the fragment, as `../../diagnostics.md`. The assembler
  rewrites it relative to MIGRATION, so the link checker passes on both files. Link to the train
  heading in MIGRATION from elsewhere, never to a fragment, because the cut deletes the fragments.
- The front matter is the one the organisation docs-lint requires of every page. The assembler
  drops it.

`python3 tools/migration/assemble-migration.py --check` validates every fragment and runs in
`build.yml` on every pull request.

## The cut

In the release pull request, `python3 tools/migration/assemble-migration.py --release <version>`
replaces the train's marker block in MIGRATION with the fragments, in order, and deletes them.
`--dry-run` prints the assembled MIGRATION instead and changes nothing.
`tools/release-readiness/release-readiness.sh` fails a release whose fragments are still here.

The pull request that opens the next cycle adds the next train's heading to MIGRATION with an
empty marker block:

```markdown
## 0.11.0 train — regeneration deltas

<!-- BEGIN migration-fragments 0.11.0 -->
Until 0.11.0 is cut, each step of this train is a file of its own under `docs/migration/0.11.0/`,
and the release assembles them here ([how a step is written](migration/README.md)).
<!-- END migration-fragments 0.11.0 -->
```

Text between the train heading and the `BEGIN` marker is the train's introduction and stays.
