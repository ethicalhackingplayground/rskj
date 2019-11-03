/*
 * This file is part of RskJ
 * Copyright (C) 2019 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.pcc.altBN128;

import com.bakaoh.altbn128.cloudflare.JniBn128;
import org.ethereum.vm.PrecompiledContracts;

import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;

/**
 * Computes pairing check. <br/>
 * See {@link PairingCheck} for details.<br/>
 * <br/>
 *
 * Input data[]: <br/>
 * an array of points (a1, b1, ... , ak, bk), <br/>
 * where "ai" is a point of {@link BN128Fp} curve and encoded as two 32-byte left-padded integers (x; y) <br/>
 * "bi" is a point of {@link BN128G2} curve and encoded as four 32-byte left-padded integers {@code (ai + b; ci + d)},
 * each coordinate of the point is a big-endian {@link Fp2} number, so {@code b} precedes {@code a} in the encoding:
 * {@code (b, a; d, c)} <br/>
 * thus each pair (ai, bi) has 192 bytes length, if 192 is not a multiple of {@code data.length} then execution fails <br/>
 * the number of pairs is derived from input length by dividing it by 192 (the length of a pair) <br/>
 * <br/>
 *
 * output: <br/>
 * pairing product which is either 0 or 1, encoded as 32-byte left-padded integer <br/>
 *
 */

/**
 * @author Sebastian Sicardi
 * @since 10.09.2019
 */
public class BN128Pairing extends PrecompiledContracts.PrecompiledContract {

    private static final int PAIR_SIZE = 192;

    @Override
    public long getGasForData(byte[] data) {

        if (data == null) {
            return 100000;
        }

        return 80000L * (data.length / PAIR_SIZE) + 100000L;
    }

    @Override
    public byte[] execute(byte[] data) {
        if (data == null) {
            data = EMPTY_BYTE_ARRAY;
        }
        byte[] output = new byte[32];
        int rs = new JniBn128().pairing(data, data.length, output);
        if (rs < 0) {
            return EMPTY_BYTE_ARRAY;
        }
        return output;
    }
}
