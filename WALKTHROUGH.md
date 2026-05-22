# OmniChat MCP & Memory System Stabilization Walkthrough

## Overview
This update stabilizes the embedded MCP runtimes (Node.js & Python) and adds convenience features for configuration.

## Key Changes

### 1. MCP Runtime Stability
- **Node.js**: Fixed `std::regex_error` crash by injecting `LC_ALL=C` locale at the JNI level.
- **Python**: Fixed SIGSEGV by implementing proper GIL (Global Interpreter Lock) management across different work threads.
- **Process Safety**: Re-engineered the Node.js bridge to support multiple services within a single Node.js instance (due to `nodejs-mobile` limitations) using a standard `require()`-based injection method instead of `child_process.spawn`.

### 2. JSON Configuration Import
- Added a new import button in the MCP Tools tab.
- Supports standard `mcpServers` JSON format.
- Intelligent runtime detection:
  - Absolute paths or `.js` files $\rightarrow$ `node`
  - `.py` files $\rightarrow$ `python`
  - Command names $\rightarrow$ `npx`

### 3. Native Bridge Enhancements
- Optimized `dlopen` flags to prevent symbol collisions between Python and Android system libraries.
- Fixed memory management in `node_bridge.cpp` regarding `argv` termination.

## Verification
- **Node.js**: Run `mcp_filesystem.js`. It should list files without crashing.
- **Python**: Run any Python-based MCP server. It should handshake correctly without SIGSEGV.
- **Import**: Paste a standard MCP JSON config. Services should be added and categorized automatically.
