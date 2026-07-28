## Plan: Sync Documentation with Current Codebase

### Key Discrepancies Found

| Issue | README says | Actual code |
|-------|------------|-------------|
| Room DB version | v47, 43 migrations | v55, **94 migrations** |
| `workspace/` package | Listed as a directory | **Does not exist** — consolidated into `agent/` |
| `assets/node/` | Listed in project tree | **Does not exist** |
| `scripts/` | Listed in project tree | **Does not exist** |
| `WorkspaceViewModel` | In architecture diagram | **Does not exist** |
| `agent/` package | "Legacy subAgent system" | It's the **active** multi-agent system |
| Chinese UI strings | "Hardcoded in Compose" | Uses `strings.xml` + `uiText()` pattern |
| `util/` package | Not listed | **Exists** with `DocumentParser.kt` and `SessionLogExporter.kt` |

### Files to Modify

**1. README.md** — English version
- Room: v47→v55, 43→94 migrations
- Remove `workspace/` from project tree and architecture diagram
- Remove `assets/node/` and `scripts/` from project tree
- Remove `WorkspaceViewModel` from diagram
- Update `agent/` description from "legacy" to "current multi-agent system"
- Fix Code Conventions: Chinese UI strings use `strings.xml` + `uiText()` pattern
- Add `util/` to project structure

**2. README_zh.md** — Chinese version (mirror all English changes)

**3. CLAUDE.md** — Internal agent guidance
- Remove `com.omnichat.workspace` from Package Structure
- Update `com.omnichat.agent` from "Legacy" to current multi-agent files
- Add `com.omnichat.util` to Package Structure
- Update migration count note

### No Changes To
- Core features, MCP tool table, cloud backup, build commands, release process, license