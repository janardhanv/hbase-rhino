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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.wal;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.LargeTests;
import org.junit.BeforeClass;
import org.junit.experimental.categories.Category;

@Category(LargeTests.class)
public class TestHLogSplitEncrypted extends TestHLogSplit {
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TestHLogSplit.setUpBeforeClass();
    Configuration conf = TEST_UTIL.getConfiguration();
    conf.setBoolean(HConstants.ENABLE_WAL_ENCRYPTION, true);
    conf.set(HConstants.CRYPTO_KEYPROVIDER_CONF_KEY,
      "org.apache.hadoop.io.crypto.KeyProviderForTesting");
    conf.set(HConstants.CRYPTO_KEYPROVIDER_PARAMETERS_KEY, "123456");
  }
}
