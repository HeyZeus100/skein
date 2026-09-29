# Prior native/Qwen reuse review for 5d58f7c

Source comparison and original artifact/public-model evidence review are complete. Native runtime parity reuse is NOT approved yet: new candidate APK paths, exact ZIP/native inventory, and build/source identities remain pending.

`source-file-manifest.json` hashes568 selected files across both commits:563 equal; differences are app testOptions, editor lifecycle dependency and the3 already-reviewed synthetic setup files. Full native, inference-service, core inference/IPC/model/verify/RAG/vault, build-logic, Gradle, submodule and app model-service trees are equal. The synthetic setup files also exactly match prior test-only source aee46e4.

Original APKs were independently rehashed and every ZIP entry read with CRC checking: prior app125280302B, native test130230126B, prior test-only app test91163348B. Native libraries match pinned baseline inventory (7app,1native test,0app test). Per-entry ELF architecture metadata is recorded. No binary was copied or mutated.

Original host Qwen identity is pinned SHA2562c5f9a12…71b12,1590475744B,BLAKE3db158ff6…0eb0e4a; template SHA256cd8e9439…30527f. Public native report5cases independently rechecked against source predicates, including one fewer parsed literal-control token than unsafe reference. EOS/EOG classification remains prior observation only; generated stopping is unmeasured. Host evidence9children, pinned BLAKE3 class copies/stdlib/source reverified without rereading the1.59GB model. Prior broad summary was hashed only and never inspected.

Draft review JSONs have `approved:false` and null candidate hashes intentionally. They align to frozen v5's required fields but cannot release a run. The v5 contract separately requires native test APK exactbb1b2f49…1f342; a different new native test APK would need coordinator review/fresh native release rather than silent substitution.

First-send Knowledge metadata/controller and workspace UI differ from old app. No whole-app equivalence, answer-quality, owner-vault model or retrieval/embedding acceptance follows from unchanged native source or eventual byte equality. Full-device model SHA256/size and service verification remain required. No build, device command, CI, Beads or source mutation was performed.
