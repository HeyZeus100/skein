/* Decode standard UTF-8, ART encoded NUL, and compatible CESU-8 sequences.
 * CESU-8 surrogate pairs and C0 80 NUL must remain readable without
 * rewriting source revisions. Derived lexical writes bind exact Kotlin UTF-8 bytes. */
#ifndef SKEIN_UTF16_H
#define SKEIN_UTF16_H
#include <stdint.h>

static int skein_utf16(const unsigned char *text, int bytes, uint16_t *output) {
    int count = 0;
    int i = 0;
    while (i < bytes) {
        uint32_t value = text[i++];
        if (value >= 0x80) {
            int extra = value >= 0xc2 && value <= 0xdf ? 1 :
                        value >= 0xe0 && value <= 0xef ? 2 :
                        value >= 0xf0 && value <= 0xf4 ? 3 : 0;
            if (value == 0xc0 && i < bytes && text[i] == 0x80) {
                value = 0;
                i++;
            } else {
                uint32_t decoded = extra ? value & ((1u << (6 - extra)) - 1) : 0;
                int valid = extra > 0 && extra <= bytes - i;
                for (int j = 0; valid && j < extra; j++) {
                    if ((text[i + j] & 0xc0) != 0x80) valid = 0;
                    else decoded = (decoded << 6) | (text[i + j] & 0x3f);
                }
                uint32_t minimum = extra == 1 ? 0x80 : extra == 2 ? 0x800 : 0x10000;
                if (valid && decoded >= minimum && decoded <= 0x10ffff) {
                    value = decoded;
                    i += extra;
                } else value = 0xfffd;
            }
        }
        if (value >= 0x10000) {
            value -= 0x10000;
            output[count++] = (uint16_t)(0xd800 | (value >> 10));
            output[count++] = (uint16_t)(0xdc00 | (value & 0x3ff));
        } else output[count++] = (uint16_t)value;
    }
    return count;
}
#endif
