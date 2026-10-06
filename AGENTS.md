# Project Goal

Everything you need for a small SQL database, in one simple Kotlin library.

Build a lightweight embedded SQL database for Kotlin/JVM that provides storage, SQL execution, parameter binding, and result mapping through a direct Kotlin API, without a separate database server or JDBC.

# General Principles

- State the conclusion first and briefly explain the key reasons.
- Be clear and precise; avoid unnecessary formatting and elaboration.
- Distinguish ambiguous concepts and uncertain claims.
- Understand the request and complete the necessary work and verification.

# Language

- Write all project text in English, including Markdown documents, commit messages, code comments, user-facing messages, and pull request titles and descriptions.
- Conversation with the user may be in Korean.

# Branch Workflow

- Perform all project work on a dedicated branch for the task. Do not make changes directly on the default branch.
- Keep each branch focused on a coherent purpose and submit its changes through a pull request.

# Pull Request Rules

- Make readability the highest priority. Use short sections and bullet points as the primary structure of the PR body.
- Explain the background: the existing behavior, problem, or need that prompted the change.
- Explain why the chosen approach addresses that need, including relevant alternatives and tradeoffs.
- Describe the resulting behavior and scope so a reviewer can assess the change without reading the conversation.
- Provide sufficient evidence for the claims: relevant test results, reproduction steps, before-and-after examples, or measurements. State any checks that were not run and any remaining limitations.
- Use Mermaid sequence diagrams or other diagrams when they make interactions, execution flow, or architecture easier to understand. Keep diagrams focused and avoid adding them when text is clearer.
- Scale the detail to the change. Keep small PRs concise while providing enough context and evidence for meaningful review.

# Commit Rules

- Keep each commit focused on one logical change. Do not mix unrelated changes or large formatting changes.
- Use `type: description`, or `type(scope): description` when a scope is useful.
- Choose the appropriate type: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, or `perf`.
- Describe the change specifically in English. Example: `feat(parser): support equality conditions in SELECT`.
- When needed, explain the rationale or caveats in a body separated from the title by a blank line. Mark breaking changes with `type!:` or `type(scope)!:` and describe their impact in the body.
- Review the staged diff and run the relevant build and tests before committing. Do not claim that checks passed if they were not run.
- Do not commit secrets, local configuration, or build artifacts. Do not include or revert another contributor's changes without authorization.
