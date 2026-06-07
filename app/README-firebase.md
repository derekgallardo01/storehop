# Firebase wiring (deferred milestone)

Firebase / Firestore is **not** wired into the build yet. The data-layer schema is
sync-ready (UUID PKs, `updatedAt`, soft-delete tombstones, per-row `userId`), but the
SDK and `google-services.json` come in a later milestone.

When wiring lands:

1. Drop your `google-services.json` here (`app/google-services.json`). It is in `.gitignore` —
   never commit it.
2. Add the `com.google.gms:google-services` and `com.google.firebase:firebase-bom`
   plugins/dependencies in `app/build.gradle.kts`.
3. Add a Firebase Auth + Firestore module under `app/src/main/java/com/storehop/app/sync/`
   following the schema's `userId` scoping convention.
