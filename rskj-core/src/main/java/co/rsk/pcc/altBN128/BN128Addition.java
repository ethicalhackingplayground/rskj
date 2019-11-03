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

import static org.ethereum.util.ByteUtil.*;

/**
 * Computes point addition on Barreto–Naehrig curve.
 * See {@link BN128Fp} for details<br/>
 * <br/>
 *
 * input data[]:<br/>
 * two points encoded as (x, y), where x and y are 32-byte left-padded integers,<br/>
 * if input is shorter than expected, it's assumed to be right-padded with zero bytes<br/>
 * <br/>
 *
 * output:<br/>
 * resulting point (x', y'), where x and y encoded as 32-byte left-padded integers<br/>
 *
 */

/**
 * @author Sebastian Sicardi
 * @since 10.09.2019
 */
public class BN128Addition extends PrecompiledContracts.PrecompiledContract {

    @Override
    public long getGasForData(byte[] data) {
        return 500;
    }

    @Override
    public byte[] execute(byte[] data) {
        if (data == null) {
            data = EMPTY_BYTE_ARRAY;
        }
        byte[] output = new byte[64];
        int rs = new JniBn128().add(data, data.length, output);
        if (rs < 0) {
            return EMPTY_BYTE_ARRAY;
        }
        return output;
    }
}
