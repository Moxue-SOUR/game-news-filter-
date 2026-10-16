# Source feasibility validation

This directory contains a small JDK 21 probe for the project's pre-development source check. It is not the production collector and does not implement event clustering or call an AI service.

Run from the repository root:

```sh
java validation/FeedSourceProbe.java validation/sources.tsv probe-results
```

The probe makes at most one request to each configured public URL, without login or credentials. It uses timeouts and a 2 MiB response cap, identifies RSS/Atom, and checks title, link, identifier, date and approximate text length. XML external entities and external DTD/schema access are disabled. It does not follow article links or execute feed content.

Some URLs are deliberately unverified candidates. A 404, an HTML response, or a connection failure is diagnostic evidence, not a reason to disguise a failed source as valid. A successful workflow means the probe executed; source validity must be read from `feed-summary.csv`.

Local outputs:

- `feed-summary.csv`: aggregate HTTP, parse and field statistics.
- `article-index.csv`: article metadata for local sample review. No article bodies are written by this probe.

The workflow uploads only `feed-summary.csv`. It has read-only repository permissions, no secrets and no schedule. It runs on a relevant push or manual dispatch. Raw feed snapshots, article contents, personal development logs, prompts and credentials must not be added to this public repository.

Technical access, permission to use content, and suitability for event deduplication are three separate checks. This program proves only the first one and basic field completeness; it does not establish publication rights or algorithm accuracy. A technology-wide feed is not automatically a suitable game-news feed.

