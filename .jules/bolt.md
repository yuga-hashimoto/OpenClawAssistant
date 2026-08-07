## 2025-02-28 - Pre-compiled Regex patterns
**Learning:** Frequent initialization of `Regex("...")` objects inside hot loops or parsing functions (like text-to-speech text normalization or markdown parsing) introduces significant, measurable performance overhead because the regular expression pattern has to be compiled on every invocation. In this Kotlin Android codebase, extracting them to `private val` properties avoids repeated compilation, especially in methods like `stripMarkdownForSpeech` which parses an entire text block multiple times per message string.
**Action:** When working on text parsing features (e.g. ChatMarkdown blocks, TTS filters, Tool output formatting), always ensure `Regex` objects are pre-compiled as top-level file constants, or inside a `companion object` of a class, or as a property within a singleton `object`.

## 2024-05-24 - Hoist Static Collection Allocations in Loop Parsers
**Learning:** Instantiating static data structures like `listOf(...)` inside frequently called parsing methods (such as text chunking) causes redundant memory allocations and garbage collection pressure, leading to hidden CPU overhead.
**Action:** Always hoist static parsing collections (like sentence or comma enders) to `private val` properties at the file or object level to ensure they are created exactly once.

## 2025-05-24 - Readable Kotlin String Interpolation Refactor
**Learning:** When refactoring multi-line `listOf(...).joinToString(...)` constructions into direct string interpolation to avoid list allocation overhead, squashing the entire multi-chained list element instantiation into a single, excessively long string interpolation string (e.g. `"${part.type.trim().lowercase()}\u001F${part.text?.trim().orEmpty()}..."`) severely degrades code readability.
**Action:** When replacing multi-line list interpolations, assign the components to individual, properly named local variables first, then construct the final string using those simple variables in the string interpolation template (e.g., `"$type\u001F$text\u001F..."`). This preserves the line-by-line readability of the original list while still eliminating the list allocation performance bottleneck.

## 2026-03-26 - Kotlin `ByteArray.joinToString("") { "%02x".format(it) }` Performance Bottleneck
**Learning:** Formatting byte arrays to hex strings using Kotlin's `joinToString` with `"%02x".format(it)` is extremely slow and memory inefficient because it allocates a new string and lambda invocation for every single byte, creating immense garbage collector pressure during heavy data processing (like image encoding or cryptographic hashing). A benchmark showed `joinToString` taking ~691ms for 1000 iterations vs a manual `CharArray` bit-shift approach taking only ~26ms (a 26x speedup).
**Action:** When converting large byte arrays to hex strings (e.g., in `ChatImageCodec` for MD5 caching or `DeviceIdentity` for signature generation), replace `joinToString` formatting with a fast manual `CharArray` bitwise approach.

## 2024-04-03 - Kotlin `joinToString` memory allocations inside object mapping/looping
**Learning:** Using `collection.joinToString(separator) { ... }` with a lambda that returns interpolated strings inside frequently-invoked object mapping layers (like mapping elements into a cache key inside `ChatController.messageIdentityKey`) creates immense pressure on the Garbage Collector. It instantiates intermediate string iterators, implicit list mapping elements, and implicit string builder lambda invokes. This is particularly problematic in UI component list-reconciliation (e.g. `LazyColumn` message keys), causing jitter.
**Action:** Replace this pattern with a manual `StringBuilder` where the items are appended via an indexed loop (e.g., `for (i in collection.indices)`), preventing mapping iterators and intermediate lambda string allocations.

## 2025-05-27 - O(N^2) String Chunking Regression in `lastIndexOf`
**Learning:** Using `text.substring().lastIndexOf(delimiter)` inside a text chunking loop (e.g. for Text-to-Speech normalization) creates a hidden performance regression. `substring` allocates new strings for each chunk, but more problematically, `lastIndexOf` searches backwards across the *entire* text. If the delimiter is missing from the chunk, it will search backwards all the way to index 0, resulting in O(N^2) complexity and massive latency spikes for long strings without delimiters.
**Action:** When searching backward for a delimiter to split text within a length constraint without allocating substrings, use a custom bounded `regionMatches` loop (tracking `offset` and `limit`) instead of an unbounded `lastIndexOf`. Additionally, convert constant boundary lists (like sentence enders) to `arrayOf()` to prevent hidden iterator allocations during the per-chunk delimiter searches.

## 2024-05-27 - Hoist Static Collection Allocations in Loop Parsers
**Learning:** Replaced `.split("\n")` and `mutableListOf<String>().joinToString("\n")` pipeline with `lineSequence()` and a pre-sized `StringBuilder` to eliminate massive GC pressure in frequently called parsers. Also replaced final trailing Regex replaces with simple `trimStart()`.
**Action:** Always favor `lineSequence()` and direct string building when processing and transforming multi-line text strings over intermediate list allocations.
