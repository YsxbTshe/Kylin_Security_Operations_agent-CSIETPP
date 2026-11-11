# Contract test vectors

Files prefixed with `TEST_ONLY_` are deterministic interoperability fixtures. They are not
deployment credentials and must never be configured as platform keys. Production keys are
resolved through `SecretResolver` and use independent key IDs for approval, catalog, node CA,
and package signing.
