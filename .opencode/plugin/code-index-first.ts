import type { Plugin } from "@opencode-ai/plugin"

/**
 * code-index-first hook
 * 
 * 跟踪 grep/glob/read 调用，记录日志。
 * 实际的 "索引优先" 行为由 AGENTS.md 中的 Code Search 规则控制。
 */
export default (async () => {
  return {
    "tool.execute.before": async (input, output) => {
      const name = input.tool
      // 记录非索引搜索工具的使用，方便调试
      if (["grep", "glob", "read"].includes(name)) {
        console.error(`[code-index-first] ${name} called — did you check code-index first?`)
      }
    },
  }
}) satisfies Plugin
