package com.omnichat.memory

/**
 * 共享的文本分词器，支持 CJK bigram 和英文 word-level 分词。
 *
 * 统一了 ChatViewModel（去重用）和 BuiltinToolHandler（搜索用）的分词逻辑，
 * 修复了原来 ChatViewModel 使用词级分词导致中文去重失效的问题。
 */
object MemoryTokenizer {

    private val cjkRange = '一'..'鿿'
    private val punctuation = "，。！？、；：“”‘’（）【】《》,.!?;:\"'()[]<>"
    private val separatorRegex = Regex("\\s|[\\p{Punct}]|[，。！？、；：“”‘’（）【】《》]")

    /**
     * Bigram 分词器：CJK 字符生成 unigram + 相邻 bigram，英文按空格/标点分词。
     *
     * 修复了旧实现中 "用户Kotlin编程" 产生虚假 bigram "户编" 的问题——
     * 只对原文中连续的 CJK 字符生成 bigram。
     */
    fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val buffer = StringBuilder()

        // Pass 1: 提取 unigrams（CJK 单字）和英文/数字 token
        for (ch in text) {
            if (ch in cjkRange) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
                tokens.add(ch.toString())
            } else if (ch.isWhitespace() || ch in punctuation) {
                if (buffer.isNotEmpty()) {
                    tokens.add(buffer.toString().lowercase())
                    buffer.clear()
                }
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString().lowercase())
        }

        // Pass 2: 仅对连续 CJK 字符生成 bigram
        var prevCjk: Char? = null
        for (ch in text) {
            if (ch in cjkRange) {
                if (prevCjk != null) {
                    tokens.add("$prevCjk$ch")
                }
                prevCjk = ch
            } else {
                prevCjk = null
            }
        }

        return tokens
    }

    /**
     * Jaccard 相似度：两段文本 tokenize 后计算交集/并集比例。
     * 用于记忆去重和搜索排序。
     */
    fun jaccardSimilarity(a: String, b: String): Double {
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return intersection.toDouble() / union.toDouble()
    }

    /**
     * 余弦相似度：计算两个向量的余弦值。
     * 用于 embedding 语义搜索。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else dot / denominator
    }

    /**
     * 解析 JSON 格式的 embedding 向量字符串为 FloatArray。
     * 输入格式: "[0.1, 0.2, ...]"
     * 返回 null 表示解析失败或为空。
     */
    fun parseEmbedding(json: String): FloatArray? {
        if (json.isBlank()) return null
        return try {
            val cleaned = json.trim().removePrefix("[").removeSuffix("]")
            if (cleaned.isBlank()) return null
            cleaned.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            android.util.Log.d("MemoryTokenizer", "Failed to parse embedding (${json.length} chars): ${e.message}")
            null
        }
    }

    /**
     * 将 FloatArray 序列化为 JSON 字符串用于数据库存储。
     */
    fun embeddingToJson(embedding: FloatArray): String {
        return "[${embedding.joinToString(",")}]"
    }
}
