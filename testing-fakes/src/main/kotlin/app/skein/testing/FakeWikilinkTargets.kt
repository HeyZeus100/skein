package app.skein.testing

/** Small fake-only counterpart of the vault extractor; avoids an Android module dependency. */
internal object FakeWikilinkTargets {
    fun extract(body: String): Set<String> =
        buildSet {
            var inFence = false
            for (line in body.lineSequence()) {
                if (line.trimStart().startsWith("```")) {
                    inFence = !inFence
                    continue
                }
                if (inFence) continue
                var offset = 0
                while (offset < line.length) {
                    when {
                        line[offset] == '`' -> {
                            val close = line.indexOf('`', offset + 1)
                            if (close < 0) break
                            offset = close + 1
                        }
                        line.startsWith("[[", offset) -> {
                            val close = line.indexOf("]]", offset + 2)
                            if (close < 0) break
                            val target =
                                line
                                    .substring(offset + 2, close)
                                    .substringBefore('|')
                                    .substringBefore('#')
                                    .trim()
                            if (target.isNotEmpty()) add(target)
                            offset = close + 2
                        }
                        else -> offset++
                    }
                }
            }
        }
}
