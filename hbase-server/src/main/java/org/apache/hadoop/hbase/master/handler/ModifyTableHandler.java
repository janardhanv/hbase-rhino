/**
 *
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
package org.apache.hadoop.hbase.master.handler;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.executor.EventType;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.MasterCoprocessorHost;
import org.apache.hadoop.hbase.master.MasterServices;

@InterfaceAudience.Private
public class ModifyTableHandler extends TableEventHandler {
  private final HTableDescriptor htd;

  public ModifyTableHandler(final byte [] tableName,
      final HTableDescriptor htd, final Server server,
      final MasterServices masterServices) {
    super(EventType.C_M_MODIFY_TABLE, tableName, server, masterServices);
    // This is the new schema we are going to write out as this modification.
    this.htd = htd;
  }

  @Override
  protected void prepareWithTableLock() throws IOException {
    super.prepareWithTableLock();
    // Check table exists.
    getTableDescriptor();
  }

  @Override
  protected void handleTableOperation(List<HRegionInfo> hris)
  throws IOException {
    MasterCoprocessorHost cpHost = ((HMaster) this.server)
        .getCoprocessorHost();
    if (cpHost != null) {
      cpHost.preModifyTableHandler(this.tableName, this.htd);
    }
    // Update descriptor
    this.masterServices.getTableDescriptors().add(this.htd);
    if (cpHost != null) {
      cpHost.postModifyTableHandler(this.tableName, this.htd);
    }
  }

  @Override
  public String toString() {
    String name = "UnknownServerName";
    if(server != null && server.getServerName() != null) {
      name = server.getServerName().toString();
    }
    return getClass().getSimpleName() + "-" + name + "-" + getSeqid() + "-" +
      tableNameStr;
  }
}
