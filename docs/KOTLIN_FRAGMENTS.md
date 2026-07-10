# Kotlin Fragment Sources

Large Kotlin source and regression files are maintained through a shared
fragment manifest:

```text
config/kotlin-fragments.tsv
```

Each manifest row names the generated Kotlin source, its fragment directory, the
ordered fragment list, and the maximum reviewable fragment size. The compiled
`.kt` file stays in its original path so Gradle behavior does not change, but
edits should be made in the fragments and assembled back into the generated
source.

Common commands:

```bash
python scripts/kotlin_fragments.py list
python scripts/kotlin_fragments.py check
python scripts/kotlin_fragments.py assemble
python scripts/kotlin_fragments.py split --id structural-refactor-extra-test
```

`check` is byte-for-byte: fragment assembly must exactly reproduce the generated
Kotlin file, including line endings. The splitter prefers Kotlin member
boundaries; if a single method contains a very large embedded fixture, it can
hard-split that method text because fragments are assembled before compilation.
The default test suite also verifies the manifest, fragment sync, and configured
line budgets. This keeps architecture cleanup behavior-preserving while making
the rule and test corpus reviewable in small chunks.
