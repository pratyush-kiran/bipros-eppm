# HDS open questions (resolve before phase 4 ships)

These are from spec §13. Phase 0 commits use the default-listed answers; revisit before phase 4 frontend lockup.

1. **OpenAI embeddings tier**: Confirm the `LlmProviderConfig` row for the active OpenAI provider supports embeddings + tier-1 rate limits. If not, ingestion of a 1GB doc will take >12h.
   - Default assumed: tier-1 OK.

2. **`HDS_LIBRARY.READ` default**: Currently granted to all roles. If gating to project-access-having users only is desired, restrict in the role-permission matrix.
   - Default assumed: all roles.

3. **New-conversation default scope**: Empty scope (user must explicitly pick versions before first HDS query).
   - Default assumed: empty.

4. **Discipline taxonomy**: Spec uses `HIGHWAY|BRIDGE|GEOTECH|PAVEMENT|TRAFFIC|DRAINAGE|OTHER`. Engineering may want extra: `STRUCTURAL`, `ELECTRICAL`, `ENVIRONMENT`.
   - Default assumed: the 7 in spec.

Update this file and the relevant code if any answer changes.
