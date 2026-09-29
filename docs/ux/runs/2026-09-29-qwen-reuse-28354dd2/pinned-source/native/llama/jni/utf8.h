/*
 * utf8.h — real UTF-8 <-> Java UTF-16 conversion for the llama.cpp JNI
 * boundary (E4.I1, bd skein-3aw).
 *
 * WHY NOT GetStringUTFChars / NewStringUTF. JNI's "UTF" is *modified* UTF-8,
 * which differs from the real thing in two ways that matter here:
 *
 *   1. U+0000 is encoded as the two bytes C0 80 rather than as 00.
 *   2. A character outside the BMP is encoded as its two UTF-16 surrogates,
 *      each separately encoded in three bytes (CESU-8), rather than as one
 *      four-byte sequence.
 *
 * llama.cpp's tokenizer takes and produces real UTF-8. Handing it CESU-8 would
 * tokenize every emoji and every rarer CJK character as a pair of lone
 * surrogates — silently, producing a plausible-looking but wrong token stream
 * — and `NewStringUTF` on a real four-byte sequence is undefined behaviour
 * that ART's checked JNI aborts the process for.
 *
 * So the conversion is done here, explicitly, via UTF-16 (`GetStringChars` /
 * `NewString`), which is Java's actual string representation.
 *
 * Both directions are lossy only for genuinely malformed input, and then in a
 * defined way: an unpaired surrogate or an invalid byte becomes U+FFFD. They
 * never throw, never allocate on failure, and never log — a failure to convert
 * must not become a log line containing the text that failed to convert
 * (spec §9).
 */

#ifndef SKEIN_LLAMA_JNI_UTF8_H
#define SKEIN_LLAMA_JNI_UTF8_H

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace skein {

/* Appends one code point to `out` as real UTF-8. */
inline void AppendUtf8(std::string &out, std::uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

/*
 * jstring -> real UTF-8. Returns an empty string for a null jstring, which is
 * the caller's cue to have already rejected null arguments.
 */
inline std::string JStringToUtf8(JNIEnv *env, jstring str) {
    std::string out;
    if (str == nullptr) {
        return out;
    }
    const jsize len = env->GetStringLength(str);
    const jchar *chars = env->GetStringChars(str, nullptr);
    if (chars == nullptr) {
        return out;
    }
    out.reserve(static_cast<std::size_t>(len) * 3 / 2);
    for (jsize i = 0; i < len; ++i) {
        std::uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const std::uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            } else {
                cp = 0xFFFD;  /* high surrogate with no low */
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;  /* lone surrogate */
        }
        AppendUtf8(out, cp);
    }
    env->ReleaseStringChars(str, chars);
    return out;
}

/*
 * Real UTF-8 -> jstring. Invalid bytes become U+FFFD rather than aborting the
 * process the way NewStringUTF would. Returns nullptr only if the JVM could
 * not allocate, in which case a pending OutOfMemoryError is already set.
 */
inline jstring Utf8ToJString(JNIEnv *env, const char *data, std::size_t size) {
    std::vector<jchar> utf16;
    utf16.reserve(size);
    std::size_t i = 0;
    while (i < size) {
        const auto b0 = static_cast<std::uint8_t>(data[i]);
        std::uint32_t cp = 0xFFFD;
        std::size_t extra = 0;
        if (b0 < 0x80) {
            cp = b0;
        } else if ((b0 & 0xE0) == 0xC0) {
            cp = b0 & 0x1Fu;
            extra = 1;
        } else if ((b0 & 0xF0) == 0xE0) {
            cp = b0 & 0x0Fu;
            extra = 2;
        } else if ((b0 & 0xF8) == 0xF0) {
            cp = b0 & 0x07u;
            extra = 3;
        }
        if (extra > 0) {
            if (i + extra >= size) {
                cp = 0xFFFD;
                extra = 0;
                i = size;  /* truncated tail */
            } else {
                for (std::size_t k = 1; k <= extra; ++k) {
                    const auto bk = static_cast<std::uint8_t>(data[i + k]);
                    if ((bk & 0xC0) != 0x80) {
                        cp = 0xFFFD;
                        extra = 0;
                        break;
                    }
                    cp = (cp << 6) | (bk & 0x3Fu);
                }
            }
        }
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            cp = 0xFFFD;
        }
        if (cp < 0x10000) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            const std::uint32_t v = cp - 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (v >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (v & 0x3FF)));
        }
        i += 1 + extra;
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

inline jstring Utf8ToJString(JNIEnv *env, const std::string &s) {
    return Utf8ToJString(env, s.data(), s.size());
}

}  // namespace skein

#endif  // SKEIN_LLAMA_JNI_UTF8_H
