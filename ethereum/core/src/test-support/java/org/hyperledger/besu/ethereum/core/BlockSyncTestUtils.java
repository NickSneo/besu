/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.util.RawBlockIterator;
import org.hyperledger.besu.testutil.BlockTestUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BlockSyncTestUtils {

  private BlockSyncTestUtils() {
    // Utility Class
  }

  public static List<Block> firstBlocks(final int count) {
    final List<Block> result = new ArrayList<>(count);
    Path tempDir = null;
    try {
      tempDir = Files.createTempDirectory("tempDir");
      final Path blocks = tempDir.resolve("blocks");
      final BlockHeaderFunctions blockHeaderFunctions = new MainnetBlockHeaderFunctions();
      BlockTestUtil.write1000Blocks(blocks);
      try (final RawBlockIterator iterator = new RawBlockIterator(blocks, blockHeaderFunctions)) {
        for (int i = 0; i < count; ++i) {
          result.add(iterator.next());
        }
      }
    } catch (final IOException ex) {
      throw new IllegalStateException(ex);
    } finally {
      tempDirCleanup(tempDir);
    }
    return result;
  }

  private static void tempDirCleanup(Path temp){
    if (temp != null){
      try {
        Files.deleteIfExists(temp);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
