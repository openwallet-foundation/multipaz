# Contributing

Bug fixes and new features are welcome! We use GitHub and request that changes are
submitted as pull requests. When uploading a PR, it will be reviewed by maintainers,
here are some pointers about what we're looking for:

## Contributing to Code

- Every PR must link an issue. Reference it in the PR description using a closing keyword,
  e.g. `Fixes #123` (or `Closes`/`Resolves`), as prompted by the PR template. This is enforced
  in CI, so a PR that doesn't link an issue will fail the `check-linked-issue` check. It's a good
  idea to open an issue first for discussion.
- A PR should not contain unrelated changes
  - In particular do not include unrelated changes even despite how tempting it is to e.g. change
    `check(!something.isEmpty())` to `check(something.isNotEmpty())` in a unrelated file,
    clean up imports, or change whitespace elsewhere.
- We generally prefer a series of small commits to a single big commit but acknowledge
  there are cases where it's appropriate to have big commits / PRs.
- All new public API must have KDoc docstrings, no exceptions.
- Most of this code is library code which 3rd party depends on so care must be taken when
  exposing new APIs or removing or changing existing APIs. In the future we might have
  e.g. an `api.txt` file to more carefully control this.
- The commit message should be short, to the point, and explain exactly what the change is
  doing. Not too short, not too long. See the git history for inspiration and what the
  project is generally doing.
- The code in the change should follow the style in [CODING-STYLE.md](CODING-STYLE.md).
- The code must be tested, either with unit tests or manual tests. See [TESTING.md](TESTING.md) for details.
- We generally use a single commit per PR, to keep the git history sane.
- For notable features / bug-fixes the PR should include additions to CHANGELOG.md

### Detekt Documentation Coverage

This project enforces documentation coverage using [detekt](https://detekt.dev).
All public APIs need to be documented with KDoc, and this is enforced in CI.

Documentation coverage checks include requirements for:

- Public classes
- Public functions
- Public properties

Documentation is not required for test code or generated code. Make sure to add
comprehensive KDoc docstrings to all public APIs explaining their purpose,
parameters, return values, and any exceptions they may throw.

#### Pre-commit Hook

You can install a local pre-commit hook to run the same checks before each commit:

```bash
bash ./.scripts/install-precommit-git-hook
```

The hook runs:

- `./gradlew detektMetadataCommonMain`

If either check fails, the commit is blocked until the issues are fixed.

#### Running Detekt Locally

To run detekt and check for documentation coverage violations before pushing your
code, please run the following command:

```bash
./gradlew detektMetadataCommonMain -Pdisable.web.targets=true
```

This will analyze your code and report any documentation issues or other code
quality problems. Please fix any violations before submitting your PR.

Before uploading a PR, it's generally a good idea to manually review the commit (using
e.g. `git show`) and check that it meets all the requirements above.

## Contributing to Documentation

The Multipaz developer website at [developer.multipaz.org](https://developer.multipaz.org) is also open source! 
For documentation contributions, see the [website repository](https://github.com/openwallet-foundation/multipaz-developer-website).

