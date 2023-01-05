/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.tsfile.encoding.encoder;

import java.io.ByteArrayOutputStream;

import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;

import static org.apache.iotdb.tsfile.common.conf.TSFileConfig.LEADING_ZERO_BITS_LENGTH_32BIT;
import static org.apache.iotdb.tsfile.common.conf.TSFileConfig.MEANINGFUL_XOR_BITS_LENGTH_32BIT;
import static org.apache.iotdb.tsfile.common.conf.TSFileConfig.VALUE_BITS_LENGTH_32BIT;

/**
 * This class includes code modified from Panagiotis Liakos chimp project.
 *
 * <p>Copyright: 2022- Panagiotis Liakos, Katia Papakonstantinopoulou and Yannis Kotidis
 *
 * <p>Project page: https://github.com/panagiotisl/chimp
 *
 * <p>License: https://github.com/panagiotisl/chimp/blob/main/LICENCE.md
 */
public class IntChimpEncoder extends GorillaEncoderV2 {

    private final static int PREVIOUS_VALUES = 64;
    private final static int PREVIOUS_VALUES_LOG2 = (int)(Math.log(PREVIOUS_VALUES) / Math.log(2));
    private final static int THRESHOLD = 5 + PREVIOUS_VALUES_LOG2;
    private final static int SET_LSB = (int) Math.pow(2, THRESHOLD + 1) - 1;
    private final static int FLAG_ONE_SIZE = PREVIOUS_VALUES_LOG2 + 10;
    private final static int FLAG_ZERO_SIZE = PREVIOUS_VALUES_LOG2 + 2;
    public final static short[] LEADING_REPRESENTATION = {0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7
        };

    public final static short[] LEADING_ROUND = {0, 0, 0, 0, 0, 0, 0, 0,
            8, 8, 8, 8, 12, 12, 12, 12,
            16, 16, 18, 18, 20, 20, 22, 22,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24,
            24, 24, 24, 24, 24, 24, 24, 24
        };

    private int storedValues[];
    private int[] indices;
    private int index = 0;
    private int current = 0;

    public IntChimpEncoder() {
      this.setType(TSEncoding.CHIMP);
      this.indices = new int[(int) Math.pow(2, THRESHOLD + 1)];
      this.storedValues = new int[PREVIOUS_VALUES];
    }

    private static final int CHIMP_ENCODING_ENDING =
            Float.floatToRawIntBits(Float.NaN);

    private static final int ONE_ITEM_MAX_SIZE =
            (2
                        + LEADING_ZERO_BITS_LENGTH_32BIT
                        + MEANINGFUL_XOR_BITS_LENGTH_32BIT
                        + VALUE_BITS_LENGTH_32BIT)
                    / Byte.SIZE
                + 1;

    @Override
    public final int getOneItemMaxSize() {
      return ONE_ITEM_MAX_SIZE;
    }

    @Override
    protected void reset() {
      super.reset();
      this.current = 0;
      this.index = 0;
      this.indices = new int[(int) Math.pow(2, THRESHOLD + 1)];
      this.storedValues = new int[PREVIOUS_VALUES];
    }

    @Override
    public void flush(ByteArrayOutputStream out) {
      // ending stream
      encode(CHIMP_ENCODING_ENDING, out);

      // flip the byte no matter it is empty or not
      // the empty ending byte is necessary when decoding
      bitsLeft = 0;
      flipByte(out);

      // the encoder may be reused, so let us reset it
      reset();
    }

    @Override
    public final void encode(int value, ByteArrayOutputStream out) {
      if (firstValueWasWritten) {
        compressValue(value, out);
      } else {
        writeFirst(value, out);
        firstValueWasWritten = true;
      }
    }

    private void writeFirst(int value, ByteArrayOutputStream out) {
        storedValues[current] = value;
          writeBits(value, VALUE_BITS_LENGTH_32BIT, out);
          indices[value & SET_LSB] = index;
        }

    private void compressValue(int value, ByteArrayOutputStream out) {
      int key = value & SET_LSB;
      int xor;
      int previousIndex;
      int trailingZeros = 0;
      int currIndex = indices[key];
      if ((index - currIndex) < PREVIOUS_VALUES) {
          int tempXor = value ^ storedValues[currIndex % PREVIOUS_VALUES];
          trailingZeros = Integer.numberOfTrailingZeros(tempXor);
          if (trailingZeros > THRESHOLD) {
              previousIndex = currIndex % PREVIOUS_VALUES;
              xor = tempXor;
          } else {
              previousIndex =  index % PREVIOUS_VALUES;
              xor = storedValues[previousIndex] ^ value;
          }
      } else {
          previousIndex =  index % PREVIOUS_VALUES;
          xor = storedValues[previousIndex] ^ value;
      }

        if(xor == 0) {
            writeBits(previousIndex, FLAG_ZERO_SIZE, out);
            storedLeadingZeros = VALUE_BITS_LENGTH_32BIT + 1;
        } else {
            int leadingZeros = LEADING_ROUND[Integer.numberOfLeadingZeros(xor)];

            if (trailingZeros > THRESHOLD) {
              int significantBits = VALUE_BITS_LENGTH_32BIT - leadingZeros - trailingZeros;
              writeBits(256 * (PREVIOUS_VALUES + previousIndex) + 32 * LEADING_REPRESENTATION[leadingZeros] + significantBits, FLAG_ONE_SIZE, out);
              writeBits(xor >>> trailingZeros, significantBits, out); // Store the meaningful bits of XOR
              storedLeadingZeros = VALUE_BITS_LENGTH_32BIT + 1;
          } else if (leadingZeros == storedLeadingZeros) {
              writeBit(out);
              skipBit(out);
              int significantBits = VALUE_BITS_LENGTH_32BIT - leadingZeros;
              writeBits(xor, significantBits, out);
          } else {
              storedLeadingZeros = leadingZeros;
              int significantBits = VALUE_BITS_LENGTH_32BIT - leadingZeros;
              writeBits(24 + LEADING_REPRESENTATION[leadingZeros], 5, out);
              writeBits(xor, significantBits, out);
          }
      }
        current = (current + 1) % PREVIOUS_VALUES;
        storedValues[current] = value;
        index++;
        indices[key] = index;
    }
}
