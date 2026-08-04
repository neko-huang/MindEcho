package com.moodecho.app.analysis

import com.moodecho.app.domain.model.EmotionType

/**
 * Local text-based sentiment analyzer.
 *
 * Uses keyword dictionaries + linguistic pattern matching to detect
 * emotional cues from transcribed text. Runs fully on-device with
 * zero cloud cost.
 *
 * Analyzes:
 * - Emotional vocabulary (positive/negative/anxiety words)
 * - Intensifiers and hedges (非常/有点/可能)
 * - Negation patterns (不开心 = not happy)
 * - Sentence types (exclamations, questions)
 * - First-person pronoun frequency (self-focus indicator)
 * - Cognitive process words (明白/觉得/理解)
 *
 * V2 additions (2026-08-04):
 * - Chinese-specific sentiment analysis
 * - Cross-modal output format (aligns with EmotionAnalyzer output)
 */
class TextSentimentAnalyzer {

    // ===== Emotion Keyword Dictionaries =====

    private val positiveWords = setOf(
        "开心", "高兴", "快乐", "喜欢", "爱", "棒", "好", "不错", "厉害", "赞",
        "满意", "幸福", "美好", "期待", "希望", "谢谢", "感谢", "感动", "舒服",
        "放松", "享受", "轻松", "顺利", "成功", "进步", "太好了", "真棒",
        "优秀", "佩服", "羡慕", "幸运", "温暖", "温馨", "灿烂", "乐观",
        "哈哈", "呵呵", "嘻嘻", "笑", "开心死", "爽", "痛快", "满足"
    )

    private val negativeWords = setOf(
        "烦", "烦死", "讨厌", "恨", "气", "生气", "难受", "伤心", "哭",
        "惨", "差", "糟糕", "失败", "累", "累死", "崩溃", "受不了",
        "无聊", "没意思", "恶心", "痛苦", "绝望", "无奈", "倒霉",
        "郁闷", "压抑", "憋屈", "焦躁", "不安", "紧张", "担心",
        "害怕", "恐惧", "慌", "急", "焦虑", "压力", "喘不过气",
        "唉", "唉声叹气", "切", "哼", "烦死了"
    )

    private val angerWords = setOf(
        "气死", "愤怒", "火大", "忍不了", "受不了", "过分", "岂有此理",
        "滚", "闭嘴", "烦不烦", "有完没完", "你给我", "你凭什么",
        "神经病", "有病", "可恶", "混蛋", "真烦", "爆炸"
    )

    private val sadnessWords = setOf(
        "伤心", "难过", "悲伤", "失落", "寂寞", "孤独", "想哭",
        "泪", "眼泪", "哭", "心碎", "委屈", "思念", "怀念",
        "遗憾", "后悔", "空虚", "迷茫", "无助", "失望"
    )

    private val anxietyWords = setOf(
        "担心", "怕", "焦虑", "紧张", "不安", "慌", "着急",
        "害怕", "恐惧", "压力", "崩溃", "睡不着", "失眠",
        "怎么办", "万一", "如果", "会不会", "能不能",
        "不确定", "没把握", "忐忑", "心慌", "悬"
    )

    private val calmWords = setOf(
        "平静", "淡定", "从容", "安静", "平和", "自然", "随意",
        "没关系", "没事", "放心", "慢慢来", "不急", "好说",
        "算了", "就这样", "可以", "行", "嗯", "好的"
    )

    // Intensifiers (amplify sentiment)
    private val intensifiers = setOf(
        "非常", "特别", "极其", "超级", "太", "很", "真", "好",
        "十分", "无比", "格外", "尤其", "太...了"
    )

    // Hedges (weaken sentiment)
    private val hedges = setOf(
        "可能", "也许", "大概", "似乎", "好像", "算是", "有点",
        "稍微", "略微", "不太", "不太确定", "一般"
    )

    // Negation words (invert sentiment)
    private val negations = setOf("不", "没", "别", "不要", "没有", "不是", "并非")

    // Cognitive process words (indicate thinking/reflecting)
    private val cognitiveWords = setOf(
        "明白", "理解", "觉得", "认为", "想", "思考", "考虑",
        "分析", "判断", "决定", "计划", "打算", "意识到",
        "发现", "注意到", "体会", "领悟", "反思"
    )

    // First-person pronouns (self-focus indicator)
    private val firstPersonPronouns = setOf("我", "我们", "我的", "我们的", "我自己")

    // ===== Main Analysis =====

    /**
     * Analyze text for emotional content.
     * @param text Transcribed text (could be multiple sentences)
     * @return SentimentResult with detected emotion, confidence, and linguistic indicators
     */
    fun analyze(text: String): SentimentResult {
        if (text.isBlank()) return SentimentResult(EmotionType.NEUTRAL, 0f, 0f, 0f, emptyMap())

        val sentences = splitSentences(text)
        val words = tokenize(text)

        // Count raw keyword matches
        val positiveCount = countWords(text, positiveWords)
        val negativeCount = countWords(text, negativeWords)
        val angerCount = countWords(text, angerWords)
        val sadnessCount = countWords(text, sadnessWords)
        val anxietyCount = countWords(text, anxietyWords)
        val calmCount = countWords(text, calmWords)

        // Count intensifiers, hedges, negations
        val intensifierCount = countWords(text, intensifiers)
        val hedgeCount = countWords(text, hedges)
        val negationCount = countWords(text, negations)
        val cognitiveCount = countWords(text, cognitiveWords)
        val firstPersonCount = countWords(text, firstPersonPronouns)

        // Sentence-level analysis
        val exclamationCount = text.count { it == '！' || it == '!' }
        val questionCount = text.count { it == '？' || it == '?' }
        val ellipsisCount = text.count { it == '…' } / 2  // Each "…" is 3 chars in Chinese

        // Apply negation inversion: "不开心" = not happy
        // Count negated emotional words
        val negatedPositive = countNegatedWords(sentences, positiveWords)
        val negatedNegative = countNegatedWords(sentences, negativeWords)

        // Effective counts after negation
        val effectivePositive = positiveCount - negatedNegative
        val effectiveNegative = negativeCount - negatedPositive

        // Apply intensifier/hedge weighting
        val intensityMultiplier = 1.0f + (intensifierCount * 0.3f) - (hedgeCount * 0.2f)

        // Compute arousal (emotional intensity)
        val totalEmotional = positiveCount + negativeCount + angerCount + sadnessCount + anxietyCount + calmCount
        val wordCount = words.size.coerceAtLeast(1)
        val emotionalDensity = totalEmotional.toFloat() / wordCount

        // Arousal: based on emotional density + exclamation intensity + intensifiers
        val arousal = ((emotionalDensity * 3f)
                + (exclamationCount.toFloat() / wordCount * 5f)
                + (intensifierCount.toFloat() / wordCount * 3f))
            .coerceIn(0f, 1f)

        // Compute valence (positive - negative)
        val rawValence = (effectivePositive - effectiveNegative).toFloat() / wordCount * 10f
        val valence = (rawValence * intensityMultiplier).coerceIn(-1f, 1f)

        // Compute emotion-specific scores
        val emotionScores = mutableMapOf<EmotionType, Float>()

        // HAPPY score
        val happyScore = (effectivePositive.toFloat() / wordCount * 5f
                + (exclamationCount.toFloat() / wordCount * 3f)
                + (calmCount.toFloat() / wordCount * 2f))
            .coerceIn(0f, 1f)
        if (happyScore > 0.1f) emotionScores[EmotionType.HAPPY] = happyScore

        // SAD score
        val sadScore = (sadnessCount.toFloat() / wordCount * 5f
                + (ellipsisCount.toFloat() / wordCount * 3f)
                + (effectiveNegative.toFloat() / wordCount * 2f))
            .coerceIn(0f, 1f)
        if (sadScore > 0.1f) emotionScores[EmotionType.SAD] = sadScore

        // ANGRY score
        val angryScore = (angerCount.toFloat() / wordCount * 5f
                + (exclamationCount.toFloat() / wordCount * 4f)
                + (intensifierCount.toFloat() / wordCount * 2f))
            .coerceIn(0f, 1f)
        if (angryScore > 0.1f) emotionScores[EmotionType.ANGRY] = angryScore

        // ANXIOUS score
        val anxiousScore = (anxietyCount.toFloat() / wordCount * 5f
                + (questionCount.toFloat() / wordCount * 3f)
                + (hedgeCount.toFloat() / wordCount * 2f)
                + (firstPersonCount.toFloat() / wordCount * 2f))
            .coerceIn(0f, 1f)
        if (anxiousScore > 0.1f) emotionScores[EmotionType.ANXIOUS] = anxiousScore

        // CALM score
        val calmScore = (calmCount.toFloat() / wordCount * 4f
                + (cognitiveCount.toFloat() / wordCount * 2f))
            .coerceIn(0f, 1f)
        if (calmScore > 0.1f) emotionScores[EmotionType.CALM] = calmScore

        // EXCITED score
        val excitedScore = (exclamationCount.toFloat() / wordCount * 4f
                + (intensifierCount.toFloat() / wordCount * 3f)
                + (effectivePositive.toFloat() / wordCount * 2f))
            .coerceIn(0f, 1f)
        if (excitedScore > 0.1f) emotionScores[EmotionType.EXCITED] = excitedScore

        // Determine primary emotion
        val primaryEmotion = if (emotionScores.isNotEmpty()) {
            emotionScores.maxByOrNull { it.value }!!.key
        } else {
            EmotionType.NEUTRAL
        }

        // Confidence: based on how distinct the best match is
        val confidence = if (emotionScores.isNotEmpty()) {
            val best = emotionScores.values.max()
            val secondBest = emotionScores.values
                .filter { it < best }
                .maxOrNull() ?: 0f
            val margin = (best - secondBest).coerceIn(0f, 0.5f)
            (best * 0.5f + margin * 0.5f).coerceIn(0.3f, 0.9f)
        } else {
            0.3f
        }

        return SentimentResult(
            primaryEmotion = primaryEmotion,
            confidence = confidence,
            arousal = arousal,
            valence = valence,
            linguisticIndicators = mapOf(
                "emotionalDensity" to emotionalDensity,
                "firstPersonRatio" to firstPersonCount.toFloat() / wordCount,
                "cognitiveRatio" to cognitiveCount.toFloat() / wordCount,
                "exclamationRatio" to exclamationCount.toFloat() / wordCount,
                "questionRatio" to questionCount.toFloat() / wordCount,
                "intensifierRatio" to intensifierCount.toFloat() / wordCount,
                "hedgeRatio" to hedgeCount.toFloat() / wordCount,
                "negationRatio" to negationCount.toFloat() / wordCount,
                "positiveRatio" to effectivePositive.toFloat() / wordCount,
                "negativeRatio" to effectiveNegative.toFloat() / wordCount
            )
        )
    }

    /**
     * Merge text sentiment with audio-based emotion results.
     * Text confidence > 0.6 → trust text; audio confidence > 0.6 → trust audio;
     * otherwise → weighted average.
     */
    fun mergeWithAudio(
        textResult: SentimentResult,
        audioResults: List<com.moodecho.app.domain.model.EmotionResult>
    ): com.moodecho.app.domain.model.EmotionResult {
        if (audioResults.isEmpty()) {
            return com.moodecho.app.domain.model.EmotionResult(
                emotionType = textResult.primaryEmotion,
                confidence = textResult.confidence,
                arousal = textResult.arousal,
                valence = textResult.valence,
                timestamp = 0L
            )
        }

        // Aggregate audio results (use most frequent emotion)
        val audioDominant = audioResults
            .groupBy { it.emotionType }
            .maxByOrNull { it.value.size }
            ?.key ?: EmotionType.NEUTRAL
        val audioAvgConfidence = audioResults.map { it.confidence }.average().toFloat()
        val audioAvgArousal = audioResults.map { it.arousal }.average().toFloat()
        val audioAvgValence = audioResults.map { it.valence }.average().toFloat()

        // Weighted fusion based on confidence
        val textWeight = (textResult.confidence * 0.6f).coerceIn(0.2f, 0.7f)
        val audioWeight = 1.0f - textWeight

        val fusedEmotion = if (textResult.confidence > audioAvgConfidence) {
            textResult.primaryEmotion
        } else if (audioAvgConfidence > textResult.confidence) {
            audioDominant
        } else {
            // Tie: prefer text for valence-heavy emotions, audio for arousal-heavy
            if (textResult.valence != 0f) textResult.primaryEmotion else audioDominant
        }

        return com.moodecho.app.domain.model.EmotionResult(
            emotionType = fusedEmotion,
            confidence = (textResult.confidence * textWeight + audioAvgConfidence * audioWeight)
                .coerceIn(0f, 1f),
            arousal = (textResult.arousal * textWeight + audioAvgArousal * audioWeight)
                .coerceIn(0f, 1f),
            valence = (textResult.valence * textWeight + audioAvgValence * audioWeight)
                .coerceIn(-1f, 1f),
            timestamp = 0L
        )
    }

    // ===== Helper Methods =====

    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("[。！？.!?\\n]")).filter { it.isNotBlank() }
    }

    private fun tokenize(text: String): List<String> {
        // Simple character-based tokenization for Chinese
        // In production, use a proper Chinese tokenizer (jieba, etc.)
        // For now, split on whitespace and punctuation
        return text.split(Regex("[\\s,，。！？、；：\"\"''（）()\\[\\]【】…—]+"))
            .filter { it.isNotBlank() }
    }

    private fun countWords(text: String, dictionary: Set<String>): Int {
        var count = 0
        for (word in dictionary) {
            var index = 0
            while (true) {
                index = text.indexOf(word, index)
                if (index < 0) break
                count++
                index += word.length
            }
        }
        return count
    }

    /**
     * Count negated emotional words: "不开心" = negation + positive word.
     * Checks if a negation word appears within 2 characters before an emotional word.
     */
    private fun countNegatedWords(sentences: List<String>, emotionalWords: Set<String>): Int {
        var count = 0
        for (sentence in sentences) {
            for (word in emotionalWords) {
                val wordIndex = sentence.indexOf(word)
                if (wordIndex >= 1) {
                    val before = sentence.substring(maxOf(0, wordIndex - 2), wordIndex)
                    for (neg in negations) {
                        if (before.contains(neg)) {
                            count++
                            break
                        }
                    }
                }
            }
        }
        return count
    }

    /**
     * Result of text sentiment analysis.
     */
    data class SentimentResult(
        val primaryEmotion: EmotionType,
        val confidence: Float,
        val arousal: Float,
        val valence: Float,
        val linguisticIndicators: Map<String, Float> = emptyMap()
    )
}