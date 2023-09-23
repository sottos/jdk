/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, Alibaba Group Holding Limited. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.util;

import java.io.IOException;
import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.util.Formatter.FormatSpecifier;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.util.FormatConcatItem;
import jdk.internal.util.DecimalDigits;
import jdk.internal.util.HexDigits;
import jdk.internal.util.OctalDigits;
import jdk.internal.vm.annotation.Stable;

/**
 * A specialized objects used by FormatterBuilder that knows how to insert
 * themselves into a concatenation performed by StringConcatFactory.
 *
 * @since 21
 *
 * Warning: This class is part of PreviewFeature.Feature.STRING_TEMPLATES.
 *          Do not rely on its availability.
 */
class FormatItem {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private static long charMix(long lengthCoder, char value) {
        return JLA.stringConcatMix(lengthCoder, value);
    }

    private static long stringMix(long lengthCoder, String value) {
        return JLA.stringConcatMix(lengthCoder, value);
    }

    private static long stringPrepend(long lengthCoder, byte[] buffer,
                                            String value) throws Throwable {
        return JLA.stringConcatHelperPrepend(lengthCoder, buffer, value,
                (String)null);
    }

    private static void putCharUTF16(byte[] buffer, int index, int ch) {
        JLA.putCharUTF16(buffer, index, ch);
    }

    private static boolean isLatin1(long lengthCoder) {
        return JLA.stringConcatHelpeIsLatin1(lengthCoder);
    }

    private FormatItem() {
        throw new AssertionError("private constructor");
    }

    /**
     * Decimal value format item.
     */
    static final class FormatItemDecimal implements FormatConcatItem {
        private final char groupingSeparator;
        private final char zeroDigit;
        private final char minusSign;
        private final int digitOffset;
        private final int length;
        private final boolean isNegative;
        private final int width;
        private final byte prefixSign;
        private final int groupSize;
        private final long value;
        private final boolean parentheses;

        FormatItemDecimal(DecimalFormatSymbols dfs, int width, char sign,
                          boolean parentheses, int groupSize, long value) throws Throwable {
            this.groupingSeparator = dfs.getGroupingSeparator();
            this.zeroDigit = dfs.getZeroDigit();
            this.minusSign = dfs.getMinusSign();
            this.digitOffset = this.zeroDigit - '0';
            int length = DecimalDigits.stringSize(value);
            this.isNegative = value < 0L;
            this.length = this.isNegative ? length - 1 : length;
            this.width = width;
            this.groupSize = groupSize;
            this.value = value;
            this.parentheses = parentheses && isNegative;
            this.prefixSign = (byte)(isNegative ? (parentheses ? '\0' : minusSign) : sign);
        }

        private int signLength() {
            return (prefixSign != '\0' ? 1 : 0) + (parentheses ? 2 : 0);
        }

        private int groupLength() {
            return 0 < groupSize ? (length - 1) / groupSize : 0;
        }

        @Override
        public long mix(long lengthCoder) {
            return JLA.stringConcatCoder(zeroDigit) |
                    (lengthCoder +
                     Integer.max(length + signLength() + groupLength(), width));
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            if (isLatin1(lengthCoder)) {
                return prependLatin1(lengthCoder, buffer);
            } else {
                return prependUTF16(lengthCoder, buffer);
            }
        }

        private long prependLatin1(long lengthCoder, byte[] buffer) throws Throwable {
            int lengthCoderLatin1 = (int) lengthCoder;

            if (parentheses) {
                buffer[--lengthCoderLatin1] = ')';
            }

            if (0 < groupSize) {
                int groupIndex = groupSize;

                byte[] digits = new byte[length];
                DecimalDigits.getCharsLatin1(value, length, digits);

                for (int i = 1; i <= length; i++) {
                    if (groupIndex-- == 0) {
                        buffer[--lengthCoderLatin1] = (byte) groupingSeparator;
                        groupIndex = groupSize - 1;
                    }

                    buffer[--lengthCoderLatin1] = (byte) (digits[digits.length - i] + digitOffset);
                }
            } else {
                DecimalDigits.getCharsLatin1(value, (int)lengthCoder, buffer);
                lengthCoderLatin1 -= length;
            }

            for (int i = length + signLength() + groupLength(); i < width; i++) {
                buffer[--lengthCoderLatin1] = '0';
            }

            if (parentheses) {
                buffer[--lengthCoderLatin1] = '(';
            }

            if (prefixSign != '\0') {
                buffer[--lengthCoderLatin1] = prefixSign;
            }

            return lengthCoderLatin1;
        }

        private long prependUTF16(long lengthCoder, byte[] buffer) throws Throwable {
            if (parentheses) {
                putCharUTF16(buffer, (int)--lengthCoder, (int)')');
            }

            if (0 < groupSize) {
                int groupIndex = groupSize;

                byte[] digits = new byte[length];
                DecimalDigits.getCharsLatin1(value, length, digits);

                for (int i = 1; i <= length; i++) {
                    if (groupIndex-- == 0) {
                        putCharUTF16(buffer, (int) --lengthCoder, (int) groupingSeparator);
                        groupIndex = groupSize - 1;
                    }

                    putCharUTF16(buffer, (int) --lengthCoder, digits[digits.length - i] + digitOffset);
                }
            } else {
                DecimalDigits.getCharsUTF16(value, (int)lengthCoder, buffer);
                lengthCoder -= length;
            }

            for (int i = length + signLength() + groupLength(); i < width; i++) {
                putCharUTF16(buffer, (int) --lengthCoder, (int) '0');
            }

            if (parentheses) {
                putCharUTF16(buffer, (int) --lengthCoder, (int) '(');
            }

            if (prefixSign != '\0') {
                putCharUTF16(buffer, (int) --lengthCoder, (int) prefixSign);
            }

            return lengthCoder;
        }
    }

    /**
     * Hexadecimal format item.
     */
    static final class FormatItemHexadecimal implements FormatConcatItem {
        private final int width;
        private final boolean hasPrefix;
        private final long value;
        private final int length;

        FormatItemHexadecimal(int width, boolean hasPrefix, long value) {
            this.width = width;
            this.hasPrefix = hasPrefix;
            this.value = value;
            this.length = HexDigits.stringSize(value);
        }

        private int prefixLength() {
            return hasPrefix ? 2 : 0;
        }

        private int zeroesLength() {
            return Integer.max(0, width - length - prefixLength());
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + length + prefixLength() + zeroesLength();
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            if (isLatin1(lengthCoder)) {
                return prependLatin1(lengthCoder, buffer);
            } else {
                return prependUTF16(lengthCoder, buffer);
            }
        }

        protected long prependLatin1(long lengthCoder, byte[] buffer) throws Throwable {
            int lengthCoderLatin1 = (int) lengthCoder;
            HexDigits.getCharsLatin1(value, lengthCoderLatin1, buffer);
            lengthCoderLatin1 -= length;

            if (hasPrefix && value != 0) {
                buffer[--lengthCoderLatin1] = 'x';
                buffer[--lengthCoderLatin1] = '0';
            }

            return lengthCoderLatin1;
        }

        protected long prependUTF16(long lengthCoder, byte[] buffer) throws Throwable {
            HexDigits.getCharsUTF16(value, (int)lengthCoder, buffer);
            lengthCoder -= length;

            for (int i = 0; i < zeroesLength(); i++) {
                putCharUTF16(buffer, (int)--lengthCoder, '0');
            }

            if (hasPrefix && value != 0) {
                putCharUTF16(buffer, (int)--lengthCoder, 'x');
                putCharUTF16(buffer, (int)--lengthCoder, '0');
            }

            return lengthCoder;
        }
    }

    /**
     * Hexadecimal format item.
     */
    static final class FormatItemOctal implements FormatConcatItem {
        private final int width;
        private final boolean hasPrefix;
        private final long value;
        private final int length;

        FormatItemOctal(int width, boolean hasPrefix, long value) {
            this.width = width;
            this.hasPrefix = hasPrefix;
            this.value = value;
            this.length = OctalDigits.stringSize(value);
        }

        private int prefixLength() {
            return hasPrefix && value != 0 ? 1 : 0;
        }

        private int zeroesLength() {
            return Integer.max(0, width - length - prefixLength());
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + length + prefixLength() + zeroesLength();
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            if (isLatin1(lengthCoder)) {
                return prependLatin1(lengthCoder, buffer);
            } else {
                return prependUTF16(lengthCoder, buffer);
            }
        }

        protected long prependLatin1(long lengthCoder, byte[] buffer) throws Throwable {
            int lengthCoderLatin1 = (int) lengthCoder;
            OctalDigits.getCharsLatin1(value, lengthCoderLatin1, buffer);
            lengthCoderLatin1 -= length;

            int zeroesLength = zeroesLength();
            if (hasPrefix && value != 0) {
                zeroesLength++;
            }

            for (int i = 0; i < zeroesLength; i++) {
                buffer[--lengthCoderLatin1] = '0';
            }

            return lengthCoderLatin1;
        }

        protected long prependUTF16(long lengthCoder, byte[] buffer) throws Throwable {
            OctalDigits.getCharsUTF16(value, (int) lengthCoder, buffer);
            lengthCoder -= length;

            int zeroesLength = zeroesLength();
            if (hasPrefix && value != 0) {
                zeroesLength++;
            }
            for (int i = 0; i < zeroesLength; i++) {
                putCharUTF16(buffer, (int)--lengthCoder, '0');
            }

            return lengthCoder;
        }
    }

    /**
     * Boolean format item.
     */
    static final class FormatItemBoolean implements FormatConcatItem {
        private final boolean value;
        @Stable
        private final static byte[] BYTES_TRUE_LATIN1 = "true".getBytes(StandardCharsets.ISO_8859_1);
        @Stable
        private final static byte[] BYTES_TRUE_UTF16 = "true".getBytes(StandardCharsets.UTF_16);
        @Stable
        private final static byte[] BYTES_FALSE_LATIN1 = "false".getBytes(StandardCharsets.ISO_8859_1);
        @Stable
        private final static byte[] BYTES_FALSE_UTF16 = "false".getBytes(StandardCharsets.UTF_16);

        FormatItemBoolean(boolean value) {
            this.value = value;
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + (value ? "true".length() : "false".length());
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            boolean latin1 = isLatin1(lengthCoder);
            byte[] bytes;
            if (latin1) {
                bytes = value ? BYTES_TRUE_LATIN1 : BYTES_FALSE_LATIN1;
            } else {
                bytes = value ? BYTES_FALSE_LATIN1 : BYTES_FALSE_UTF16;
            }
            System.arraycopy(bytes, 0, buffer, (int) (lengthCoder) - bytes.length, bytes.length);
            return lengthCoder - bytes.length;
         }
    }

    /**
     * Character format item.
     */
    static final class FormatItemCharacter implements FormatConcatItem {
        private final char value;

        FormatItemCharacter(char value) {
            this.value = value;
        }

        @Override
        public long mix(long lengthCoder) {
            return charMix(lengthCoder, value);
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            if (isLatin1(lengthCoder)) {
                buffer[(int) --lengthCoder] = (byte) value;
            } else {
                putCharUTF16(buffer, (int) --lengthCoder, value);
            }
            return lengthCoder;
        }
    }

    /**
     * String format item.
     */
    static final class FormatItemString implements FormatConcatItem {
        private String value;

        FormatItemString(String value) {
            this.value = value;
        }

        @Override
        public long mix(long lengthCoder) {
            return stringMix(lengthCoder, value);
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            return stringPrepend(lengthCoder, buffer, value);
        }
    }

    /**
     * FormatSpecifier format item.
     */
    static final class FormatItemFormatSpecifier implements FormatConcatItem {
        private StringBuilder sb;

        FormatItemFormatSpecifier(FormatSpecifier fs, Locale locale, Object value) {
            this.sb = new StringBuilder(64);
            Formatter formatter = new Formatter(this.sb, locale);

            try {
                fs.print(formatter, value, locale);
            } catch (IOException ex) {
                throw new AssertionError("FormatItemFormatSpecifier IOException", ex);
            }
        }

        FormatItemFormatSpecifier(Locale locale,
                                  int flags, int width, int precision,
                                  Formattable formattable) {
            this.sb = new StringBuilder(64);
            Formatter formatter = new Formatter(this.sb, locale);
            formattable.formatTo(formatter, flags, width, precision);
        }

        @Override
        public long mix(long lengthCoder) {
            return JLA.stringBuilderConcatMix(lengthCoder, sb);
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            return JLA.stringBuilderConcatPrepend(lengthCoder, buffer, sb);
        }
    }

    static abstract sealed class FormatItemModifier implements FormatConcatItem
        permits FormatItemFillLeft,
                FormatItemFillRight
    {
        private final long itemLengthCoder;
        protected final FormatConcatItem item;

        FormatItemModifier(FormatConcatItem item) {
            this.itemLengthCoder = item.mix(0L);
            this.item = item;
        }

        int length() {
            return (int)itemLengthCoder;
        }

        long coder() {
            return itemLengthCoder & ~Integer.MAX_VALUE;
        }

        @Override
        public abstract long mix(long lengthCoder);

        @Override
        public abstract long prepend(long lengthCoder, byte[] buffer) throws Throwable;
    }

    /**
     * Fill left format item.
     */
    static final class FormatItemFillLeft extends FormatItemModifier
            implements FormatConcatItem {
        private final int width;

        FormatItemFillLeft(int width, FormatConcatItem item) {
            super(item);
            this.width = Integer.max(length(), width);
        }

        @Override
        public long mix(long lengthCoder) {
            return (lengthCoder | coder()) + width;
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            lengthCoder = item.prepend(lengthCoder, buffer);

            if (isLatin1(lengthCoder)) {
                for (int i = length(); i < width; i++) {
                    buffer[(int) --lengthCoder] = ' ';
                }
            } else {
                for (int i = length(); i < width; i++) {
                    putCharUTF16(buffer, (int) --lengthCoder, (int) ' ');
                }
            }

            return lengthCoder;
        }
    }

    /**
     * Fill right format item.
     */
    static final class FormatItemFillRight extends FormatItemModifier
            implements FormatConcatItem {
        private final int width;

        FormatItemFillRight(int width, FormatConcatItem item) {
            super(item);
            this.width = Integer.max(length(), width);
        }

        @Override
        public long mix(long lengthCoder) {
            return (lengthCoder | coder()) + width;
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            int length = length();
            if (isLatin1(lengthCoder)) {
                for (int i = length; i < width; i++) {
                    buffer[(int)--lengthCoder] = ' ';
                }
            } else {
                for (int i = length; i < width; i++) {
                    putCharUTF16(buffer, (int)--lengthCoder, ' ');
                }
            }

            lengthCoder = item.prepend(lengthCoder, buffer);

            return lengthCoder;
        }
    }


    /**
     * Null format item.
     */
    static final class FormatItemNull implements FormatConcatItem {
        @Stable
        private final static byte[] BYTES_LATIN1 = "null".getBytes(StandardCharsets.ISO_8859_1);

        @Stable
        private final static byte[] BYTES_UTF16 = "null".getBytes(StandardCharsets.UTF_16);

        FormatItemNull() {
        }

        @Override
        public long mix(long lengthCoder) {
            return lengthCoder + "null".length();
        }

        @Override
        public long prepend(long lengthCoder, byte[] buffer) throws Throwable {
            byte[] bytes = isLatin1(lengthCoder) ? BYTES_LATIN1 : BYTES_UTF16;
            System.arraycopy(bytes, 0, buffer, (int) (lengthCoder) - bytes.length, bytes.length);
            return lengthCoder - 4;
        }
    }
}
