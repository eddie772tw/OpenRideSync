# Clean debug build checks

This guard checks the explicitly unconfigured debug profile only. It does not run a phone, authenticate, connect to a vehicle or establish upgrade compatibility.

Run the synthetic tests with python -m unittest discover -s tools/validation -p 'test_*.py'.

Use config mode with an already installed Gradle executable, --repo pointing to this project root, a full --source-commit, --with-honda-config false and --with-oem-ui-assets false. Five sequential offline probes check clean defaults, explicit defaults, missing Honda input, rejected OEM assets and invalid source identity. Do not run it concurrently with another Gradle build.

Artifact mode requires --apk, --aapt2, --apksigner and --expected-signer-sha256, plus the same explicit source and private flags. Pin the expected signing identity independently before the build. A generated local debug key does not establish compatibility with an installed phone candidate.

Exit 0 confirms only that command's checks. Exit 1 is failure and exit 2 is incomplete evidence. APK ZIP/DEX identity, selected credential patterns, resource names, package/version/SDK and signature checks have finite coverage; they are not a security certification or device acceptance.

The client-configuration check runs only synthetic configuration/copy tasks with an existing Gradle runtime. It verifies the allowlist, malformed-input rejection and exclusion of adjacent files, without authentication or network requests.
