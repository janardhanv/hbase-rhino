/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.security;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.ipc.HBaseRPC;
import org.apache.hadoop.hbase.ipc.SecureRpcEngine;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.access.AccessController;
import org.apache.hadoop.hbase.security.access.SecureBulkLoadEndpoint;

/**
 * Utility methods for testing security
 */
public class SecureTestUtil {
  public static void enableSecurity(Configuration conf) throws IOException {
    conf.set("hadoop.security.authorization", "false");
    conf.set("hadoop.security.authentication", "simple");
    conf.set("hbase.security.authorization", "false");
    conf.set("hbase.security.authentication", "simple");
    conf.set(HBaseRPC.RPC_ENGINE_PROP, SecureRpcEngine.class.getName());
    conf.set(CoprocessorHost.MASTER_COPROCESSOR_CONF_KEY, AccessController.class.getName());
    conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY, AccessController.class.getName() +
        "," + SecureBulkLoadEndpoint.class.getName());
    conf.set(CoprocessorHost.REGIONSERVER_COPROCESSOR_CONF_KEY, AccessController.class.getName());
    String baseuser = User.getCurrent().getShortName();
    conf.set("hbase.superuser", "admin," + baseuser +
        String.format(",%s.hfs.0,%s.hfs.1,%s.hfs.2", baseuser, baseuser, baseuser));
  }

  public static boolean isSecurityEnabled(Configuration conf) {
    return conf.get(HBaseRPC.RPC_ENGINE_PROP, "").equals(SecureRpcEngine.class.getName()) &&
        conf.get(CoprocessorHost.MASTER_COPROCESSOR_CONF_KEY, "")
          .equals(AccessController.class.getName()) &&
        conf.get(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY, "")
          .equals(AccessController.class.getName()) &&
        conf.get(CoprocessorHost.REGIONSERVER_COPROCESSOR_CONF_KEY, "")
          .equals(AccessController.class.getName()) &&
        conf.get("hbase.superuser") != null;
  }
}